package com.bluetooth.phone.hfp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import com.bluetooth.phone.BluetoothPhoneConfig
import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.BondState
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.PhoneLogLevel
import com.bluetooth.phone.api.ProfileState
import com.bluetooth.phone.core.PhoneLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.forEach

/**
 * Android 14 HFP Client 适配器。
 *
 * 公开 SDK 不包含 BluetoothHeadsetClient，因此这里用反射隔离系统 API；
 * 真正的车机项目应平台签名 + priv-app + BLUETOOTH_PRIVILEGED。
 * 如果芯片厂商提供了 HFP Client SDK，只需替换本类，不影响上层。
 */
@SuppressLint("MissingPermission")
internal class SystemHfpGateway(
    private val context: Context,
    private val config: BluetoothPhoneConfig
) : HfpGateway {

    private companion object {
        /** BluetoothProfile.HEADSET_CLIENT；公开 SDK 未导出，故写常量。 */
        const val PROFILE_HEADSET_CLIENT = 16 // BluetoothProfile.HEADSET_CLIENT

        // 系统 HFP Client 广播 action（@SystemApi 字符串常量）。
        const val ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val ACTION_AG_EVENT =
            "android.bluetooth.headsetclient.profile.action.AG_EVENT"
        const val ACTION_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"

        const val EXTRA_AUDIO_WBS =
            "android.bluetooth.headsetclient.extra.AUDIO_WBS"
        const val EXTRA_NETWORK_SIGNAL_STRENGTH =
            "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH"
        const val EXTRA_BATTERY_LEVEL =
            "android.bluetooth.headsetclient.extra.BATTERY_LEVEL"
        const val EXTRA_NETWORK_ROAMING =
            "android.bluetooth.headsetclient.extra.NETWORK_ROAMING"
        const val EXTRA_OPERATOR_NAME =
            "android.bluetooth.headsetclient.extra.OPERATOR_NAME"
        const val EXTRA_IN_BAND_RING =
            "android.bluetooth.headsetclient.extra.IN_BAND_RING"
    }

    /** 网关事件流；缓冲区避免广播线程被阻塞。 */
    private val _events = MutableSharedFlow<HfpEvent>(
        extraBufferCapacity = 64
    )
    override val events: SharedFlow<HfpEvent> = _events.asSharedFlow()

    /** 系统蓝牙入口。 */
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    /** 反射拿到的 BluetoothHeadsetClient 类；为 null 表示系统不支持。 */
    private val clientClass: Class<*>? = runCatching {
        Class.forName("android.bluetooth.BluetoothHeadsetClient")
    }.getOrNull()

    /** 反射方法缓存，只缓存找到的方法，避免 ConcurrentHashMap 存 null。 */
    private val methodCache = ConcurrentHashMap<String, Method>()

    /** 不存在的方法只探测一次，避免反复反射并刷日志。 */
    private val missingMethods = ConcurrentHashMap.newKeySet<String>()

    /** 通话对象缓存：terminateCall 必须传回系统原始 call 对象。 */
    private val callRefs = ConcurrentHashMap<String, Any>()

    /** 最近一次广播的音频状态，避免主动补查把宽带标记覆盖成窄带。 */
    private val lastAudioState = ConcurrentHashMap<String, AudioState>()

    /** HFP Client 代理；由主线程的 ServiceListener/广播写入，引擎线程读取。 */
    @Volatile
    private var client: Any? = null

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var started = false

    /** 防止重复请求代理。 */
    @Volatile
    private var proxyRequested = false

    /** 权限错误只上报一次，避免刷屏。 */
    private var missingPrivilegedReported = false

    /** 系统蓝牙广播 → 统一网关事件。 */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 广播是状态变化的最初来源；VERBOSE 级留痕，避免高频事件刷屏。
            PhoneLog.hfp.v { "广播 ${intent.action}" }
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // TURNING_ON/OFF 只是中间态，只按最终 ON/OFF 上报，避免提前清空通话。
                    when (
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    ) {
                        BluetoothAdapter.STATE_ON ->
                            _events.tryEmit(HfpEvent.AdapterChanged(true))

                        BluetoothAdapter.STATE_OFF ->
                            _events.tryEmit(HfpEvent.AdapterChanged(false))

                        else -> Unit
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    intent.device()?.let { _events.tryEmit(HfpEvent.BondChanged(it.address)) }
                }

                ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    val state = profileState(
                        intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    )
                    _events.tryEmit(HfpEvent.ConnectionChanged(device.address, state))
                    if (state.isConnected) refresh(device.address)
                }

                ACTION_AUDIO_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    val state = audioState(
                        intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE,
                            BluetoothProfile.STATE_DISCONNECTED
                        )
                    )
                    lastAudioState[device.address] = state
                    _events.tryEmit(
                        HfpEvent.AudioChanged(
                            address = device.address,
                            state = state,
                            wideband = intent.getBooleanExtra(EXTRA_AUDIO_WBS, false)
                        )
                    )
                }

                ACTION_AG_EVENT -> {
                    intent.device()?.let { refreshAg(it) }
                }

                ACTION_CALL_CHANGED -> {
                    val device = intent.device()
                    if (device != null) refreshCalls(device)
                    else connectedDevices().forEach { refreshCalls(it) }
                }
            }
        }
    }

    /** 获取 HFP Client 代理，并补发一次已连接设备状态。 */
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        /** 代理就绪：记录代理并补发已连接设备状态。 */
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != PROFILE_HEADSET_CLIENT) return
            proxyRequested = false
            client = proxy
            PhoneLog.hfp.i { "HFP Client 代理就绪" }
            // getProfileProxy 是异步的；代理就绪后补发一次已连接状态，
            // 否则启动瞬间拿到的 DISCONNECTED 会一直留在设备列表里。
            connectedDevices().forEach { device ->
                setAudioRouteAllowed(device.address, true)
                _events.tryEmit(
                    HfpEvent.ConnectionChanged(device.address, ProfileState.CONNECTED)
                )
            }
            refreshAll()
        }

        /** 系统回收代理：清空缓存，必要时由引擎重建。 */
        override fun onServiceDisconnected(profile: Int) {
            if (profile != PROFILE_HEADSET_CLIENT) return
            proxyRequested = false
            client = null
            callRefs.clear()
            PhoneLog.hfp.w { "HFP Client 代理断开，等待重建" }
            // 主动 stop() 时 started 已置 false，不应触发重连。
            if (started) _events.tryEmit(HfpEvent.ServiceLost)
        }
    }

    /** 代理是否已就绪；所有 HFP 调用都依赖它。 */
    override val isReady: Boolean get() = client != null

    /** 是否能找到厂商会议方法或厂商 AT 通道；不探测手机侧 CHLD=3 支持。 */
    override val supportsConference: Boolean
        get() {
            val vendorMethod = listOf("conferenceCall", "mergeCall", "mergeCalls")
                .any { method(it, 1) != null }
            val vendorAt = config.conferenceAtCommand.isNotBlank() &&
                method("sendVendorAtCommand", 3) != null
            return vendorMethod || vendorAt
        }

    /** 初始化权限检查、广播监听并请求代理；可重复调用做补偿。 */
    override fun start() {
        if (!started) {
            started = true
            PhoneLog.hfp.i { "HFP 网关启动" }
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                PhoneLog.hfp.w { "缺少 BLUETOOTH_CONNECT 权限" }
                _events.tryEmit(HfpEvent.AdapterChanged(false))
                _events.tryEmit(HfpEvent.Error("缺少 BLUETOOTH_CONNECT 权限"))
            }
            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED")
                != PackageManager.PERMISSION_GRANTED
            ) {
                PhoneLog.hfp.w { "缺少 BLUETOOTH_PRIVILEGED，HFP Client 不可用" }
                _events.tryEmit(HfpEvent.Error("HFP Client 需要系统签名与 BLUETOOTH_PRIVILEGED"))
            }
            if (clientClass == null) {
                PhoneLog.hfp.e { "当前系统不包含 BluetoothHeadsetClient" }
                _events.tryEmit(HfpEvent.Error("当前系统不包含 BluetoothHeadsetClient"))
            }
            val enabled = isBluetoothEnabled()
            PhoneLog.hfp.i { "蓝牙开关: ${if (enabled) "开" else "关"}" }
            _events.tryEmit(HfpEvent.AdapterChanged(enabled))
        }

        // 权限可能在运行中才授予，重复调用 start() 时补注册、补拿代理。
        if (!receiverRegistered) {
            runCatching { registerReceiver() }
                .onFailure {
                    PhoneLog.hfp.w(it) { "注册蓝牙广播失败" }
                    _events.tryEmit(HfpEvent.Error("注册蓝牙广播失败: ${it.message}"))
                }
        }
        if (client == null && !proxyRequested) {
            proxyRequested = true
            PhoneLog.hfp.d { "请求 HFP Client 代理" }
            if (!requestProxy()) proxyRequested = false
        }
    }

    /** getProfileProxy 是异步的；返回 false 表示这次请求没发出去。 */
    private fun requestProxy(): Boolean {
        val requested = runCatching {
            adapter?.getProfileProxy(context, serviceListener, PROFILE_HEADSET_CLIENT) ?: false
        }.getOrElse {
            PhoneLog.hfp.w(it) { "获取 HFP Client 代理异常" }
            _events.tryEmit(HfpEvent.Error("获取 HFP Client 代理失败: ${it.message}"))
            false
        }
        if (!requested) {
            PhoneLog.hfp.w { "HFP Client 代理不可用，请检查系统签名/蓝牙状态" }
            _events.tryEmit(HfpEvent.Error("HFP Client 代理不可用，请检查系统签名/蓝牙状态"))
        }
        return requested
    }

    /** 释放广播与代理；之后需重新 [start]。 */
    override fun stop() {
        if (!started) return
        started = false
        PhoneLog.hfp.i { "HFP 网关停止" }
        proxyRequested = false
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        val proxy = client as? BluetoothProfile
        if (proxy != null) {
            runCatching { adapter?.closeProfileProxy(PROFILE_HEADSET_CLIENT, proxy) }
        }
        client = null
        callRefs.clear()
        lastAudioState.clear()
    }

    /** 读取系统蓝牙开关状态。 */
    override fun isBluetoothEnabled(): Boolean =
        runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    /** 读取已配对设备，并附带各自的 HFP 连接状态。 */
    override fun bondedDevices(): List<GatewayDevice> {
        return runCatching {
            adapter?.bondedDevices.orEmpty().map { device ->
                GatewayDevice(
                    address = device.address,
                    name = runCatching { device.alias }.getOrNull()
                        ?: runCatching { device.name }.getOrNull(),
                    bondState = bondState(device.bondState),
                    hfpState = connectionState(device.address)
                )
            }
        }.getOrElse {
            reportPrivilegedIfNeeded(it)
            emptyList()
        }
    }

    /** 通过反射查询单台设备的 HFP 连接状态。 */
    override fun connectionState(address: String): ProfileState {
        if (client == null) return ProfileState.DISCONNECTED
        val device = remoteDevice(address) ?: return ProfileState.DISCONNECTED
        return profileState(invokeInt("getConnectionState", device) ?: 0)
    }

    /** 主动拉取一次通话列表、AG 与音频状态。 */
    override fun refresh(address: String) {
        val device = remoteDevice(address) ?: return
        refreshCalls(device)
        refreshAg(device)
        refreshAudio(device)
    }

    /** 调用系统 API 配对；配对结果通过广播回传。 */
    override fun createBond(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return runCatching { device.createBond() }.getOrDefault(false)
    }

    /** removeBond 是隐藏 API，只能用反射调用。 */
    override fun removeBond(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return runCatching {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: true
        }.getOrDefault(false)
    }

    /** 建立 HFP 连接（异步，结果看广播）。 */
    override fun connect(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("connect", device)
    }

    /** 断开 HFP 连接（异步，结果看广播）。 */
    override fun disconnect(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("disconnect", device)
    }

    /** 拨号；号码不能为空。 */
    override fun dial(address: String, number: String): Boolean {
        if (number.isBlank()) return false
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("dial", device, number)
    }

    /** 接听来电，按策略转换为系统 acceptCall 的 flag。 */
    override fun acceptCall(address: String, policy: AnswerPolicy): Boolean {
        val device = remoteDevice(address) ?: return false
        val flag = when (policy) {
            AnswerPolicy.NORMAL -> 0       // CALL_ACCEPT_NONE
            AnswerPolicy.HOLD_ACTIVE -> 1  // CALL_ACCEPT_HOLD
            AnswerPolicy.END_ACTIVE -> 2   // CALL_ACCEPT_TERMINATE
        }
        return invokeBoolean("acceptCall", device, flag)
    }

    /** 拒接普通来电或第二路等待。 */
    override fun rejectCall(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("rejectCall", device)
    }

    /** 挂断指定通话；需要原始 call 对象。 */
    override fun hangup(address: String, callId: String): Boolean {
        val device = remoteDevice(address) ?: return false
        // terminateCall 必须带原始 call 对象；缓存缺失时先刷新一次再重试。
        val call = callRefs["$address|$callId"] ?: run {
            PhoneLog.hfp.d { "hangup($callId): 原始 call 缓存缺失，刷新后重试" }
            refreshCalls(device)
            callRefs["$address|$callId"] ?: return false
        }
        return invokeBoolean("terminateCall", device, call)
    }

    /** AT+CHLD=2：保持、恢复或互换。 */
    override fun hold(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("holdCall", device)
    }

    /** 发送 DTMF 单字符。 */
    override fun sendDtmf(address: String, code: Char): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("sendDTMF", device, code.code.toByte())
    }

    /** 建立 SCO/eSCO 通话音频。 */
    override fun connectAudio(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("connectAudio", device)
    }

    /** 断开 SCO/eSCO，把音频交回手机。 */
    override fun disconnectAudio(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean("disconnectAudio", device)
    }

    /** 允许系统把通话音频路由到车机；失败不影响主流程。 */
    override fun setAudioRouteAllowed(address: String, allowed: Boolean): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeExists("setAudioRouteAllowed", device, allowed)
    }

    /** 合并会议：先试厂商方法，再用厂商 AT 通道发 CHLD=3。 */
    override fun conference(address: String): Boolean {
        val device = remoteDevice(address) ?: return false

        // 部分芯片/车机栈会在 BluetoothHeadsetClient 上补会议合并方法。
        listOf("conferenceCall", "mergeCall", "mergeCalls").forEach { name ->
            if (method(name, 1) != null) {
                PhoneLog.hfp.d { "会议合并: 尝试厂商方法 $name" }
                if (invokeBoolean(name, device)) return true
            }
        }

        // AOSP 14 的兜底：允许通过厂商 AT 通道发送 CHLD=3。
        val vendorAt = method("sendVendorAtCommand", 3)
        if (vendorAt != null) {
            PhoneLog.hfp.d { "会议合并: 使用厂商 AT 通道 ${config.conferenceAtCommand}" }
        }
        return vendorAt != null && invokeBoolean(
            "sendVendorAtCommand",
            device,
            config.conferenceVendorId,
            config.conferenceAtCommand
        )
    }

    /** 启动/停止手机侧语音识别。 */
    override fun voiceRecognition(address: String, start: Boolean): Boolean {
        val device = remoteDevice(address) ?: return false
        return invokeBoolean(
            if (start) "startVoiceRecognition" else "stopVoiceRecognition",
            device
        )
    }

    /** 注册系统蓝牙/HFP 广播；重复调用安全。 */
    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_AUDIO_STATE_CHANGED)
            addAction(ACTION_AG_EVENT)
            addAction(ACTION_CALL_CHANGED)
        }
        // 系统蓝牙广播来自系统进程，必须使用 RECEIVER_EXPORTED 才能收到。
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        PhoneLog.hfp.d { "蓝牙广播已注册" }
    }

    /** 重新拉取所有已连接设备的通话、AG 与音频状态。 */
    private fun refreshAll() {
        connectedDevices().forEach { device ->
            refreshCalls(device)
            refreshAg(device)
            refreshAudio(device)
        }
    }

    /** 拉取指定设备的通话列表，并缓存原始 call 对象。 */
    private fun refreshCalls(device: BluetoothDevice) {
        val rawList = invoke("getCurrentCalls", device) as? List<*> ?: return
        val calls = rawList.mapNotNull { raw -> rawCall(raw) }
        PhoneLog.hfp.d {
            "通话列表 ${device.address}: 原始 ${rawList.size} 路，有效 ${calls.size} 路"
        }
        // 只有 VERBOSE 才展开每一路，避免高频事件刷屏。
        if (PhoneLog.hfp.isEnabled(PhoneLogLevel.VERBOSE)) {
            calls.forEach {
                PhoneLog.hfp.v {
                    "  call id=${it.id}, state=${it.state}, number=${PhoneLog.mask(it.number)}, " +
                        "multi=${it.isMultiParty}, outgoing=${it.isOutgoing}, ring=${it.isInBandRing}"
                }
            }
        }

        callRefs.keys.removeAll { it.startsWith("${device.address}|") }
        rawList.forEach { raw ->
            val id = rawCallId(raw) ?: return@forEach
            callRefs["${device.address}|$id"] = raw as Any
        }
        _events.tryEmit(HfpEvent.CallsChanged(device.address, calls))
    }

    /** 把系统 call 对象转换为内部模型；已结束的忽略。 */
    private fun rawCall(raw: Any?): HfpCall? {
        if (raw == null) return null
        val state = callState(invokeOn(raw, "getState") as? Int ?: 7)
        if (state == CallState.TERMINATED) return null
        return HfpCall(
            id = rawCallId(raw) ?: return null,
            state = state,
            number = invokeOn(raw, "getNumber") as? String,
            isMultiParty = invokeOn(raw, "isMultiParty") as? Boolean ?: false,
            isOutgoing = invokeOn(raw, "isOutgoing") as? Boolean ?: false,
            isInBandRing = invokeOn(raw, "isInBandRing") as? Boolean ?: false,
            createdAtElapsed = invokeOn(raw, "getCreationElapsedMilli") as? Long
                ?: SystemClock.elapsedRealtime()
        )
    }

    /** 读取 call id；不同版本可能用 getId 或 getUUID。 */
    private fun rawCallId(raw: Any?): String? {
        if (raw == null) return null
        return (invokeOn(raw, "getId") as? Int)?.toString()
            ?: (invokeOn(raw, "getUUID")?.toString())
    }

    /** 拉取 AG 上报的电量/信号/运营商/带内铃声。 */
    private fun refreshAg(device: BluetoothDevice) {
        val bundle = invoke("getCurrentAgEvents", device) as? Bundle ?: return
        PhoneLog.hfp.v { "AG 事件 ${device.address}: $bundle" }
        _events.tryEmit(
            HfpEvent.AgChanged(
                device.address,
                HfpAgState(
                    battery = bundle.intOrNull(EXTRA_BATTERY_LEVEL),
                    signal = bundle.intOrNull(EXTRA_NETWORK_SIGNAL_STRENGTH),
                    roaming = bundle.intOrNull(EXTRA_NETWORK_ROAMING)?.let { it != 0 },
                    operatorName = bundle.stringOrNull(EXTRA_OPERATOR_NAME),
                    inBandRing = bundle.intOrNull(EXTRA_IN_BAND_RING)?.let { it != 0 }
                )
            )
        )
    }

    /** 补查 SCO/eSCO：车机启动时音频可能已经连接，不能只等广播。 */
    private fun refreshAudio(device: BluetoothDevice) {
        val current = invoke("getAudioState", device) as? Int ?: return
        val state = audioState(current)
        // 广播已经同步过的状态无需重复上报，避免覆盖广播里的宽带标记。
        if (lastAudioState[device.address] == state) return
        PhoneLog.hfp.d { "音频补查 ${device.address}: raw=$current -> $state" }
        lastAudioState[device.address] = state
        _events.tryEmit(HfpEvent.AudioChanged(device.address, state, false))
    }

    /** 读取当前已连接 HFP 的设备。 */
    private fun connectedDevices(): List<BluetoothDevice> {
        if (client == null) return emptyList()
        return runCatching {
            (invoke("getConnectedDevices") as? List<*>)
                .orEmpty()
                .filterIsInstance<BluetoothDevice>()
        }.getOrDefault(emptyList())
    }

    /** 按 MAC 地址获取远程设备。 */
    private fun remoteDevice(address: String): BluetoothDevice? {
        return runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
    }

    /** 按名称+参数个数查找并缓存反射方法。 */
    private fun method(name: String, argCount: Int): Method? {
        val key = "$name/$argCount"
        methodCache[key]?.let { return it }
        // 否定结果也缓存：系统方法表不会在运行期变化，避免反复反射。
        if (key in missingMethods) return null
        val found = clientClass?.methods?.firstOrNull {
            it.name == name && it.parameterCount == argCount
        }
        if (found == null) {
            missingMethods += key
            PhoneLog.hfp.v { "反射方法不存在: $key" }
            return null
        }
        methodCache[key] = found
        return found
    }

    /** 反射调用代理方法；异常统一转成网关错误。 */
    private fun invoke(name: String, vararg args: Any?): Any? {
        val client = client ?: return null
        val method = method(name, args.size) ?: return null
        return try {
            method.invoke(client, *args)
        } catch (e: InvocationTargetException) {
            reportInvokeError(name, e.targetException)
            null
        } catch (e: Throwable) {
            reportInvokeError(name, e)
            null
        }
    }

    /** 反射调用无参方法（如 call.getState）。 */
    private fun invokeOn(target: Any, name: String): Any? {
        return runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.invoke(target)
        }.getOrNull()
    }

    /** 反射调用并返回 Boolean，异常或非 Boolean 结果都当作 false。 */
    private fun invokeBoolean(name: String, vararg args: Any?): Boolean {
        return invoke(name, *args) as? Boolean ?: false
    }

    /** 反射调用并返回 Int，用于 connectionState。 */
    private fun invokeInt(name: String, vararg args: Any?): Int? {
        return invoke(name, *args) as? Int
    }

    /** 只关心调用是否成功、不关心返回值（如 setAudioRouteAllowed）。 */
    private fun invokeExists(name: String, vararg args: Any?): Boolean {
        val client = client ?: return false
        val method = method(name, args.size) ?: return false
        return runCatching { method.invoke(client, *args); true }.getOrDefault(false)
    }

    /** 记录反射异常并上报一次错误事件。 */
    private fun reportInvokeError(name: String, error: Throwable) {
        PhoneLog.hfp.w(error) { "HFP $name 调用失败" }
        _events.tryEmit(HfpEvent.Error("HFP $name 调用失败: ${error.message ?: error.javaClass.simpleName}"))
    }

    /** 首次遇到 SecurityException 时提示需要系统签名。 */
    private fun reportPrivilegedIfNeeded(error: Throwable) {
        if (missingPrivilegedReported) return
        if (error is SecurityException) {
            missingPrivilegedReported = true
            PhoneLog.hfp.w(error) { "蓝牙操作被拒绝，请检查系统签名/BLUETOOTH_PRIVILEGED" }
            _events.tryEmit(HfpEvent.Error("蓝牙操作被拒绝，请检查系统签名/BLUETOOTH_PRIVILEGED"))
        }
    }

    /** 兼容 Android 13+ 的 Parcelable 读取方式。 */
    private fun Intent.device(): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    /** Bundle 中没有该 key 时返回 null，避免默认 0 误导 UI。 */
    private fun Bundle.intOrNull(key: String): Int? =
        if (containsKey(key)) getInt(key) else null

    /** String 版本的空安全读取。 */
    private fun Bundle.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    /** 系统 Profile 状态 → 内部状态。 */
    private fun profileState(value: Int): ProfileState = when (value) {
        BluetoothProfile.STATE_CONNECTED -> ProfileState.CONNECTED
        BluetoothProfile.STATE_CONNECTING -> ProfileState.CONNECTING
        BluetoothProfile.STATE_DISCONNECTING -> ProfileState.DISCONNECTING
        BluetoothProfile.STATE_DISCONNECTED -> ProfileState.DISCONNECTED
        else -> ProfileState.UNKNOWN
    }

    /** 系统配对状态 → 内部状态。 */
    private fun bondState(value: Int): BondState = when (value) {
        BluetoothDevice.BOND_NONE -> BondState.NONE
        BluetoothDevice.BOND_BONDING -> BondState.BONDING
        BluetoothDevice.BOND_BONDED -> BondState.BONDED
        else -> BondState.UNKNOWN
    }

    /**
     * 系统 HFP 音频状态 → 内部状态。
     * HFP Client 广播使用 10/11/12，0/1/2 兼容旧版与部分厂商栈。
     */
    private fun audioState(value: Int): AudioState = when (value) {
        0, 10 -> AudioState.DISCONNECTED
        1, 11 -> AudioState.CONNECTING
        2, 12 -> AudioState.CONNECTED
        else -> AudioState.UNKNOWN
    }

    /** HFP 标准 call state（0..7）→ 内部状态。 */
    private fun callState(value: Int): CallState = when (value) {
        0 -> CallState.ACTIVE
        1 -> CallState.HELD
        2 -> CallState.DIALING
        3 -> CallState.ALERTING
        4 -> CallState.INCOMING
        5 -> CallState.WAITING
        6 -> CallState.HELD_BY_RESPONSE
        else -> CallState.TERMINATED
    }
}
