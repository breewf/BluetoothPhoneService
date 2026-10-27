package com.bluetooth.phone.hfp

import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.BondState
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.ProfileState
import kotlinx.coroutines.flow.SharedFlow

/** 网关层的设备快照，字段与 BluetoothDevice 解耦。 */
internal data class GatewayDevice(
    /** MAC 地址。 */
    val address: String,
    /** 蓝牙名称（别名优先）。 */
    val name: String?,
    /** 配对状态。 */
    val bondState: BondState,
    /** HFP 连接状态。 */
    val hfpState: ProfileState
)

/** 网关层的一路 HFP 通话，尚未做 UI 归并。 */
internal data class HfpCall(
    /** 通话 ID。 */
    val id: String,
    /** 原始通话状态。 */
    val state: CallState,
    /** 号码。 */
    val number: String?,
    /** 是否会议通话。 */
    val isMultiParty: Boolean,
    /** 是否去电。 */
    val isOutgoing: Boolean,
    /** 是否带内铃声：手机侧已播放铃声时，车机不再播放本地铃声。 */
    val isInBandRing: Boolean,
    /** 创建时刻（elapsedRealtime），用于估算启动前的接通时间。 */
    val createdAtElapsed: Long
)

/** AG（手机侧）上报的信号/电量/运营商信息。 */
internal data class HfpAgState(
    /** 电量百分比。 */
    val battery: Int? = null,
    /** 信号等级（通常 0–5）。 */
    val signal: Int? = null,
    /** 是否漫游。 */
    val roaming: Boolean? = null,
    /** 运营商名称。 */
    val operatorName: String? = null
)

/** 网关向上层发送的统一事件。 */
internal sealed interface HfpEvent {
    /** 蓝牙开关变化。 */
    data class AdapterChanged(
        /** 是否已开启。 */
        val enabled: Boolean
    ) : HfpEvent

    /** 配对关系变化。 */
    data class BondChanged(
        /** 变化的设备地址。 */
        val address: String
    ) : HfpEvent

    /** HFP Profile 连接状态变化。 */
    data class ConnectionChanged(
        /** 设备地址。 */
        val address: String,
        /** 新的连接状态。 */
        val state: ProfileState
    ) : HfpEvent

    /** 通话音频状态变化。 */
    data class AudioChanged(
        /** 设备地址。 */
        val address: String,
        /** 音频状态。 */
        val state: AudioState,
        /** 是否宽带语音。 */
        val wideband: Boolean
    ) : HfpEvent

    /** 当前通话列表变化，是三方通话状态的来源。 */
    data class CallsChanged(
        /** 设备地址。 */
        val address: String,
        /** 该设备当前未结束的通话。 */
        val calls: List<HfpCall>
    ) : HfpEvent

    /** 信号/电量/运营商变化。 */
    data class AgChanged(
        /** 设备地址。 */
        val address: String,
        /** 最新 AG 状态。 */
        val ag: HfpAgState
    ) : HfpEvent

    /** 系统蓝牙服务进程重启，需要重新获取代理。 */
    data object ServiceLost : HfpEvent

    /** 网关内部错误，向上转为 PhoneEvent.Error。 */
    data class Error(
        /** 错误文案。 */
        val message: String
    ) : HfpEvent

    /** 日志摘要：只输出类型与关键字段，不展开完整 call 列表。 */
    fun summary(): String = when (this) {
        is AdapterChanged -> "AdapterChanged(enabled=$enabled)"
        is BondChanged -> "BondChanged($address)"
        is ConnectionChanged -> "ConnectionChanged($address, $state)"
        is AudioChanged -> "AudioChanged($address, $state, wideband=$wideband)"
        is CallsChanged -> "CallsChanged($address, ${calls.size} 路)"
        is AgChanged -> "AgChanged($address, battery=${ag.battery}, signal=${ag.signal})"
        ServiceLost -> "ServiceLost"
        is Error -> "Error($message)"
    }
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
