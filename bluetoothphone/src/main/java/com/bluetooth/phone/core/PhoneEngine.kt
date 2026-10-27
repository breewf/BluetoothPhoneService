package com.bluetooth.phone.core

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.bluetooth.phone.BluetoothPhoneConfig
import com.bluetooth.phone.api.AudioRoute
import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.CallInfo
import com.bluetooth.phone.api.CallLogEntry
import com.bluetooth.phone.api.CallState
import com.bluetooth.phone.api.Contact
import com.bluetooth.phone.api.PhoneCallState
import com.bluetooth.phone.api.PhoneConnectionState
import com.bluetooth.phone.api.PhoneDevice
import com.bluetooth.phone.api.PhoneError
import com.bluetooth.phone.api.PhoneEvent
import com.bluetooth.phone.api.PhoneResult
import com.bluetooth.phone.api.ProfileState
import com.bluetooth.phone.hfp.AnswerPolicy
import com.bluetooth.phone.hfp.DebugCallSimulator
import com.bluetooth.phone.hfp.FakeHfpGateway
import com.bluetooth.phone.hfp.FakePbapGateway
import com.bluetooth.phone.hfp.HfpEvent
import com.bluetooth.phone.hfp.HfpGateway
import com.bluetooth.phone.hfp.PbapGateway
import com.bluetooth.phone.hfp.SystemHfpGateway
import com.bluetooth.phone.hfp.SystemPbapGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** 内部可变连接状态。 */
    private val _connectionState = MutableStateFlow(PhoneConnectionState.NO_DEVICE)

    /** 一次性提示事件；缓冲区满时丢弃最旧事件，保证 tryEmit 不会静默失败。 */
    private val _events = MutableSharedFlow<PhoneEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 是否已启动；[shutdown] 可能来自主线程，需要保证可见性。 */
    @Volatile
    private var started = false

    /** 蓝牙开关快照，用于推导整体连接状态。 */
    @Volatile
    private var adapterEnabled = false

    /** 每台设备的自动重连次数。 */
    private val reconnectAttempts = mutableMapOf<String, Int>()

    /** 用户主动断开的设备，不参与自动重连。 */
    private val manualDisconnects = mutableSetOf<String>()

    /** 正在等待的重连任务，可取消。 */
    private val reconnectJobs = mutableMapOf<String, Job>()
    /** 蓝牙服务重启后的代理重建任务。 */
    private var serviceRestartJob: Job? = null

    /** 避免并发重复加载联系人。 */
    private var contactsLoading = false

    /** 上一次加载联系人的时刻，避免启动阶段重复全量读取。 */
    private var lastContactLoadElapsed = 0L

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
        // 所有网关事件在引擎线程串行处理；单条事件异常不能终止整条事件流。
        scope.launch {
            gateway.events.collect { event ->
                runCatching { onGatewayEvent(event) }
                    .onFailure { PhoneLog.engine.e(it) { "处理网关事件失败: ${event.summary()}" } }
            }
        }
        // 对外状态变化后生成一次性事件，保证 HMI 能可靠处理“结束页”等场景。
        scope.launch {
            callStore.state.collect { state ->
                runCatching { onCallStatePublished(state) }
                    .onFailure { PhoneLog.engine.e(it) { "处理通话状态失败" } }
            }
        }
    }

    /** 启动链路；重复调用只做补偿，不会重建网关。 */
    fun start() {
        scope.launch {
            if (started) {
                // 重复 start() 只做补偿：网关自行重试代理，不重建整条链路。
                PhoneLog.engine.d { "start(): 已在运行，执行补偿启动" }
                if (!gateway.isReady) gateway.start()
                pbap.start()
                refreshDevices()
                loadContactNames()
                // 权限可能在运行中才授予，补偿启动时补一次自动连接。
                maybeAutoConnect()
                return@launch
            }
            started = true
            adapterEnabled = gateway.isBluetoothEnabled()
            PhoneLog.engine.i {
                "start(): mock=$isMock, 蓝牙=${if (adapterEnabled) "开" else "关"}"
            }
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
            PhoneLog.engine.i { "stop(): 主动停止，清理设备与通话状态" }
            gateway.stop()
            pbap.stop()
            ringtone.stop()
            serviceRestartJob?.cancel()
            reconnectJobs.values.forEach { it.cancel() }
            reconnectJobs.clear()
            reconnectAttempts.clear()
            registry.clearConnections()
            changeMicMute(false)
            emitAllCallsEnded()
            callStore.clearAll()
            _connectionState.value = PhoneConnectionState.NO_DEVICE
        }
    }

    /**
     * 彻底释放：先停网关阻断新事件，再取消协程、关闭线程；
     * 待引擎线程退出后再复位对外状态，避免与归并线程竞争。
     */
    fun shutdown() {
        started = false
        PhoneLog.engine.i { "shutdown(): 释放引擎与线程" }
        runCatching { gateway.stop() }
        runCatching { pbap.stop() }
        ringtone.stop()
        serviceRestartJob?.cancel()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        scope.cancel()
        executor.shutdown()
        // 引擎线程通常已空闲，这里只做有界等待。
        runCatching { executor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        // 线程退出后再补发结束事件、复位静音与状态。
        emitAllCallsEnded()
        changeMicMute(false)
        callStore.clearAll()
    }

    /** 重新拉取设备/通话/信号，并刷新当前设备。 */
    fun refresh() {
        scope.launch {
            if (!started) return@launch
            PhoneLog.engine.d { "refresh(): 主动刷新设备与通话" }
            if (!gateway.isReady) gateway.start()
            pbap.start()
            refreshDevices()
            loadContactNames()
            val device = registry.activeDevice() ?: return@launch
            if (device.isConnected) gateway.refresh(device.address)
        }
    }

    /** 切换当前设备；只接受已连接设备，避免界面与拨号目标不一致。 */
    fun selectDevice(address: String) {
        scope.launch {
            val device = registry.device(address)
            if (device == null || !device.isConnected) {
                PhoneLog.engine.w {
                    "selectDevice($address): ${if (device == null) "设备不存在" else "设备未连接"}，已忽略"
                }
                _events.tryEmit(PhoneEvent.Info("设备未连接，无法切换"))
                return@launch
            }
            PhoneLog.engine.i { "selectDevice($address): 当前设备 -> ${device.displayName}" }
            registry.select(address)
            callStore.selectDevice(address, device.displayName)
            // 铃声跟随当前数据源，避免切走后另一台设备的铃声继续响。
            ringtone.update(callStore.state.value)
            // 切换后主动拉一次最新通话，避免展示缓存的旧快照。
            gateway.refresh(address)
        }
    }

    /** 让设备列表的“当前设备”跟随通话数据源；没有数据源时选第一台已连接设备。 */
    private fun syncActiveDevice() {
        val callDevice = callStore.state.value.deviceId?.let { registry.device(it) }
            ?.takeIf { it.isConnected }
        if (callDevice != null) registry.select(callDevice.address)
        else registry.selectConnectedIfNeeded()
    }

    /** 调试入口：构造模拟来电（仅 Mock 网关生效）。 */
    fun simulateIncomingCall(number: String) {
        scope.launch {
            PhoneLog.engine.i {
                "simulateIncomingCall(${PhoneLog.mask(number)}): 仅 Mock 生效"
            }
            (gateway as? DebugCallSimulator)?.simulateIncomingCall(number)
        }
    }

    /** 连接手机；需要蓝牙已开且设备存在。 */
    suspend fun connect(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (!started) return@withContext failure(PhoneError.NOT_READY, "中间件尚未启动")
        if (!gateway.isBluetoothEnabled()) {
            PhoneLog.engine.w { "connect($address): 蓝牙未开启" }
            return@withContext failure(PhoneError.BLUETOOTH_OFF, "蓝牙未开启")
        }
        val device = registry.device(address)
            ?: return@withContext failure(PhoneError.NO_DEVICE, "设备不存在")
        if (device.isConnected) {
            PhoneLog.engine.d { "connect($address): 已连接，无需重复下发" }
            return@withContext success()
        }
        PhoneLog.engine.d { "connect($address): 请求 HFP 连接" }
        manualDisconnects.remove(address)
        reconnectAttempts.remove(address)
        registry.markConnecting(address)
        if (gateway.connect(address)) {
            PhoneLog.engine.i { "connect($address): 连接命令已下发" }
            // 本地已标记 CONNECTING，立即刷新整体状态，无需等系统广播。
            updateConnectionState()
            success()
        } else {
            PhoneLog.engine.w { "connect($address): 连接命令发送失败" }
            // 命令未下发时回退“连接中”状态，避免界面永久停留。
            registry.updateConnection(address, ProfileState.DISCONNECTED)
            updateConnectionState()
            failure(PhoneError.NOT_READY, "连接命令发送失败")
        }
    }

    /** 断开手机；标记为主动断开以阻止自动重连。 */
    suspend fun disconnect(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (!started) return@withContext failure(PhoneError.NOT_READY, "中间件尚未启动")
        PhoneLog.engine.i { "disconnect($address): 主动断开，禁止自动重连" }
        manualDisconnects += address
        reconnectJobs.remove(address)?.cancel()
        // 已经断开时直接成功，避免把“断开失败”误报给用户。
        if (registry.device(address)?.isConnected == false) return@withContext success()
        if (gateway.disconnect(address)) success()
        else {
            PhoneLog.engine.w { "disconnect($address): 断开命令发送失败" }
            failure(PhoneError.NOT_READY, "断开命令发送失败")
        }
    }

    /** 发起配对，结果通过广播回调。 */
    suspend fun createBond(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (!started) return@withContext failure(PhoneError.NOT_READY, "中间件尚未启动")
        PhoneLog.engine.i { "createBond($address): 发起配对" }
        if (gateway.createBond(address)) success()
        else {
            PhoneLog.engine.w { "createBond($address): 发起配对失败" }
            failure(PhoneError.NOT_READY, "发起配对失败")
        }
    }

    /** 解除配对并清理该设备的本地记录。 */
    suspend fun removeBond(address: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (!started) return@withContext failure(PhoneError.NOT_READY, "中间件尚未启动")
        if (gateway.removeBond(address)) {
            PhoneLog.engine.i { "removeBond($address): 已解绑并清理本地状态" }
            manualDisconnects += address
            reconnectJobs.remove(address)?.cancel()
            reconnectAttempts.remove(address)
            if (prefs.lastDeviceAddress == address) prefs.lastDeviceAddress = null
            pbap.disconnect(address)
            if (callStore.state.value.deviceId == address) emitAllCallsEnded()
            callStore.clearDevice(address)
            // 解绑后 HFP 必然断开，先本地标记，避免界面短暂显示“已配对且已连接”。
            registry.updateConnection(address, ProfileState.DISCONNECTED)
            updateConnectionState()
            // 解绑可能发生在响铃过程中，按最新状态停掉铃声。
            ringtone.update(callStore.state.value)
            syncActiveDevice()
            success()
        } else {
            PhoneLog.engine.w { "removeBond($address): 解除配对失败" }
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
            PhoneLog.engine.w { "dial(${PhoneLog.mask(normalized)}): 有等待来电，拒绝拨号" }
            return@withContext failure(PhoneError.BUSY, "有等待来电，请先接听或拒接")
        }
        if (state.outgoing != null) {
            PhoneLog.engine.w { "dial(${PhoneLog.mask(normalized)}): 已有去电，拒绝拨号" }
            return@withContext failure(PhoneError.BUSY, "已有去电中的通话，请先处理")
        }
        if (state.hasCall) {
            PhoneLog.engine.w { "dial(${PhoneLog.mask(normalized)}): 已有通话，需走加拨" }
            return@withContext failure(PhoneError.BUSY, "已有通话，请使用加拨")
        }
        val device = connectedDevice()
            ?: return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        PhoneLog.engine.i { "dial(${PhoneLog.mask(normalized)}): -> ${device.displayName}" }
        if (gateway.dial(device.address, normalized)) success()
        else {
            PhoneLog.engine.w { "dial(${PhoneLog.mask(normalized)}): 下发失败" }
            failure(PhoneError.NOT_READY, "拨号失败")
        }
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
            PhoneLog.engine.d { "addCall: 当前不可加拨 -> $message" }
            return@withContext failure(error, message)
        }
        // 保持状态下 primaryCall 会取到 held，保证拨号发往正确的手机。
        val device = state.primaryCall?.deviceId?.let { registry.device(it) } ?: connectedDevice()
        if (device?.isConnected != true) {
            return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        }
        PhoneLog.engine.i {
            "addCall(${PhoneLog.mask(normalized)}): 加拨 -> ${device.displayName}"
        }
        if (gateway.dial(device.address, normalized)) success()
        else {
            PhoneLog.engine.w { "addCall(${PhoneLog.mask(normalized)}): 下发失败" }
            failure(PhoneError.NOT_READY, "加拨失败")
        }
    }

    /** 接听响铃中的一路：普通来电优先，其次是没有活动通话时的等待来电。 */
    suspend fun answer(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        // 少数 AG 会把第二路直接上报为 WAITING 且当前没有活动通话，这里一并接听。
        val ringing = state.incoming
            ?: state.waiting?.takeIf { state.active == null }
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有来电")
        PhoneLog.engine.i {
            "answer(): 接听 ${PhoneLog.mask(ringing.number)}, " +
                "callId=${ringing.id}, state=${ringing.state}"
        }
        if (gateway.acceptCall(ringing.deviceId, AnswerPolicy.NORMAL)) success()
        else {
            PhoneLog.engine.w { "answer(): 接听失败" }
            failure(PhoneError.NOT_READY, "接听失败")
        }
    }

    /** 拒接来电；优先普通来电，其次第二路等待。 */
    suspend fun reject(): PhoneResult<Unit> = withContext(dispatcher) {
        val call = callStore.state.value.incoming
            ?: callStore.state.value.waiting
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可拒接的电话")
        PhoneLog.engine.i {
            "reject(): 拒接 ${PhoneLog.mask(call.number)}, callId=${call.id}, state=${call.state}"
        }
        if (gateway.rejectCall(call.deviceId)) success()
        else {
            PhoneLog.engine.w { "reject(): 拒接失败" }
            failure(PhoneError.NOT_READY, "拒接失败")
        }
    }

    /** 挂断当前活动通话。 */
    suspend fun hangup(): PhoneResult<Unit> = withContext(dispatcher) {
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        PhoneLog.engine.i {
            "hangup(): 挂断活动通话 ${PhoneLog.mask(active.number)}, callId=${active.id}"
        }
        if (gateway.hangup(active.deviceId, active.id)) success()
        else {
            PhoneLog.engine.w { "hangup(): 挂断失败, callId=${active.id}" }
            failure(PhoneError.NOT_READY, "挂断失败")
        }
    }

    /** 取消去电中的一路（对方尚未接听）；有保持通话时保持不受影响。 */
    suspend fun cancelOutgoing(): PhoneResult<Unit> = withContext(dispatcher) {
        val outgoing = callStore.state.value.outgoing
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有去电中的通话")
        PhoneLog.engine.i {
            "cancelOutgoing(): 取消 ${PhoneLog.mask(outgoing.number)}, callId=${outgoing.id}"
        }
        if (gateway.hangup(outgoing.deviceId, outgoing.id)) success()
        else {
            PhoneLog.engine.w { "cancelOutgoing(): 取消失败, callId=${outgoing.id}" }
            failure(PhoneError.NOT_READY, "取消拨号失败")
        }
    }

    /** 挂断保持中的通话，不影响当前活动通话。 */
    suspend fun hangupHeld(): PhoneResult<Unit> = withContext(dispatcher) {
        val held = callStore.state.value.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有保持中的通话")
        PhoneLog.engine.i {
            "hangupHeld(): 挂断保持通话 ${PhoneLog.mask(held.number)}, callId=${held.id}"
        }
        if (gateway.hangup(held.deviceId, held.id)) success()
        else {
            PhoneLog.engine.w { "hangupHeld(): 挂断失败, callId=${held.id}" }
            failure(PhoneError.NOT_READY, "挂断失败")
        }
    }

    /** 只挂断第二路等待来电，不影响当前活动通话。 */
    suspend fun hangupWaiting(): PhoneResult<Unit> = withContext(dispatcher) {
        val waiting = callStore.state.value.waiting
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有等待中的来电")
        // AT+CHUP 在多路通话中只作用于等待/来电，不会挂断当前活动通话。
        PhoneLog.engine.i {
            "hangupWaiting(): 挂断等待来电 ${PhoneLog.mask(waiting.number)}, callId=${waiting.id}"
        }
        if (gateway.rejectCall(waiting.deviceId)) success()
        else {
            PhoneLog.engine.w { "hangupWaiting(): 挂断失败" }
            failure(PhoneError.NOT_READY, "挂断等待来电失败")
        }
    }

    /**
     * 保持/恢复。若 active + held 同时存在，等同切换；
     * 有等待来电或去电时直接拒绝。标准 HFP 由 AT+CHLD=2 完成。
     */
    suspend fun hold(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        // CHLD=2 在存在等待来电时会直接接听该路，避免误把“保持”按成接听。
        if (state.waiting != null) {
            PhoneLog.engine.w { "hold(): 有等待来电，CHLD=2 会误接听，已阻止" }
            return@withContext failure(PhoneError.NOT_SUPPORTED, "有等待来电，请先接听或拒接")
        }
        // 去电中无法确定 CHLD=2 的作用目标，先要求处理第二路。
        if (state.outgoing != null) {
            return@withContext failure(PhoneError.BUSY, "有去电中的通话，请先处理")
        }
        val call = state.active ?: state.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可保持/恢复的通话")
        val action = when {
            state.active != null && state.held != null -> "切换"
            state.active != null -> "保持"
            else -> "恢复"
        }
        PhoneLog.engine.i {
            "hold(): $action ${PhoneLog.mask(call.number)}, callId=${call.id}"
        }
        if (gateway.hold(call.deviceId)) success()
        else {
            PhoneLog.engine.w { "hold(): $action 失败, callId=${call.id}" }
            failure(PhoneError.NOT_READY, "保持/切换失败")
        }
    }

    /** AT+CHLD=2：活动与保持互换；有等待来电时禁止保持。 */
    suspend fun swap(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (state.waiting != null) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "有等待来电，请先接听或拒接")
        }
        // 去电中无法确定切换目标，先要求处理第二路。
        if (state.outgoing != null) {
            return@withContext failure(PhoneError.BUSY, "有去电中的通话，请先处理")
        }
        if (state.active != null && state.held == null) {
            return@withContext failure(PhoneError.NOT_SUPPORTED, "没有保持中的通话可切换")
        }
        val call = state.active ?: state.held
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有可切换的通话")
        PhoneLog.engine.i {
            "swap(): 切换 ${PhoneLog.mask(state.active?.number)} <-> " +
                PhoneLog.mask(state.held?.number)
        }
        if (gateway.hold(call.deviceId)) success()
        else {
            PhoneLog.engine.w { "swap(): 切换失败" }
            failure(PhoneError.NOT_READY, "切换失败")
        }
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
            PhoneLog.engine.w { "merge(): 系统未提供会议合并通道" }
            return@withContext failure(PhoneError.NOT_SUPPORTED, "当前系统未提供会议合并通道")
        }
        PhoneLog.engine.i {
            "merge(): 合并 ${PhoneLog.mask(state.active.number)} + ${PhoneLog.mask(state.held.number)}"
        }
        if (gateway.conference(state.active.deviceId)) success()
        else {
            PhoneLog.engine.w { "merge(): 手机/协议栈拒绝 CHLD=3" }
            failure(PhoneError.NOT_SUPPORTED, "当前手机/协议栈不支持会议合并")
        }
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
        PhoneLog.engine.i {
            "answerWaiting(holdCurrent=$holdCurrent): policy=$policy, " +
                "等待来电=${PhoneLog.mask(waiting.number)}"
        }
        if (gateway.acceptCall(waiting.deviceId, policy)) success()
        else {
            PhoneLog.engine.w { "answerWaiting(): 接听失败, policy=$policy" }
            failure(PhoneError.NOT_READY, "接听等待来电失败")
        }
    }

    /** 发送 DTMF；只允许 0-9、*、#。 */
    suspend fun sendDtmf(code: Char): PhoneResult<Unit> = withContext(dispatcher) {
        if (code !in "0123456789*#") {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "非法 DTMF 字符")
        }
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        PhoneLog.engine.v { "sendDtmf(): '$code'" }
        if (gateway.sendDtmf(active.deviceId, code)) success()
        else {
            PhoneLog.engine.w { "sendDtmf(): '$code' 发送失败" }
            failure(PhoneError.NOT_READY, "DTMF 发送失败")
        }
    }

    /** 连续发送多个 DTMF 字符，用于分机号、卡号等长串输入。 */
    suspend fun sendDtmf(dtmf: String): PhoneResult<Unit> = withContext(dispatcher) {
        if (dtmf.isEmpty() || dtmf.any { it !in "0123456789*#" }) {
            return@withContext failure(PhoneError.INVALID_ARGUMENT, "非法 DTMF 字符")
        }
        val active = callStore.state.value.active
            ?: return@withContext failure(PhoneError.NO_CALL, "当前没有活动通话")
        PhoneLog.engine.d { "sendDtmf(): ${dtmf.length} 个字符" }
        dtmf.forEachIndexed { index, code ->
            if (!gateway.sendDtmf(active.deviceId, code)) {
                PhoneLog.engine.w { "sendDtmf(): 第 ${index + 1} 个字符发送失败" }
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
            PhoneLog.engine.d { "setAudioToCar(): 已是 ${state.audio}，跳过" }
            success()
        } else {
            PhoneLog.engine.i { "setAudioToCar(): 请求建立 SCO/eSCO" }
            audioToCar()
        }
    }

    /** 把通话音频交回手机；已经断开时直接成功。 */
    suspend fun setAudioToPhone(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (!state.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可建立音频的通话")
        }
        if (state.audio == AudioState.DISCONNECTED) {
            PhoneLog.engine.d { "setAudioToPhone(): 已是断开状态，跳过" }
            success()
        } else {
            PhoneLog.engine.i { "setAudioToPhone(): 断开 SCO，交回手机" }
            audioToPhone()
        }
    }

    /** 根据当前路由切换车机/手机音频，供单个“切换声道”按钮使用。 */
    suspend fun toggleAudioRoute(): PhoneResult<Unit> = withContext(dispatcher) {
        val state = callStore.state.value
        if (!state.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可建立音频的通话")
        }
        val toCar = state.audioRoute != AudioRoute.CAR
        PhoneLog.engine.i {
            "toggleAudioRoute(): ${state.audioRoute} -> ${if (toCar) AudioRoute.CAR else AudioRoute.PHONE}"
        }
        if (toCar) audioToCar() else audioToPhone()
    }

    /** 设置车机麦克风静音；只静音本地采集，不改变手机侧麦克风。 */
    suspend fun setMicMute(muted: Boolean): PhoneResult<Unit> = withContext(dispatcher) {
        if (!callStore.state.value.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可静音的通话")
        }
        if (changeMicMute(muted)) success()
        else {
            PhoneLog.engine.w { "setMicMute($muted): 系统拒绝修改" }
            failure(PhoneError.NOT_SUPPORTED, "系统不允许修改麦克风静音")
        }
    }

    /** 在静音/取消静音之间切换。 */
    suspend fun toggleMicMute(): PhoneResult<Unit> = withContext(dispatcher) {
        if (!callStore.state.value.hasVoiceSession) {
            return@withContext failure(PhoneError.NO_CALL, "当前没有可静音的通话")
        }
        val target = !callStore.state.value.micMuted
        if (changeMicMute(target)) success()
        else {
            PhoneLog.engine.w { "toggleMicMute(): 系统拒绝修改" }
            failure(PhoneError.NOT_SUPPORTED, "系统不允许修改麦克风静音")
        }
    }

    /** 启动/停止手机侧语音识别。 */
    suspend fun voiceRecognition(start: Boolean): PhoneResult<Unit> = withContext(dispatcher) {
        val device = connectedDevice()
            ?: return@withContext failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        PhoneLog.engine.i { "voiceRecognition(start=$start)" }
        if (gateway.voiceRecognition(device.address, start)) success()
        else {
            PhoneLog.engine.w { "voiceRecognition(start=$start): 手机不支持" }
            failure(PhoneError.NOT_SUPPORTED, "手机不支持语音识别")
        }
    }

    /** 读取联系人；无权限时返回 PERMISSION_DENIED。 */
    suspend fun loadContacts(limit: Int = 500): PhoneResult<List<Contact>> {
        if (!contacts.canReadContacts()) {
            PhoneLog.engine.w { "loadContacts(): 缺少 READ_CONTACTS 权限" }
            return PhoneResult.Failure(PhoneError.PERMISSION_DENIED, "缺少 READ_CONTACTS 权限")
        }
        val list = contacts.contacts(limit)
        PhoneLog.engine.d { "loadContacts(): 返回 ${list.size} 条" }
        return PhoneResult.Success(list)
    }

    /** 读取通话记录；无权限时返回 PERMISSION_DENIED。 */
    suspend fun loadCallLog(limit: Int = 200): PhoneResult<List<CallLogEntry>> {
        if (!contacts.canReadCallLog()) {
            PhoneLog.engine.w { "loadCallLog(): 缺少 READ_CALL_LOG 权限" }
            return PhoneResult.Failure(PhoneError.PERMISSION_DENIED, "缺少 READ_CALL_LOG 权限")
        }
        val list = contacts.callLog(limit)
        PhoneLog.engine.d { "loadCallLog(): 返回 ${list.size} 条" }
        return PhoneResult.Success(list)
    }

    /** 网关事件总入口：更新设备/通话/音频，并驱动铃声与重连。 */
    private fun onGatewayEvent(event: HfpEvent) {
        // stop/shutdown 后仍可能有已入队的事件，统一丢弃，避免状态被重新写回。
        if (!started) return
        // 每条网关事件留痕，排查“状态为何未变”时可按事件顺序比对。
        // AG 电量/信号可能频繁上报，降为 VERBOSE，避免 DEBUG 刷屏。
        if (event is HfpEvent.AgChanged) {
            PhoneLog.engine.v { "网关事件 ${event.summary()}" }
        } else {
            PhoneLog.engine.d { "网关事件 ${event.summary()}" }
        }
        when (event) {
            is HfpEvent.AdapterChanged -> {
                adapterEnabled = event.enabled
                if (event.enabled) {
                    PhoneLog.engine.i { "蓝牙开启: 重建监听并刷新设备" }
                    // 蓝牙刚打开或代理丢失时，网关会自行补拿代理。
                    if (!gateway.isReady) gateway.start()
                    pbap.start()
                    refreshDevices()
                    loadContactNames()
                    maybeAutoConnect()
                } else {
                    PhoneLog.engine.i { "蓝牙关闭: 清理设备、通话与铃声" }
                    pbap.stop()
                    serviceRestartJob?.cancel()
                    reconnectJobs.values.forEach { it.cancel() }
                    reconnectJobs.clear()
                    reconnectAttempts.clear()
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
                    PhoneLog.engine.i { "设备已解绑: ${event.address}" }
                    // 解绑后清理重连任务与上次设备记录，避免下次开机自动连接已不存在的设备。
                    reconnectJobs.remove(event.address)?.cancel()
                    reconnectAttempts.remove(event.address)
                    manualDisconnects.remove(event.address)
                    if (prefs.lastDeviceAddress == event.address) prefs.lastDeviceAddress = null
                    pbap.disconnect(event.address)
                    if (callStore.state.value.deviceId == event.address) emitAllCallsEnded()
                    callStore.clearDevice(event.address)
                    ringtone.update(callStore.state.value)
                    // 数据源可能跟随另一台仍有通话的设备，同步设备焦点。
                    syncActiveDevice()
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
                PhoneLog.engine.d {
                    "音频状态: ${event.address}, ${event.state}, wideband=${event.wideband}"
                }
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

            is HfpEvent.Error -> {
                PhoneLog.engine.w { "网关错误: ${event.message}" }
                _events.tryEmit(PhoneEvent.Error(event.message))
            }
        }
    }

    /** 是否是会议合并前的参与方（活动/保持/去电）。 */
    private fun isConferenceParticipant(call: CallInfo): Boolean =
        call.isActive || call.isHeld ||
            call.state == CallState.DIALING || call.state == CallState.ALERTING

    /** 非 HFP 终止场景（蓝牙关闭/断开/停止）主动补发结束事件。 */
    private fun emitAllCallsEnded() {
        val state = callStore.state.value
        val calls = state.calls
        if (calls.isNotEmpty()) {
            PhoneLog.engine.i { "补发通话结束: ${calls.size} 路" }
        }
        calls.forEach { _events.tryEmit(PhoneEvent.CallEnded(it)) }
        if (calls.isNotEmpty()) {
            _events.tryEmit(PhoneEvent.SessionEnded(state.primaryCall ?: calls.last()))
        }
        // 已经主动补发结束事件，同步基准快照；随后清空/重算状态时不再重复上报。
        lastPublishedState = PhoneCallState()
    }

    /** 对外通话状态变化：补发阶段/结束事件，并在无通话时自动恢复麦克风。 */
    private fun onCallStatePublished(current: PhoneCallState) {
        val previous = lastPublishedState
        lastPublishedState = current

        if (previous.uiState != current.uiState) {
            PhoneLog.engine.i { "UI 阶段: ${previous.uiState} -> ${current.uiState}" }
            _events.tryEmit(PhoneEvent.CallUiChanged(previous.uiState, current.uiState))
        }

        // 调试归并结果：只输出关键字段，号码脱敏。
        PhoneLog.engine.d {
            "通话快照: ui=${current.uiState}, active=${PhoneLog.mask(current.active?.number)}, " +
                "held=${PhoneLog.mask(current.held?.number)}, " +
                "waiting=${PhoneLog.mask(current.waiting?.number)}, " +
                "会议=${current.isConference}, audio=${current.audio}/${current.audioRoute}, " +
                "mic=${current.micMuted}"
        }

        // 只有同一台手机才能确定是“通话结束”，避免切换设备时误报。
        if (previous.deviceId == current.deviceId && previous.calls.isNotEmpty()) {
            val currentIds = current.calls.mapTo(mutableSetOf()) { it.id }
            // 合并会议时原来的活动/保持会被会议替代，不能当作挂断。
            val conferenceStarted = previous.conference == null && current.conference != null
            val removed = previous.calls
                .filter { it.id !in currentIds }
                .filterNot { conferenceStarted && isConferenceParticipant(it) }
                // 部分 AG 在会议成员变化时会换 call id，不能把旧会议当成“挂断”。
                .filterNot { it.isMultiParty && current.conference != null }
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
        PhoneLog.engine.i {
            "设备连接: $address (${registry.device(address)?.displayName ?: "<未知>"})"
        }
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
            ringtone.update(callStore.state.value)
        }
        gateway.refresh(address)
        loadContactNames()
    }

    /** 把联系人姓名回填到已有通话；失败不影响主流程。 */
    private fun loadContactNames() {
        if (!contacts.canReadContacts() || contactsLoading) return
        // 启动、蓝牙开启、设备连接会连续触发，短时间内只读一次，避免重复全表查询。
        // 0 表示尚未加载过，避免开机不足一个间隔时被误判为“刚读过”。
        val now = SystemClock.elapsedRealtime()
        if (lastContactLoadElapsed != 0L &&
            now - lastContactLoadElapsed < CONTACT_RELOAD_INTERVAL_MS
        ) {
            return
        }
        contactsLoading = true
        PhoneLog.engine.d { "开始加载联系人用于回填" }
        scope.launch {
            try {
                val list = contacts.contacts(CONTACT_LOOKUP_LIMIT)
                // 空结果可能是权限被回收或查询失败，保留已有姓名/头像，避免误清空。
                if (list.isEmpty()) {
                    PhoneLog.engine.d { "联系人回填: 未读取到联系人，保留已有映射" }
                } else {
                    callStore.updateContacts(list)
                    PhoneLog.engine.d { "联系人回填: 读取 ${list.size} 条" }
                }
                // 读取完成后刷新节流时间，避免短时间内重复全表查询。
                lastContactLoadElapsed = SystemClock.elapsedRealtime()
            } finally {
                contactsLoading = false
            }
        }
    }

    /** 设备断开：清理通话与铃声，并按配置安排重连。 */
    private fun onDeviceDisconnected(address: String) {
        PhoneLog.engine.i {
            "设备断开: $address (${registry.device(address)?.displayName ?: "<未知>"})"
        }
        pbap.disconnect(address)
        if (callStore.state.value.deviceId == address) emitAllCallsEnded()
        callStore.clearDevice(address)
        // 另一台手机可能仍在响铃，这里按最新状态重算，而不是直接停铃。
        ringtone.update(callStore.state.value)
        // 数据源优先跟随仍有通话的设备，其次才是任意已连接设备。
        syncActiveDevice()
        registry.activeDevice()?.takeIf { it.isConnected }?.let {
            callStore.selectDevice(it.address, it.displayName)
        }
        scheduleReconnect(address)
    }

    /** 自动重连：递增退避，最多 [MAX_RECONNECT_ATTEMPTS] 次；主动断开不重连。 */
    private fun scheduleReconnect(address: String) {
        if (!started) return
        if (!config.autoReconnect || address in manualDisconnects) return
        val attempt = reconnectAttempts.getOrDefault(address, 0)
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            PhoneLog.engine.w {
                "reconnect($address): 已达最大次数 $MAX_RECONNECT_ATTEMPTS，停止重连"
            }
            return
        }
        val delayMs = config.reconnectDelayMs * (attempt + 1)
        PhoneLog.engine.i { "reconnect($address): 第 ${attempt + 1} 次，${delayMs}ms 后重试" }
        reconnectAttempts[address] = attempt + 1
        reconnectJobs.remove(address)?.cancel()
        reconnectJobs[address] = scope.launch {
            delay(delayMs.milliseconds)
            if (!started || !gateway.isBluetoothEnabled()) {
                PhoneLog.engine.d { "reconnect($address): 条件不满足，放弃本次" }
                return@launch
            }
            if (registry.device(address)?.isConnected == true) {
                PhoneLog.engine.d { "reconnect($address): 已连接，无需重试" }
                return@launch
            }
            PhoneLog.engine.d { "reconnect($address): 下发连接命令" }
            gateway.connect(address)
        }
    }

    /** 系统蓝牙服务重启后，延时重建网关代理。 */
    private fun scheduleGatewayRestart() {
        if (!started) return
        PhoneLog.engine.w { "蓝牙服务断开，${GATEWAY_RESTART_DELAY_MS}ms 后重建代理" }
        _events.tryEmit(PhoneEvent.Info("蓝牙电话服务断开，正在重连"))
        serviceRestartJob?.cancel()
        serviceRestartJob = scope.launch {
            delay(GATEWAY_RESTART_DELAY_MS.milliseconds)
            if (!started || !gateway.isBluetoothEnabled()) return@launch
            PhoneLog.engine.i { "重建 HFP 网关代理" }
            gateway.stop()
            gateway.start()
            // 蓝牙服务重启可能同时回收 PBAP 代理，这里一并补获。
            pbap.start()
            refreshDevices()
        }
    }

    /** 重建设备列表，并保留历史电量/信号等状态。 */
    private fun refreshDevices() {
        adapterEnabled = gateway.isBluetoothEnabled()
        callStore.setConferenceMergeSupported(gateway.supportsConference)
        registry.replaceBonded(gateway.bondedDevices())
        registry.selectConnectedIfNeeded()
        val deviceList = registry.devices.value
        PhoneLog.engine.d {
            "刷新设备: 共 ${deviceList.size} 台，已连接 ${deviceList.count { it.isConnected }} 台"
        }
        deviceList.filter { it.isConnected }.forEach {
            gateway.setAudioRouteAllowed(it.address, true)
            pbap.connect(it.address)
        }
        updateConnectionState()
    }

    /** 启动后自动连接上次使用的设备。 */
    private fun maybeAutoConnect() {
        if (!config.autoConnectLastDevice) return
        val address = prefs.lastDeviceAddress ?: return
        // 用户主动断开后，本次进程内不再自动连接。
        if (address in manualDisconnects) {
            PhoneLog.engine.d { "autoConnect($address): 用户已主动断开，跳过" }
            return
        }
        val device = registry.device(address) ?: return
        if (device.isConnected || device.hfpState == ProfileState.CONNECTING) return
        PhoneLog.engine.i { "autoConnect($address): 自动连接上次使用的设备" }
        scope.launch {
            delay(config.reconnectDelayMs.milliseconds)
            if (!started || !gateway.isBluetoothEnabled()) return@launch
            if (registry.device(address)?.isConnected == true) return@launch
            gateway.connect(address)
        }
    }

    /** 根据蓝牙开关和设备状态计算整体连接状态。 */
    private fun updateConnectionState() {
        val devices = registry.devices.value
        val next = when {
            !adapterEnabled -> PhoneConnectionState.BLUETOOTH_OFF
            devices.any { it.isConnected } -> PhoneConnectionState.CONNECTED
            devices.any { it.hfpState == ProfileState.CONNECTING } -> PhoneConnectionState.CONNECTING
            else -> PhoneConnectionState.NO_DEVICE
        }
        if (_connectionState.value != next) {
            PhoneLog.engine.i { "连接状态: ${_connectionState.value} -> $next" }
        }
        _connectionState.value = next
    }

    /** 取当前设备；显式选中但未连接时返回 null，避免拨号发到另一台手机。 */
    private fun connectedDevice(): PhoneDevice? =
        registry.activeDevice()?.takeIf { it.isConnected }

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
        else {
            PhoneLog.engine.w { "audioToCar(): SCO 建立失败, device=${device.address}" }
            failure(PhoneError.NOT_READY, "音频切到车机失败")
        }
    }

    /** 实际切换到手机音频。 */
    private fun audioToPhone(): PhoneResult<Unit> {
        val device = activeAudioDevice()
            ?: return failure(PhoneError.NO_DEVICE, "没有已连接的手机")
        return if (gateway.disconnectAudio(device.address)) success()
        else {
            PhoneLog.engine.w { "audioToPhone(): SCO 断开失败, device=${device.address}" }
            failure(PhoneError.NOT_READY, "音频切到手机失败")
        }
    }

    /** 修改 AudioManager 静音并同步到状态；系统拒绝时返回 false。 */
    private fun changeMicMute(muted: Boolean): Boolean {
        if (callStore.state.value.micMuted == muted) return true
        val audio = audioManager ?: run {
            PhoneLog.engine.w { "changeMicMute($muted): AudioManager 不可用" }
            return false
        }
        val applied = runCatching {
            audio.setMicrophoneMute(muted)
            audio.isMicrophoneMute == muted
        }.getOrDefault(false)
        if (!applied) {
            PhoneLog.engine.w { "changeMicMute($muted): 系统拒绝修改" }
            return false
        }
        PhoneLog.audio.i { "车机麦克风静音: $muted" }
        callStore.setMicMuted(muted)
        return true
    }

    /** 构造空成功结果。 */
    private fun success(): PhoneResult<Unit> = PhoneResult.Success(Unit)

    /** 构造失败结果，message 可直接展示。 */
    private fun failure(error: PhoneError, message: String? = null): PhoneResult<Unit> =
        PhoneResult.Failure(error, message)

    private companion object {
        /** 自动重连的最大尝试次数。 */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** 蓝牙服务重启后，重建代理前的等待时间。 */
        const val GATEWAY_RESTART_DELAY_MS = 500L

        /** 联系人回填的重新读取间隔。 */
        const val CONTACT_RELOAD_INTERVAL_MS = 30_000L

        /** 联系人回填单次读取上限。 */
        const val CONTACT_LOOKUP_LIMIT = 2_000
    }
}
