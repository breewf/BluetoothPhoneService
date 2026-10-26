package com.example.bluetoothphone.core

import com.example.bluetoothphone.api.AudioState
import com.example.bluetoothphone.api.PhoneDevice
import com.example.bluetoothphone.api.ProfileState
import com.example.bluetoothphone.hfp.GatewayDevice
import com.example.bluetoothphone.hfp.HfpAgState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 只负责“设备列表 + 每台设备的 Profile/信号状态”。
 * 所有写入都应发生在 PhoneEngine 的单线程调度器上。
 */
internal class DeviceRegistry {

    private val _devices = MutableStateFlow<List<PhoneDevice>>(emptyList())

    /** 对外发布的设备列表（已排序）。 */
    val devices: StateFlow<List<PhoneDevice>> = _devices.asStateFlow()

    /** 当前选中设备的 MAC 地址。 */
    private val _activeAddress = MutableStateFlow<String?>(null)

    /** 当前选中设备地址，供引擎组合 activeDevice。 */
    val activeAddress: StateFlow<String?> = _activeAddress.asStateFlow()

    /** 按地址保存设备，保持系统返回顺序。 */
    private val map = LinkedHashMap<String, PhoneDevice>()

    /** 用已配对列表重建数据，并保留历史信号/电量/音频状态。 */
    fun replaceBonded(devices: List<GatewayDevice>) {
        val old = map.toMap()
        map.clear()
        devices.forEach { device ->
            val previous = old[device.address]
            map[device.address] = PhoneDevice(
                address = device.address,
                name = device.name,
                alias = previous?.alias ?: device.name,
                bondState = device.bondState,
                hfpState = device.hfpState,
                isActive = previous?.isActive == true,
                battery = previous?.battery,
                signal = previous?.signal,
                roaming = previous?.roaming,
                operatorName = previous?.operatorName,
                audioState = previous?.audioState ?: AudioState.DISCONNECTED
            )
        }
        ensureActive()
        publish()
    }

    /** 更新 HFP 连接状态；连接成功时自动设为当前设备。 */
    fun updateConnection(address: String, state: ProfileState) {
        val current = map[address] ?: return
        map[address] = current.copy(hfpState = state)
        // 已有可用当前设备时不抢焦点，避免第二台手机连接打断正在进行的通话。
        if (state == ProfileState.CONNECTED) {
            val active = map[_activeAddress.value]
            if (active == null || !active.isConnected) select(address)
        }
        if (state == ProfileState.DISCONNECTED && _activeAddress.value == address) {
            _activeAddress.value = map.values.firstOrNull { it.isConnected }?.address
        }
        ensureActive()
        publish()
    }

    /** 标记为连接中，让界面立即有反馈。 */
    fun markConnecting(address: String) {
        val current = map[address] ?: return
        map[address] = current.copy(hfpState = ProfileState.CONNECTING)
        publish()
    }

    /** 更新 AG 上报的电量/信号/运营商。 */
    fun updateAg(address: String, ag: HfpAgState) {
        val current = map[address] ?: return
        map[address] = current.copy(
            battery = ag.battery ?: current.battery,
            signal = ag.signal ?: current.signal,
            roaming = ag.roaming ?: current.roaming,
            operatorName = ag.operatorName ?: current.operatorName
        )
        publish()
    }

    /** 更新通话音频状态。 */
    fun updateAudio(address: String, state: AudioState) {
        val current = map[address] ?: return
        map[address] = current.copy(audioState = state)
        publish()
    }

    /** 指定当前设备，并同步 isActive 标记。 */
    fun select(address: String) {
        if (map[address] == null) return
        _activeAddress.value = address
        map.keys.forEach { key ->
            map[key] = map.getValue(key).copy(isActive = key == address)
        }
        publish()
    }

    /** 当前设备不可用时，自动选第一台已连接设备。 */
    fun selectConnectedIfNeeded() {
        if (map[_activeAddress.value]?.isConnected == true) return
        _activeAddress.value = map.values.firstOrNull { it.isConnected }?.address
        val active = _activeAddress.value
        map.keys.forEach { key ->
            map[key] = map.getValue(key).copy(isActive = key == active)
        }
        publish()
    }

    /** 蓝牙关闭时清空连接与音频状态。 */
    fun clearConnections() {
        map.keys.forEach { key ->
            map[key] = map.getValue(key).copy(
                hfpState = ProfileState.DISCONNECTED,
                audioState = AudioState.DISCONNECTED,
                isActive = false
            )
        }
        _activeAddress.value = null
        publish()
    }

    /** 按地址查询设备。 */
    fun device(address: String): PhoneDevice? = map[address]

    /** 取当前设备。 */
    fun activeDevice(): PhoneDevice? = map[_activeAddress.value]

    /** 当前设备不可用时重新选择，并同步 isActive。 */
    private fun ensureActive() {
        val current = map[_activeAddress.value]
        if (current != null && current.isConnected) return

        _activeAddress.value = map.values.firstOrNull { it.isConnected }?.address
        val active = _activeAddress.value
        map.keys.forEach { key ->
            map[key] = map.getValue(key).copy(isActive = key == active)
        }
    }

    /** 发布排序后的设备列表：当前设备、已连接设备优先。 */
    private fun publish() {
        _devices.value = map.values.sortedWith(
            compareByDescending<PhoneDevice> { it.isActive }
                .thenByDescending { it.isConnected }
                .thenBy { it.displayName }
        )
    }
}
