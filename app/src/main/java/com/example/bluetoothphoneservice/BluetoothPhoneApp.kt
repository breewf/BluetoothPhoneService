package com.example.bluetoothphoneservice

import android.app.Application
import com.bluetooth.phone.BluetoothPhone
import com.bluetooth.phone.BluetoothPhoneConfig

/** 应用入口：根据系统能力决定使用真实 HFP 还是 Mock。 */
class BluetoothPhoneApp : Application() {

    /** 初始化中间件单例；普通手机/模拟器自动进入 Mock。 */
    override fun onCreate() {
        super.onCreate()
        // 普通手机/模拟器没有系统蓝牙权限，自动进入 Mock；车机系统应用走真实 HFP。
        val canUseRealHfp = BluetoothPhone.isSystemApiAvailable() &&
            BluetoothPhone.hasPrivilegedPermission(this)
        BluetoothPhone.init(
            context = this,
            config = BluetoothPhoneConfig(useMockGateway = !canUseRealHfp)
        )
    }

    /** 进程结束前释放监听（真机通常不会回调）。 */
    override fun onTerminate() {
        BluetoothPhone.shutdown()
        super.onTerminate()
    }
}
