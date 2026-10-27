package com.bluetooth.phone.api

import java.util.Locale

/** 单个蓝牙 Profile 的连接状态。 */
enum class ProfileState {
    /** 未连接。 */
    DISCONNECTED,

    /** 连接中。 */
    CONNECTING,

    /** 已连接。 */
    CONNECTED,

    /** 断开中。 */
    DISCONNECTING,

    /** 系统未返回有效状态。 */
    UNKNOWN;

    /** 是否已连接。 */
    val isConnected: Boolean get() = this == CONNECTED
}

/** 蓝牙配对状态。 */
enum class BondState {
    /** 未配对。 */
    NONE,
    /** 配对中。 */
    BONDING,
    /** 已配对。 */
    BONDED,
    /** 系统未返回有效状态。 */
    UNKNOWN
}

/** HFP 通话状态，已按车机 UI 需要做归一化。 */
enum class CallState {
    /** 普通来电振铃。 */
    INCOMING,
    /** 通话中第二路来电。 */
    WAITING,
    /** 去电拨号中。 */
    DIALING,
    /** 对方振铃中。 */
    ALERTING,
    /** 通话中。 */
    ACTIVE,
    /** 已保持。 */
    HELD,
    /** 由协议栈标记的“响应方保持”，界面按普通保持处理。 */
    HELD_BY_RESPONSE,
    /** 已结束。 */
    TERMINATED;

    /** 是否是响铃中的来电（含第二路）。 */
    val isIncoming: Boolean get() = this == INCOMING || this == WAITING

    /** 是否处于保持状态。 */
    val isHeld: Boolean get() = this == HELD || this == HELD_BY_RESPONSE
}

/** 通话方向。 */
enum class CallDirection {
    /** 来电。 */
    INCOMING,

    /** 去电。 */
    OUTGOING,

    /** 方向未知（如会议无号码）。 */
    UNKNOWN
}

/**
 * 通话来源。
 *
 * HFP 只提供号码，不携带应用包名；微信/VoIP 等需要上层分类器识别。
 */
enum class CallKind {
    /** 普通蜂窝电话。 */
    CELLULAR,
    /** 微信、VoIP 等网络电话。 */
    VOIP,
    /** 无法判断。 */
    UNKNOWN
}

/**
 * 供 HMI 直接切换界面的通话阶段。
 *
 * 三方通话典型流转：
 * INCOMING/OUTGOING -> ACTIVE -> WAITING（第二路来电）
 * -> MULTI_CALL（接听并保持）-> CONFERENCE（合并）。
 */
enum class CallUiState {
    /** 无通话。 */
    IDLE,
    /** 单路来电响铃。 */
    INCOMING,
    /** 去电拨号或对方振铃，尚未接通。 */
    OUTGOING,
    /** 单路通话中。 */
    ACTIVE,
    /** 只有一路保持中的通话。 */
    HELD,
    /** 通话中收到第二路来电。 */
    WAITING,
    /** 两路及以上普通通话：如活动+保持、活动+去电。 */
    MULTI_CALL,
    /** 会议通话。 */
    CONFERENCE
}

/**
 * 号码来源分类器。
 *
 * HFP 不携带应用包名，无法直接判断微信/VoIP；
 * 车机可注入自己的识别规则，例如号码前缀、联系人号码库或厂商通道标记。
 */
fun interface CallClassifier {
    /**
     * 返回该号码对应的通话来源；无法判断时返回 [CallKind.UNKNOWN]。
     * 会在通话事件线程同步调用，实现必须轻量、无阻塞。
     */
    fun classify(number: String?): CallKind
}

/** HFP 通话音频（SCO/eSCO）连接状态。 */
enum class AudioState {
    /** 音频链路未建立。 */
    DISCONNECTED,

    /** 正在建立音频链路。 */
    CONNECTING,

    /** 音频已连接到车机。 */
    CONNECTED,

    /** 系统未返回有效状态。 */
    UNKNOWN
}

/** 音频路由：车机或手机。 */
enum class AudioRoute {
    /** 通话音频在车机（SCO/eSCO）。 */
    CAR,

    /** 通话音频在手机。 */
    PHONE
}

/** 中间件整体连接状态，可直接用于首页/状态栏。 */
enum class PhoneConnectionState {
    /** 蓝牙未开启。 */
    BLUETOOTH_OFF,
    /** 蓝牙已开但没有已连接手机。 */
    NO_DEVICE,
    /** 正在连接手机。 */
    CONNECTING,
    /** 至少一台手机已连接。 */
    CONNECTED
}

/**
 * 一台已配对手机。
 *
 * 车机无 SIM 时，电话、信号、电量都来自这台手机；不要用 TelephonyManager 判断。
 */
data class PhoneDevice(
    /** MAC 地址，设备唯一标识。 */
    val address: String,
    /** 蓝牙名称；系统已优先取别名。 */
    val name: String? = null,
    /** 配对状态。 */
    val bondState: BondState = BondState.UNKNOWN,
    /** HFP 连接状态。 */
    val hfpState: ProfileState = ProfileState.DISCONNECTED,
    /** 是否为当前操作设备。 */
    val isActive: Boolean = false,
    /** 手机电量百分比。 */
    val battery: Int? = null,
    /** HFP 信号等级（通常 0–5，不是 dBm）。 */
    val signal: Int? = null,
    /** 是否漫游。 */
    val roaming: Boolean? = null,
    /** 运营商名称。 */
    val operatorName: String? = null,
    /** 该设备的 SCO/eSCO 状态。 */
    val audioState: AudioState = AudioState.DISCONNECTED
) {
    /** 界面展示名称：蓝牙名 > 地址。 */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address

    /** HFP 是否已连接。 */
    val isConnected: Boolean get() = hfpState.isConnected
}

/** 一路 HFP 通话。会议通话由 [isMultiParty] 标记。 */
data class CallInfo(
    /** HFP call 标识，同一次连接内稳定。 */
    val id: String,
    /** 所属手机地址。 */
    val deviceId: String,
    /** 归一化后的通话状态。 */
    val state: CallState,
    /** 通话方向。 */
    val direction: CallDirection,
    /** 号码；会议可能为空。 */
    val number: String? = null,
    /** 展示名称：联系人姓名 > 号码。 */
    val displayName: String? = null,
    /** 联系人头像 URI，来自 ContactsProvider；无权限或未匹配时为 null。 */
    val photoUri: String? = null,
    /** 是否为会议通话（多方）。 */
    val isMultiParty: Boolean = false,
    /** 是否为带内铃声（手机侧已播放铃声）。 */
    val isInBandRing: Boolean = false,
    /** 通话来源；默认未知，可由 [CallClassifier] 补充。 */
    val kind: CallKind = CallKind.UNKNOWN,
    /** 接通时刻（墙上时间）；未接通为 null，避免把振铃时长计入通话时长。 */
    val startedAtMillis: Long? = null
) {
    /** 是否是当前活动通话。 */
    val isActive: Boolean get() = state == CallState.ACTIVE

    /** 是否是响铃中的来电。 */
    val isIncoming: Boolean get() = state.isIncoming

    /** 是否处于保持状态。 */
    val isHeld: Boolean get() = state.isHeld

    /** 是否是微信/VoIP 等网络电话。 */
    val isVoip: Boolean get() = kind == CallKind.VOIP

    /** 界面标题：联系人 > 号码 > 会议/未知号码。 */
    fun title(): String {
        if (isMultiParty && number.isNullOrBlank()) return "会议通话"
        return displayName?.takeIf { it.isNotBlank() }
            ?: number?.takeIf { it.isNotBlank() }
            ?: "未知号码"
    }

    /**
     * 是否处于计时状态：已真正接通（活动或保持）。
     * 保持期间话路不释放，运营商仍按“接通到挂断”计费，因此保持继续计时；
     * 振铃、拨号阶段尚未接通，不计入通话时长。
     */
    val isTimed: Boolean
        get() = startedAtMillis != null && (state == CallState.ACTIVE || state.isHeld)

    /** 已持续通话秒数（含保持时间，不含振铃/拨号）；未接通时返回 0。 */
    fun durationSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        if (!isTimed) return 0
        val start = startedAtMillis ?: return 0
        return ((nowMillis - start) / 1000).coerceAtLeast(0)
    }

    /** 已持续通话时长文本；未接通时返回空串，界面可直接显示。 */
    fun durationText(nowMillis: Long = System.currentTimeMillis()): String =
        if (isTimed) formatDuration(durationSeconds(nowMillis)) else ""
}

/**
 * 车机侧统一通话快照。
 *
 * 三方通话的典型状态：
 * - 通话中接到第二路：active + waiting
 * - 接听并保持第一路：active + held
 * - 合并：conference 非空，active.isMultiParty = true
 */
data class PhoneCallState(
    /** 当前设备全部未结束通话，按展示优先级排序。 */
    val calls: List<CallInfo> = emptyList(),
    /** 活动通话。 */
    val active: CallInfo? = null,
    /** 保持中的通话（含被保持的会议）。 */
    val held: CallInfo? = null,
    /** 普通来电（仅在没有活动通话时出现）。 */
    val incoming: CallInfo? = null,
    /** 通话中的第二路来电。 */
    val waiting: CallInfo? = null,
    /** 当前会议通话；可能处于活动或保持状态。 */
    val conference: CallInfo? = null,
    /**
     * 合并会议前的第二路快照，仅用于展示姓名/号码/头像；
     * 其 state 不再代表实时状态，会议状态请以 [conference] 为准。
     */
    val conferencePeer: CallInfo? = null,
    /** 去电中的一路（DIALING/ALERTING），用于加拨第二路界面。 */
    val outgoing: CallInfo? = null,
    /** 界面阶段，HMI 可直接 when 切换布局。 */
    val uiState: CallUiState = CallUiState.IDLE,
    /** SCO/eSCO 音频状态。 */
    val audio: AudioState = AudioState.DISCONNECTED,
    /** 是否宽带语音（mSBC）。 */
    val audioWideband: Boolean = false,
    /** 当前音频路由；无通话时为 null，避免空闲时误显示“车机音频”。 */
    val audioRoute: AudioRoute? = null,
    /** 中间件设置的车机麦克风静音状态；只影响本地采集，不影响手机侧。 */
    val micMuted: Boolean = false,
    /** 当前系统/配置是否具备会议合并通道，不代表手机一定支持。 */
    val conferenceMergeSupported: Boolean = false,
    /** 当前数据源手机地址。 */
    val deviceId: String? = null,
    /** 当前数据源手机名称。 */
    val deviceName: String? = null
) {
    /** 是否存在未结束的通话。 */
    val hasCall: Boolean get() = calls.any { it.state != CallState.TERMINATED }

    /** 当前响铃的一路：普通来电优先，其次第二路。 */
    val ringingCall: CallInfo? get() = incoming ?: waiting

    /** 是否有来电/等待来电在响铃。 */
    val isRinging: Boolean get() = ringingCall != null

    /** 当前是否是会议通话。 */
    val isConference: Boolean get() = conference != null

    /** 是否已有接通/保持/去电中的通话；来电未接听前不应建立 SCO 音频。 */
    val hasVoiceSession: Boolean
        get() = active != null || held != null || outgoing != null

    /** 是否存在已接通、需要计时的通话（会议对方也计入）。 */
    val hasTimedCall: Boolean
        get() = calls.any { it.isTimed } || conferencePeer?.isTimed == true

    /** 是否存在两路及以上通话。 */
    val hasMultipleCalls: Boolean
        get() = calls.count { it.state != CallState.TERMINATED } > 1

    /** 是否处于三方通话：两路通话或已合并为会议。 */
    val isThreeWayCall: Boolean get() = isConference || hasMultipleCalls

    /** 是否具备三方通话合并能力（取决于系统栈和手机协议栈）。 */
    val isThreeWayEnabled: Boolean get() = conferenceMergeSupported

    /** 当前主展示的一路：活动 > 来电 > 等待 > 去电 > 保持。 */
    val primaryCall: CallInfo?
        get() = active ?: incoming ?: waiting ?: outgoing ?: held

    /** 第二路通话：等待 > 普通保持 > 去电 > 会议对方，供三方界面展示。 */
    val secondaryCall: CallInfo?
        get() = when {
            waiting != null -> waiting
            held != null && !held.isMultiParty -> held
            outgoing != null -> outgoing
            else -> conferencePeer
        }

    /** 当前是否存在微信/VoIP 通话，由 [CallClassifier] 标记。 */
    val isVoipCall: Boolean
        get() = calls.any { it.isVoip } || conferencePeer?.isVoip == true

    /**
     * 是否可以接听响铃中的一路：
     * 普通来电，或没有活动通话时被 AG 单独上报的等待来电。
     */
    val canAnswer: Boolean
        get() = incoming != null || (waiting != null && active == null)

    /** 是否可以拒接来电或第二路。 */
    val canReject: Boolean get() = incoming != null || waiting != null

    /** 是否有可挂断的活动通话。 */
    val canHangup: Boolean get() = active != null

    /** 是否有可取消的去电（对方尚未接听）。 */
    val canCancelOutgoing: Boolean get() = outgoing != null

    /** 是否可以在活动/保持之间切换；有等待来电或去电时禁止。 */
    val canSwap: Boolean
        get() = active != null && held != null && waiting == null && outgoing == null

    /** 是否可以合并为会议：需活动+保持两路，且无等待/去电，系统提供合并通道。 */
    val canMerge: Boolean
        get() = active != null && held != null && waiting == null && outgoing == null &&
            !active.isMultiParty && !held.isMultiParty && conferenceMergeSupported

    /**
     * 是否可以保持/切换当前通话。
     * 有等待来电时 CHLD=2 会接听，有去电时切换目标不明确，两种情况都禁止。
     */
    val canHold: Boolean get() = active != null && waiting == null && outgoing == null

    /** 是否可以恢复保持中的通话（有活动通话、去电或等待来电时先处理当前）。 */
    val canResume: Boolean
        get() = active == null && held != null && waiting == null && outgoing == null

    /** 是否可以挂断保持中的通话。 */
    val canHangupHeld: Boolean get() = held != null

    /** 是否可以只挂断第二路等待来电。 */
    val canHangupWaiting: Boolean get() = waiting != null

    /** 是否可以在车机/手机之间切换音频。 */
    val canToggleAudio: Boolean get() = hasVoiceSession

    /** 是否可以切换车机麦克风静音。 */
    val canToggleMic: Boolean get() = hasVoiceSession

    /**
     * 是否可以再加拨一路：
     * 恰好一路已接通（活动或保持），且没有等待来电、去电中的通话和会议。
     * 保持后仍可拨第二路，HFP 会在拨号时自动保持原通话。
     */
    val canAddCall: Boolean
        get() {
            if (waiting != null || outgoing != null) return false
            val connected = calls.filter { it.state != CallState.TERMINATED }
            if (connected.size != 1) return false
            val only = connected.first()
            return !only.isMultiParty && (only.isActive || only.isHeld)
        }

    /** 是否可以“接听第二路并保持当前”。 */
    val canAnswerWaitingAndHold: Boolean get() = waiting != null && active != null

    /** 是否可以“接听第二路并挂断当前”。 */
    val canAnswerWaitingAndEndActive: Boolean get() = waiting != null && active != null
}

/**
 * 把秒数格式化为 mm:ss；超过一小时显示 h:mm:ss。
 * 放在模型层，避免各 HMI 各写一套格式。
 */
fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainder = seconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, remainder)
    }
}

/** 联系人条目，来自系统 ContactsProvider。 */
data class Contact(
    /** 联系人 ID。 */
    val id: Long,
    /** 显示名称。 */
    val name: String?,
    /** 号码。 */
    val number: String,
    /** 头像 content URI。 */
    val photoUri: String? = null,
    /** 系统号码类型（手机/家庭/工作等）。 */
    val type: Int = 0
)

/** 通话记录条目，来自系统 CallLog。 */
data class CallLogEntry(
    /** 记录 ID。 */
    val id: Long,
    /** 号码。 */
    val number: String?,
    /** 缓存的联系人名称。 */
    val name: String?,
    /** 系统原始类型：1 来电、2 去电、3 未接等。 */
    val type: Int,
    /** 发生时间（墙上时间）。 */
    val dateMillis: Long,
    /** 通话时长（秒）。 */
    val durationSeconds: Long
)

/** 一次性事件，适合 Toast、Snackbar 或日志；持续状态请用 StateFlow。 */
sealed interface PhoneEvent {
    /** 普通提示，如服务重连。 */
    data class Info(
        /** 提示文案。 */
        val message: String
    ) : PhoneEvent

    /** 错误提示，如权限缺失或 HFP 调用失败。 */
    data class Error(
        /** 错误文案。 */
        val message: String
    ) : PhoneEvent

    /** 通话阶段变化；适合埋点或触发一次性动画。 */
    data class CallUiChanged(
        /** 变化前阶段。 */
        val previous: CallUiState,
        /** 变化后阶段。 */
        val current: CallUiState
    ) : PhoneEvent

    /** 某一路通话结束；适合日志、提示或局部动画。 */
    data class CallEnded(
        /** 结束的通话快照。 */
        val call: CallInfo
    ) : PhoneEvent

    /** 当前手机的所有通话结束；用于展示“通话结束”页。 */
    data class SessionEnded(
        /** 最后结束的一路；无信息时为 null。 */
        val lastCall: CallInfo?
    ) : PhoneEvent
}
