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
import com.geely.carplay.ui.widget.IconView
import com.shilapi.xcertplay.network.CarHotspotConfiguration
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotState
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.network.HotspotRadioFrequency
import com.shilapi.xcertplay.network.wifiChannelToFrequencyMhz
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 首页和真实会话状态保持同步
 */
class HomeActivity : Activity() {

    private var sessionConnected = false
    private var appliedNightTheme = false

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

    /** 刷新令牌会被后台线程递增 主线程读取 */
    @Volatile private var deviceStateGeneration = 0
    private val refreshLock = Any()
    private var refreshInFlight = false
    private var refreshPending = false

    private lateinit var hotspotStateText: TextView
    private lateinit var hotspotIcon: IconView
    private lateinit var bluetoothIcon: IconView
    private lateinit var deviceLabelText: TextView
    private lateinit var deviceText: TextView
    private lateinit var deviceDot: View
    private lateinit var quickConnectButton: View
    private lateinit var quickConnectLabel: View
    private lateinit var connectActions: View
    private lateinit var connectedActions: View
    private lateinit var stateText: TextView
    private lateinit var previewDiagram: ConnectDiagramView
    private lateinit var hintText: TextView

    /** 首页状态刷新共用一条后台线程 避免每次刷新新建 Thread */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "home-background").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        appliedNightTheme = ThemeStore.isNight
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
        hotspotStateText = findViewById(R.id.home_hotspot_state)
        hotspotIcon = findViewById(R.id.home_hotspot_icon)
        bluetoothIcon = findViewById(R.id.home_bluetooth_icon)
        deviceLabelText = findViewById(R.id.home_device_label)
        deviceText = findViewById(R.id.home_device)
        deviceDot = findViewById(R.id.home_device_dot)
        quickConnectButton = findViewById(R.id.home_quick_connect)
        quickConnectLabel = findViewById(R.id.home_quick_connect_label)
        connectActions = findViewById(R.id.home_connect_actions)
        connectedActions = findViewById(R.id.home_connected_actions)
        stateText = findViewById(R.id.home_state)
        previewDiagram = findViewById(R.id.home_preview)
        hintText = findViewById(R.id.home_hint)
        val wideLayout = resources.getBoolean(R.bool.wide_layout)
        val detailVisibility = if (wideLayout) View.VISIBLE else View.GONE
        previewDiagram.visibility = detailVisibility
        findViewById<View>(R.id.home_app_title).visibility = detailVisibility
        hotspotStateText.visibility = detailVisibility

        findViewById<View>(R.id.home_connect_button).setOnClickListener { connectPhone() }
        quickConnectButton.setOnClickListener { quickConnect() }
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
        if (appliedNightTheme != ThemeStore.isNight) {
            recreate()
            return
        }
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
        background.execute {
            val target = history.firstOrNull { BluetoothOnline.isConnected(this, it.address) }
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
        }
    }

    /**
     * 连接前确认车机热点与系统蓝牙已开启
     * 未开启时只提示用户手动打开 不代改系统开关
     * 状态读不到时按就绪处理 避免误拦截
     */
    private fun ensureWirelessReady(onReady: () -> Unit) {
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        background.execute {
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
        }
    }

    /** 提示缺失的开关 同时刷新顶部热点与蓝牙状态标记 */
    private fun showConnectBlocked(messageRes: Int) {
        refreshDeviceState()
        showHint(getString(messageRes))
    }

    private fun showHint(message: String) {
        hintText.text = message
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

        connectActions.visibility = if (connected) View.GONE else View.VISIBLE
        connectedActions.visibility = if (connected) View.VISIBLE else View.GONE
        stateText.setText(
            if (connected) R.string.home_state_connected else R.string.home_wireless_state
        )
        previewDiagram.setConnected(connected)
        hintText.text = when {
            CarPlaySessionManager.isExiting -> getString(R.string.exit_closing)
            CarPlaySessionManager.statusText == CarPlaySessionManager.STATUS_IDLE -> getString(R.string.home_wireless_hint)
            else -> CarPlaySessionManager.statusText
        }
    }

    /** 同一时间只跑一次刷新 期间的新请求合并成结束后再跑一次 */
    private fun refreshDeviceState() {
        if (isFinishing || isDestroyed || background.isShutdown) return
        synchronized(refreshLock) {
            if (refreshInFlight) {
                refreshPending = true
                return
            }
            refreshInFlight = true
        }
        val token = ++deviceStateGeneration
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        val phoneAddress = DiPlayPreferences.phoneAddress(app)
        val phoneName = DiPlayPreferences.phoneName(app)
        background.execute {
            try {
                val hotspot = CarHotspotTools.readState(app)
                val hotspotConfiguration = (CarHotspotTools.read(app) as? CarHotspotReadResult.Available)?.configuration
                val hotspotFrequency = if (hotspot == CarHotspotState.ENABLED) {
                    HotspotRadioFrequency.read()
                        ?: hotspotConfiguration?.channel?.let(::wifiChannelToFrequencyMhz)
                } else {
                    null
                }
                val hotspotBand = hotspotBandLabel(hotspotConfiguration, hotspotFrequency)
                val bluetooth = if (geely) null else runCatching {
                    (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
                }.getOrNull()
                val bluetoothOnline = phoneAddress != null && BluetoothOnline.isConnected(app, phoneAddress)
                runOnUiThread {
                    if (token != deviceStateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    val sessionOnline = CarPlaySessionManager.isConnected
                    val online = sessionOnline || bluetoothOnline
                    renderLastDevice(phoneName, phoneAddress != null, online, sessionOnline)
                    renderQuickConnect(online)
                    renderHotspotState(hotspot, hotspotBand)
                    renderBluetoothState(geely, bluetooth)
                }
            } finally {
                val rerun = synchronized(refreshLock) {
                    refreshInFlight = false
                    refreshPending.also { refreshPending = false }
                }
                if (rerun) refreshDeviceState()
            }
        }
    }

    /** 热点胶囊文字只保留频段 开关状态由图标颜色表达 */
    private fun renderHotspotState(hotspot: CarHotspotState?, bandResId: Int) {
        val text = getString(bandResId)
        hotspotStateText.apply {
            this.text = text
            setTextColor(resources.getColor(R.color.text_primary))
        }
        val tintResId = when (hotspot) {
            CarHotspotState.ENABLED -> R.color.ok
            CarHotspotState.DISABLED, CarHotspotState.DISABLING, CarHotspotState.FAILED -> R.color.warn
            else -> R.color.text_secondary
        }
        hotspotIcon.setIconTint(resources.getColor(tintResId))
        hotspotIcon.contentDescription = "$text ${hotspotStateLabel(hotspot)}"
    }

    /** 蓝牙胶囊只保留图标 状态由图标颜色表达 */
    private fun renderBluetoothState(geely: Boolean, bluetooth: Boolean?) {
        val source = getString(if (geely) R.string.home_bluetooth_factory else R.string.home_bluetooth_system)
        val tintResId = when {
            geely -> R.color.ok
            bluetooth == true -> R.color.ok
            bluetooth == false -> R.color.warn
            else -> R.color.text_secondary
        }
        bluetoothIcon.setIconTint(resources.getColor(tintResId))
        bluetoothIcon.contentDescription = "$source ${bluetoothStateLabel(geely, bluetooth)}"
    }

    /** 频段优先按驱动实际频率判断 其次热点配置 最后按信道 */
    private fun hotspotBandLabel(
        configuration: CarHotspotConfiguration?,
        frequencyMHz: Int?,
    ): Int {
        val channel = configuration?.channel
        return when {
            frequencyMHz != null && frequencyMHz in 2400..2500 -> R.string.home_hotspot_band_2g
            frequencyMHz != null && frequencyMHz in 4900..5900 -> R.string.home_hotspot_band_5g
            configuration?.band == ManualHotspotBand.GHZ_5 -> R.string.home_hotspot_band_5g
            configuration?.band == ManualHotspotBand.GHZ_2_4 -> R.string.home_hotspot_band_2g
            channel != null && channel >= 32 -> R.string.home_hotspot_band_5g
            channel != null && channel in 1..14 -> R.string.home_hotspot_band_2g
            else -> R.string.home_hotspot_band_unknown
        }
    }

    private fun hotspotStateLabel(state: CarHotspotState?): String = when (state) {
        CarHotspotState.ENABLED -> "已开启"
        CarHotspotState.ENABLING -> "启动中"
        CarHotspotState.DISABLING -> "正在关闭"
        CarHotspotState.DISABLED -> "未开启"
        CarHotspotState.FAILED -> "异常"
        else -> "状态未知"
    }

    private fun bluetoothStateLabel(geely: Boolean, bluetooth: Boolean?): String = when {
        geely -> "原厂通道"
        bluetooth == true -> "已开启"
        bluetooth == false -> "未开启"
        else -> "状态未知"
    }

    /** 会话在线时标注当前连接 只有蓝牙在线时仍显示上次连接 */
    private fun renderLastDevice(name: String, hasDevice: Boolean, online: Boolean, connected: Boolean) {
        deviceLabelText.text =
            getString(if (connected) R.string.home_current_device else R.string.home_last_device)
        deviceText.apply {
            text = when {
                connected && !hasDevice -> "iPhone"
                connected -> name
                hasDevice && !online -> getString(R.string.home_device_offline, name)
                else -> name
            }
            setTextColor(resources.getColor(when {
                connected || online -> R.color.text_primary
                !hasDevice -> R.color.text_tertiary
                else -> R.color.text_secondary
            }))
        }
        deviceDot.setBackgroundResource(
            if (connected || (hasDevice && online)) R.drawable.dot_ok else R.drawable.dot_muted
        )
        android.util.Log.i(
            "GeelyCarPlay",
            "设备在线: connected=$connected hasDevice=$hasDevice online=$online bluetoothOnlineOnly=${online && !connected}",
        )
    }

    /** 上次设备不在线时快速连接置灰 同时退出焦点序列 */
    private fun renderQuickConnect(ready: Boolean) {
        quickConnectButton.isEnabled = ready
        quickConnectLabel.isEnabled = ready
    }

    private fun showClock() {
        val pattern = getString(R.string.home_clock_pattern)
        val now = SimpleDateFormat(pattern, Locale.CHINA).format(Date())
        findViewById<TextView>(R.id.home_clock).text = now
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_EXIT = "com.geely.carplay.EXIT"
    }
}
