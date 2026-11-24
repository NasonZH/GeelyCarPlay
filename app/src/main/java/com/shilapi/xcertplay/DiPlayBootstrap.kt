package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val privateData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.noBackupFilesDir
        } else {
            // Android 4.3 has no separate no-backup directory; the app-private files dir is
            // protected by the manifest's allowBackup=false setting.
            context.filesDir
        }
        val target = File(privateData, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(privateData, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    try {
                        context.assets.open("offline-mfi/$name").use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (error: java.io.FileNotFoundException) {
                        throw IllegalStateException("缺少内置 MFi 认证资产, 请确认 assets/offline-mfi 下的文件完整后重新构建", error)
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        ready = true
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private const val KEY_PHONE_HISTORY = "phone_history"
    private const val PHONE_HISTORY_LIMIT = 5

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "尚未选择"
    fun phoneUsesGeelyBluetooth(context: Context): Boolean = prefs(context).getBoolean("phone_uses_geely_h52_bluetooth", false)
    fun phoneSelectedForTransport(context: Context, geelyBluetooth: Boolean): Boolean =
        phoneAddress(context) != null && phoneUsesGeelyBluetooth(context) == geelyBluetooth
    fun savePhone(context: Context, address: String, name: String, geelyBluetooth: Boolean = false) {
        prefs(context).edit()
            .putString("phone_address", address)
            .putString("phone_name", name)
            .putBoolean("phone_uses_geely_h52_bluetooth", geelyBluetooth)
            .apply()
    }

    /** 连接成功过的设备 最近连接时间倒序 最多 5 台 */
    fun phoneHistory(context: Context): List<PhoneRecord> {
        val raw = prefs(context).getString(KEY_PHONE_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            val records = mutableListOf<PhoneRecord>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val address = item.optString("address")
                if (address.isEmpty()) continue
                records += PhoneRecord(
                    address = address,
                    name = item.optString("name", address),
                    lastConnectedAt = item.optLong("last_connected_at"),
                )
            }
            records
        }.getOrDefault(emptyList())
    }

    fun recordConnectedPhone(context: Context, address: String, name: String) {
        val records = phoneHistory(context)
            .filterNot { it.address.equals(address, ignoreCase = true) }
            .toMutableList()
        records.add(0, PhoneRecord(address, name, System.currentTimeMillis()))
        val array = org.json.JSONArray()
        for (record in records.take(PHONE_HISTORY_LIMIT)) {
            array.put(org.json.JSONObject()
                .put("address", record.address)
                .put("name", record.name)
                .put("last_connected_at", record.lastConnectedAt))
        }
        prefs(context).edit().putString(KEY_PHONE_HISTORY, array.toString()).apply()
    }

    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}

/** 连接成功过的设备记录 */
internal data class PhoneRecord(
    val address: String,
    val name: String,
    val lastConnectedAt: Long,
)
