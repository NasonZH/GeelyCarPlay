package com.geely.carplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.AirPlayPersistence
import com.geely.carplay.ui.widget.ConnectDiagramView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页和真实会话状态保持同步
 */
class HomeActivity : Activity() {

    private var sessionConnected = false

    private val connectionObserver: () -> Unit = {
        renderConnectionState()
        val connected = CarPlaySessionManager.isConnected
        if (connected != sessionConnected) {
            sessionConnected = connected
            refreshDeviceState()
        }
    }

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refreshDeviceState()
        }
    }

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

        findViewById<View>(R.id.home_connect_button).setOnClickListener { connectPhone() }
        findViewById<View>(R.id.home_quick_connect).setOnClickListener { quickConnect() }
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
        sessionConnected = CarPlaySessionManager.isConnected
        refreshDeviceState()
        registerReceiver(bluetoothReceiver, android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
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
        runCatching { unregisterReceiver(bluetoothReceiver) }
        CarPlaySessionManager.removeObserver(connectionObserver)
        super.onPause()
    }

    /** 连接手机：先确认热点和蓝牙已开启，再进入连接页 */
    private fun connectPhone() {
        if (CarPlaySessionManager.isExiting) return
        ensureWirelessReady {
            startActivity(ConnectActivity.wirelessIntent(this))
        }
    }

    /**
     * 快速连接最近连接过的在线设备
     * 优先上一次连接的设备 不在线时在历史里找其他在线设备
     * 没有在线设备时只提示 不发起连接
     */
    private fun quickConnect() {
        if (CarPlaySessionManager.isExiting || CarPlaySessionManager.isRunning) return
        ensureWirelessReady { connectRecentPhone() }
    }

    private fun connectRecentPhone() {
        val history = DiPlayPreferences.phoneHistory(this)
        if (history.isEmpty()) {
            showHint(getString(R.string.quick_connect_no_history))
            return
        }
        Thread({
            val target = history.firstOrNull { isBluetoothConnected(it.address) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (target == null) {
                    showHint(getString(R.string.quick_connect_offline))
                    return@runOnUiThread
                }
                DiPlayPreferences.savePhone(
                    this,
                    target.address,
                    target.name,
                    AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this),
                )
                startActivity(ConnectActivity.wirelessIntent(this)
                    .putExtra(ConnectActivity.EXTRA_QUICK_CONNECT, true))
            }
        }, "carplay-quick-connect").start()
    }

    /**
     * 连接前确认车机热点与系统蓝牙已开启
     * 未开启时只提示用户手动打开 不代改系统开关
     * 状态读不到时按就绪处理 避免误拦截
     */
    private fun ensureWirelessReady(onReady: () -> Unit) {
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        Thread({
            val hotspot = com.shilapi.xcertplay.network.CarHotspotTools.readState(app)
            val hotspotBlocked = hotspot == com.shilapi.xcertplay.network.CarHotspotState.DISABLED ||
                hotspot == com.shilapi.xcertplay.network.CarHotspotState.DISABLING ||
                hotspot == com.shilapi.xcertplay.network.CarHotspotState.FAILED
            val bluetoothBlocked = if (geely) false else runCatching {
                (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == false
            }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    hotspotBlocked && bluetoothBlocked -> showConnectBlocked(R.string.home_need_hotspot_bluetooth)
                    hotspotBlocked -> showConnectBlocked(R.string.home_need_hotspot)
                    bluetoothBlocked -> showConnectBlocked(R.string.home_need_bluetooth)
                    else -> onReady()
                }
            }
        }, "carplay-wireless-ready").start()
    }

    /** 提示缺失的开关 同时刷新顶部热点与蓝牙状态标记 */
    private fun showConnectBlocked(messageRes: Int) {
        refreshDeviceState()
        showHint(getString(messageRes))
    }

    private fun showHint(message: String) {
        findViewById<TextView>(R.id.home_hint).text = message
    }

    private fun isBluetoothConnected(address: String): Boolean = runCatching {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: return false
        val device = adapter.getRemoteDevice(address)
        android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean == true
    }.getOrDefault(false)

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

        findViewById<View>(R.id.home_connect_actions).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.home_connected_actions).visibility = if (connected) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.home_state).setText(
            if (connected) R.string.home_state_connected else R.string.home_wireless_state
        )
        findViewById<ConnectDiagramView>(R.id.home_preview).setConnected(connected)
        findViewById<TextView>(R.id.home_hint).text = when {
            CarPlaySessionManager.isExiting -> getString(R.string.exit_closing)
            CarPlaySessionManager.statusText == CarPlaySessionManager.STATUS_IDLE -> getString(R.string.home_wireless_hint)
            else -> CarPlaySessionManager.statusText
        }
    }

    private fun refreshDeviceState() {
        val token = ++deviceStateGeneration
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        val phoneAddress = DiPlayPreferences.phoneAddress(app)
        val phoneName = DiPlayPreferences.phoneName(app)
        Thread({
            val hotspot = com.shilapi.xcertplay.network.CarHotspotTools.readState(app)
            val bluetooth = if (geely) null else runCatching {
                (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
            }.getOrNull()
            val bluetoothOnline = phoneAddress != null && (geely || isBluetoothConnected(phoneAddress))
            runOnUiThread {
                if (token != deviceStateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                val sessionOnline = CarPlaySessionManager.isConnected
                val online = sessionOnline || bluetoothOnline
                renderLastDevice(phoneName, phoneAddress != null, online, sessionOnline)
                renderQuickConnect(online)
                val enabled = hotspot == com.shilapi.xcertplay.network.CarHotspotState.ENABLED
                findViewById<TextView>(R.id.home_hotspot_state).text = when (hotspot) {
                    com.shilapi.xcertplay.network.CarHotspotState.ENABLED -> "热点已开启"
                    com.shilapi.xcertplay.network.CarHotspotState.ENABLING -> "热点启动中"
                    null -> "热点状态未知"
                    else -> "热点未开启"
                }
                findViewById<TextView>(R.id.home_transport).setText(
                    if (geely) R.string.home_transport_geely else R.string.home_transport_system,
                )
                findViewById<TextView>(R.id.home_bluetooth_state).text = when (bluetooth) {
                    true -> "已开启"
                    false -> "未开启"
                    null -> if (geely) "已开启" else "不可用"
                }
                findViewById<View>(R.id.home_hotspot_icon).contentDescription = findViewById<TextView>(R.id.home_hotspot_state).text
                findViewById<View>(R.id.home_bluetooth_icon).contentDescription = findViewById<TextView>(R.id.home_bluetooth_state).text
                val hotspotReady = enabled
                val bluetoothReady = if (geely) true else bluetooth == true
                findViewById<com.geely.carplay.ui.widget.IconView>(R.id.home_hotspot_icon).setIconTint(resources.getColor(if (hotspotReady) R.color.ok else R.color.warn))
                findViewById<com.geely.carplay.ui.widget.IconView>(R.id.home_bluetooth_icon).setIconTint(resources.getColor(if (bluetoothReady) R.color.ok else R.color.warn))
                findViewById<TextView>(R.id.home_hotspot_state).setTextColor(resources.getColor(if (hotspotReady) R.color.text_primary else R.color.warn))
                findViewById<TextView>(R.id.home_bluetooth_state).setTextColor(resources.getColor(if (bluetoothReady) R.color.text_primary else R.color.warn))
            }
        }, "carplay-device-state").start()
    }

    /** 会话在线时标注当前连接 只有蓝牙在线时仍显示上次连接 */
    private fun renderLastDevice(name: String, hasDevice: Boolean, online: Boolean, connected: Boolean) {
        findViewById<TextView>(R.id.home_device_label).text =
            getString(if (connected) R.string.home_current_device else R.string.home_last_device)
        findViewById<TextView>(R.id.home_device).apply {
            text = if (hasDevice && !online) getString(R.string.home_device_offline, name) else name
            setTextColor(resources.getColor(when {
                !hasDevice -> R.color.text_tertiary
                online -> R.color.text_primary
                else -> R.color.text_secondary
            }))
        }
        findViewById<View>(R.id.home_device_dot).setBackgroundResource(
            if (hasDevice && online) R.drawable.dot_ok else R.drawable.dot_muted
        )
    }

    /** 上次设备不在线时快速连接置灰 同时退出焦点序列 */
    private fun renderQuickConnect(ready: Boolean) {
        findViewById<View>(R.id.home_quick_connect).isEnabled = ready
        findViewById<View>(R.id.home_quick_connect_label).isEnabled = ready
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
