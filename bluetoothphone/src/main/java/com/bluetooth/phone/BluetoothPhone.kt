package com.bluetooth.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.bluetooth.phone.api.AudioRoute
import com.bluetooth.phone.api.CallLogEntry
import com.bluetooth.phone.api.Contact
import com.bluetooth.phone.api.PhoneCallState
import com.bluetooth.phone.api.PhoneConnectionState
import com.bluetooth.phone.api.PhoneDevice
import com.bluetooth.phone.api.PhoneEvent
import com.bluetooth.phone.api.PhoneResult
import com.bluetooth.phone.core.PhoneEngine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机蓝牙电话中间件入口。
 *
 * 车机无 SIM 时，通话状态全部来自 HFP Client，不使用 TelephonyManager/Telecom。
 * 典型用法：
 *
 * Application:
 * ```
 * BluetoothPhone.init(this, BluetoothPhoneConfig(useMockGateway = false))
 * ```
 *
 * HMI:
 * ```
 * val phone = BluetoothPhone.get(context)
 * lifecycleScope.launch {
 *     phone.callState.collect { state -> render(state) }
 * }
 * scope.launch { phone.dial("13800138000") }
 * ```
 */
class BluetoothPhone private constructor(
    context: Context,
    val config: BluetoothPhoneConfig
) {
    private val engine = PhoneEngine(context, config)

    /** 已配对手机列表，界面可直接订阅渲染。 */
    val devices: StateFlow<List<PhoneDevice>> = engine.devices

    /** 当前操作的手机；没有连接时为 null。 */
    val activeDevice: StateFlow<PhoneDevice?> = engine.activeDevice

    /** 统一通话快照，含来电/等待/保持/会议/音频状态。 */
    val callState: StateFlow<PhoneCallState> = engine.callState

    /** 中间件整体状态，适合状态栏或首页展示。 */
    val connectionState: StateFlow<PhoneConnectionState> = engine.connectionState

    /** 一次性提示事件，界面用 Toast/Snackbar 消费即可。 */
    val events: SharedFlow<PhoneEvent> = engine.events

    /** true 表示当前运行在模拟 HFP，不是真实车机链路。 */
    val isMock: Boolean = config.useMockGateway

    /** 启动中间件：注册广播、获取 HFP 代理并加载设备。 */
    fun start() = engine.start()

    /** 停止监听，但保留实例；之后仍可再次 [start]。 */
    fun stop() = engine.stop()

    /** 释放引擎资源；由 [companion.init] / [companion.shutdown] 统一调用。 */
    private fun release() = engine.shutdown()

    /** 主动刷新设备列表、通话和信号状态。 */
    fun refresh() = engine.refresh()

    /** 多设备时切换当前操作的手机。 */
    fun selectDevice(address: String) = engine.selectDevice(address)

    /** 仅 Mock 网关有效，用于没有真机时模拟来电。 */
    fun debugSimulateIncomingCall(number: String) = engine.simulateIncomingCall(number)

    /** 连接指定手机的 HFP。 */
    suspend fun connect(address: String): PhoneResult<Unit> = engine.connect(address)

    /** 断开指定手机，并阻止自动重连。 */
    suspend fun disconnect(address: String): PhoneResult<Unit> = engine.disconnect(address)

    /** 发起系统蓝牙配对。 */
    suspend fun createBond(address: String): PhoneResult<Unit> = engine.createBond(address)

    /** 解除配对，同时清理该设备的本地状态。 */
    suspend fun removeBond(address: String): PhoneResult<Unit> = engine.removeBond(address)

    /** 通过 HFP 拨号。 */
    suspend fun dial(number: String): PhoneResult<Unit> = engine.dial(number)

    /** 已有一路接通（活动或保持）时加拨第二路；之后可接听等待/合并会议。 */
    suspend fun addCall(number: String): PhoneResult<Unit> = engine.addCall(number)

    /** 接听响铃中的一路：普通来电优先，其次是没有活动通话时的等待来电。 */
    suspend fun answer(): PhoneResult<Unit> = engine.answer()

    /** 拒接来电或第二路等待来电。 */
    suspend fun reject(): PhoneResult<Unit> = engine.reject()

    /** 挂断当前活动通话。 */
    suspend fun hangup(): PhoneResult<Unit> = engine.hangup()

    /** 挂断保持中的通话。 */
    suspend fun hangupHeld(): PhoneResult<Unit> = engine.hangupHeld()

    /** 只挂断第二路等待来电，不影响当前通话。 */
    suspend fun hangupWaiting(): PhoneResult<Unit> = engine.hangupWaiting()

    /** 保持当前通话；已有保持通话时等同切换。 */
    suspend fun hold(): PhoneResult<Unit> = engine.hold()

    /** 活动通话与保持通话互换（HFP CHLD=2）。 */
    suspend fun swap(): PhoneResult<Unit> = engine.swap()

    /** 合并活动+保持通话（HFP CHLD=3，需手机/协议栈支持）。 */
    suspend fun merge(): PhoneResult<Unit> = engine.merge()

    /**
     * 接听等待来电。
     * holdCurrent=true 保持当前通话；false 挂断当前通话。
     */
    suspend fun answerWaiting(holdCurrent: Boolean): PhoneResult<Unit> =
        engine.answerWaiting(holdCurrent)

    /** 接听第二路并保持当前通话。 */
    suspend fun answerWaitingAndHold(): PhoneResult<Unit> = answerWaiting(true)

    /** 接听第二路并挂断当前通话。 */
    suspend fun answerWaitingAndEndActive(): PhoneResult<Unit> = answerWaiting(false)

    /** 发送单个 DTMF 按键音，用于 IVR 等场景。 */
    suspend fun sendDtmf(code: Char): PhoneResult<Unit> = engine.sendDtmf(code)

    /** 连续发送多个 DTMF 字符，用于分机号、卡号等长串输入。 */
    suspend fun sendDtmf(dtmf: String): PhoneResult<Unit> = engine.sendDtmf(dtmf)

    /** 把通话音频切到车机（SCO/eSCO）。 */
    suspend fun setAudioToCar(): PhoneResult<Unit> = engine.setAudioToCar()

    /** 把通话音频交回手机。 */
    suspend fun setAudioToPhone(): PhoneResult<Unit> = engine.setAudioToPhone()

    /** 根据当前状态在车机/手机之间切换音频。 */
    suspend fun toggleAudioRoute(): PhoneResult<Unit> = engine.toggleAudioRoute()

    /** 设置车机麦克风静音；状态可通过 [callState] 的 micMuted 读取。 */
    suspend fun setMicMute(muted: Boolean): PhoneResult<Unit> = engine.setMicMute(muted)

    /** 切换车机麦克风静音。 */
    suspend fun toggleMicMute(): PhoneResult<Unit> = engine.toggleMicMute()

    /** 按枚举选择音频路由。 */
    suspend fun setAudioRoute(route: AudioRoute): PhoneResult<Unit> = when (route) {
        AudioRoute.CAR -> setAudioToCar()
        AudioRoute.PHONE -> setAudioToPhone()
    }

    /** 启动/停止手机侧语音识别（对应语音按键）。 */
    suspend fun voiceRecognition(start: Boolean): PhoneResult<Unit> =
        engine.voiceRecognition(start)

    /** 读取系统通讯录；需要 READ_CONTACTS 权限。 */
    suspend fun loadContacts(limit: Int = 500): PhoneResult<List<Contact>> =
        engine.loadContacts(limit)

    /** 读取系统通话记录；需要 READ_CALL_LOG 权限。 */
    suspend fun loadCallLog(limit: Int = 200): PhoneResult<List<CallLogEntry>> =
        engine.loadCallLog(limit)

    companion object {
        @Volatile
        private var instance: BluetoothPhone? = null

        /** 创建并启动进程内单例；建议在 Application.onCreate 调用。 */
        @JvmStatic
        fun init(
            context: Context,
            config: BluetoothPhoneConfig = BluetoothPhoneConfig()
        ): BluetoothPhone {
            synchronized(this) {
                instance?.release()
                val created = BluetoothPhone(context.applicationContext, config)
                instance = created
                created.start()
                return created
            }
        }

        /**
         * 未显式 init 时使用安全默认值：没有 BLUETOOTH_PRIVILEGED 就进入 Mock，
         * 避免普通手机/模拟器直接崩溃。车机系统应用应显式 init(useMockGateway=false)。
         */
        @JvmStatic
        fun get(context: Context): BluetoothPhone {
            instance?.let { return it }
            // 双重检查：并发首次获取时只创建一次实例，避免重复建链。
            synchronized(this) {
                instance?.let { return it }
                val config = BluetoothPhoneConfig(
                    useMockGateway = !isSystemApiAvailable() ||
                        !hasPrivilegedPermission(context)
                )
                return init(context, config)
            }
        }

        /** 停止并释放单例，下次使用会重新创建。 */
        @JvmStatic
        fun shutdown() {
            synchronized(this) {
                instance?.release()
                instance = null
            }
        }

        /** 当前系统是否包含 @SystemApi 的 BluetoothHeadsetClient。 */
        @JvmStatic
        fun isSystemApiAvailable(): Boolean = runCatching {
            Class.forName("android.bluetooth.BluetoothHeadsetClient")
            true
        }.getOrDefault(false)

        /** 是否已授予 Android 12+ 的 BLUETOOTH_CONNECT 权限。 */
        @JvmStatic
        fun hasBluetoothPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** 是否持有系统级 BLUETOOTH_PRIVILEGED，真实 HFP 的前提。 */
        @JvmStatic
        fun hasPrivilegedPermission(context: Context): Boolean =
            context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
                PackageManager.PERMISSION_GRANTED
    }
}
