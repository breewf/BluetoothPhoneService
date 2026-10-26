package com.bluetooth.phone.core

import android.content.Context

/** 保存跨进程重启的轻量配置（当前只记录上次连接的设备）。 */
internal class PhonePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bluetooth_phone", Context.MODE_PRIVATE)

    /** 上次成功连接的设备地址，用于开机自动连接。 */
    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_DEVICE, value).apply()
        }

    private companion object {
        const val KEY_LAST_DEVICE = "last_device"
    }
}
