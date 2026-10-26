package com.example.bluetoothphone.hfp

import com.example.bluetoothphone.api.AudioState
import com.example.bluetoothphone.api.BondState
import com.example.bluetoothphone.api.CallState
import com.example.bluetoothphone.api.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 无真机时使用的 HFP 模拟器。
 *
 * 支持连接、来电、去电、保持/切换、接听等待、合并会议和音频切换，
 * 用于应用层联调与 UI 回归；不要在生产配置中启用。
 */
internal class FakeHfpGateway : HfpGateway, DebugCallSimulator {

    /** 模拟设备，状态可变以模拟连接过程。 */
    private data class FakeDevice(
        val address: String,
        val name: String,
        var bondState: BondState,
        var state: ProfileState
    )

    /** 模拟通话，多路组合即可构造三方场景。 */
    private data class FakeCall(
        val id: String,
        var state: CallState,
        val number: String?,
        var isMultiParty: Boolean,
        val isOutgoing: Boolean,
        val isInBandRing: Boolean,
        val createdAtElapsed: Long
    )

    /** 模拟事件流，结构与真实网关一致。 */
    private val _events = MutableSharedFlow<HfpEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<HfpEvent> = _events.asSharedFlow()

    /** 保护模拟状态的锁；回调可能来自协程线程。 */
    private val lock = Any()

    /** 递增的通话 id。 */
    private val nextId = AtomicLong(1)

    private var scope = newScope()
    private var started = false

    /** 预置两台已配对手机，便于演示多设备切换。 */
    private val devices = linkedMapOf(
        "AA:BB:CC:DD:EE:01" to FakeDevice(
            "AA:BB:CC:DD:EE:01",
            "Demo Phone",
            BondState.BONDED,
            ProfileState.DISCONNECTED
        ),
        "AA:BB:CC:DD:EE:02" to FakeDevice(
            "AA:BB:CC:DD:EE:02",
            "Second Phone",
            BondState.BONDED,
            ProfileState.DISCONNECTED
        )
    )
    private val calls = mutableMapOf<String, MutableList<FakeCall>>()
    private val audio = mutableMapOf<String, AudioState>()
    private val ag = mutableMapOf<String, HfpAgState>()

    /** 模拟网关无代理，始终就绪。 */
    override val isReady: Boolean get() = true

    /** 模拟网关支持会议合并，便于演示三方通话。 */
    override val supportsConference: Boolean get() = true

    /** 启动模拟器并广播蓝牙已开启。 */
    override fun start() {
        synchronized(lock) {
            if (started) return
            if (scope.coroutineContext[Job]?.isActive != true) scope = newScope()
            started = true
            _events.tryEmit(HfpEvent.AdapterChanged(true))
        }
    }

    /** 停止模拟器并取消所有延迟回调。 */
    override fun stop() {
        synchronized(lock) {
            started = false
            scope.cancel()
        }
    }

    /** 模拟蓝牙始终可用。 */
    override fun isBluetoothEnabled(): Boolean = true

    /** 返回已配对的模拟设备。 */
    override fun bondedDevices(): List<GatewayDevice> = synchronized(lock) {
        devices.values
            .filter { it.bondState == BondState.BONDED }
            .map { GatewayDevice(it.address, it.name, it.bondState, it.state) }
    }

    /** 查询模拟设备的连接状态。 */
    override fun connectionState(address: String): ProfileState = synchronized(lock) {
        devices[address]?.state ?: ProfileState.DISCONNECTED
    }

    /** 补发通话与 AG 状态，用于界面手动刷新。 */
    override fun refresh(address: String) {
        synchronized(lock) {
            emitCalls(address)
            emitAg(address)
        }
    }

    /** 模拟配对成功。 */
    override fun createBond(address: String): Boolean = synchronized(lock) {
        val device = devices[address] ?: return false
        device.bondState = BondState.BONDED
        _events.tryEmit(HfpEvent.BondChanged(address))
        true
    }

    /** 模拟解除配对并清理通话。 */
    override fun removeBond(address: String): Boolean = synchronized(lock) {
        val device = devices[address] ?: return false
        device.bondState = BondState.NONE
        device.state = ProfileState.DISCONNECTED
        calls.remove(address)
        _events.tryEmit(HfpEvent.BondChanged(address))
        _events.tryEmit(HfpEvent.ConnectionChanged(address, ProfileState.DISCONNECTED))
        true
    }

    /** 模拟 350ms 连接过程，并填充信号/电量。 */
    override fun connect(address: String): Boolean {
        synchronized(lock) {
            val device = devices[address] ?: return false
            if (device.state == ProfileState.CONNECTED) return true
            device.state = ProfileState.CONNECTING
            _events.tryEmit(HfpEvent.ConnectionChanged(address, ProfileState.CONNECTING))
        }
        scope.launch {
            delay(350)
            synchronized(lock) {
                if (!started) return@synchronized
                val device = devices[address] ?: return@synchronized
                if (device.state != ProfileState.CONNECTING) return@synchronized
                device.state = ProfileState.CONNECTED
                audio[address] = AudioState.DISCONNECTED
                ag[address] = HfpAgState(
                    battery = 82,
                    signal = 4,
                    roaming = false,
                    operatorName = "Demo Carrier",
                    inBandRing = false
                )
                _events.tryEmit(HfpEvent.ConnectionChanged(address, ProfileState.CONNECTED))
                emitCalls(address)
                emitAg(address)
                _events.tryEmit(HfpEvent.AudioChanged(address, AudioState.DISCONNECTED, false))
            }
        }
        return true
    }

    /** 模拟断开并清空该设备通话。 */
    override fun disconnect(address: String): Boolean {
        synchronized(lock) {
            val device = devices[address] ?: return false
            device.state = ProfileState.DISCONNECTED
            calls.remove(address)
            audio[address] = AudioState.DISCONNECTED
            _events.tryEmit(HfpEvent.ConnectionChanged(address, ProfileState.DISCONNECTED))
            _events.tryEmit(HfpEvent.CallsChanged(address, emptyList()))
            _events.tryEmit(HfpEvent.AudioChanged(address, AudioState.DISCONNECTED, false))
            return true
        }
    }

    /** 模拟拨号：DIALING → ALERTING → ACTIVE。 */
    override fun dial(address: String, number: String): Boolean {
        if (number.isBlank()) return false
        synchronized(lock) {
            val device = devices[address] ?: return false
            if (device.state != ProfileState.CONNECTED) return false
            val list = calls.getOrPut(address) { mutableListOf() }
            if (list.any { it.state == CallState.INCOMING || it.state == CallState.WAITING }) return false

            list.firstOrNull { it.state == CallState.ACTIVE }?.state = CallState.HELD
            val call = FakeCall(
                id = nextId.getAndIncrement().toString(),
                state = CallState.DIALING,
                number = number,
                isMultiParty = false,
                isOutgoing = true,
                isInBandRing = false,
                createdAtElapsed = android.os.SystemClock.elapsedRealtime()
            )
            list.add(call)
            emitCalls(address)

            scope.launch {
                delay(500)
                updateCall(address, call.id) { it.state = CallState.ALERTING }
                delay(700)
                updateCall(address, call.id) { it.state = CallState.ACTIVE }
            }
            return true
        }
    }

    /** 模拟接听：按策略保持或挂断当前通话。 */
    override fun acceptCall(address: String, policy: AnswerPolicy): Boolean {
        synchronized(lock) {
            val list = calls[address] ?: return false
            val target = list.firstOrNull {
                it.state == CallState.INCOMING || it.state == CallState.WAITING
            } ?: return false
            val active = list.firstOrNull { it.state == CallState.ACTIVE }

            when (policy) {
                AnswerPolicy.HOLD_ACTIVE -> active?.state = CallState.HELD
                AnswerPolicy.END_ACTIVE -> active?.let { list.remove(it) }
                AnswerPolicy.NORMAL -> active?.state = CallState.HELD
            }
            target.state = CallState.ACTIVE
            emitCalls(address)
            return true
        }
    }

    /** 模拟拒接所有响铃中的来电。 */
    override fun rejectCall(address: String): Boolean {
        synchronized(lock) {
            val list = calls[address] ?: return false
            val removed = list.removeAll {
                it.state == CallState.INCOMING || it.state == CallState.WAITING
            }
            if (removed) emitCalls(address)
            return removed
        }
    }

    /** 模拟挂断，并在需要时自动恢复保持中的通话。 */
    override fun hangup(address: String, callId: String): Boolean {
        synchronized(lock) {
            val list = calls[address] ?: return false
            val removed = list.firstOrNull { it.id == callId } ?: return false
            list.remove(removed)
            if (removed.state == CallState.ACTIVE) {
                val next = list.firstOrNull { it.state == CallState.HELD }
                    ?: list.firstOrNull { it.state == CallState.WAITING }
                next?.state = CallState.ACTIVE
            }
            emitCalls(address)
            return true
        }
    }

    /** 模拟 CHLD=2：保持、恢复或互换。 */
    override fun hold(address: String): Boolean {
        synchronized(lock) {
            val list = calls[address] ?: return false
            val active = list.firstOrNull { it.state == CallState.ACTIVE }
            val held = list.firstOrNull { it.state == CallState.HELD }
            when {
                active != null && held != null -> {
                    active.state = CallState.HELD
                    held.state = CallState.ACTIVE
                }
                active != null -> active.state = CallState.HELD
                held != null -> held.state = CallState.ACTIVE
                else -> return false
            }
            emitCalls(address)
            return true
        }
    }

    /** 通话中存在活动通话时，DTMF 视为成功。 */
    override fun sendDtmf(address: String, code: Char): Boolean = synchronized(lock) {
        calls[address]?.any { it.state == CallState.ACTIVE } == true
    }

    /** 模拟音频建立，200ms 后变为已连接（宽带）。 */
    override fun connectAudio(address: String): Boolean {
        synchronized(lock) {
            val device = devices[address] ?: return false
            if (device.state != ProfileState.CONNECTED) return false
            _events.tryEmit(HfpEvent.AudioChanged(address, AudioState.CONNECTING, false))
        }
        scope.launch {
            delay(200)
            synchronized(lock) {
                if (!started) return@synchronized
                audio[address] = AudioState.CONNECTED
                _events.tryEmit(HfpEvent.AudioChanged(address, AudioState.CONNECTED, true))
            }
        }
        return true
    }

    /** 模拟音频断开。 */
    override fun disconnectAudio(address: String): Boolean {
        synchronized(lock) {
            val device = devices[address] ?: return false
            if (device.state != ProfileState.CONNECTED) return false
            audio[address] = AudioState.DISCONNECTED
            _events.tryEmit(HfpEvent.AudioChanged(address, AudioState.DISCONNECTED, false))
            return true
        }
    }

    /** 模拟网关不限制路由，直接返回成功。 */
    override fun setAudioRouteAllowed(address: String, allowed: Boolean): Boolean = true

    /** 模拟 CHLD=3：活动通话标记为会议，移除保持通话。 */
    override fun conference(address: String): Boolean {
        synchronized(lock) {
            val list = calls[address] ?: return false
            val active = list.firstOrNull { it.state == CallState.ACTIVE } ?: return false
            val held = list.firstOrNull { it.state == CallState.HELD } ?: return false
            active.isMultiParty = true
            list.remove(held)
            emitCalls(address)
            return true
        }
    }

    /** 通话中存在活动通话时，语音识别视为可用。 */
    override fun voiceRecognition(address: String, start: Boolean): Boolean = synchronized(lock) {
        calls[address]?.any { it.state == CallState.ACTIVE } == true
    }

    /** 构造来电：有活动通话时生成 WAITING，否则生成 INCOMING。 */
    override fun simulateIncomingCall(number: String) {
        synchronized(lock) {
            val address = devices.values.firstOrNull {
                it.state == ProfileState.CONNECTED
            }?.address ?: return
            val list = calls.getOrPut(address) { mutableListOf() }
            val state = if (list.any { it.state == CallState.ACTIVE }) {
                CallState.WAITING
            } else {
                CallState.INCOMING
            }
            list.add(
                FakeCall(
                    id = nextId.getAndIncrement().toString(),
                    state = state,
                    number = number,
                    isMultiParty = false,
                    isOutgoing = false,
                    isInBandRing = false,
                    createdAtElapsed = android.os.SystemClock.elapsedRealtime()
                )
            )
            emitCalls(address)
        }
    }

    /** 延迟修改通话状态；已终止或已停止时忽略。 */
    private fun updateCall(address: String, callId: String, block: (FakeCall) -> Unit) {
        synchronized(lock) {
            if (!started) return
            val call = calls[address]?.firstOrNull { it.id == callId } ?: return
            if (call.state == CallState.TERMINATED) return
            block(call)
            emitCalls(address)
        }
    }

    /** 向外发送通话列表快照。 */
    private fun emitCalls(address: String) {
        val snapshot = calls[address].orEmpty().map {
            HfpCall(
                id = it.id,
                state = it.state,
                number = it.number,
                isMultiParty = it.isMultiParty,
                isOutgoing = it.isOutgoing,
                isInBandRing = it.isInBandRing,
                createdAtElapsed = it.createdAtElapsed
            )
        }
        _events.tryEmit(HfpEvent.CallsChanged(address, snapshot))
    }

    /** 向外发送 AG 状态。 */
    private fun emitAg(address: String) {
        ag[address]?.let { _events.tryEmit(HfpEvent.AgChanged(address, it)) }
    }

    /** 创建独立协程作用域，支持 stop 后重新 start。 */
    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
