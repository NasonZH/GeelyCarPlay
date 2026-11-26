package com.geely.carplay.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.transport.AnwBluetoothBackend
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 当前蓝牙已连接的设备 */
data class ConnectedDevice(
    val address: String,
    val name: String,
    val isCurrent: Boolean,
)

/**
 * 手机蓝牙在线判断
 *
 * 只看具体设备的连接状态：设备 isConnected、HFP/A2DP 已连接设备列表、厂商 ANW 已连接槽位
 */
object BluetoothOnline {
    private const val PROFILE_BIND_TIMEOUT_MILLIS = 400L
    private const val ANW_RETRY_MILLIS = 10_000L
    private const val IPHONE_NAME = "iPhone"

    /** ANW 服务不可用时暂停重试 避免每次轮询都等待绑定超时 */
    @Volatile private var anwRetryAt = 0L

    /** 已知地址优先查设备自身连接状态 未连接时才回退到 profile 列表 */
    fun isConnected(context: Context, address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (bondedDeviceConnected(context, address)) return true
        return connectedAddresses(context).any { it.equals(address, ignoreCase = true) }
    }

    /**
     * 当前已连接的设备 名称优先取系统别名 其次取配对名称和地址
     * 只保留手机类设备 识别不到手机类时回退显示全部 避免完全选不到设备
     */
    fun connectedDevices(context: Context): List<ConnectedDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val currentAddress = if (CarPlaySessionManager.isConnected && AirPlayPersistence.loadWirelessEnabled(context)) {
            DiPlayPreferences.phoneAddress(context)
        } else null
        val devices = connectedAddresses(context)
            .map { address ->
                val device = bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }
                val name = device?.let(::deviceName) ?: address
                ConnectedDevice(address, name, address.equals(currentAddress, ignoreCase = true))
            }
            .distinctBy { it.address.uppercase(Locale.US) }
            .sortedWith(compareByDescending<ConnectedDevice> { it.isCurrent }
                .thenBy { it.name.lowercase(Locale.CHINA) })

        val phones = devices.filter { device ->
            val bondedDevice = bonded.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
            bondedDevice?.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE ||
                device.name.contains(IPHONE_NAME, ignoreCase = true)
        }
        return if (phones.isEmpty()) devices else phones
    }

    private fun bondedDeviceConnected(context: Context, address: String): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return false
        val device = adapter.bondedDevices.orEmpty()
            .firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return false
        return deviceConnected(device)
    }

    /** 返回当前已连接设备的蓝牙地址集合 需要在后台线程调用 */
    fun connectedAddresses(context: Context): Set<String> {
        val result = linkedSetOf<String>()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter != null && adapter.isEnabled) {
            adapter.bondedDevices.orEmpty().forEach { device ->
                if (deviceConnected(device)) result += device.address
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
                    if (readProfileAddresses(manager, profile, result)) continue
                    val proxy = bindProfile(context, adapter, profile) ?: continue
                    try {
                        runCatching { proxy.connectedDevices.orEmpty().forEach { result += it.address } }
                    } finally {
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                    }
                }
            }
        }
        if (AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(context)) {
            readAnwAddresses(context, result)
        }
        return result
    }

    /** 优先使用系统已连接设备列表 避免每次绑定 profile 代理 */
    private fun readProfileAddresses(manager: BluetoothManager, profile: Int, result: MutableSet<String>): Boolean {
        val devices = runCatching { manager.getConnectedDevices(profile) }.getOrNull() ?: return false
        devices.forEach { result += it.address }
        return true
    }

    private fun readAnwAddresses(context: Context, result: MutableSet<String>) {
        if (SystemClock.elapsedRealtime() < anwRetryAt) return
        val failure = runCatching {
            result += AnwBluetoothBackend(context).connectedAddresses()
        }.exceptionOrNull()
        if (failure != null) anwRetryAt = SystemClock.elapsedRealtime() + ANW_RETRY_MILLIS
    }

    private fun deviceName(device: BluetoothDevice): String {
        // 旧版 Android 的别名接口未公开 读取失败时保留原名称
        val alias = runCatching {
            BluetoothDevice::class.java.getMethod("getAlias").invoke(device) as? String
        }.getOrNull()
        return alias?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
            ?: device.address
    }

    private fun deviceConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected")
            .invoke(device) as? Boolean == true
    }.getOrDefault(false)

    private fun bindProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
    ): BluetoothProfile? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<BluetoothProfile>(1)
        var accepting = true
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, service: BluetoothProfile?) {
                val keep = synchronized(holder) {
                    if (accepting) holder[0] = service
                    accepting
                }
                if (!keep && service != null) runCatching { adapter.closeProfileProxy(profile, service) }
                latch.countDown()
            }

            override fun onServiceDisconnected(profileId: Int) {}
        }
        if (!runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)) {
            return null
        }
        try {
            latch.await(PROFILE_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            synchronized(holder) {
                accepting = false
                holder[0]?.let { runCatching { adapter.closeProfileProxy(profile, it) } }
                holder[0] = null
            }
            throw interrupted
        } finally {
            synchronized(holder) { accepting = false }
        }
        return synchronized(holder) { holder[0] }
    }
}
