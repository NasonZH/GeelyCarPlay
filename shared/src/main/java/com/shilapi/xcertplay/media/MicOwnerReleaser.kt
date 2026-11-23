// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.media

import android.util.Log
import java.io.File

/**
 * Frees the cabin microphone from the vehicle voice client while a CarPlay call is active.
 *
 * The GKUI voice client is a *persistent* process: `am force-stop` is ignored and a bare `kill -9`
 * only opens a ~2 s window before it is respawned. Disabling the package stops that respawn loop, so
 * the sequence is: disable, kill the running process, then open AudioRecord immediately — whoever
 * opens the input device first keeps it. The package is re-enabled when the call ends and on the
 * next app start, so a crash cannot leave the voice assistant permanently disabled.
 */
object MicOwnerReleaser {
    private const val TAG = "xcertplay-usb"
    private val owned = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Set from the call-mode preference: only the "preempt" mode may touch the voice client. */
    @Volatile var enabled: Boolean = false

    /** `su` is present and grants root; probed once and cached. */
    val rootAvailable: Boolean by lazy {
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0 }
            .getOrDefault(false)
    }

    /** Preempts the voice client only when the user selected the microphone-preemption call mode. */
    fun preemptIfEnabled(packageName: String): Boolean {
        if (!enabled) return false
        return preempt(packageName)
    }
    /** Disables the voice client and kills its current process. Returns true when it acted. */
    fun preempt(packageName: String): Boolean {
        if (!rootAvailable) {
            Log.i(TAG, "mic preempt unavailable: no root")
            return false
        }
        owned.add(packageName)
        val disabled = su("pm disable $packageName")
        val victims = pids(packageName)
        if (victims.isNotEmpty()) su("kill -9 " + victims.joinToString(" "))
        Log.i(TAG, "mic preempt package=$packageName disabled=$disabled killed=$victims")
        return disabled
    }

    /** Re-enables a package previously disabled by [preempt]; safe to call repeatedly. */
    fun restore(packageName: String) {
        if (!owned.remove(packageName)) return
        if (!rootAvailable) return
        val enabled = su("pm enable $packageName")
        Log.i(TAG, "mic preempt restored package=$packageName enabled=$enabled")
    }

    /**
     * Startup self-heal: re-enable the known voice clients unless this process owns them right now.
     * A crash during a call must never leave the assistant disabled forever.
     */
    fun selfHeal() {
        if (!rootAvailable) return
        for (packageName in KNOWN_VOICE_CLIENTS) {
            if (!owned.contains(packageName)) su("pm enable $packageName")
        }
    }
    /** Startup self-heal: re-enable anything a previous run forgot to restore. */
    fun restoreAll() {
        for (packageName in owned.toList()) restore(packageName)
    }

    private val KNOWN_VOICE_CLIENTS = listOf("com.iflytek.cutefly.speechclient")

    private fun pids(packageName: String): List<Int> {
        val entries = File("/proc").list() ?: return emptyList()
        val found = ArrayList<Int>(2)
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val cmdline = runCatching { File("/proc/$entry/cmdline").readText() }.getOrNull() ?: continue
            if (cmdline.contains(packageName)) found.add(pid)
        }
        return found
    }

    private fun su(command: String): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
    }.getOrDefault(false)
}
