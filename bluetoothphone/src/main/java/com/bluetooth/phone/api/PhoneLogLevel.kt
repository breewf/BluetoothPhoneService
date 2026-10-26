package com.bluetooth.phone.api

/**
 * 中间层日志级别。
 *
 * 通过 [com.bluetooth.phone.BluetoothPhoneConfig.logLevel] 设置初始级别，
 * 运行期可用 [com.bluetooth.phone.BluetoothPhone.setLogLevel] 动态调整，
 * 不需要重启中间层，便于车机现场排查。
 */
enum class PhoneLogLevel(val weight: Int) {
    /** 完全关闭；量产默认可用。 */
    NONE(0),

    /** 仅错误：状态归并异常、未预期异常。 */
    ERROR(1),

    /** 警告：权限缺失、反射失败、AG 行为差异、动作失败。 */
    WARN(2),

    /** 默认级别：生命周期、设备连接、通话阶段变化、动作结果。 */
    INFO(3),

    /** 详细：每条 HFP 事件、状态归并摘要、重连与联系人同步。 */
    DEBUG(4),

    /** 最详细：逐条 call 映射、AG 原始字段、DTMF 单字符。 */
    VERBOSE(5);

    /** 当前级别是否允许输出 [target] 级别的日志。 */
    fun allows(target: PhoneLogLevel): Boolean = weight >= target.weight
}
