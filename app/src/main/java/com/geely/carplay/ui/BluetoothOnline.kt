package com.geely.carplay.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.transport.AnwBluetoothBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机蓝牙在线判断
 *
 * 只看具体设备的连接状态：设备 isConnected、HFP/A2DP 已连接设备列表、厂商 ANW 已连接槽位
 */
object BluetoothOnline {
    private const val PROFILE_BIND_TIMEOUT_MILLIS = 400L

    fun isConnected(context: Context, address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return connectedAddresses(context).any { it.equals(address, ignoreCase = true) }
    }

    /** 返回当前已连接设备的蓝牙地址集合 需要在后台线程调用 */
    fun connectedAddresses(context: Context): Set<String> {
        val result = linkedSetOf<String>()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null && adapter.isEnabled) {
            adapter.bondedDevices.orEmpty().forEach { device ->
                if (deviceConnected(device)) result += device.address
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).forEach { profile ->
                    val proxy = bindProfile(context, adapter, profile) ?: return@forEach
                    runCatching {
                        proxy.connectedDevices.orEmpty().forEach { result += it.address }
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }
            }
        }
        if (AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(context)) {
            runCatching { result += AnwBluetoothBackend(context).connectedAddresses() }
        }
        return result
    }

    private fun deviceConnected(device: android.bluetooth.BluetoothDevice): Boolean = runCatching {
        android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected")
            .invoke(device) as? Boolean == true
    }.getOrDefault(false)

    private fun bindProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
    ): BluetoothProfile? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<BluetoothProfile>(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, service: BluetoothProfile?) {
                holder[0] = service
                latch.countDown()
            }

            override fun onServiceDisconnected(profileId: Int) {}
        }
        if (!runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)) {
            return null
        }
        return if (latch.await(PROFILE_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) holder[0] else null
    }
}
