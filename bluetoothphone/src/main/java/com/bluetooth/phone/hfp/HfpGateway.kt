package com.bluetooth.phone.hfp

import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.BondState
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.ProfileState
import kotlinx.coroutines.flow.SharedFlow

/** 网关层的设备快照，字段与 BluetoothDevice 解耦。 */
internal data class GatewayDevice(
    val address: String,
    val name: String?,
    val bondState: BondState,
    val hfpState: ProfileState
)

/** 网关层的一路 HFP 通话，尚未做 UI 归并。 */
internal data class HfpCall(
    val id: String,
    val state: CallState,
    val number: String?,
    val isMultiParty: Boolean,
    val isOutgoing: Boolean,
    val isInBandRing: Boolean,
    val createdAtElapsed: Long
)

/** AG（手机侧）上报的信号/电量/运营商信息。 */
internal data class HfpAgState(
    val battery: Int? = null,
    val signal: Int? = null,
    val roaming: Boolean? = null,
    val operatorName: String? = null,
    val inBandRing: Boolean? = null
)

/** 网关向上层发送的统一事件。 */
internal sealed interface HfpEvent {
    /** 蓝牙开关变化。 */
    data class AdapterChanged(val enabled: Boolean) : HfpEvent

    /** 配对关系变化。 */
    data class BondChanged(val address: String) : HfpEvent

    /** HFP Profile 连接状态变化。 */
    data class ConnectionChanged(val address: String, val state: ProfileState) : HfpEvent

    /** 通话音频状态变化。 */
    data class AudioChanged(
        val address: String,
        val state: AudioState,
        val wideband: Boolean
    ) : HfpEvent

    /** 当前通话列表变化，是三方通话状态的来源。 */
    data class CallsChanged(val address: String, val calls: List<HfpCall>) : HfpEvent

    /** 信号/电量/运营商变化。 */
    data class AgChanged(val address: String, val ag: HfpAgState) : HfpEvent

    /** 系统蓝牙服务进程重启，需要重新获取代理。 */
    data object ServiceLost : HfpEvent

    /** 网关内部错误，向上转为 PhoneEvent.Error。 */
    data class Error(val message: String) : HfpEvent
}

/** 接听策略，对应 HFP AT+CHLD 的不同分支。 */
internal enum class AnswerPolicy {
    /** 普通接听。 */
    NORMAL,
    /** 接听并保持当前通话（CHLD=2）。 */
    HOLD_ACTIVE,
    /** 接听并挂断当前通话（CHLD=1）。 */
    END_ACTIVE
}

/**
 * HFP HF 能力的边界。
 *
 * Android 14 的 BluetoothHeadsetClient 属于 @SystemApi，公开 SDK 不可直接引用；
 * 系统实现放在 SystemHfpGateway，通过反射隔离。测试/演示可替换为 FakeHfpGateway。
 */
internal interface HfpGateway {
    /** 网关事件流，由 PhoneEngine 统一消费。 */
    val events: SharedFlow<HfpEvent>

    /** HFP Client 代理是否已就绪。 */
    val isReady: Boolean

    /** 当前系统栈/配置是否提供会议合并通道（不代表手机一定支持 CHLD=3）。 */
    val supportsConference: Boolean

    /** 初始化监听并获取系统代理。 */
    fun start()

    /** 释放代理与广播监听。 */
    fun stop()

    /** 系统蓝牙是否已开启。 */
    fun isBluetoothEnabled(): Boolean

    /** 读取所有已配对设备。 */
    fun bondedDevices(): List<GatewayDevice>

    /** 查询指定设备的 HFP 连接状态。 */
    fun connectionState(address: String): ProfileState

    /** 主动拉取一次通话列表、AG 与音频状态。 */
    fun refresh(address: String)

    /** 发起配对。 */
    fun createBond(address: String): Boolean

    /** 解除配对。 */
    fun removeBond(address: String): Boolean

    /** 建立 HFP 连接。 */
    fun connect(address: String): Boolean

    /** 断开 HFP 连接。 */
    fun disconnect(address: String): Boolean

    /** 拨号（ATD）。 */
    fun dial(address: String, number: String): Boolean

    /** 按策略接听来电（ATA / CHLD）。 */
    fun acceptCall(address: String, policy: AnswerPolicy): Boolean

    /** 拒接来电（AT+CHUP）。 */
    fun rejectCall(address: String): Boolean

    /** 挂断指定通话。 */
    fun hangup(address: String, callId: String): Boolean

    /** 保持/恢复/切换（AT+CHLD=2）。 */
    fun hold(address: String): Boolean

    /** 发送 DTMF 按键。 */
    fun sendDtmf(address: String, code: Char): Boolean

    /** 建立通话音频（SCO/eSCO）。 */
    fun connectAudio(address: String): Boolean

    /** 断开通话音频，把声音交回手机。 */
    fun disconnectAudio(address: String): Boolean

    /** 允许/禁止系统把通话音频路由到车机。 */
    fun setAudioRouteAllowed(address: String, allowed: Boolean): Boolean

    /** 标准 HFP CHLD=3；AOSP 14 未暴露时返回 false，由系统/厂商扩展补足。 */
    fun conference(address: String): Boolean

    /** 启动/停止手机侧语音识别。 */
    fun voiceRecognition(address: String, start: Boolean): Boolean
}

/** 仅调试/模拟网关实现，用于主动构造来电。 */
internal interface DebugCallSimulator {
    /** 构造一路模拟来电；有活动通话时自动变为等待来电。 */
    fun simulateIncomingCall(number: String)
}
