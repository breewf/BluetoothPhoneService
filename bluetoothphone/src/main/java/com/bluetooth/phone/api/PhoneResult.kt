package com.bluetooth.phone.api

/** 统一错误码，界面可直接映射提示文案。 */
enum class PhoneError {
    /** 中间件未启动或网关未就绪。 */
    NOT_READY,
    /** 没有已连接手机。 */
    NO_DEVICE,
    /** 当前没有对应通话。 */
    NO_CALL,
    /** 蓝牙未开启。 */
    BLUETOOTH_OFF,
    /** 手机/协议栈不支持该能力。 */
    NOT_SUPPORTED,
    /** 参数非法，如空号码。 */
    INVALID_ARGUMENT,
    /** 缺少运行时权限。 */
    PERMISSION_DENIED,
    /** 当前状态不允许该操作，如等待来电未处理。 */
    BUSY,
    /** 内部异常。 */
    INTERNAL
}

/** 统一返回值：成功带数据，失败带错误码与可选提示。 */
sealed interface PhoneResult<out T> {
    /** 调用成功。 */
    data class Success<T>(
        /** 成功返回的数据。 */
        val value: T
    ) : PhoneResult<T>

    /** 调用失败；message 适合直接展示给用户。 */
    data class Failure(
        /** 错误码。 */
        val error: PhoneError,
        /** 可直接展示的提示；null 时由界面按错误码兜底。 */
        val message: String? = null
    ) : PhoneResult<Nothing>
}

/** 成功时取值，失败返回 null。 */
fun <T> PhoneResult<T>.getOrNull(): T? = (this as? PhoneResult.Success)?.value

/** 失败时取错误码，成功返回 null。 */
fun PhoneResult<*>.errorOrNull(): PhoneError? = (this as? PhoneResult.Failure)?.error
