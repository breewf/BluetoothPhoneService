package com.bluetooth.phone.core

import android.util.Log
import com.bluetooth.phone.api.PhoneLogLevel

/**
 * 中间层统一日志输出。
 *
 * 约定：
 * - 所有日志都走这里，禁止各模块直接使用 [android.util.Log]；
 * - 消息用 lambda 延迟拼接，日志关闭时不产生字符串开销；
 * - TAG 与级别由配置统一控制，运行期可调。
 *
 * 过滤命令：`adb logcat -s BluetoothPhone`
 */
internal object PhoneLog {

    /** 当前日志级别，默认 [PhoneLogLevel.INFO]。 */
    @Volatile
    var level: PhoneLogLevel = PhoneLogLevel.INFO

    /** 日志 TAG；由配置的 logTag 覆盖。 */
    @Volatile
    var tag: String = "BluetoothPhone"

    /** 公共入口与单例生命周期。 */
    val phone = LogScope("Bluetooth-Phone")

    /** 引擎调度、生命周期、动作与重连。 */
    val engine = LogScope("Bluetooth-Engine")

    /** HFP 网关：广播、代理、反射调用。 */
    val hfp = LogScope("Bluetooth-Hfp")

    /** PBAP 电话本同步。 */
    val pbap = LogScope("Bluetooth-Pbap")

    /** 通话状态归并。 */
    val state = LogScope("Bluetooth-State")

    /** 设备列表与当前设备。 */
    val device = LogScope("Bluetooth-Device")

    /** 联系人 / 通话记录读取。 */
    val contact = LogScope("Bluetooth-Contact")

    /** 铃声与本地音频。 */
    val audio = LogScope("Bluetooth-Audio")

    /**
     * 号码脱敏：保留前 3 位与后 4 位。
     * 日志可能被回传，避免写入完整号码。
     */
    fun mask(number: String?): String {
        val text = number.orEmpty()
        if (text.isEmpty()) return "<无号码>"
        if (text.length <= 7) return "***"
        return text.take(3) + "****" + text.takeLast(4)
    }
}

/** 某个模块的日志句柄，输出格式为 `模块 | 内容`。 */
internal class LogScope(private val module: String) {

    /** 最详细日志：逐条映射、原始字段。 */
    fun v(message: () -> String) = log(PhoneLogLevel.VERBOSE, null, message)

    /** 详细日志：事件、归并摘要。 */
    fun d(message: () -> String) = log(PhoneLogLevel.DEBUG, null, message)

    /** 关键流程：生命周期、连接、阶段变化、动作结果。 */
    fun i(message: () -> String) = log(PhoneLogLevel.INFO, null, message)

    /** 可恢复异常：失败但主链路继续。 */
    fun w(message: () -> String) = log(PhoneLogLevel.WARN, null, message)

    /** 带异常堆栈的警告。 */
    fun w(error: Throwable?, message: () -> String) =
        log(PhoneLogLevel.WARN, error, message)

    /** 未预期异常。 */
    fun e(error: Throwable? = null, message: () -> String) =
        log(PhoneLogLevel.ERROR, error, message)

    /** 当前是否输出该级别，供调用方跳过昂贵的上下文拼装。 */
    fun isEnabled(target: PhoneLogLevel): Boolean = PhoneLog.level.allows(target)

    private fun log(
        level: PhoneLogLevel,
        error: Throwable?,
        message: () -> String
    ) {
        if (!PhoneLog.level.allows(level)) return
        val text = "$module | ${message()}"
        when (level) {
            PhoneLogLevel.VERBOSE -> Log.v(PhoneLog.tag, text, error)
            PhoneLogLevel.DEBUG -> Log.d(PhoneLog.tag, text, error)
            PhoneLogLevel.INFO -> Log.i(PhoneLog.tag, text, error)
            PhoneLogLevel.WARN -> Log.w(PhoneLog.tag, text, error)
            PhoneLogLevel.ERROR -> Log.e(PhoneLog.tag, text, error)
            PhoneLogLevel.NONE -> Unit
        }
    }
}
