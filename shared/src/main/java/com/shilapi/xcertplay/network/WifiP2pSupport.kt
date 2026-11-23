package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime answer to “can this head unit do Wi-Fi Direct at all?”.
 *
 * Firmware may advertise the system feature and still reject `createGroup` with
 * `P2P_UNSUPPORTED`, so the verdict combines three sources:
 *  1. [PackageManager.FEATURE_WIFI_DIRECT],
 *  2. a persisted “firmware rejected P2P” flag written when a bring-up fails with code 1,
 *  3. an optional one-shot `discoverPeers` probe.
 */
object WifiP2pSupport {
    private const val TAG = "DiPlay-P2pSupport"
    private const val PREFS = "wifi_p2p_support"
    private const val KEY_UNSUPPORTED = "firmware_unsupported"

    @Volatile private var probeResult: Boolean? = null

    fun hasSystemFeature(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun isKnownUnsupported(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UNSUPPORTED, false)

    /** Cheap, synchronous answer used while building the connection page. */
    fun isAvailable(context: Context): Boolean {
        probeResult?.let { return it }
        return hasSystemFeature(context) && !isKnownUnsupported(context)
    }

    fun isProbed(): Boolean = probeResult != null

    fun markUnsupported(context: Context) {
        probeResult = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNSUPPORTED, true).apply()
        Log.i(TAG, "Wi-Fi Direct marked unsupported for this firmware")
    }

    fun clearUnsupported(context: Context) {
        probeResult = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNSUPPORTED, false).apply()
    }

    /**
     * One-shot probe. Only [WifiP2pManager.P2P_UNSUPPORTED] counts as a definitive “no”; busy or
     * transient errors leave the mode selectable and report the current optimistic answer.
     */
    fun probe(context: Context, onResult: (Boolean) -> Unit) {
        if (probeResult != null) {
            onResult(probeResult == true)
            return
        }
        if (!hasSystemFeature(context)) {
            markUnsupported(context)
            onResult(false)
            return
        }
        val manager = try {
            context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        } catch (_: Exception) {
            null
        }
        if (manager == null) {
            markUnsupported(context)
            onResult(false)
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        try {
            val channel = manager.initialize(context, Looper.getMainLooper(), null)
            if (channel == null) {
                markUnsupported(context)
                onResult(false)
                return
            }
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!finished.compareAndSet(false, true)) return
                    probeResult = true
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    if (!finished.compareAndSet(false, true)) return
                    val supported = reason != WifiP2pManager.P2P_UNSUPPORTED
                    Log.i(TAG, "discoverPeers failed reason=$reason supported=$supported")
                    if (supported) {
                        probeResult = true
                    } else {
                        markUnsupported(context)
                    }
                    onResult(supported)
                }
            })
            mainHandler.postDelayed({
                if (finished.compareAndSet(false, true)) onResult(isAvailable(context))
            }, PROBE_TIMEOUT_MILLIS)
        } catch (error: Exception) {
            Log.w(TAG, "P2P probe failed", error)
            markUnsupported(context)
            onResult(false)
        }
    }

    private const val PROBE_TIMEOUT_MILLIS = 4_000L
}
