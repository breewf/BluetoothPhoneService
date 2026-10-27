package com.bluetooth.phone.core

import com.bluetooth.phone.api.AudioState
import com.bluetooth.phone.api.PhoneDevice
import com.bluetooth.phone.api.ProfileState
import com.bluetooth.phone.hfp.GatewayDevice
import com.bluetooth.phone.hfp.HfpAgState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 只负责“设备列表 + 每台设备的 Profile/信号状态”。
 * 所有写入都应发生在 PhoneEngine 的单线程调度器上。
 */
internal class DeviceRegistry {

    /** 内部可变设备列表。 */
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
                bondState = device.bondState,
                hfpState = device.hfpState,
                isActive = previous?.isActive == true,
                battery = previous?.battery,
                signal = previous?.signal,
                roaming = previous?.roaming,
                operatorName = previous?.operatorName,
                // 只有已连接设备才可能保留 SCO 状态。
                audioState = if (device.hfpState == ProfileState.CONNECTED) {
                    previous?.audioState ?: AudioState.DISCONNECTED
                } else {
                    AudioState.DISCONNECTED
                }
            )
        }
        ensureActive()
        publish()
        PhoneLog.device.d { "设备列表更新: ${map.size} 台" }
    }

    /** 更新 HFP 连接状态；没有可用当前设备时才接管焦点。 */
    fun updateConnection(address: String, state: ProfileState) {
        val current = map[address] ?: return
        if (current.hfpState != state) {
            PhoneLog.device.d { "HFP 状态: $address ${current.hfpState} -> $state" }
        }
        map[address] = current.copy(
            hfpState = state,
            // HFP 断开时 SCO 必然不可用，同步清掉音频状态。
            audioState = if (state == ProfileState.DISCONNECTED) {
                AudioState.DISCONNECTED
            } else {
                current.audioState
            }
        )
        // 已有可用当前设备时不抢焦点，避免第二台手机连接打断正在进行的通话。
        if (state == ProfileState.CONNECTED) {
            val active = map[_activeAddress.value]
            if (active == null || !active.isConnected) _activeAddress.value = address
        }
        ensureActive()
        publish()
    }

    /** 标记为连接中，让界面立即有反馈。 */
    fun markConnecting(address: String) {
        val current = map[address] ?: return
        PhoneLog.device.d { "标记连接中: $address" }
        map[address] = current.copy(hfpState = ProfileState.CONNECTING)
        publish()
    }

    /** 更新 AG 上报的电量/信号/运营商。 */
    fun updateAg(address: String, ag: HfpAgState) {
        val current = map[address] ?: return
        val updated = current.copy(
            battery = ag.battery ?: current.battery,
            signal = ag.signal ?: current.signal,
            roaming = ag.roaming ?: current.roaming,
            operatorName = ag.operatorName ?: current.operatorName
        )
        // 信号/电量可能频繁上报，只在内容真正变化时记录。
        if (updated == current) return
        PhoneLog.device.v {
            "AG 状态: $address battery=${updated.battery}, signal=${updated.signal}, " +
                "roaming=${updated.roaming}, operator=${updated.operatorName}"
        }
        map[address] = updated
        publish()
    }

    /** 更新通话音频状态；未连接设备忽略迟到的音频广播。 */
    fun updateAudio(address: String, state: AudioState) {
        val current = map[address] ?: return
        if (!current.isConnected || current.audioState == state) return
        PhoneLog.device.d { "音频状态: $address ${current.audioState} -> $state" }
        map[address] = current.copy(audioState = state)
        publish()
    }

    /** 指定当前设备，并同步 isActive 标记。 */
    fun select(address: String) {
        if (map[address] == null) return
        if (_activeAddress.value != address) {
            PhoneLog.device.d { "当前设备: ${_activeAddress.value} -> $address" }
        }
        _activeAddress.value = address
        syncActiveFlags()
        publish()
    }

    /** 当前设备不可用时，自动选第一台已连接设备。 */
    fun selectConnectedIfNeeded() {
        ensureActive()
        publish()
    }

    /** 蓝牙关闭时清空连接与音频状态。 */
    fun clearConnections() {
        PhoneLog.device.i { "清空全部连接状态" }
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
        if (current == null || !current.isConnected) {
            _activeAddress.value = map.values.firstOrNull { it.isConnected }?.address
        }
        syncActiveFlags()
    }

    /** 只把 isActive 标记同步到当前设备，避免整表无意义重建。 */
    private fun syncActiveFlags() {
        val active = _activeAddress.value
        map.keys.forEach { key ->
            val device = map.getValue(key)
            val flag = key == active
            if (device.isActive != flag) map[key] = device.copy(isActive = flag)
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
