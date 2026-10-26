package com.example.bluetoothphoneservice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bluetooth.phone.api.AudioRoute
import com.bluetooth.phone.api.CallInfo
import com.bluetooth.phone.api.CallLogEntry
import com.bluetooth.phone.api.Contact
import com.bluetooth.phone.api.PhoneCallState
import com.bluetooth.phone.api.PhoneConnectionState
import com.bluetooth.phone.api.PhoneDevice
import com.example.bluetoothphoneservice.databinding.ActivityMainBinding
import com.example.bluetoothphoneservice.databinding.ItemCallBinding
import com.example.bluetoothphoneservice.databinding.ItemDeviceBinding
import com.example.bluetoothphoneservice.databinding.ViewKeypadBinding
import com.example.bluetoothphoneservice.ui.PhoneViewModel
import com.example.bluetoothphoneservice.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 演示 Activity：只做 ViewBinding、事件转发和渲染。
 *
 * 业务状态全部在 [PhoneViewModel]，Activity 不直接调用 HFP 网关。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PhoneViewModel by viewModels()

    /** 通话/设备列表快照：内容未变时跳过重建，避免无关状态触发 View 重建。 */
    private var renderedCalls: List<CallInfo>? = null
    private var renderedDevices: List<PhoneDevice>? = null

    /** 通话行绑定缓存，供计时器每秒只更新对应行的时长。 */
    private val callRows = mutableMapOf<String, ItemCallBinding>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onPermissionResult(result.values.all { it })
    }

    /** 加载布局、绑定事件并开始监听状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindActions()
        observeState()
        requestRuntimePermissions()
    }

    /** 把所有按钮点击转发给 ViewModel，行尾注释标明对应功能。 */
    private fun bindActions() = with(binding) {
        // 调试：模拟一路来电，验证来电界面
        mockBanner.btnSimulateIncoming.setOnClickListener { viewModel.simulateIncomingCall() }

        // 设备：刷新已配对手机列表
        deviceSection.btnRefreshDevices.setOnClickListener { viewModel.refresh() }

        // 来电：接听 / 拒接
        callSection.btnAnswer.setOnClickListener { viewModel.answer() }
        callSection.btnReject.setOnClickListener { viewModel.reject() }

        // 第二路等待来电：拒接 / 只挂断第二路 / 接听并保持 / 接听并挂断当前
        callSection.btnRejectWaiting.setOnClickListener { viewModel.reject() }
        callSection.btnHangupWaiting.setOnClickListener { viewModel.hangupWaiting() }
        callSection.btnAnswerWaitingHold.setOnClickListener { viewModel.answerWaitingAndHold() }
        callSection.btnAnswerWaitingEnd.setOnClickListener { viewModel.answerWaitingAndEndActive() }

        // 当前通话：挂断 / 保持或切换 / 合并会议 / 挂断保持中的一路
        callSection.btnHangup.setOnClickListener { viewModel.hangup() }
        callSection.btnHoldOrSwap.setOnClickListener { viewModel.holdOrSwap() }
        callSection.btnMerge.setOnClickListener { viewModel.merge() }
        callSection.btnHangupHeld.setOnClickListener { viewModel.hangupHeld() }
        callSection.btnHangupHeldOnly.setOnClickListener { viewModel.hangupHeld() }
        callSection.btnResumeHeld.setOnClickListener { viewModel.hold() }

        // 音频：切到车机 / 切回手机 / 麦克风静音
        callSection.btnAudioCar.setOnClickListener { viewModel.audioToCar() }
        callSection.btnAudioPhone.setOnClickListener { viewModel.audioToPhone() }
        callSection.btnMicMute.setOnClickListener { viewModel.toggleMicMute() }

        // 拨号盘：拨号或加拨 / 清空 / 输入同步 / 数字键
        dialerSection.btnDialOrAdd.setOnClickListener { viewModel.dialOrAddCall() }
        dialerSection.btnClearNumber.setOnClickListener { viewModel.clearNumber() }
        dialerSection.etNumber.doAfterTextChanged { text ->
            val value = text?.toString().orEmpty()
            // 只同步用户输入，避免回写触发循环
            if (value != viewModel.uiState.value.number) viewModel.setNumber(value)
        }
        bindKeypad(dialerSection.keypad) { viewModel.appendDigit(it) }

        // DTMF：通话中发送按键音
        bindKeypad(dtmfSection.keypad) { viewModel.sendDtmf(it) }

        // 通讯录 / 通话记录：手动读取
        contactSection.btnLoadContacts.setOnClickListener { viewModel.loadContacts() }
        contactSection.btnLoadCallLog.setOnClickListener { viewModel.loadCallLog() }
    }

    /**
     * 按状态域分别订阅：任一域变化只刷新它对应的区块，避免整页重算。
     * 每个域都做 [distinctUntilChanged]，相同状态不会重复渲染。
     */
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 通话域：通话列表、阶段标题、三方操作、音频状态
                launch {
                    viewModel.uiState.map { it.call }
                        .distinctUntilChanged()
                        .collect(::renderCall)
                }
                // 设备域：已配对手机列表
                launch {
                    viewModel.uiState.map { it.devices }
                        .distinctUntilChanged()
                        .collect(::renderDevices)
                }
                // 连接域：蓝牙连接状态与当前手机
                launch {
                    viewModel.uiState.map { it.connection to it.activeDevice }
                        .distinctUntilChanged()
                        .collect { (connection, device) -> renderConnection(connection, device) }
                }
                // 输入域：拨号盘号码
                launch {
                    viewModel.uiState.map { it.number }
                        .distinctUntilChanged()
                        .collect(::renderNumber)
                }
                // 提示域：操作结果与错误信息
                launch {
                    viewModel.uiState.map { it.status }
                        .distinctUntilChanged()
                        .collect(::renderStatus)
                }
                // 数据域：通讯录与通话记录
                launch {
                    viewModel.uiState.map { it.contacts to it.callLog }
                        .distinctUntilChanged()
                        .collect { (contacts, callLog) -> renderContacts(contacts, callLog) }
                }
                // 展示域：模拟模式开关
                launch {
                    viewModel.uiState.map { it.isMock }
                        .distinctUntilChanged()
                        .collect(::renderMock)
                }
                // 计时域：有已接通通话时每秒刷新时长，不写回 uiState
                launch {
                    viewModel.uiState.map { it.call.hasTimedCall }
                        .distinctUntilChanged()
                        .collectLatest { hasTimed ->
                            while (hasTimed) {
                                renderDurations(viewModel.uiState.value.call)
                                delay(1_000.milliseconds)
                            }
                        }
                }
            }
        }
    }

    /** 首次进入时申请蓝牙和通讯录权限。 */
    private fun requestRuntimePermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** 模拟模式：顶部调试横幅与状态栏提示。 */
    private fun renderMock(isMock: Boolean) = with(binding) {
        mockBanner.root.isVisible = isMock
        statusSection.tvMockHint.isVisible = isMock
    }

    /** 连接域：蓝牙状态、当前手机及其电量/信号/运营商。 */
    private fun renderConnection(
        connection: PhoneConnectionState,
        device: PhoneDevice?
    ) = with(binding.statusSection) {
        // 蓝牙整体状态与当前操作手机
        tvConnection.renderText("蓝牙：${connection.label()}")
        tvActiveDevice.renderText("当前手机：${device?.displayName ?: "未选择"}")

        // 没有活动手机时隐藏 HFP 行，其余信息显示占位
        tvHfpState.isVisible = device != null
        if (device != null) {
            // HFP 连接状态
            tvHfpState.renderText("HFP：${device.hfpState.label()}")
            // 手机电量与信号
            tvAgInfo.renderText(
                "电量：${device.battery?.let { "$it%" } ?: "--"}  信号：${device.signal ?: "--"}"
            )
            // 运营商与漫游状态
            tvOperator.renderText(
                "运营商：${device.operatorName ?: "--"}  漫游：" +
                    (device.roaming?.let { if (it) "是" else "否" } ?: "--")
            )
        } else {
            tvAgInfo.renderText("电量：--  信号：--")
            tvOperator.renderText("运营商：--  漫游：--")
        }
    }

    /** 通话域：音频状态、通话列表、阶段标题与三方操作区。 */
    private fun renderCall(call: PhoneCallState) = with(binding) {
        // 音频路由与 SCO 状态（归属通话域，保证通话变化时同步）
        val route = when (call.audioRoute) {
            AudioRoute.CAR -> "车机"
            AudioRoute.PHONE -> "手机"
            null -> "--"
        }
        statusSection.tvAudioState.renderText(
            "通话音频：$route / ${call.audio.label()}" +
                if (call.audioWideband) "（宽带）" else ""
        )

        // 通话列表需要按快照判断是否重建
        renderCallRows(call.calls)
        // 状态变化时立即刷新时长，不等待下一次 tick
        renderDurations(call)

        with(callSection) {
            // 阶段标题：来电/去电/通话中/保持/多路/会议
            tvCallPhase.renderText("通话 · ${call.uiState.label()}")
            // 无通话时显示空态
            tvNoCall.isVisible = call.calls.isEmpty()

            // 会议状态：活动、保持或显示会议对方
            tvConference.isVisible = call.isConference
            tvConference.renderText(
                when {
                    call.conference == null -> "会议通话中"
                    call.conference?.isHeld == true -> "会议通话（保持）"
                    else -> call.conferencePeer?.let { "会议通话中：${it.title()}" } ?: "会议通话中"
                }
            )

            // 来电操作区
            rowIncoming.isVisible = call.canAnswer

            // 第二路等待来电：拒接 / 只挂断 / 接听策略
            groupWaiting.isVisible = call.waiting != null
            btnAnswerWaitingHold.isEnabled = call.canAnswerWaitingAndHold
            btnAnswerWaitingEnd.isEnabled = call.canAnswerWaitingAndEndActive
            btnHangupWaiting.isEnabled = call.canHangupWaiting

            // 活动/保持操作区
            groupActive.isVisible = call.active != null
            groupHeld.isVisible = call.active == null && call.held != null
            btnResumeHeld.isEnabled = call.canResume

            // CHLD=2 在等待来电存在时会直接接听，因此此时不展示“保持/切换”。
            btnHoldOrSwap.isVisible = call.waiting == null
            btnHoldOrSwap.renderText(if (call.canSwap) "切换" else "保持")
            // 仅两路普通通话且系统支持合并时展示
            btnMerge.isVisible = call.canMerge
            // 有保持中的通话才能单独挂断
            btnHangupHeld.isVisible = call.canHangupHeld

            // 音频按钮：文案固定，用 selected 状态高亮当前所在端，避免换行
            btnAudioCar.isEnabled = call.canToggleAudio
            btnAudioPhone.isEnabled = call.canToggleAudio
            btnAudioCar.isSelected = call.audioRoute == AudioRoute.CAR
            btnAudioPhone.isSelected = call.audioRoute == AudioRoute.PHONE
            // 麦克风静音开关，仅通话中存在；已静音时保持高亮
            btnMicMute.isVisible = call.canToggleMic
            btnMicMute.isSelected = call.micMuted
            btnMicMute.renderText(if (call.micMuted) "取消静音" else "静音")
        }

        // 已有通话时按钮表示加拨；只有恰好一路接通且可加拨时才允许点击
        dialerSection.btnDialOrAdd.renderText(if (call.hasCall) "加拨（三方）" else "拨打")
        dialerSection.btnDialOrAdd.isEnabled = !call.hasCall || call.canAddCall
        dtmfSection.root.isVisible = call.active != null
    }

    /** 计时域：刷新每路通话与会议对方的时长，只改文本不重建行。 */
    private fun renderDurations(call: PhoneCallState) {
        val now = System.currentTimeMillis()
        call.calls.forEach { item ->
            val row = callRows[item.id] ?: return@forEach
            // 未接通（振铃/拨号）不展示计时
            row.tvCallDuration.isVisible = item.isTimed
            row.tvCallDuration.renderText(item.durationText(now))
        }

        // 会议对方是合并前第二路的快照，时长继续独立累计
        val peer = call.conferencePeer
        val peerDuration = peer?.takeIf { it.isTimed }?.durationText(now).orEmpty()
        binding.callSection.tvConferencePeerDuration.isVisible = peerDuration.isNotEmpty()
        binding.callSection.tvConferencePeerDuration.renderText(
            if (peerDuration.isEmpty()) "" else "会议对方时长 $peerDuration"
        )
    }

    /** 输入域：号码回填；值相同不写入，避免光标跳动。 */
    private fun renderNumber(number: String) {
        val field = binding.dialerSection.etNumber
        if (field.text?.toString() == number) return
        field.setText(number)
        field.setSelection(number.length)
    }

    /** 提示域：操作结果或错误信息。 */
    private fun renderStatus(status: String?) = with(binding) {
        tvStatus.isVisible = !status.isNullOrBlank()
        tvStatus.renderText(status.orEmpty())
    }

    /** 数据域：通讯录与通话记录摘要。 */
    private fun renderContacts(
        contacts: List<Contact>,
        callLog: List<CallLogEntry>
    ) = with(binding.contactSection) {
        // 联系人摘要
        tvContacts.renderText(contactText(contacts))
        // 通话记录摘要
        tvCallLog.renderText(callLogText(callLog))
    }

    /** 设备列表：内容未变化时跳过重建，避免无关状态触发 inflate。 */
    private fun renderDevices(devices: List<PhoneDevice>) {
        if (devices == renderedDevices) return
        renderedDevices = devices

        with(binding.deviceSection) {
            devicesContainer.removeAllViews()
            // 无已配对设备时显示空态
            tvEmptyDevices.isVisible = devices.isEmpty()

            devices.forEach { device ->
                val row = ItemDeviceBinding.inflate(layoutInflater, devicesContainer, false)
                // 设备名称与当前标记
                row.tvDeviceName.text = device.displayName + if (device.isActive) "（当前）" else ""
                // 地址与 HFP 连接状态
                row.tvDeviceInfo.text = "${device.address}  ${device.hfpState.label()}"
                // 切换当前操作的手机
                row.btnSelectDevice.setOnClickListener { viewModel.selectDevice(device.address) }
                // 连接或断开该手机
                row.btnConnectDevice.text = if (device.isConnected) "断开" else "连接"
                row.btnConnectDevice.setOnClickListener {
                    if (device.isConnected) viewModel.disconnect(device.address)
                    else viewModel.connect(device.address)
                }
                // 解除配对
                row.btnRemoveBond.setOnClickListener { viewModel.removeBond(device.address) }
                devicesContainer.addView(row.root)
            }
        }
    }

    /** 通话列表：按内容快照比较，未变化时不重建行。 */
    private fun renderCallRows(calls: List<CallInfo>) {
        if (calls == renderedCalls) return
        renderedCalls = calls

        with(binding.callSection) {
            callsContainer.removeAllViews()
            callRows.clear()
            calls.forEach { item ->
                val row = ItemCallBinding.inflate(layoutInflater, callsContainer, false)
                val title = item.title()
                // 会议本身没有号码时标题已是“会议通话”，不再追加后缀
                row.tvCallTitle.text = if (item.isMultiParty && !item.number.isNullOrBlank()) {
                    "$title（会议）"
                } else {
                    title
                }
                // 通话状态与方向
                row.tvCallState.text = "${item.state.label()}  ${item.direction.label()}"
                // 时长先隐藏，由 renderDurations 根据是否接通决定
                row.tvCallDuration.isVisible = false
                callRows[item.id] = row
                callsContainer.addView(row.root)
            }
        }
    }

    /** 号码键盘复用同一份 XML，绑定两次即可。 */
    private fun bindKeypad(keypad: ViewKeypadBinding, onKey: (String) -> Unit) {
        listOf(
            keypad.key1 to "1",
            keypad.key2 to "2",
            keypad.key3 to "3",
            keypad.key4 to "4",
            keypad.key5 to "5",
            keypad.key6 to "6",
            keypad.key7 to "7",
            keypad.key8 to "8",
            keypad.key9 to "9",
            keypad.keyStar to "*",
            keypad.key0 to "0",
            keypad.keyHash to "#"
        ).forEach { (button, key) ->
            button.setOnClickListener { onKey(key) }
        }
    }

    /** 联系人展示文案。 */
    private fun contactText(contacts: List<Contact>): String {
        if (contacts.isEmpty()) return "联系人：未读取"
        return buildString {
            append("联系人（前 8 条）")
            contacts.take(8).forEach { append("\n${it.name ?: "未命名"}  ${it.number}") }
        }
    }

    /** 通话记录展示文案。 */
    private fun callLogText(callLog: List<CallLogEntry>): String {
        if (callLog.isEmpty()) return "通话记录：未读取"
        return buildString {
            append("通话记录（前 8 条）")
            callLog.take(8).forEach {
                append("\n${it.name ?: it.number ?: "未知"}  ${it.type}  ${it.durationSeconds}s")
            }
        }
    }

    /**
     * 文本相同则不写入。
     * TextView.setText 会走 checkForRelayout，重复赋值会多一次布局。
     */
    private fun TextView.renderText(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private companion object {
        /**
         * 演示所需运行时权限。
         * HFP Client 只依赖 BLUETOOTH_CONNECT；不请求 BLUETOOTH_SCAN，
         * 它在 Android 12+ 容易被归为定位用途，被拒后不应影响通话。
         */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        )
    }
}
