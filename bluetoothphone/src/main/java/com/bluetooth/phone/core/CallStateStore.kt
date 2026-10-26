package com.bluetooth.phone.core

import android.os.SystemClock
import com.bluetooth.phone.api.AudioRoute
import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.CallClassifier
import com.bluetooth.phone.api.CallDirection
import com.bluetooth.phone.api.CallInfo
import com.bluetooth.phone.api.CallKind
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.CallUiState
import com.bluetooth.phone.api.PhoneCallState
import com.bluetooth.phone.hfp.HfpCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 号码匹配到的联系人信息，供通话界面展示姓名和头像。 */
internal data class ContactMatch(
    val name: String?,
    val photoUri: String?
)

/**
 * 把 HFP 的扁平 call 列表归并为 UI 可直接消费的状态。
 * 这里不直接引用 BluetoothHeadsetClient，方便测试和替换厂商栈。
 */
internal class CallStateStore(
    private val classifier: CallClassifier? = null
) {

    /** 每台设备对应的音频快照。 */
    private data class AudioSnapshot(val state: AudioState, val wideband: Boolean)

    private val _state = MutableStateFlow(PhoneCallState())

    /** 对外唯一的通话状态。 */
    val state: StateFlow<PhoneCallState> = _state.asStateFlow()

    /** 按设备缓存通话列表，切换设备时不丢失状态。 */
    private val callsByDevice = mutableMapOf<String, List<CallInfo>>()
    private val audioByDevice = mutableMapOf<String, AudioSnapshot>()

    /** 合并会议前的第二路快照，会议中继续展示其姓名/头像。 */
    private val conferencePeers = mutableMapOf<String, CallInfo>()

    private var currentDeviceId: String? = null
    private var currentDeviceName: String? = null

    /** 号码后 10 位 → 联系人信息，用于回填姓名和头像。 */
    private var contactsByNumber: Map<String, ContactMatch> = emptyMap()

    /** 当前系统/配置是否具备会议合并通道。 */
    private var conferenceMergeSupported = false

    /** 中间件设置的车机麦克风静音状态。 */
    private var micMuted = false

    /** 切换当前设备并重新计算对外的 [state]。 */
    fun selectDevice(address: String, name: String?) {
        PhoneLog.state.d { "切换数据源: $address (${name ?: "<未知>"})" }
        currentDeviceId = address
        currentDeviceName = name
        recompute()
    }

    /** 用网关上报的原始通话刷新某台设备，并保留已计算的通话开始时间。 */
    fun updateCalls(deviceId: String, deviceName: String?, rawCalls: List<HfpCall>) {
        val previous = callsByDevice[deviceId].orEmpty().associateBy { it.id }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        // 本进程第一次收到该设备的快照，可能是在通话已建立后才启动服务。
        val firstSnapshot = deviceId !in callsByDevice

        val mapped = rawCalls.map { raw ->
            // 会议可能换新 call id，沿用合并前活动通话的开始时间，避免计时重置。
            val mergedFrom = if (raw.isMultiParty) {
                previous.values.firstOrNull { it.isActive }?.startedAtMillis
            } else {
                null
            }
            // 只有真正接通才计时：来电振铃、去电拨号不计入通话时长。
            val connected = raw.state == CallState.ACTIVE || raw.state.isHeld
            val started = previous[raw.id]?.startedAtMillis
                ?: mergedFrom
                ?: when {
                    !connected -> null
                    // 启动时通话已存在（HFP 不提供接通时间，用 call 创建时间近似）
                    // getCreationElapsedMilli 是隐藏 API，异常值退回当前时刻。
                    firstSnapshot -> raw.createdAtElapsed.createdAtFor(nowElapsed, nowWall)
                    // 正常流转到接通：以当前时刻为起点，排除振铃时长
                    else -> nowWall
                }
            val match = contactMatch(raw.number)
            CallInfo(
                id = raw.id,
                deviceId = deviceId,
                state = raw.state,
                // HFP 只标记是否呼出；未标记呼出且有号码的按来电处理。
                direction = when {
                    raw.isOutgoing -> CallDirection.OUTGOING
                    raw.number.isNullOrBlank() -> CallDirection.UNKNOWN
                    else -> CallDirection.INCOMING
                },
                number = raw.number?.takeIf { it.isNotBlank() },
                displayName = match?.name ?: raw.number?.takeIf { it.isNotBlank() },
                photoUri = match?.photoUri,
                isMultiParty = raw.isMultiParty,
                isInBandRing = raw.isInBandRing,
                // 分类器由上层注入，异常时退回 UNKNOWN，不能影响通话主链路。
                kind = runCatching { classifier?.classify(raw.number) }
                    .getOrNull() ?: CallKind.UNKNOWN,
                startedAtMillis = started
            )
        }
            .filter { it.state != CallState.TERMINATED }
            .sortedBy { it.state.order() }

        callsByDevice[deviceId] = mapped
        rememberConferencePeer(deviceId, previous.values, mapped)
        // 归并输入留痕：排查“第二路为何没显示”时看这里。
        PhoneLog.state.d {
            "归并 $deviceId: ${mapped.size} 路 [" +
                mapped.joinToString { "${it.state}:${PhoneLog.mask(it.number)}" } + "]"
        }

        if (mapped.isNotEmpty() && currentDeviceId?.let { callsByDevice[it] }.isNullOrEmpty()) {
            currentDeviceId = deviceId
            currentDeviceName = deviceName
        }
        if (currentDeviceId == null) {
            currentDeviceId = deviceId
            currentDeviceName = deviceName
        }
        recompute()
    }

    /** 设备断开时清空其通话与音频，并选出新的当前设备。 */
    fun clearDevice(deviceId: String) {
        PhoneLog.state.d { "清理设备通话: $deviceId" }
        callsByDevice.remove(deviceId)
        audioByDevice.remove(deviceId)
        conferencePeers.remove(deviceId)
        if (currentDeviceId == deviceId) {
            currentDeviceId = callsByDevice.entries.firstOrNull { it.value.isNotEmpty() }?.key
            currentDeviceName = null
        }
        recompute()
    }

    /** 更新联系人映射，并回填已有通话和会议对方的姓名/头像。 */
    fun updateContacts(matches: Map<String, ContactMatch>) {
        contactsByNumber = matches
        callsByDevice.replaceAll { _, calls ->
            calls.map { it.withContact(contactMatch(it.number)) }
        }
        conferencePeers.replaceAll { _, call -> call.withContact(contactMatch(call.number)) }
        recompute()
        PhoneLog.state.d { "联系人映射更新: ${matches.size} 个号码" }
    }

    /** 更新会议合并能力；能力变化后 [PhoneCallState.canMerge] 会同步变化。 */
    fun setConferenceMergeSupported(supported: Boolean) {
        if (conferenceMergeSupported == supported) return
        conferenceMergeSupported = supported
        PhoneLog.state.i { "会议合并能力: $supported" }
        recompute()
    }

    /** 更新车机麦克风静音状态。 */
    fun setMicMuted(muted: Boolean) {
        if (micMuted == muted) return
        micMuted = muted
        recompute()
    }

    /** 清空全部设备状态（蓝牙关闭时使用），保留会议能力标记。 */
    fun clearAll() {
        PhoneLog.state.d { "清空全部通话状态" }
        callsByDevice.clear()
        audioByDevice.clear()
        conferencePeers.clear()
        currentDeviceId = null
        currentDeviceName = null
        micMuted = false
        recompute()
    }

    /** 按设备更新 SCO/eSCO 音频状态。 */
    fun updateAudio(deviceId: String, state: AudioState, wideband: Boolean) {
        audioByDevice[deviceId] = AudioSnapshot(state, wideband)
        recompute()
    }

    /** 记录会议前的第二路，会议结束后自动清除。 */
    private fun rememberConferencePeer(
        deviceId: String,
        previousCalls: Collection<CallInfo>,
        currentCalls: List<CallInfo>
    ) {
        val conference = currentCalls.firstOrNull { it.isMultiParty }
        if (conference == null) {
            conferencePeers.remove(deviceId)
            return
        }
        if (conferencePeers[deviceId] != null) return
        // 会议由“活动 + 保持”合并而来，保留合并前那一路的姓名/头像。
        val peer = previousCalls.firstOrNull {
            it.id != conference.id && (it.isHeld || it.state == CallState.WAITING)
        } ?: return
        conferencePeers[deviceId] = peer
        PhoneLog.state.i {
            "记录会议对方: ${PhoneLog.mask(peer.number)} (callId=${peer.id})"
        }
    }

    /** 把扁平通话列表归并为 active/held/incoming/waiting/conference 与 UI 阶段。 */
    private fun recompute() {
        val calls = currentDeviceId?.let { callsByDevice[it] }.orEmpty()
        val active = calls.firstOrNull { it.state == CallState.ACTIVE }
        // 部分 AG 会把第二路来电上报成 INCOMING，有活动通话时统一归一化为等待来电。
        val normalizedCalls = if (active != null) {
            if (calls.any { it.state == CallState.INCOMING }) {
                PhoneLog.state.d { "AG 将第二路上报为 INCOMING，已归一化为 WAITING" }
            }
            calls.map {
                if (it.state == CallState.INCOMING) it.copy(state = CallState.WAITING) else it
            }
        } else {
            calls
        }
        val held = normalizedCalls.firstOrNull { it.isHeld }
        val incoming = normalizedCalls.firstOrNull { it.state == CallState.INCOMING }
        val waiting = normalizedCalls.firstOrNull { it.state == CallState.WAITING }
        val outgoing = normalizedCalls.firstOrNull {
            it.state == CallState.DIALING || it.state == CallState.ALERTING
        }
        // 会议也可能被保持：active 或 held 上的 multiParty 都算会议。
        val conference = active?.takeIf { it.isMultiParty }
            ?: held?.takeIf { it.isMultiParty }
        val peer = if (conference != null) currentDeviceId?.let { conferencePeers[it] } else null

        val audio = currentDeviceId?.let { audioByDevice[it] }
        val audioRoute = if (normalizedCalls.isNotEmpty()) {
            if (audio?.state == AudioState.CONNECTED || audio?.state == AudioState.CONNECTING) {
                AudioRoute.CAR
            } else {
                AudioRoute.PHONE
            }
        } else {
            null
        }

        val uiState = when {
            // 新来电优先于其他阶段，确保会议/通话中也能立刻展示第二路。
            waiting != null -> CallUiState.WAITING
            incoming != null -> CallUiState.INCOMING
            // 活动会议是主界面；但活动普通通话 + 保持会议属于多路通话。
            active?.isMultiParty == true -> CallUiState.CONFERENCE
            active != null && (held != null || outgoing != null) -> CallUiState.MULTI_CALL
            active != null -> CallUiState.ACTIVE
            outgoing != null && held == null -> CallUiState.OUTGOING
            normalizedCalls.count { it.state != CallState.TERMINATED } > 1 -> CallUiState.MULTI_CALL
            held?.isMultiParty == true -> CallUiState.CONFERENCE
            held != null -> CallUiState.HELD
            else -> CallUiState.IDLE
        }

        _state.value = PhoneCallState(
            calls = normalizedCalls,
            active = active,
            held = held,
            incoming = incoming,
            waiting = waiting,
            conference = conference,
            conferencePeer = peer,
            outgoing = outgoing,
            uiState = uiState,
            audio = audio?.state ?: AudioState.DISCONNECTED,
            audioWideband = audio?.wideband == true,
            audioRoute = audioRoute,
            micMuted = micMuted,
            conferenceMergeSupported = conferenceMergeSupported,
            deviceId = currentDeviceId,
            deviceName = currentDeviceName
        )
    }

    /** 把 call 的 elapsed 创建时间换算成墙上时间，异常值退回当前时刻。 */
    private fun Long.createdAtFor(nowElapsed: Long, nowWall: Long): Long =
        takeIf { it in 1..nowElapsed }?.let { nowWall - (nowElapsed - it) } ?: nowWall

    /** 用最新联系人信息刷新一路通话。 */
    private fun CallInfo.withContact(match: ContactMatch?): CallInfo = copy(
        displayName = match?.name ?: number,
        photoUri = match?.photoUri
    )

    /** 按号码后 10 位匹配联系人。 */
    private fun contactMatch(number: String?): ContactMatch? {
        val key = normalize(number) ?: return null
        return contactsByNumber[key]
    }

    /** 去除非数字字符并保留后 10 位，兼容国家码差异。 */
    private fun normalize(number: String?): String? {
        val digits = number.orEmpty().filter { it.isDigit() }
        return digits.takeIf { it.isNotBlank() }?.takeLast(10)
    }

    /** 通话列表排序权重，活动/等待/来电优先展示。 */
    private fun CallState.order(): Int = when (this) {
        CallState.ACTIVE -> 0
        CallState.WAITING -> 1
        CallState.INCOMING -> 2
        CallState.DIALING -> 3
        CallState.ALERTING -> 4
        CallState.HELD -> 5
        CallState.HELD_BY_RESPONSE -> 6
        CallState.TERMINATED -> 7
    }
}
