package com.bluetooth.phone.hfp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.bluetooth.phone.core.PhoneLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * PBAP = Phone Book Access Profile，即「电话本访问协议」，蓝牙 SIG 定义的标准 Profile 之一，用于在车机与手机之间同步通讯录和通话记录。
 * PBAP Client 只负责把手机电话本/通话记录同步到系统 ContactsProvider。
 * AOSP 没有公开“下载电话本”的 API，数据读取统一走 ContactRepository。
 */
internal interface PbapGateway {
    /** 获取 PBAP Client 代理。 */
    fun start()

    /** 释放 PBAP Client 代理。 */
    fun stop()

    /** 请求同步指定手机的电话本。 */
    fun connect(address: String): Boolean

    /** 停止同步指定手机的电话本。 */
    fun disconnect(address: String)
}

/** 模拟实现：没有系统 PBAP，直接返回成功。 */
internal class FakePbapGateway : PbapGateway {
    /** 无需获取代理。 */
    override fun start() = Unit

    /** 无需释放代理。 */
    override fun stop() = Unit

    /** 模拟同步成功。 */
    override fun connect(address: String): Boolean = true

    /** 模拟停止同步。 */
    override fun disconnect(address: String) = Unit
}

/**
 * Android 14 的 BluetoothPbapClient 同样是 @SystemApi。
 * 这里只做 Profile 连接/断开，真正的 OBEX 下载由系统 Bluetooth app 完成。
 */
@SuppressLint("MissingPermission")
internal class SystemPbapGateway(private val context: Context) : PbapGateway {

    private companion object {
        /** BluetoothProfile.PBAP_CLIENT。 */
        const val PROFILE_PBAP_CLIENT = 17
    }

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val clientClass: Class<*>? = runCatching {
        Class.forName("android.bluetooth.BluetoothPbapClient")
    }.getOrNull()

    /** PBAP Client 代理；ServiceListener 在主线程写入，引擎线程读取。 */
    @Volatile
    private var client: Any? = null

    @Volatile
    private var proxyRequested = false

    /** 等待代理就绪后需要连接的设备。 */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 已经发起过连接的设备，避免重复 connect。 */
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    /** 反射方法缓存，只缓存找到的方法，避免 ConcurrentHashMap 存 null。 */
    private val methodCache = ConcurrentHashMap<String, Method>()

    /** PBAP Client 代理回调。 */
    private val listener = object : BluetoothProfile.ServiceListener {
        /** 代理就绪后补发等待中的连接请求。 */
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != PROFILE_PBAP_CLIENT) return
            client = proxy
            PhoneLog.pbap.d { "PBAP 代理就绪，待连接 ${pending.size} 台" }
            pending.toList().forEach {
                attempted += it
                connectNow(it)
            }
        }

        /** 代理断开时清空引用，允许重新申请。 */
        override fun onServiceDisconnected(profile: Int) {
            if (profile != PROFILE_PBAP_CLIENT) return
            client = null
            // 不重置会在系统回收代理后永久无法重新获取，PBAP 同步随之失效。
            proxyRequested = false
            PhoneLog.pbap.w { "PBAP 代理断开，等待重新申请" }
        }
    }

    /** 获取 PBAP Client 代理；重复调用不会重复请求。 */
    override fun start() {
        if (proxyRequested) return
        PhoneLog.pbap.d { "请求 PBAP Client 代理" }
        proxyRequested = runCatching {
            adapter?.getProfileProxy(context, listener, PROFILE_PBAP_CLIENT) ?: false
        }.getOrDefault(false)
    }

    /** 关闭代理并清理待连接队列。 */
    override fun stop() {
        proxyRequested = false
        PhoneLog.pbap.d { "停止 PBAP 同步" }
        val proxy = client as? BluetoothProfile
        if (proxy != null) {
            runCatching { adapter?.closeProfileProxy(PROFILE_PBAP_CLIENT, proxy) }
        }
        client = null
        pending.clear()
        attempted.clear()
    }

    /** 记录待连接设备；代理未就绪时等 onServiceConnected 再执行。 */
    override fun connect(address: String): Boolean {
        pending += address
        if (attempted.add(address)) {
            PhoneLog.pbap.d { "PBAP 触发同步: $address" }
            connectNow(address)
        }
        return true
    }

    /** 断开指定设备的 PBAP 同步。 */
    override fun disconnect(address: String) {
        PhoneLog.pbap.d { "PBAP 断开: $address" }
        pending -= address
        attempted -= address
        val device = remote(address) ?: return
        invoke("disconnect", device)
    }

    /** 代理就绪后真正发起 connect。 */
    private fun connectNow(address: String) {
        if (client == null) return
        val device = remote(address) ?: return
        invoke("connect", device)
    }

    /** 按 MAC 地址获取远程设备。 */
    private fun remote(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /** 反射调用 BluetoothPbapClient 的 connect/disconnect。 */
    private fun invoke(name: String, vararg args: Any?): Any? {
        val target = client ?: return null
        val method = method(name, args.size) ?: run {
            PhoneLog.pbap.d { "PBAP 方法不存在: $name/${args.size}" }
            return null
        }
        return runCatching { method.invoke(target, *args) }
            .onFailure { PhoneLog.pbap.w(it) { "PBAP $name 调用失败" } }
            .getOrNull()
    }

    /** 按名称+参数个数查找并缓存方法。 */
    private fun method(name: String, argCount: Int): Method? {
        val key = "$name/$argCount"
        methodCache[key]?.let { return it }
        val found = clientClass?.methods?.firstOrNull {
            it.name == name && it.parameterCount == argCount
        } ?: return null
        methodCache[key] = found
        return found
    }
}
