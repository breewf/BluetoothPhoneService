package com.example.bluetoothphone.core

import android.content.Context
import android.media.AudioManager
import com.example.bluetoothphone.BluetoothPhoneConfig
import com.example.bluetoothphone.api.AudioRoute
import com.example.bluetoothphone.api.AudioState
import com.example.bluetoothphone.api.CallInfo
import com.example.bluetoothphone.api.CallLogEntry
import com.example.bluetoothphone.api.CallState
import com.example.bluetoothphone.api.Contact
import com.example.bluetoothphone.api.PhoneCallState
import com.example.bluetoothphone.api.PhoneConnectionState
import com.example.bluetoothphone.api.PhoneDevice
import com.example.bluetoothphone.api.PhoneError
import com.example.bluetoothphone.api.PhoneEvent
import com.example.bluetoothphone.api.PhoneResult
import com.example.bluetoothphone.api.ProfileState
import com.example.bluetoothphone.hfp.AnswerPolicy
import com.example.bluetoothphone.hfp.DebugCallSimulator
import com.example.bluetoothphone.hfp.FakeHfpGateway
import com.example.bluetoothphone.hfp.FakePbapGateway
import com.example.bluetoothphone.hfp.HfpEvent
import com.example.bluetoothphone.hfp.HfpGateway
import com.example.bluetoothphone.hfp.PbapGateway
import com.example.bluetoothphone.hfp.SystemHfpGateway
import com.example.bluetoothphone.hfp.SystemPbapGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * 中间件唯一事实源。
 *
 * 所有 HFP 事件、设备状态、通话状态都在这条单线程调度器上归并；
 * UI 只读 StateFlow，不直接碰 BluetoothHeadsetClient。
 */
internal class PhoneEngine(
    context: Context,
    private val config: BluetoothPhoneConfig
) {
    private val appContext = context.applicationContext

    /** 仅用于本地麦克风静音；HFP 没有标准的静音 AT 命令。 */
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** 单线程执行器：所有 HFP 事件按顺序归并，避免并发竞争。 */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BluetoothPhoneEngine")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** HFP 网关：真实系统实现或模拟实现。 */
    private val gateway: HfpGateway =
        if (config.useMockGateway) FakeHfpGateway() else SystemHfpGateway(appContext, config)

    /** PBAP 网关：只负责触发电话本同步。 */
    private val pbap: PbapGateway =
        if (config.useMockGateway) FakePbapGateway() else SystemPbapGateway(appContext)

    /** 设备列表与当前设备。 */
    private val registry = DeviceRegistry()

    /** 通话状态归并，并注入上层提供的号码分类器。 */
    private val callStore = CallStateStore(config.callClassifier)

    private val prefs = PhonePreferences(appContext)
    private val contacts = ContactRepository(appContext)
    private val ringtone = RingtonePlayer(appContext)

    private val _connectionState = MutableStateFlow(PhoneConnectionState.NO_DEVICE)
    private val _events = MutableSharedFlow<PhoneEvent>(
        extraBufferCapacity = 16
    )

    private var started = false
    private var adapterEnabled = false

    /** 每台设备的自动重连次数。 */
    private val reconnectAttempts = mutableMapOf<String, Int>()

    /** 用户主动断开的设备，不参与自动重连。 */
    private val manualDisconnects = mutableSetOf<String>()

    /** 正在等待的重连任务，可取消。 */
    private val reconnectJobs = mutableMapOf<String, Job>()
    private var serviceRestartJob: Job? = null

    /** 避免并发重复加载联系人。 */
    private var contactsLoading = false

    /** 上一次对外发布的通话快照，用于生成来电/结束/阶段变化事件。 */
    private var lastPublishedState: PhoneCallState = PhoneCallState()

    /** 已配对设备列表。 */
    val devices: StateFlow<List<PhoneDevice>> = registry.devices

    /** 当前设备的通话快照。 */
    val callState: StateFlow<PhoneCallState> = callStore.state

    /** 整体连接状态。 */
    val connectionState: StateFlow<PhoneConnectionState> = _connectionState.asStateFlow()

    /** 一次性提示事件。 */
    val events: SharedFlow<PhoneEvent> = _events.asSharedFlow()

    /** 是否使用模拟网关。 */
    val isMock: Boolean = config.useMockGateway

    /** 当前设备：优先取用户选择，否则取第一台已连接设备。 */
    val activeDevice: StateFlow<PhoneDevice?> =
        combine(registry.devices, registry.activeAddress) { devices, address ->
            devices.firstOrNull { it.address == address }
                ?: devices.firstOrNull { it.isConnected }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // 所有网关事件在引擎线程串行处理。
        scope.launch { gateway.events.collect(::onGatewayEvent) }
        // 对外状态变化后生成一次性事件，保证 HMI 能可靠处理“结束页”等场景。
        scope.launch { callStore.state.collect(::onCallStatePublished) }
    }

    /** 启动链路；重复调用只做补偿，不会重建网关。 */
    fun start() {
        scope.launch {
            if (started) {
                // 重复 start() 只做补偿：网关自行重试代理，不重建整条链路。
                if (!gateway.isReady) gateway.start()
                pbap.start()
                refreshDevices()
                loadContactNames()
                return@launch
            }
            started = true
            adapterEnabled = gateway.isBluetoothEnabled()
            gateway.start()
            pbap.start()
            refreshDevices()
            loadContactNames()
            maybeAutoConnect()
        }
    }

    /** 停止监听并清空连接状态，实例可再次 [start]。 */
    fun stop() {
        scope.launch {
            if (!started) return@launch
            started = false
            gateway.stop()
            pbap.stop()
            ringtone.stop()
            serviceRestartJob?.cancel()
            registry.clearConnections()
            changeMicMute(false)
            emitAllCallsEnded()
            callStore.clearAll()
            _connectionState.value = PhoneConnectionState.NO_DEVICE
        }
    }

    /** 彻底释放：取消协程并关闭线程，实例不可再用。 */
    fun shutdown() {
        started = false
        emitAllCallsEnded()
        changeMicMute(false)
        ringtone.stop()
        runCatching { gateway.stop() }
        runCatching { pbap.stop() }
        scope.cancel()
        executor.shutdown()
    }

    /** 重新拉取设备/通话/信号，并刷新当前设备。 */
    fun refresh() {
        scope.launch {
            if (!started) return@launch
            if (!gateway.isReady) gateway.start()
            pbap.start()
            refreshDevices()
            loadContactNames()
            val device = registry.activeDevice() ?: return@launch
            if (device.isConnected) gateway.refresh(device.address)
        }
    }

    /** 切换当前设备，同时通知通话状态切换数据源。 */
    fun selectDevice(address: String) {
        scope.launch {
            registry.select(address)
            registry.device(address)?.let { callStore.selectDevice(address, it.displayName) }
        }
    }

    /** 调试入口：构造模拟来电（仅 Mock 网关生效）。 */
    fun simulateIncomingCall(number: String) {
        scope.launch {
            (gateway as? DebugCallSimulator)?.simulateIncomingCall(number)
        }
    }

    /** 连接手机；需要蓝牙已开且设备存在。 */
    suspend fun connect(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (!started) return@withContext failure(PhoneError.NOT_READY, "中间件尚未启动")
        if (!gateway.isBluetoothEnabled()) {
            return@withContext failure(PhoneError.BLUETOOTH_OFF, "蓝牙未开启")
        }
        registry.device(address) ?: return@withContext failure(PhoneError.NO_DEVICE, "设备不存在")
        manualDisconnects.remove(address)
        reconnectAttempts.remove(address)
        registry.markConnecting(address)
        if (gateway.connect(address)) success()
        else failure(PhoneError.NOT_READY, "连接命令发送失败")
    }

    /** 断开手机；标记为主动断开以阻止自动重连。 */
    suspend fun disconnect(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        manualDisconnects += address
        reconnectJobs.remove(address)?.cancel()
        if (gateway.disconnect(address)) success()
        else failure(PhoneError.NOT_READY, "断开命令发送失败")
    }

    /** 发起配对，结果通过广播回调。 */
    suspend fun createBond(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (gateway.createBond(address)) success()
        else failure(PhoneError.NOT_READY, "发起配对失败")
    }

    /** 解除配对并清理该设备的本地记录。 */
    suspend fun removeBond(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (gateway.removeBond(address)) {
            manualDisconnects += address
            reconnectJobs.remove(address)?.cancel()
            if (prefs.lastDeviceAddress == address) prefs.lastDeviceAddress = null
            pbap.disconnect(address)
            if (callStore.state.value.deviceId == address) emitAllCallsEnded()
            callStore.clearDevice(address)
            success()
        } else {
            failure(PhoneError.NOT_READY, "解除配对失败")
        }
    }

    /** 新拨一路电话；已有通话时必须走 [addCall]。 */
    suspend fun dial(number: String): PhoneResult<Unit> = withContext(dispatcher) {
        val normalized = number.trim()
        if (normalized.isBlank()) {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "号码为空")
        }
        val state = callStore.state.value
        if (state.waiting != null) {
            return@withContext failure(PhoneError.BUSY, "有等待来电，请先接听或拒接")
        }
        if (state.hasCall) {
            return@withContext failure(PhoneError.BUSY, "已有通话，请使用加拨")
        }
        val device = connectedDevice()
            ?: return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        if (gateway.dial(device.address, normalized)) success()
        else failure(PhoneError.NOT_READY, "拨号失败")
    }

    /** 已有一路接通（活动或保持）时加拨第二路，用于三方通话。 */
    suspend fun addCall(number: String): PhoneResult<Unit> = withContext(dispatcher) {
        val normalized = number.trim()
        if (normalized.isBlank()) {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "号码为空")
        }
        val state = callStore.state.value
        if (!state.canAddCall) {
            val (error, message) = when {
                state.waiting != null -> PhoneError.BUSY to "有等待来电，请先接听或拒接"
                state.outgoing != null -> PhoneError.BUSY to "已有去电中的通话，请先处理"
                state.conference != null -> PhoneError.NOT_SUPPORTED to "会议通话中不支持加拨"
                state.hasMultipleCalls -> PhoneError.BUSY to "已有两路通话，请先处理"
                else -> PhoneError.NO_CALL to "当前没有可加拨的通话"
            }
            return@withContext failure(error, message)
        }
        // 保持状态下 primaryCall 会取到 held，保证拨号发往正确的手机。
        val device = state.primaryCall?.deviceId?.let { registry.device(it) } ?: connectedDevice()
        if (device?.isConnected != true) {
            return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        }
        if (gateway.dial(device.address, normalized)) success()
        else failure(PhoneError.NOT_READY, "加拨失败")
    }

    /** 接听普通来电。 */
    suspend fun answer(): PhoneResult<Unit> = withContext(dispatcher) {
        val incoming = callStore.state.value.incoming
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有来电")
        if (gateway.acceptCall(incoming.deviceId, AnswerPolicy.NORMAL)) success()
        else failure(PhoneError.NOT_READY, "接听失败")
    }

    /** 拒接来电；优先普通来电，其次第二路等待。 */
    suspend fun reject(): PhoneResult<Unit> = withContext(dispatcher) {
        val call = callStore.state.value.incoming
            ?: callStore.state.value.waiting
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可拒接的电话")
        if (gateway.rejectCall(call.deviceId)) success()
        else failure(PhoneError.NOT_READY, "拒接失败")
    }

    /** 挂断当前活动通话。 */
    suspend fun hangup(): PhoneResult<Unit> = withContext(dispatcher) {
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        if (gateway.hangup(active.deviceId, active.id)) success()
        else failure(PhoneError.NOT_READY, "挂断失败")
    }

    /** 挂断保持中的通话，不影响当前活动通话。 */
    suspend fun hangupHeld(): PhoneResult<Unit> = withContext(dispatcher) {
        val held = callStore.state.value.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有保持中的通话")
        if (gateway.hangup(held.deviceId, held.id)) success()
        else failure(PhoneError.NOT_READY, "挂断失败")
    }

    /** 只挂断第二路等待来电，不影响当前活动通话。 */
    suspend fun hangupWaiting(): PhoneResult<Unit> = withContext(dispatcher) {
        val waiting = callStore.state.value.waiting
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有等待中的来电")
        // AT+CHUP 在多路通话中只作用于等待/来电，不会挂断当前活动通话。
        if (gateway.rejectCall(waiting.deviceId)) success()
        else failure(PhoneError.NOT_READY, "挂断等待来电失败")
    }

    /**
     * 保持/恢复。若 active + held 同时存在，等同切换。
     * 标准 HFP 由 AT+CHLD=2 完成。
     */
    suspend fun hold(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        // CHLD=2 在存在等待来电时会直接接听该路，避免误把“保持”按成接听。
        if (state.waiting != null) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "有等待来电，请先接听或拒接")
        }
        val call = state.active ?: state.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可保持/恢复的通话")
        if (gateway.hold(call.deviceId)) success()
        else failure(PhoneError.NOT_READY, "保持/切换失败")
    }

    /** AT+CHLD=2：活动与保持互换；有等待来电时禁止保持。 */
    suspend fun swap(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (state.waiting != null) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "有等待来电，请先接听或拒接")
        }
        if (state.active != null && state.held == null) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "没有保持中的通话可切换")
        }
        val call = state.active ?: state.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可切换的通话")
        if (gateway.hold(call.deviceId)) success()
        else failure(PhoneError.NOT_READY, "切换失败")
    }

    /** 合并活动+保持通话为会议（三方通话）。 */
    suspend fun merge(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (state.active == null || state.held == null) {
            return@withContext failure(PhoneError.NO_CALL, "需要一路活动通话和一路保持通话")
        }
        if (state.waiting != null) {
            return@withContext failure(PhoneError.BUSY, "有等待来电，请先接听或拒接")
        }
        if (state.outgoing != null) {
            return@withContext failure(PhoneError.BUSY, "有去电中的通话，请先处理")
        }
        if (state.active.isMultiParty || state.held.isMultiParty) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "当前已是会议通话，无需合并")
        }
        if (!gateway.supportsConference) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "当前系统未提供会议合并通道")
        }
        if (gateway.conference(state.active.deviceId)) success()
        else failure(PhoneError.NOT_SUPPORTED, "当前手机/协议栈不支持会议合并")
    }

    /**
     * 来电等待处理。
     * holdCurrent=true: 保持当前通话并接听第二路（CHLD=2）。
     * holdCurrent=false: 挂断当前通话并接听第二路（CHLD=1）。
     */
    suspend fun answerWaiting(holdCurrent: Boolean): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        val waiting = state.waiting
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有等待中的来电")
        // 没有活动通话时退化为普通接听，避免发送无意义的 HOLD/END 标志。
        val policy = when {
            state.active == null -> AnswerPolicy.NORMAL
            holdCurrent -> AnswerPolicy.HOLD_ACTIVE
            else -> AnswerPolicy.END_ACTIVE
        }
        if (gateway.acceptCall(waiting.deviceId, policy)) success()
        else failure(PhoneError.NOT_READY, "接听等待来电失败")
    }

    /** 发送 DTMF；只允许 0-9、*、#。 */
    suspend fun sendDtmf(code: Char): PhoneResult<Unit> = withContext(dispatcher) {
        if (code !in "0123456789*#") {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "非法 DTMF 字符")
        }
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        if (gateway.sendDtmf(active.deviceId, code)) success()
        else failure(PhoneError.NOT_READY, "DTMF 发送失败")
    }

    /** 连续发送多个 DTMF 字符，用于分机号、卡号等长串输入。 */
    suspend fun sendDtmf(dtmf: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (dtmf.isEmpty() || dtmf.any { it !in "0123456789*#" }) {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "非法 DTMF 字符")
        }
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        dtmf.forEachIndexed { index, code ->
            if (!gateway.sendDtmf(active.deviceId, code)) {
                return@withContext failure(PhoneError.NOT_READY, "DTMF 发送失败")
            }
            // 逐字符发送，给 AG 留出处理 AT+VTS 的间隔。
            if (index != dtmf.lastIndex) delay(60.milliseconds)
        }
        success()
    }

    /** 把通话音频切到车机；已经连接时直接成功。 */
    suspend fun setAudioToCar(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (!state.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可建立音频的通话")
        }
        if (state.audio == AudioState.CONNECTED || state.audio == AudioState.CONNECTING) {
            success()
        } else {
            audioToCar()
        }
    }

    /** 把通话音频交回手机；已经断开时直接成功。 */
    suspend fun setAudioToPhone(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (!state.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可建立音频的通话")
        }
        if (state.audio == AudioState.DISCONNECTED) success() else audioToPhone()
    }

    /** 根据当前路由切换车机/手机音频，供单个“切换声道”按钮使用。 */
    suspend fun toggleAudioRoute(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (!state.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可建立音频的通话")
        }
        if (state.audioRoute == AudioRoute.CAR) audioToPhone() else audioToCar()
    }

    /** 设置车机麦克风静音；只静音本地采集，不改变手机侧麦克风。 */
    suspend fun setMicMute(muted: Boolean): PhoneResult<Unit> = withContext(dispatcher) {
        if (!callStore.state.value.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可静音的通话")
        }
        if (changeMicMute(muted)) success()
        else failure(PhoneError.NOT_SUPPORTED, "系统不允许修改麦克风静音")
    }

    /** 在静音/取消静音之间切换。 */
    suspend fun toggleMicMute(): PhoneResult<Unit> = withContext(dispatcher) {
        if (!callStore.state.value.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可静音的通话")
        }
        if (changeMicMute(!callStore.state.value.micMuted)) success()
        else failure(PhoneError.NOT_SUPPORTED, "系统不允许修改麦克风静音")
    }

    /** 启动/停止手机侧语音识别。 */
    suspend fun voiceRecognition(start: Boolean): PhoneResult<Unit> = withContext(dispatcher) {
        val device = connectedDevice()
            ?: return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        if (gateway.voiceRecognition(device.address, start)) success()
        else failure(PhoneError.NOT_SUPPORTED, "手机不支持语音识别")
    }

    /** 读取联系人；无权限时返回 PERMISSION_DENIED。 */
    suspend fun loadContacts(limit: Int = 500): PhoneResult<List<Contact>> {
        if (!contacts.canReadContacts()) {
            return PhoneResult.Failure(PhoneError.PERMISSION_DENIED, "缺少 READ_CONTACTS 权限")
        }
        return PhoneResult.Success(contacts.contacts(limit))
    }

    /** 读取通话记录；无权限时返回 PERMISSION_DENIED。 */
    suspend fun loadCallLog(limit: Int = 200): PhoneResult<List<CallLogEntry>> {
        if (!contacts.canReadCallLog()) {
            return PhoneResult.Failure(PhoneError.PERMISSION_DENIED, "缺少 READ_CALL_LOG 权限")
        }
        return PhoneResult.Success(contacts.callLog(limit))
    }

    /** 网关事件总入口：更新设备/通话/音频，并驱动铃声与重连。 */
    private fun onGatewayEvent(event: HfpEvent) {
        when (event) {
            is HfpEvent.AdapterChanged -> {
                adapterEnabled = event.enabled
                if (event.enabled) {
                    // 蓝牙刚打开或代理丢失时，网关会自行补拿代理。
                    if (!gateway.isReady) gateway.start()
                    pbap.start()
                    refreshDevices()
                    loadContactNames()
                    maybeAutoConnect()
                } else {
                    pbap.stop()
                    registry.clearConnections()
                    changeMicMute(false)
                    emitAllCallsEnded()
                    callStore.clearAll()
                    ringtone.stop()
                    updateConnectionState()
                }
            }

            is HfpEvent.BondChanged -> {
                refreshDevices()
                if (registry.device(event.address) == null) {
                    pbap.disconnect(event.address)
                    if (callStore.state.value.deviceId == event.address) emitAllCallsEnded()
                    callStore.clearDevice(event.address)
                    ringtone.update(callStore.state.value)
                }
            }

            is HfpEvent.ConnectionChanged -> {
                if (registry.device(event.address) == null) refreshDevices()
                registry.updateConnection(event.address, event.state)
                when (event.state) {
                    ProfileState.CONNECTED -> onDeviceConnected(event.address)
                    ProfileState.DISCONNECTED -> onDeviceDisconnected(event.address)
                    else -> Unit
                }
                updateConnectionState()
            }

            is HfpEvent.AudioChanged -> {
                registry.updateAudio(event.address, event.state)
                callStore.updateAudio(event.address, event.state, event.wideband)
            }

            is HfpEvent.CallsChanged -> {
                if (registry.device(event.address) == null) refreshDevices()
                val name = registry.device(event.address)?.displayName
                callStore.updateCalls(event.address, name, event.calls)
                val current = callStore.state.value
                // 当前手机已有通话时不切换到另一台设备，避免把正在进行的通话隐藏掉。
                if (event.calls.isNotEmpty() &&
                    (current.deviceId == event.address || !current.hasCall)
                ) {
                    registry.select(event.address)
                    callStore.selectDevice(event.address, name)
                }
                ringtone.update(callStore.state.value)
            }

            is HfpEvent.AgChanged -> {
                registry.updateAg(event.address, event.ag)
                ringtone.update(callStore.state.value)
            }

            is HfpEvent.ServiceLost -> scheduleGatewayRestart()

            is HfpEvent.Error -> _events.tryEmit(PhoneEvent.Error(event.message))
        }
    }

    /** 是否是会议合并前的参与方（活动/保持/去电）。 */
    private fun isConferenceParticipant(call: CallInfo): Boolean =
        call.isActive || call.isHeld ||
            call.state == CallState.DIALING || call.state == CallState.ALERTING

    /** 非 HFP 终止场景（蓝牙关闭/断开/停止）主动补发结束事件。 */
    private fun emitAllCallsEnded() {
        val calls = callStore.state.value.calls
        calls.forEach { _events.tryEmit(PhoneEvent.CallEnded(it)) }
        if (calls.isNotEmpty()) _events.tryEmit(PhoneEvent.SessionEnded(calls.last()))
    }

    /** 对外通话状态变化：补发阶段/结束事件，并在无通话时自动恢复麦克风。 */
    private fun onCallStatePublished(current: PhoneCallState) {
        val previous = lastPublishedState
        lastPublishedState = current

        if (previous.uiState != current.uiState) {
            _events.tryEmit(PhoneEvent.CallUiChanged(previous.uiState, current.uiState))
        }

        // 只有同一台手机才能确定是“通话结束”，避免切换设备时误报。
        if (previous.deviceId == current.deviceId && previous.calls.isNotEmpty()) {
            val currentIds = current.calls.mapTo(mutableSetOf()) { it.id }
            // 合并会议时原来的活动/保持会被会议替代，不能当作挂断。
            val conferenceStarted = previous.conference == null && current.conference != null
            val removed = previous.calls
                .filter { it.id !in currentIds }
                .filterNot { conferenceStarted && isConferenceParticipant(it) }
            removed.forEach { _events.tryEmit(PhoneEvent.CallEnded(it)) }

            if (current.calls.isEmpty()) {
                _events.tryEmit(PhoneEvent.SessionEnded(removed.lastOrNull()))
            }
        }

        // 通话结束后系统可能仍保持静音，这里恢复，避免下一通电话没声音。
        if (!current.hasVoiceSession && current.micMuted) changeMicMute(false)
    }

    /** 设备连接成功：记住设备、同步电话本、刷新状态。 */
    private fun onDeviceConnected(address: String) {
        manualDisconnects.remove(address)
        reconnectAttempts[address] = 0
        reconnectJobs.remove(address)?.cancel()
        prefs.lastDeviceAddress = address
        pbap.connect(address)
        // 代理就绪后再刷新一次能力，避免启动瞬间误判不支持会议。
        callStore.setConferenceMergeSupported(gateway.supportsConference)
        gateway.setAudioRouteAllowed(address, true)
        // 另一台手机正在通话时，不把当前通话切走。
        val current = callStore.state.value
        val active = registry.activeDevice()
        if (current.deviceId == address || (!current.hasCall && active?.address == address)) {
            registry.device(address)?.let { callStore.selectDevice(address, it.displayName) }
        }
        gateway.refresh(address)
        loadContactNames()
    }

    /** 把联系人姓名回填到已有通话；失败不影响主流程。 */
    private fun loadContactNames() {
        if (!contacts.canReadContacts() || contactsLoading) return
        contactsLoading = true
        scope.launch {
            try {
                val matches = contacts.contacts(2_000)
                    .mapNotNull { contact ->
                        val name = contact.name?.takeIf { it.isNotBlank() }
                        val photoUri = contact.photoUri?.takeIf { it.isNotBlank() }
                        if (name == null && photoUri == null) return@mapNotNull null
                        val key = contact.number.filter { it.isDigit() }.takeLast(10)
                        key.takeIf { it.isNotBlank() }?.let { it to ContactMatch(name, photoUri) }
                    }
                    .toMap()
                callStore.updateContacts(matches)
            } finally {
                contactsLoading = false
            }
        }
    }

    /** 设备断开：清理通话与铃声，并按配置安排重连。 */
    private fun onDeviceDisconnected(address: String) {
        pbap.disconnect(address)
        if (callStore.state.value.deviceId == address) emitAllCallsEnded()
        callStore.clearDevice(address)
        // 另一台手机可能仍在响铃，这里按最新状态重算，而不是直接停铃。
        ringtone.update(callStore.state.value)
        registry.selectConnectedIfNeeded()
        // 优先跟随仍有通话的设备，其次才是任意已连接设备。
        val callDeviceId = callStore.state.value.deviceId
        val target = callDeviceId?.let { registry.device(it) } ?: registry.activeDevice()
        target?.let {
            if (callDeviceId != null) registry.select(it.address)
            callStore.selectDevice(it.address, it.displayName)
        }
        scheduleReconnect(address)
    }

    /** 自动重连：递增退避，最多 3 次；主动断开不重连。 */
    private fun scheduleReconnect(address: String) {
        if (!config.autoReconnect || address in manualDisconnects) return
        val attempt = reconnectAttempts.getOrDefault(address, 0)
        if (attempt >= 3) return
        reconnectAttempts[address] = attempt + 1
        reconnectJobs.remove(address)?.cancel()
        reconnectJobs[address] = scope.launch {
            delay(config.reconnectDelayMs * (attempt + 1))
            if (!started || !gateway.isBluetoothEnabled()) return@launch
            if (registry.device(address)?.isConnected == true) return@launch
            gateway.connect(address)
        }
    }

    /** 系统蓝牙服务重启后，延时重建网关代理。 */
    private fun scheduleGatewayRestart() {
        if (!started) return
        _events.tryEmit(PhoneEvent.Info("蓝牙电话服务断开，正在重连"))
        serviceRestartJob?.cancel()
        serviceRestartJob = scope.launch {
            delay(500)
            if (!started || !gateway.isBluetoothEnabled()) return@launch
            gateway.stop()
            gateway.start()
            refreshDevices()
        }
    }

    /** 重建设备列表，并保留历史电量/信号等状态。 */
    private fun refreshDevices() {
        adapterEnabled = gateway.isBluetoothEnabled()
        callStore.setConferenceMergeSupported(gateway.supportsConference)
        registry.replaceBonded(gateway.bondedDevices())
        registry.selectConnectedIfNeeded()
        registry.devices.value.filter { it.isConnected }.forEach {
            gateway.setAudioRouteAllowed(it.address, true)
            pbap.connect(it.address)
        }
        updateConnectionState()
    }

    /** 启动后自动连接上次使用的设备。 */
    private fun maybeAutoConnect() {
        if (!config.autoConnectLastDevice) return
        val address = prefs.lastDeviceAddress ?: return
        val device = registry.device(address) ?: return
        if (device.isConnected || device.hfpState == ProfileState.CONNECTING) return
        scope.launch {
            delay(config.reconnectDelayMs)
            if (!started || !gateway.isBluetoothEnabled()) return@launch
            if (registry.device(address)?.isConnected == true) return@launch
            gateway.connect(address)
        }
    }

    /** 根据蓝牙开关和设备状态计算整体连接状态。 */
    private fun updateConnectionState() {
        val devices = registry.devices.value
        _connectionState.value = when {
            !adapterEnabled -> PhoneConnectionState.BLUETOOTH_OFF
            devices.any { it.isConnected } -> PhoneConnectionState.CONNECTED
            devices.any { it.hfpState == ProfileState.CONNECTING } -> PhoneConnectionState.CONNECTING
            else -> PhoneConnectionState.NO_DEVICE
        }
    }

    /** 取当前可用设备：优先当前设备，否则任意已连接设备。 */
    private fun connectedDevice(): PhoneDevice? {
        val active = registry.activeDevice()
        if (active?.isConnected == true) return active
        return registry.devices.value.firstOrNull { it.isConnected }
    }

    /** 音频操作优先跟随当前通话所属设备。 */
    private fun activeAudioDevice(): PhoneDevice? {
        val state = callStore.state.value
        val fromCall = state.deviceId?.let { registry.device(it) }
        return fromCall?.takeIf { it.isConnected } ?: connectedDevice()
    }

    /** 实际切换到车机音频，避免 suspend 方法内部嵌套 withContext。 */
    private fun audioToCar(): PhoneResult<Unit> {
        val device = activeAudioDevice()
            ?: return failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        gateway.setAudioRouteAllowed(device.address, true)
        return if (gateway.connectAudio(device.address)) success()
        else failure(PhoneError.NOT_READY, "音频切到车机失败")
    }

    /** 实际切换到手机音频。 */
    private fun audioToPhone(): PhoneResult<Unit> {
        val device = activeAudioDevice()
            ?: return failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        return if (gateway.disconnectAudio(device.address)) success()
        else failure(PhoneError.NOT_READY, "音频切到手机失败")
    }

    /** 修改 AudioManager 静音并同步到状态；系统拒绝时返回 false。 */
    private fun changeMicMute(muted: Boolean): Boolean {
        if (callStore.state.value.micMuted == muted) return true
        val audio = audioManager ?: return false
        val applied = runCatching {
            audio.setMicrophoneMute(muted)
            audio.isMicrophoneMute == muted
        }.getOrDefault(false)
        if (!applied) return false
        callStore.setMicMuted(muted)
        return true
    }

    /** 构造空成功结果。 */
    private fun success(): PhoneResult<Unit> = PhoneResult.Success(Unit)

    /** 构造失败结果，message 可直接展示。 */
    private fun failure(error: PhoneError, message: String? = null): PhoneResult<Unit> =
        PhoneResult.Failure(error, message)
}
