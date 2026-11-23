package com.shilapi.xcertplay

import com.shilapi.xcertplay.compat.systemService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    // API18 使用清单中的 BLUETOOTH 权限, 新系统不读取受限的适配器地址
    @android.annotation.SuppressLint("MissingPermission")
    fun localAddress(context: Context): String? {
        val adapter = if (android.os.Build.VERSION.SDK_INT < 23) {
            runCatching { context.systemService(BluetoothManager::class.java, "bluetooth")?.adapter?.address }.getOrNull()
        } else null
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
