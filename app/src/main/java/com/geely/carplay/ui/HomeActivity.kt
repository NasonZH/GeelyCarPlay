package com.geely.carplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.geely.carplay.ui.widget.ConnectDiagramView
import com.geely.carplay.ui.widget.IconView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.network.CarHotspotState
import com.shilapi.xcertplay.network.CarHotspotTools
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 首页和真实会话状态保持同步
 * 页面每两秒刷新热点和蓝牙状态 只显示当前蓝牙已连接的手机
 */
class HomeActivity : Activity() {

    private var sessionConnected = false
    private var appliedNightTheme = false
    private var resumed = false
    private var autoConnectAttempted = false

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

    /** 刷新结果只在首页仍处于前台且代次一致时应用 */
    private var deviceStateGeneration = 0
    private var refreshInFlight = false
    private var refreshPending = false

    private lateinit var hotspotIcon: IconView
    private lateinit var bluetoothIcon: IconView
    private lateinit var deviceLabel: View
    private lateinit var deviceNames: TextView
    private lateinit var connectActions: View
    private lateinit var connectedActions: View
    private lateinit var stateText: TextView
    private lateinit var previewDiagram: ConnectDiagramView
    private lateinit var hintText: TextView

    /** 首页状态刷新共用一条后台线程 避免每次刷新新建 Thread */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "home-background").apply { isDaemon = true }
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollTick = object : Runnable {
        override fun run() {
            refreshDeviceState()
            pollHandler.postDelayed(this, POLL_INTERVAL_MILLIS)
        }
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
        autoConnectAttempted = savedInstanceState?.getBoolean(STATE_AUTO_CONNECT_ATTEMPTED) ?: false
        setContentView(R.layout.activity_home)
        hotspotIcon = findViewById(R.id.home_hotspot_icon)
        bluetoothIcon = findViewById(R.id.home_bluetooth_icon)
        deviceLabel = findViewById(R.id.home_connected_devices_label)
        deviceNames = findViewById(R.id.home_device_list)
        connectActions = findViewById(R.id.home_connect_actions)
        connectedActions = findViewById(R.id.home_connected_actions)
        stateText = findViewById(R.id.home_state)
        previewDiagram = findViewById(R.id.home_preview)
        hintText = findViewById(R.id.home_hint)
        val wideLayout = resources.getBoolean(R.bool.wide_layout)
        val detailVisibility = if (wideLayout) View.VISIBLE else View.GONE
        previewDiagram.visibility = detailVisibility
        findViewById<View>(R.id.home_app_title).visibility = detailVisibility

        findViewById<View>(R.id.home_connect_button).setOnClickListener { connectWireless() }
        findViewById<View>(R.id.home_usb_button).setOnClickListener { connectUsb() }
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
        CarPlaySessionManager.preloadLocalMfi(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        if (intent.action == ACTION_EXIT) return
        if (appliedNightTheme != ThemeStore.isNight) {
            recreate()
            return
        }
        resumed = true
        showClock()
        sessionConnected = CarPlaySessionManager.isConnected
        refreshDeviceState()
        registerReceiver(bluetoothReceiver, android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        CarPlaySessionManager.observe(connectionObserver)
        pollHandler.removeCallbacks(pollTick)
        pollHandler.postDelayed(pollTick, POLL_INTERVAL_MILLIS)
        if (!autoConnectAttempted && !CarPlaySessionManager.isRunning &&
            CarPlaySessionManager.automaticConnectionAllowed && DiPlayPreferences.autoConnect(this)) {
            val wireless = AirPlayPersistence.loadWirelessEnabled(this)
            if (!wireless || DiPlayPreferences.phoneAddress(this) != null) {
                autoConnectAttempted = true
                startActivity(if (wireless) ConnectActivity.wirelessIntent(this) else ConnectActivity.usbIntent(this))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AUTO_CONNECT_ATTEMPTED, autoConnectAttempted)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        resumed = false
        refreshPending = false
        pollHandler.removeCallbacks(pollTick)
        deviceStateGeneration++
        runCatching { unregisterReceiver(bluetoothReceiver) }
        CarPlaySessionManager.removeObserver(connectionObserver)
        super.onPause()
    }

    /** 无线连接前先确认热点和蓝牙已开启 再读取当前在线设备 */
    private fun connectWireless() {
        if (CarPlaySessionManager.isExiting) return
        autoConnectAttempted = true
        if (CarPlaySessionManager.isRunning) {
            startActivity(ConnectActivity.wirelessIntent(this))
            return
        }
        ensureWirelessReady { chooseWirelessDevice() }
    }

    /** USB 连接不检查热点和蓝牙 直接进入连接日志页 */
    private fun connectUsb() {
        if (CarPlaySessionManager.isExiting) return
        autoConnectAttempted = true
        startActivity(ConnectActivity.usbIntent(this))
    }

    /**
     * 读取当前蓝牙已连接的手机
     * 一台直接连接 多台弹窗选择 没有设备时只提示 不发起连接
     */
    private fun chooseWirelessDevice() {
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        background.execute {
            val devices = runCatching { BluetoothOnline.connectedDevices(app) }.getOrDefault(emptyList())
            runOnUiThread {
                if (!resumed || isFinishing || isDestroyed) return@runOnUiThread
                when {
                    devices.isEmpty() -> showHint(getString(R.string.home_no_connected_phone))
                    devices.size == 1 -> connectDevice(devices.first(), geely)
                    else -> showDeviceChooser(devices, geely)
                }
            }
        }
    }

    private fun showDeviceChooser(devices: List<ConnectedDevice>, geely: Boolean) {
        val names = devices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.home_choose_device_title)
            .setItems(names) { _, index -> connectDevice(devices[index], geely) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectDevice(device: ConnectedDevice, geely: Boolean) {
        DiPlayPreferences.savePhone(this, device.address, device.name, geely)
        startActivity(ConnectActivity.wirelessIntent(this))
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
            val hotspot = CarHotspotTools.readState(app)
            val hotspotBlocked = hotspot == CarHotspotState.ENABLING ||
                hotspot == CarHotspotState.DISABLED ||
                hotspot == CarHotspotState.DISABLING ||
                hotspot == CarHotspotState.FAILED
            val bluetoothBlocked = if (geely) false else runCatching {
                (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == false
            }.getOrDefault(false)
            runOnUiThread {
                if (!resumed || isFinishing || isDestroyed) return@runOnUiThread
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

        deviceLabel.visibility = if (connected) View.GONE else View.VISIBLE
        deviceNames.visibility = if (connected) View.GONE else View.VISIBLE
        connectActions.visibility = if (connected) View.GONE else View.VISIBLE
        connectedActions.visibility = if (connected) View.VISIBLE else View.GONE
        stateText.setText(
            if (connected) R.string.home_state_connected else R.string.home_wireless_state
        )
        val stateColor = if (connected) R.color.text_primary else R.color.brand
        stateText.setTextColor(resources.getColor(stateColor))
        previewDiagram.setConnected(connected)
        hintText.text = when {
            CarPlaySessionManager.isExiting -> getString(R.string.exit_closing)
            connected -> connectedHint()
            CarPlaySessionManager.statusText == CarPlaySessionManager.STATUS_IDLE -> getString(R.string.home_wireless_hint)
            else -> CarPlaySessionManager.statusText
        }
    }

    private fun connectedHint(): CharSequence {
        if (!AirPlayPersistence.loadWirelessEnabled(this)) return getString(R.string.carplay_ready_hint)
        val address = DiPlayPreferences.phoneAddress(this) ?: return getString(R.string.carplay_ready_hint)
        val name = DiPlayPreferences.phoneName(this).ifBlank { address }
        val message = getString(R.string.home_streaming_device, name)
        val nameStart = message.lastIndexOf(name)
        return SpannableString(message).apply {
            setSpan(ForegroundColorSpan(resources.getColor(R.color.ok)),
                nameStart, nameStart + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 同一时间只跑一次刷新 期间的新请求合并成结束后再跑一次 */
    private fun refreshDeviceState() {
        if (!resumed || isFinishing || isDestroyed || background.isShutdown) return
        if (refreshInFlight) {
            refreshPending = true
            return
        }
        refreshInFlight = true
        val token = ++deviceStateGeneration
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app)
        background.execute {
            try {
                val hotspot = CarHotspotTools.readState(app)
                val bluetooth = if (geely) null else runCatching {
                    (app.getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
                }.getOrNull()
                val devices = if (!geely && bluetooth == false) {
                    emptyList()
                } else {
                    runCatching { BluetoothOnline.connectedDevices(app) }.getOrDefault(emptyList())
                }
                runOnUiThread {
                    if (token != deviceStateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    renderDeviceList(devices)
                    renderHotspotState(hotspot)
                    renderBluetoothState(geely, bluetooth)
                }
            } finally {
                runOnUiThread {
                    refreshInFlight = false
                    val rerun = refreshPending
                    refreshPending = false
                    if (rerun) refreshDeviceState()
                }
            }
        }
    }

    private fun renderDeviceList(devices: List<ConnectedDevice>) {
        val colorRes = if (devices.isEmpty()) R.color.text_secondary else R.color.ok
        deviceNames.setTextColor(resources.getColor(colorRes))
        deviceNames.text = if (devices.isEmpty()) {
            getString(R.string.home_no_connected_device)
        } else {
            devices.joinToString(" / ") { it.name }
        }
    }

    /** 热点胶囊只保留图标 状态由图标颜色表达 */
    private fun renderHotspotState(hotspot: CarHotspotState?) {
        val label = hotspotStateLabel(hotspot)
        val tintResId = when (hotspot) {
            CarHotspotState.ENABLED -> R.color.ok
            CarHotspotState.DISABLED, CarHotspotState.DISABLING, CarHotspotState.FAILED -> R.color.warn
            else -> R.color.text_secondary
        }
        hotspotIcon.setIconTint(resources.getColor(tintResId))
        hotspotIcon.contentDescription = getString(R.string.home_hotspot_accessibility, label)
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

    private fun hotspotStateLabel(state: CarHotspotState?): String = getString(when (state) {
        CarHotspotState.ENABLED -> R.string.home_hotspot_state_enabled
        CarHotspotState.ENABLING -> R.string.home_hotspot_state_enabling
        CarHotspotState.DISABLING -> R.string.home_hotspot_state_disabling
        CarHotspotState.DISABLED -> R.string.home_hotspot_state_disabled
        CarHotspotState.FAILED -> R.string.home_hotspot_state_failed
        else -> R.string.home_hotspot_state_unknown
    })

    private fun bluetoothStateLabel(geely: Boolean, bluetooth: Boolean?): String = when {
        geely -> "原厂通道"
        bluetooth == true -> "已开启"
        bluetooth == false -> "未开启"
        else -> "状态未知"
    }

    private fun showClock() {
        val pattern = getString(R.string.home_clock_pattern)
        val now = SimpleDateFormat(pattern, Locale.CHINA).format(Date())
        findViewById<TextView>(R.id.home_clock).text = now
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollTick)
        background.shutdownNow()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_EXIT = "com.geely.carplay.EXIT"
        private const val STATE_AUTO_CONNECT_ATTEMPTED = "auto_connect_attempted"
        private const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
