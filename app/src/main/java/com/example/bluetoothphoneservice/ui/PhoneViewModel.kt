package com.example.bluetoothphoneservice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetooth.phone.BluetoothPhone
import com.bluetooth.phone.api.CallLogEntry
import com.bluetooth.phone.api.Contact
import com.bluetooth.phone.api.PhoneCallState
import com.bluetooth.phone.api.PhoneConnectionState
import com.bluetooth.phone.api.PhoneDevice
import com.bluetooth.phone.api.PhoneError
import com.bluetooth.phone.api.PhoneEvent
import com.bluetooth.phone.api.PhoneResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 页面唯一状态。
 *
 * 所有字段都来自中间件的 StateFlow 或本页操作结果，XML 层只负责渲染。
 */
data class PhoneUiState(
    /** 已配对设备列表。 */
    val devices: List<PhoneDevice> = emptyList(),
    /** 当前操作的手机。 */
    val activeDevice: PhoneDevice? = null,
    /** 统一通话快照。 */
    val call: PhoneCallState = PhoneCallState(),
    /** 中间件整体连接状态。 */
    val connection: PhoneConnectionState = PhoneConnectionState.NO_DEVICE,
    /** 拨号盘输入。 */
    val number: String = "",
    /** 操作结果或错误提示。 */
    val status: String? = null,
    /** 已读取的联系人。 */
    val contacts: List<Contact> = emptyList(),
    /** 已读取的通话记录。 */
    val callLog: List<CallLogEntry> = emptyList(),
    /** 是否运行在模拟 HFP。 */
    val isMock: Boolean = false
)

/**
 * 演示页的 ViewModel。
 *
 * 职责：订阅中间件状态、处理按钮动作、把结果转成页面状态；
 * 不持有任何 View，也不直接操作 BluetoothHeadsetClient。
 */
class PhoneViewModel(application: Application) : AndroidViewModel(application) {

    /** 中间件四个状态域的合并快照，避免同一批变化多次写 uiState。 */
    private data class MiddlewareSnapshot(
        /** 已配对设备。 */
        val devices: List<PhoneDevice>,
        /** 当前设备。 */
        val activeDevice: PhoneDevice?,
        /** 通话快照。 */
        val call: PhoneCallState,
        /** 整体连接状态。 */
        val connection: PhoneConnectionState
    )

    /** 中间件单例。 */
    private val phone = BluetoothPhone.get(application)

    /** 页面可变状态。 */
    private val _uiState = MutableStateFlow(PhoneUiState(isMock = phone.isMock))

    /** 对外只读的页面状态。 */
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()

    /** 初始化订阅并主动刷新一次中间件状态。 */
    init {
        observePhone()
        phone.refresh()
    }

    /** 订阅设备、通话、连接状态与一次性事件。 */
    private fun observePhone() {
        // 四个状态域合流后只写一次 uiState，避免同一批变化触发多次页面刷新。
        viewModelScope.launch {
            combine(
                phone.devices,
                phone.activeDevice,
                phone.callState,
                phone.connectionState
            ) { devices, activeDevice, call, connection ->
                MiddlewareSnapshot(devices, activeDevice, call, connection)
            }.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        devices = snapshot.devices,
                        activeDevice = snapshot.activeDevice,
                        call = snapshot.call,
                        connection = snapshot.connection
                    )
                }
            }
        }
        // 一次性事件只影响提示文案，与持续状态分开收集。
        viewModelScope.launch {
            phone.events.collect { event ->
                when (event) {
                    is PhoneEvent.Info -> setStatus(event.message)
                    is PhoneEvent.Error -> setStatus(event.message)
                    // 阶段变化已由 callState 驱动，不覆盖当前提示。
                    is PhoneEvent.CallUiChanged -> Unit
                    is PhoneEvent.CallEnded -> Unit
                    is PhoneEvent.SessionEnded -> setStatus(
                        event.lastCall?.let { "通话已结束：${it.title()}" } ?: "通话已结束"
                    )
                }
            }
        }
    }

    /** 权限结果：无论是否全部授予都补启动一次，中间件会按实际权限降级。 */
    fun onPermissionResult(granted: Boolean) {
        setStatus(
            if (granted) "权限已授予" else "部分权限未授予，通讯录/通话记录可能不可用"
        )
        phone.start()
        phone.refresh()
    }

    /** 更新拨号盘号码，只保留合法字符。 */
    fun setNumber(value: String) {
        val normalized = value.filter { it.isDigit() || it == '*' || it == '#' }
        _uiState.update { it.copy(number = normalized) }
    }

    /** 键盘追加一位。 */
    fun appendDigit(key: String) = setNumber(_uiState.value.number + key)

    /** 清空拨号盘。 */
    fun clearNumber() = _uiState.update { it.copy(number = "") }

    /** 手动刷新设备和通话状态。 */
    fun refresh() {
        phone.refresh()
        setStatus("已刷新")
    }

    /** 选择当前操作的手机。 */
    fun selectDevice(address: String) = phone.selectDevice(address)

    /** 连接指定手机。 */
    fun connect(address: String) = runAction("正在连接手机") { phone.connect(address) }

    /** 断开指定手机。 */
    fun disconnect(address: String) = runAction("已断开手机") { phone.disconnect(address) }

    /** 解除配对。 */
    fun removeBond(address: String) = runAction("已解除配对") { phone.removeBond(address) }

    /** 有一路已接通时加拨第二路，否则直接拨号。 */
    fun dialOrAddCall() {
        val state = _uiState.value
        if (state.number.isBlank()) {
            setStatus("请先输入号码")
            return
        }
        when {
            // 活动或保持状态下都允许加拨第二路
            state.call.canAddCall ->
                runAction("正在加拨第二路") { phone.addCall(state.number) }

            state.call.waiting != null -> setStatus("有等待来电，请先接听或拒接")
            state.call.outgoing != null -> setStatus("已有去电中的通话，可取消或等待接通")
            state.call.isConference -> setStatus("会议通话中不支持加拨")
            state.call.hasCall -> setStatus("当前已有两路通话，请先处理一路")
            else -> runAction("正在拨号") { phone.dial(state.number) }
        }
    }

    /** 接听响铃中的一路：普通来电优先，其次是没有活动通话时的等待来电。 */
    fun answer() = runAction("已接听") { phone.answer() }

    /** 拒接来电或第二路等待。 */
    fun reject() = runAction("已拒接") { phone.reject() }

    /** 挂断当前活动通话。 */
    fun hangup() = runAction("已挂断") { phone.hangup() }

    /** 取消去电中的一路（对方尚未接听）。 */
    fun cancelOutgoing() = runAction("已取消拨号") { phone.cancelOutgoing() }

    /** 挂断活动通话；没有活动通话时取消去电。 */
    fun hangupOrCancel() {
        if (_uiState.value.call.active != null) hangup() else cancelOutgoing()
    }

    /** 挂断保持中的通话。 */
    fun hangupHeld() = runAction("已挂断保持通话") { phone.hangupHeld() }

    /** 只挂断第二路等待来电。 */
    fun hangupWaiting() = runAction("已挂断等待来电") { phone.hangupWaiting() }

    /** 保持/恢复当前通话。 */
    fun hold() = runAction("已保持/恢复") { phone.hold() }

    /** 活动与保持通话互换。 */
    fun swap() = runAction("已切换通话") { phone.swap() }

    /** 根据当前状态自动选择保持或切换。 */
    fun holdOrSwap() {
        if (_uiState.value.call.canSwap) swap() else hold()
    }

    /** 合并为会议（三方通话）。 */
    fun merge() = runAction("已合并为会议") { phone.merge() }

    /** 接听第二路并保持当前。 */
    fun answerWaitingAndHold() = runAction("已接听第二路") { phone.answerWaiting(true) }

    /** 接听第二路并挂断当前。 */
    fun answerWaitingAndEndActive() = runAction("已接听第二路") { phone.answerWaiting(false) }

    /** 发送 DTMF 按键，不刷新状态栏避免刷屏。 */
    fun sendDtmf(key: String) {
        val code = key.firstOrNull() ?: return
        viewModelScope.launch { phone.sendDtmf(code) }
    }

    /** 通话音频切到车机。 */
    fun audioToCar() = runAction("音频已切到车机") { phone.setAudioToCar() }

    /** 通话音频切回手机。 */
    fun audioToPhone() = runAction("音频已切到手机") { phone.setAudioToPhone() }

    /** 在车机/手机音频之间切换。 */
    fun toggleAudioChannel() = runAction("已切换音频通道") { phone.toggleAudioRoute() }

    /** 根据目标状态切换车机麦克风静音。 */
    fun toggleMicMute() {
        val target = !_uiState.value.call.micMuted
        runAction(if (target) "麦克风已静音" else "麦克风已恢复") {
            phone.setMicMute(target)
        }
    }

    /** 调试：构造一路模拟来电。 */
    fun simulateIncomingCall() {
        phone.debugSimulateIncomingCall("13800138000")
    }

    /** 读取系统联系人并更新列表。 */
    fun loadContacts() {
        viewModelScope.launch {
            when (val result = phone.loadContacts(20)) {
                is PhoneResult.Success -> _uiState.update {
                    it.copy(contacts = result.value, status = "已读取 ${result.value.size} 个联系人")
                }

                is PhoneResult.Failure -> setStatus(result.message ?: result.error.label())
            }
        }
    }

    /** 读取系统通话记录并更新列表。 */
    fun loadCallLog() {
        viewModelScope.launch {
            when (val result = phone.loadCallLog(20)) {
                is PhoneResult.Success -> _uiState.update {
                    it.copy(callLog = result.value, status = "已读取 ${result.value.size} 条通话记录")
                }

                is PhoneResult.Failure -> setStatus(result.message ?: result.error.label())
            }
        }
    }

    /** 统一执行 suspend 操作，并把成功/失败转成状态栏文案。 */
    private fun runAction(successMessage: String, action: suspend () -> PhoneResult<Unit>) {
        viewModelScope.launch {
            val result = runCatching { action() }.getOrElse {
                PhoneResult.Failure(PhoneError.INTERNAL, it.message)
            }
            when (result) {
                is PhoneResult.Success -> setStatus(successMessage)
                is PhoneResult.Failure -> setStatus(result.message ?: result.error.label())
            }
        }
    }

    /** 写入页面状态提示。 */
    private fun setStatus(message: String?) {
        _uiState.update { it.copy(status = message) }
    }
}
