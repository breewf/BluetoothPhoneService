package com.bluetooth.phone

import com.bluetooth.phone.api.CallClassifier
import com.bluetooth.phone.api.PhoneLogLevel

/**
 * 中间件运行参数。
 *
 * 普通手机/模拟器没有 BLUETOOTH_PRIVILEGED，演示时把 [useMockGateway] 设为 true；
 * 车机系统应用应设为 false，并保证应用已平台签名、预置到 priv-app。
 */
data class BluetoothPhoneConfig @JvmOverloads constructor(
    /** true 使用模拟 HFP，仅用于普通手机/模拟器联调。 */
    val useMockGateway: Boolean = false,
    /** 启动后是否自动连接上次使用的手机。 */
    val autoConnectLastDevice: Boolean = true,
    /** 被动断线后是否自动重连。 */
    val autoReconnect: Boolean = true,
    /** 重连基础间隔，会按尝试次数递增。 */
    val reconnectDelayMs: Long = 3_000L,
    /**
     * AOSP Android 14 的 BluetoothHeadsetClient 没有公开 CHLD=3 会议合并 API。
     * 该命令会先尝试厂商扩展方法，再尝试 sendVendorAtCommand；量产可按芯片栈替换。
     */
    val conferenceVendorId: Int = 0,
    /** 厂商 AT 通道发送的会议合并命令。 */
    val conferenceAtCommand: String = "+CHLD=3",
    /** 日志 TAG，便于车机日志过滤，默认 BluetoothPhone。 */
    val logTag: String = "BluetoothPhone",
    /**
     * 日志级别，默认 [PhoneLogLevel.INFO]。
     * 量产可降到 [PhoneLogLevel.WARN] 或 [PhoneLogLevel.NONE]；
     * 现场排查可用 [com.bluetooth.phone.BluetoothPhone.setLogLevel] 动态调高。
     */
    val logLevel: PhoneLogLevel = PhoneLogLevel.INFO,
    /**
     * 号码来源分类器，用于识别微信/VoIP 等网络电话。
     * HFP 不携带应用信息，默认返回 [com.bluetooth.phone.api.CallKind.UNKNOWN]。
     */
    val callClassifier: CallClassifier? = null
)
