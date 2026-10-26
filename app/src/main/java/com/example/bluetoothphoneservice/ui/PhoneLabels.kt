package com.example.bluetoothphoneservice.ui

import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.CallKind
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.CallUiState
import com.bluetooth.phone.api.PhoneConnectionState
import com.bluetooth.phone.api.PhoneError
import com.bluetooth.phone.api.ProfileState

/** 整体连接状态文案。 */
fun PhoneConnectionState.label(): String = when (this) {
    PhoneConnectionState.BLUETOOTH_OFF -> "蓝牙关闭"
    PhoneConnectionState.NO_DEVICE -> "无已连接手机"
    PhoneConnectionState.CONNECTING -> "连接中"
    PhoneConnectionState.CONNECTED -> "已连接"
}

/** HFP Profile 状态文案。 */
fun ProfileState.label(): String = when (this) {
    ProfileState.DISCONNECTED -> "未连接"
    ProfileState.CONNECTING -> "连接中"
    ProfileState.CONNECTED -> "已连接"
    ProfileState.DISCONNECTING -> "断开中"
    ProfileState.UNKNOWN -> "未知"
}

/** HFP 通话状态文案。 */
fun CallState.label(): String = when (this) {
    CallState.INCOMING -> "来电"
    CallState.WAITING -> "等待接听"
    CallState.DIALING -> "拨号中"
    CallState.ALERTING -> "振铃中"
    CallState.ACTIVE -> "通话中"
    CallState.HELD -> "保持中"
    CallState.HELD_BY_RESPONSE -> "响应保持"
    CallState.TERMINATED -> "已结束"
}

/** 通话界面阶段文案。 */
fun CallUiState.label(): String = when (this) {
    CallUiState.IDLE -> "无通话"
    CallUiState.INCOMING -> "来电"
    CallUiState.OUTGOING -> "去电"
    CallUiState.ACTIVE -> "通话中"
    CallUiState.HELD -> "保持中"
    CallUiState.WAITING -> "第二路来电"
    CallUiState.MULTI_CALL -> "多路通话"
    CallUiState.CONFERENCE -> "会议通话"
}

/** 通话来源文案。 */
fun CallKind.label(): String = when (this) {
    CallKind.CELLULAR -> "普通电话"
    CallKind.VOIP -> "网络电话"
    CallKind.UNKNOWN -> "未知来源"
}

/** 通话音频状态文案。 */
fun AudioState.label(): String = when (this) {
    AudioState.DISCONNECTED -> "断开"
    AudioState.CONNECTING -> "连接中"
    AudioState.CONNECTED -> "已连接"
    AudioState.UNKNOWN -> "未知"
}

/** 错误码兜底文案。 */
fun PhoneError.label(): String = when (this) {
    PhoneError.NOT_READY -> "服务未就绪"
    PhoneError.NO_DEVICE -> "没有已连接手机"
    PhoneError.NO_CALL -> "当前没有通话"
    PhoneError.BLUETOOTH_OFF -> "蓝牙未开启"
    PhoneError.NOT_SUPPORTED -> "当前设备不支持"
    PhoneError.INVALID_ARGUMENT -> "参数不合法"
    PhoneError.PERMISSION_DENIED -> "缺少权限"
    PhoneError.BUSY -> "当前状态不允许该操作"
    PhoneError.INTERNAL -> "内部错误"
}
