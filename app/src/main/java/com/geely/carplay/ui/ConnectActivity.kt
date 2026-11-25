package com.geely.carplay.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.geely.carplay.ui.widget.IconView
import android.app.AlertDialog
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.net.VpnService
import android.provider.Settings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.transport.AnwBluetoothBackend

/**
 * CarPlay 连接页：选择方式、确认热点、挑选 iPhone、建立连接
 * 页面引导驱动真实会话, 状态与日志来自控制器
 */
class ConnectActivity : Activity() {

    private var mode = Mode.WIRELESS
    private var stepIndex = STEP_CHOOSE
    private var connected = false
    private var autoEntered = false
    private var renderedLogMessages: List<String> = emptyList()
    private var renderedStateSignature: Int? = null
    private var phoneLookupGeneration = 0
    private var hotspotChecking = false
    private val stepHolders = mutableListOf<StepHolder>()
    private val connectionObserver: () -> Unit = {
        val wasConnected = connected
        connected = CarPlaySessionManager.isConnected
        if (CarPlaySessionManager.isRunning || connected) stepIndex = STEP_LINK
        if (!connected) {
            autoEntered = false
        }
        if (connected && !wasConnected) autoEnterCarPlay()
        render()
        renderLogs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        mode = if (CarPlaySessionManager.isRunning) {
            if (AirPlayPersistence.loadWirelessEnabled(this)) Mode.WIRELESS else Mode.USB
        } else readMode(intent)
        if (CarPlaySessionManager.isRunning) stepIndex = STEP_LINK

        findViewById<View>(R.id.connect_back).setOnClickListener { finish() }
        findViewById<View>(R.id.choose_wireless).setOnClickListener { selectMode(Mode.WIRELESS) }
        findViewById<View>(R.id.choose_usb).setOnClickListener { selectMode(Mode.USB) }
        findViewById<View>(R.id.connect_action).setOnClickListener { onActionClick() }

        buildSteps()
        render()
        if (intent.getBooleanExtra(EXTRA_QUICK_CONNECT, false) && !CarPlaySessionManager.isRunning) {
            stepTo(STEP_LINK)
        }
    }

    override fun onResume() {
        super.onResume()
        CarPlaySessionManager.observe(connectionObserver)
        if (stepIndex == STEP_PHONE) buildIphoneList()
    }

    override fun onPause() {
        phoneLookupGeneration++
        CarPlaySessionManager.removeObserver(connectionObserver)
        super.onPause()
    }

    private fun buildSteps() {
        val container = findViewById<LinearLayout>(R.id.connect_steps)
        for (titleResId in STEP_TITLE_RES) {
            val row = layoutInflater.inflate(R.layout.item_connect_step, container, false)
            container.addView(row)
            row.findViewById<TextView>(R.id.step_title).setText(titleResId)
            stepHolders += StepHolder(
                circleView = row.findViewById<FrameLayout>(R.id.step_circle),
                iconView = row.findViewById<IconView>(R.id.step_icon),
                titleView = row.findViewById<TextView>(R.id.step_title),
                stateView = row.findViewById<TextView>(R.id.step_state),
            )
        }
    }

    private fun buildIphoneList() {
        val list = findViewById<LinearLayout>(R.id.connect_iphone_list)
        list.removeAllViews()
        val loading = TextView(this).apply {
            text = getString(R.string.connect_phone_loading)
            setTextColor(color(R.color.text_secondary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
        }
        list.addView(loading)
        val token = ++phoneLookupGeneration
        val app = applicationContext
        val geely = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
        val sessionConnected = CarPlaySessionManager.isConnected
        Thread({
            val result = runCatching {
                val connectedAddresses = BluetoothOnline.connectedAddresses(app)
                if (geely) {
                    AnwBluetoothBackend(app).pairedDevices().map {
                        PairedPhone(
                            it.address,
                            it.name,
                            isOnline = (sessionConnected &&
                                it.address.equals(DiPlayPreferences.phoneAddress(app), ignoreCase = true)) ||
                                connectedAddresses.any { address ->
                                    address.equals(it.address, ignoreCase = true)
                                },
                        )
                    }
                } else {
                    val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        ?: error(getString(R.string.connect_phone_no_bluetooth))
                    check(adapter.isEnabled) { getString(R.string.connect_phone_bluetooth_off) }
                    adapter.bondedDevices.orEmpty()
                        .map {
                            PairedPhone(
                                address = it.address,
                                name = phoneDisplayName(it),
                                isPhone = it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE ||
                                    it.name?.contains(IPHONE_NAME, ignoreCase = true) == true,
                                isOnline = connectedAddresses.any { address ->
                                    address.equals(it.address, ignoreCase = true)
                                } || (sessionConnected &&
                                    it.address.equals(DiPlayPreferences.phoneAddress(app), ignoreCase = true)),
                            )
                        }
                }
            }
            runOnUiThread {
                if (token != phoneLookupGeneration || isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                result.fold(
                    onSuccess = { devices -> showPhoneList(list, devices, geely, sessionConnected) },
                    onFailure = { addPhoneHint(list, it.message ?: getString(R.string.connect_phone_read_failed)) },
                )
            }
        }, "carplay-phone-list").start()
    }

    private fun phoneDisplayName(device: BluetoothDevice): String {
        // 旧版 Android 的别名接口未公开 读取失败时保留原名称
        val alias = runCatching {
            BluetoothDevice::class.java.getMethod("getAlias").invoke(device) as? String
        }.getOrNull()
        return alias?.takeIf { it.isNotBlank() }
            ?: device.name
            ?: getString(R.string.connect_phone_unnamed)
    }

    /**
     * 系统蓝牙按设备类别筛出手机类 名称含 iPhone 时同样纳入 覆盖被改名的 iPhone
     * 原厂蓝牙配对列表本身只含目标设备 不再筛选
     * 识别不到时回退显示全部 避免完全选不到设备
     */
    private fun showPhoneList(list: LinearLayout, devices: List<PairedPhone>, geely: Boolean, sessionConnected: Boolean) {
        val candidates = if (geely) {
            devices
        } else {
            devices.filter { it.isPhone || it.name.contains(IPHONE_NAME, ignoreCase = true) }
        }
        val shown = if (candidates.isEmpty()) devices else candidates

        if (candidates.isEmpty() && devices.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.connect_phone_no_iphone)
                setTextColor(color(R.color.text_tertiary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            })
        }
        if (shown.isEmpty()) {
            addPhoneHint(list, getString(R.string.connect_phone_empty))
            return
        }

        val sorted = shown.sortedWith(
            compareByDescending<PairedPhone> { it.isOnline }.thenBy { it.name },
        )
        for (device in sorted) {
            val row = layoutInflater.inflate(R.layout.item_iphone, list, false)
            row.findViewById<TextView>(R.id.iphone_name).text = device.name
            // 会话在线时只有一个候选设备说明它就是当前连接的手机
            val online = device.isOnline || (sessionConnected && sorted.size == 1)
            row.findViewById<TextView>(R.id.iphone_state).apply {
                setText(if (online) R.string.connect_phone_online else R.string.connect_phone_paired)
                setTextColor(color(if (online) R.color.ok else R.color.text_tertiary))
            }
            row.findViewById<TextView>(R.id.iphone_name).setTextColor(
                color(if (online) R.color.text_primary else R.color.text_tertiary),
            )
            // 未在线设备置灰且不响应点击 避免对未连接的手机发起连接
            row.isEnabled = online
            row.alpha = if (online) 1f else 0.5f
            if (online) {
                row.setOnClickListener {
                    DiPlayPreferences.savePhone(this, device.address, device.name, geely)
                    stepTo(STEP_LINK)
                }
            }
            list.addView(row)
        }
    }

    private fun addPhoneHint(list: LinearLayout, message: String) {
        list.addView(TextView(this).apply {
            text = "$message\n${getString(R.string.connect_phone_open_bluetooth)}"
            setTextColor(color(R.color.text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            val padding = resources.getDimensionPixelSize(R.dimen.gap_md)
            setPadding(padding, padding, padding, padding)
            setOnClickListener { openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        })
    }

    private class PairedPhone(
        val address: String,
        val name: String,
        val isPhone: Boolean = false,
        val isOnline: Boolean = false,
    )

    /** 无线连接前确认热点信息可用 系统可读时静默保存 读不到才要求手动填写 */
    private fun ensureHotspotConfigured(onReady: () -> Unit) {
        if (hotspotChecking) return
        hotspotChecking = true
        val app = applicationContext
        Thread({
            val result = runCatching { CarHotspotTools.read(app) }.getOrNull()
            if (result is CarHotspotReadResult.Failure) HotspotSettings.logReadFailure(result.reason)
            val configured = when (result) {
                is CarHotspotReadResult.Available -> {
                    HotspotSettings.saveDetected(app, result.configuration)
                    true
                }
                else -> AirPlayPersistence.loadManualHotspotSsid(app).isNotBlank()
            }
            runOnUiThread {
                hotspotChecking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (configured) onReady() else promptHotspotSettings()
            }
        }, "carplay-hotspot-check").start()
    }

    private fun promptHotspotSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.hotspot_missing_title)
            .setMessage(R.string.hotspot_missing_message)
            .setPositiveButton(R.string.hotspot_go_settings) { _, _ ->
                startActivity(SettingsActivity.hotspotIntent(this))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectMode(newMode: Mode) {
        mode = newMode
        render()
    }

    private fun onActionClick() {
        when (stepIndex) {
            STEP_CHOOSE -> when {
                mode == Mode.USB -> stepTo(STEP_LINK)
                else -> ensureHotspotConfigured { stepTo(STEP_PHONE) }
            }
            STEP_LINK -> when {
                connected -> startCarPlay()
                CarPlaySessionManager.isRunning -> CarPlaySessionManager.cancelConnection(this)
                else -> startLinking()
            }
        }
    }

    private fun stepTo(newIndex: Int) {
        stepIndex = newIndex
        when (newIndex) {
            STEP_LINK -> startLinking()
            STEP_PHONE -> buildIphoneList()
        }
        render()
    }

    private fun startLinking() {
        if (CarPlaySessionManager.isRunning) {
            AlertDialog.Builder(this).setTitle("重新连接")
                .setMessage("是否断开当前会话并重新连接？")
                .setPositiveButton("重新连接") { _, _ ->
                    CarPlaySessionManager.disconnect(this)
                    prepareConnection()
                }.setNegativeButton("取消", null).show()
            return
        }
        prepareConnection()
    }

    private fun prepareConnection() {
        if (mode == Mode.WIRELESS && AirPlayPersistence.loadManualHotspotSsid(this).isBlank()) {
            ensureHotspotConfigured { doPrepareConnection() }
            return
        }
        doPrepareConnection()
    }

    private fun doPrepareConnection() {
        AirPlayPersistence.saveWirelessEnabled(this, mode == Mode.WIRELESS)
        if (mode == Mode.USB) {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                startActivityForResult(consent, VPN_REQUEST)
                return
            }
        }
        CarPlaySessionManager.connect(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST) return
        if (resultCode == RESULT_OK) CarPlaySessionManager.connect(this)
        else Toast.makeText(this, "USB 连接需要授权本地 VPN", Toast.LENGTH_LONG).show()
    }

    private fun renderLogs() {
        val container = findViewById<LinearLayout>(R.id.connect_log_lines)
        val messages = CarPlaySessionManager.logs()
        if (messages == renderedLogMessages) return
        val appended = messages.size > renderedLogMessages.size &&
            messages.subList(0, renderedLogMessages.size) == renderedLogMessages
        if (appended) {
            for (index in renderedLogMessages.size until messages.size) {
                container.addView(inflateLogLine(messages[index], container))
            }
        } else {
            container.removeAllViews()
            for (message in messages) {
                container.addView(inflateLogLine(message, container))
            }
        }
        renderedLogMessages = messages
        val scroll = findViewById<ScrollView>(R.id.connect_log_scroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun inflateLogLine(message: String, container: LinearLayout): TextView {
        val line = layoutInflater.inflate(R.layout.item_connect_log, container, false) as TextView
        line.text = message
        return line
    }

    private fun render() {
        // 状态没有变化时只刷新日志 避免连接阶段每 100ms 重建整套控件
        val signature = stateSignature()
        if (signature == renderedStateSignature) return
        renderedStateSignature = signature
        renderPanel()
        renderSteps()
        renderAction()
        renderModeState()
    }

    private fun stateSignature(): Int {
        var result = stepIndex
        result = result * 31 + mode.ordinal
        result = result * 31 + if (connected) 1 else 0
        result = result * 31 + if (CarPlaySessionManager.isRunning) 1 else 0
        result = result * 31 + CarPlaySessionManager.statusText.hashCode()
        return result
    }

    private fun renderPanel() {
        val activePanelId = when (stepIndex) {
            STEP_CHOOSE -> R.id.panel_choose
            STEP_PHONE -> R.id.panel_phone
            else -> R.id.panel_link
        }
        for (panelId in PANEL_IDS) {
            val visibility = if (panelId == activePanelId) View.VISIBLE else View.GONE
            findViewById<View>(panelId).visibility = visibility
        }

        if (stepIndex == STEP_LINK) {
            renderLinkState()
        }
    }

    private fun renderSteps() {
        for ((index, holder) in stepHolders.withIndex()) {
            val state = stepStateOf(index)
            val icon = if (state == StepState.DONE) IconView.Icon.CHECK else STEP_ICONS[index]
            holder.circleView.setBackgroundResource(state.circleResId)
            holder.iconView.setIcon(icon, color(state.iconTintResId))
            holder.titleView.setTextColor(color(state.titleTextColorResId))
            holder.stateView.setText(state.labelResId)
        }
    }

    private fun stepStateOf(index: Int): StepState {
        if (mode == Mode.USB && index == STEP_PHONE) {
            return StepState.SKIPPED
        }
        return when {
            index < stepIndex -> StepState.DONE
            index == stepIndex -> StepState.ACTIVE
            else -> StepState.PENDING
        }
    }

    private fun renderAction() {
        val actionView = findViewById<View>(R.id.connect_action)
        val actionLabelView = findViewById<TextView>(R.id.connect_action_label)
        val hintView = findViewById<TextView>(R.id.connect_action_hint)

        when (stepIndex) {
            STEP_CHOOSE -> {
                actionView.visibility = View.VISIBLE
                actionLabelView.setText(R.string.connect_next)
                hintView.setText(R.string.connect_hint_choose)
            }
            STEP_PHONE -> {
                actionView.visibility = View.INVISIBLE
                hintView.setText(R.string.connect_hint_phone)
            }
            else -> {
                actionView.visibility = View.VISIBLE
                val connecting = CarPlaySessionManager.isRunning && !connected
                actionLabelView.setText(when {
                    connected -> R.string.btn_enter_carplay
                    connecting -> R.string.connect_cancel
                    else -> R.string.connect_reconnect
                })
                hintView.setText(when {
                    connected -> R.string.carplay_ready_hint
                    connecting -> R.string.connect_cancel_hint
                    else -> R.string.connect_hint_link
                })
            }
        }
        actionView.contentDescription = if (CarPlaySessionManager.isRunning && !connected) {
            getString(R.string.connect_cancel)
        } else hintView.text
    }

    private fun renderModeState() {
        findViewById<TextView>(R.id.connect_mode_label).setText(
            if (mode == Mode.USB) R.string.connect_usb_mode else R.string.connect_wireless_title
        )
        findViewById<View>(R.id.choose_wireless).setBackgroundResource(
            if (mode == Mode.WIRELESS) R.drawable.bg_card_selected else R.drawable.bg_card
        )
        findViewById<View>(R.id.choose_usb).setBackgroundResource(
            if (mode == Mode.USB) R.drawable.bg_card_selected else R.drawable.bg_card
        )
    }

    private fun renderLinkState() {
        val titleView = findViewById<TextView>(R.id.connect_link_title)
        val hintView = findViewById<TextView>(R.id.connect_link_hint)
        val iconView = findViewById<IconView>(R.id.connect_link_icon)

        if (connected) {
            titleView.setText(R.string.connect_success)
            hintView.setText(R.string.carplay_ready_hint)
            iconView.setIcon(IconView.Icon.CHECK, color(R.color.ok))
        } else {
            titleView.setText(if (CarPlaySessionManager.isRunning) R.string.connect_step_linking else R.string.connect_not_connected)
            hintView.text = CarPlaySessionManager.statusText
            iconView.setIcon(IconView.Icon.CAST, color(R.color.brand))
        }
    }

    /** 会话建立后立即进入投屏 同一页面每次连接只自动进入一次 */
    private fun autoEnterCarPlay() {
        if (autoEntered || isFinishing || isDestroyed) return
        if (!CarPlaySessionManager.isConnected || CarPlaySessionManager.isExiting) return
        autoEntered = true
        CarPlaySessionManager.noteScreenEntry()
        startCarPlay()
    }

    private fun startCarPlay() {
        if (CarPlaySessionManager.isExiting) return
        startActivity(Intent(this, CarPlayActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun color(colorResId: Int): Int {
        return resources.getColor(colorResId)
    }

    private fun openSystemSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (error: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "车机未提供该设置入口", Toast.LENGTH_LONG).show()
        }
    }

    private enum class Mode { WIRELESS, USB }

    private enum class StepState(
        val labelResId: Int,
        val circleResId: Int,
        val iconTintResId: Int,
        val titleTextColorResId: Int,
    ) {
        ACTIVE(
            R.string.connect_step_state_running,
            R.drawable.bg_step_active,
            R.color.white,
            R.color.text_primary,
        ),
        DONE(
            R.string.connect_step_state_done,
            R.drawable.bg_step_done,
            R.color.ok,
            R.color.text_primary,
        ),
        SKIPPED(
            R.string.connect_step_state_skipped,
            R.drawable.bg_step_skipped,
            R.color.text_tertiary,
            R.color.text_tertiary,
        ),
        PENDING(
            R.string.connect_step_state_pending,
            R.drawable.bg_step_pending,
            R.color.text_secondary,
            R.color.text_tertiary,
        ),
    }

    private class StepHolder(
        val circleView: FrameLayout,
        val iconView: IconView,
        val titleView: TextView,
        val stateView: TextView,
    )

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        internal const val EXTRA_QUICK_CONNECT = "quick_connect"
        private const val MODE_WIRELESS = "wireless"
        private const val MODE_USB = "usb"
        private const val VPN_REQUEST = 101

        private const val STEP_CHOOSE = 0
        private const val STEP_PHONE = 1
        private const val STEP_LINK = 2

        private const val IPHONE_NAME = "iPhone"

        private val STEP_ICONS = arrayOf(
            IconView.Icon.SLIDERS,
            IconView.Icon.PHONE,
            IconView.Icon.CAST,
        )

        private val STEP_TITLE_RES = intArrayOf(
            R.string.connect_step_choose,
            R.string.connect_step_phone,
            R.string.connect_step_linking,
        )

        private val PANEL_IDS = intArrayOf(
            R.id.panel_choose,
            R.id.panel_phone,
            R.id.panel_link,
        )

        fun wirelessIntent(context: Context): Intent {
            return Intent(context, ConnectActivity::class.java).putExtra(EXTRA_MODE, MODE_WIRELESS)
        }

        fun usbIntent(context: Context): Intent {
            return Intent(context, ConnectActivity::class.java).putExtra(EXTRA_MODE, MODE_USB)
        }

        private fun readMode(intent: Intent?): Mode {
            val raw = intent?.getStringExtra(EXTRA_MODE)
            return if (raw == MODE_USB) Mode.USB else Mode.WIRELESS
        }
    }
}
