package com.example.bluetoothphoneservice

import android.app.Application
import android.content.pm.ApplicationInfo
import com.bluetooth.phone.BluetoothPhone
import com.bluetooth.phone.BluetoothPhoneConfig
import com.bluetooth.phone.api.PhoneLogLevel

/** 应用入口：根据系统能力决定使用真实 HFP 还是 Mock。 */
class BluetoothPhoneApp : Application() {

    /** 初始化中间件单例；普通手机/模拟器自动进入 Mock。 */
    override fun onCreate() {
        super.onCreate()
        // 普通手机/模拟器没有系统蓝牙权限，自动进入 Mock；车机系统应用走真实 HFP。
        val canUseRealHfp = BluetoothPhone.isSystemApiAvailable() &&
            BluetoothPhone.hasPrivilegedPermission(this)
        // 调试包输出详细日志，release 只保留警告，避免刷屏。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        BluetoothPhone.init(
            context = this,
            config = BluetoothPhoneConfig(
                useMockGateway = !canUseRealHfp,
                logLevel = if (debuggable) PhoneLogLevel.DEBUG else PhoneLogLevel.WARN
            )
        )
    }

    /** 进程结束前释放监听（真机通常不会回调）。 */
    override fun onTerminate() {
        BluetoothPhone.shutdown()
        super.onTerminate()
    }
}
