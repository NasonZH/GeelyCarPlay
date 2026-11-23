package com.geely.carplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.AirPlayPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页和真实会话状态保持同步
 */
class HomeActivity : Activity() {

    private val connectionObserver: () -> Unit = { renderConnectionState() }

    private var deviceStateGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_EXIT) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finishAffinity()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        setContentView(R.layout.activity_home)
        val wideLayout = resources.getBoolean(R.bool.wide_layout)
        val detailVisibility = if (wideLayout) View.VISIBLE else View.GONE
        findViewById<View>(R.id.home_preview).visibility = detailVisibility
        findViewById<View>(R.id.home_app_title).visibility = detailVisibility
        findViewById<View>(R.id.home_hotspot_state).visibility = detailVisibility
        findViewById<View>(R.id.home_bluetooth_state).visibility = detailVisibility

        findViewById<View>(R.id.home_connect_button).setOnClickListener {
            if (CarPlaySessionManager.isExiting) return@setOnClickListener
            startActivity(ConnectActivity.wirelessIntent(this))
        }
        findViewById<View>(R.id.home_enter_carplay).setOnClickListener {
            if (CarPlaySessionManager.isExiting) return@setOnClickListener
            startActivity(Intent(this, CarPlayActivity::class.java))
        }
        findViewById<View>(R.id.home_exit).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.home_disconnect).setOnClickListener {
            CarPlaySessionManager.disconnect(this)
            renderConnectionState()
        }
        findViewById<View>(R.id.home_settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent.action == ACTION_EXIT) return
        showClock()
        refreshDeviceState()
        CarPlaySessionManager.observe(connectionObserver)
        if (!CarPlaySessionManager.isRunning && CarPlaySessionManager.automaticConnectionAllowed && DiPlayPreferences.autoConnect(this)) {
            if (!AirPlayPersistence.loadWirelessEnabled(this) || DiPlayPreferences.phoneAddress(this) != null) {
                if (!AirPlayPersistence.loadWirelessEnabled(this) && android.net.VpnService.prepare(this) != null) {
                    startActivity(ConnectActivity.usbIntent(this))
                } else {
                    CarPlaySessionManager.connect(this)
                }
            }
        }
    }

    override fun onPause() {
        deviceStateGeneration++
        CarPlaySessionManager.removeObserver(connectionObserver)
        super.onPause()
    }

    private fun confirmExit() {
        if (CarPlaySessionManager.isExiting) return
        if (CarPlaySessionManager.isRunning || CarPlaySessionManager.isConnected) {
            AlertDialog.Builder(this)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_disconnect_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.exit_confirm) { _, _ -> exitApplication() }
                .show()
        } else {
            exitApplication()
        }
    }

    private fun exitApplication() {
        findViewById<View>(R.id.home_exit).isEnabled = false
        findViewById<TextView>(R.id.home_hint).setText(R.string.exit_closing)
        CarPlaySessionManager.prepareExit(this) {
            startActivity(Intent(this, HomeActivity::class.java)
                .setAction(ACTION_EXIT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }

    private fun renderConnectionState() {
        val connected = CarPlaySessionManager.isConnected

        findViewById<View>(R.id.home_connect_button).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.home_connected_actions).visibility = if (connected) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.home_state).setText(
            if (connected) R.string.home_state_connected else R.string.home_wireless_state
        )
        findViewById<TextView>(R.id.home_hint).text = if (CarPlaySessionManager.isExiting) {
            getString(R.string.exit_closing)
        } else CarPlaySessionManager.statusText
    }

    private fun refreshDeviceState() {
        val token = ++deviceStateGeneration
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        Thread({
            val hotspot = com.shilapi.xcertplay.network.CarHotspotTools.readState(app)
            val bluetooth = if (geely) null else runCatching {
                (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
            }.getOrNull()
            runOnUiThread {
                if (token != deviceStateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                val enabled = hotspot == com.shilapi.xcertplay.network.CarHotspotState.ENABLED
                findViewById<TextView>(R.id.home_hotspot_state).text = when (hotspot) {
                    com.shilapi.xcertplay.network.CarHotspotState.ENABLED -> "热点已开启"
                    com.shilapi.xcertplay.network.CarHotspotState.ENABLING -> "热点启动中"
                    null -> "热点状态未知"
                    else -> "热点未开启"
                }
                findViewById<TextView>(R.id.home_bluetooth_state).text = if (geely) "原厂蓝牙" else when (bluetooth) {
                    true -> "蓝牙已开启"
                    false -> "蓝牙未开启"
                    null -> "蓝牙不可用"
                }
                findViewById<View>(R.id.home_hotspot_icon).contentDescription = findViewById<TextView>(R.id.home_hotspot_state).text
                findViewById<View>(R.id.home_bluetooth_icon).contentDescription = findViewById<TextView>(R.id.home_bluetooth_state).text
                findViewById<com.geely.carplay.ui.widget.IconView>(R.id.home_hotspot_icon).setIconTint(resources.getColor(if (enabled) R.color.ok else R.color.text_secondary))
                findViewById<com.geely.carplay.ui.widget.IconView>(R.id.home_bluetooth_icon).setIconTint(resources.getColor(if (bluetooth == true) R.color.ok else R.color.text_secondary))
            }
        }, "carplay-device-state").start()
        findViewById<TextView>(R.id.home_device).text = DiPlayPreferences.phoneName(this)
    }

    private fun showClock() {
        val pattern = getString(R.string.home_clock_pattern)
        val now = SimpleDateFormat(pattern, Locale.CHINA).format(Date())
        findViewById<TextView>(R.id.home_clock).text = now
    }

    companion object {
        internal const val ACTION_EXIT = "com.geely.carplay.EXIT"
    }
}
