package com.geely.carplay.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.geely.carplay.ui.widget.IconView
import android.app.AlertDialog
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.net.VpnService
import android.provider.Settings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.network.CarHotspotFailure
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
    private var phoneLookupGeneration = 0
    private var hotspotLookupGeneration = 0
    private var hotspotReadFailure: CarHotspotFailure? = null
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
        findViewById<View>(R.id.hotspot_manual_button).setOnClickListener { editHotspot() }
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
        if (stepIndex == STEP_HOTSPOT) refreshHotspot()
    }

    override fun onPause() {
        phoneLookupGeneration++
        hotspotLookupGeneration++
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
        Thread({
            val result = runCatching {
                if (geely) {
                    AnwBluetoothBackend(app).pairedDevices().map {
                        PairedPhone(it.address, it.name, isOnline = it.address.equals(DiPlayPreferences.phoneAddress(app), ignoreCase = true))
                    }
                } else {
                    val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        ?: error(getString(R.string.connect_phone_no_bluetooth))
                    check(adapter.isEnabled) { getString(R.string.connect_phone_bluetooth_off) }
                    adapter.bondedDevices.orEmpty()
                        .map {
                            PairedPhone(
                                address = it.address,
                                name = it.name ?: getString(R.string.connect_phone_unnamed),
                                isPhone = it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE,
                                isOnline = isBluetoothConnected(it) ||
                                    (CarPlaySessionManager.isRunning &&
                                        it.address.equals(DiPlayPreferences.phoneAddress(app), ignoreCase = true)),
                            )
                        }
                }
            }
            runOnUiThread {
                if (token != phoneLookupGeneration || isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                result.fold(
                    onSuccess = { devices -> showPhoneList(list, devices, geely) },
                    onFailure = { addPhoneHint(list, it.message ?: getString(R.string.connect_phone_read_failed)) },
                )
            }
        }, "carplay-phone-list").start()
    }

    /**
     * 系统蓝牙按设备类别筛出手机类 名称含 iPhone 时同样纳入 覆盖被改名的 iPhone
     * 原厂蓝牙配对列表本身只含目标设备 不再筛选
     * 识别不到时回退显示全部 避免完全选不到设备
     */
    private fun showPhoneList(list: LinearLayout, devices: List<PairedPhone>, geely: Boolean) {
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
            val online = device.isOnline
            row.findViewById<TextView>(R.id.iphone_state).apply {
                setText(if (online) R.string.connect_phone_online else R.string.connect_phone_paired)
                setTextColor(color(if (online) R.color.ok else R.color.text_tertiary))
            }
            row.setOnClickListener {
                DiPlayPreferences.savePhone(this, device.address, device.name, geely)
                stepTo(STEP_LINK)
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

    /** 蓝牙链路是否已连接 用隐藏方法读取 与控制器中的判断保持一致 */
    private fun isBluetoothConnected(device: android.bluetooth.BluetoothDevice): Boolean = runCatching {
        android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean == true
    }.getOrDefault(false)

    private fun refreshHotspot() {
        val token = ++hotspotLookupGeneration
        val app = applicationContext
        Thread({
            val result = CarHotspotTools.read(app)
            runOnUiThread {
                if (token != hotspotLookupGeneration || isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CarHotspotReadResult.Available -> {
                        hotspotReadFailure = null
                        HotspotSettings.saveDetected(this, result.configuration)
                    }
                    is CarHotspotReadResult.Failure -> {
                        hotspotReadFailure = result.reason
                        HotspotSettings.logReadFailure(result.reason)
                    }
                }
                renderHotspot()
            }
        }, "carplay-hotspot-read").start()
    }

    private fun renderHotspot() {
        val ssid = AirPlayPersistence.loadManualHotspotSsid(this)
        val passphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        val saved = ssid.isNotBlank()
        val failure = hotspotReadFailure
        val hint = getString(if (saved) R.string.hotspot_saved_hint else R.string.hotspot_manual_hint)
        findViewById<TextView>(R.id.hotspot_state).apply {
            text = if (failure == null) hint else "${HotspotSettings.failureMessage(failure)}\n$hint"
            setTextColor(color(if (failure == null) R.color.text_secondary else R.color.text_primary))
        }
        findViewById<TextView>(R.id.hotspot_ssid).text = if (saved) ssid else getString(R.string.hotspot_not_configured)
        findViewById<TextView>(R.id.hotspot_password).text = when {
            !saved -> getString(R.string.hotspot_not_configured)
            passphrase.length > 0 -> "••••••••"
            else -> getString(R.string.hotspot_open_network)
        }
        findViewById<TextView>(R.id.hotspot_manual_button).setText(
            if (saved) R.string.hotspot_edit else R.string.hotspot_manual_title,
        )
    }

    private fun editHotspot() {
        hotspotLookupGeneration++
        val builder = AlertDialog.Builder(this,
            if (ThemeStore.isNight) AlertDialog.THEME_HOLO_DARK else AlertDialog.THEME_HOLO_LIGHT)
        val form = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_hotspot, null)
        val name = form.findViewById<EditText>(R.id.hotspot_name_input)
        val password = form.findViewById<EditText>(R.id.hotspot_password_input)
        name.setText(AirPlayPersistence.loadManualHotspotSsid(this))
        password.setText(AirPlayPersistence.loadManualHotspotPassphrase(this))
        val dialog = builder.setTitle(R.string.hotspot_manual_title)
            .setView(form)
            .setPositiveButton(R.string.hotspot_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    HotspotSettings.saveManual(this, name.text.toString(), password.text.toString())
                    renderHotspot()
                    dialog.dismiss()
                    Toast.makeText(this, R.string.hotspot_saved_hint, Toast.LENGTH_LONG).show()
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun selectMode(newMode: Mode) {
        mode = newMode
        render()
    }

    private fun onActionClick() {
        when (stepIndex) {
            STEP_CHOOSE -> stepTo(if (mode == Mode.USB) STEP_LINK else STEP_HOTSPOT)
            STEP_HOTSPOT -> {
                if (AirPlayPersistence.loadManualHotspotSsid(this).isBlank()) editHotspot()
                else stepTo(STEP_PHONE)
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
            STEP_HOTSPOT -> refreshHotspot()
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
        renderPanel()
        renderSteps()
        renderAction()
        renderModeState()
    }

    private fun renderPanel() {
        val activePanelId = when (stepIndex) {
            STEP_CHOOSE -> R.id.panel_choose
            STEP_HOTSPOT -> R.id.panel_hotspot
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
        if (mode == Mode.USB && (index == STEP_HOTSPOT || index == STEP_PHONE)) {
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
            STEP_HOTSPOT -> {
                actionView.visibility = View.VISIBLE
                actionLabelView.setText(R.string.connect_next)
                hintView.setText(R.string.connect_hint_hotspot)
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
        private const val STEP_HOTSPOT = 1
        private const val STEP_PHONE = 2
        private const val STEP_LINK = 3

        private const val IPHONE_NAME = "iPhone"

        private val STEP_ICONS = arrayOf(
            IconView.Icon.SLIDERS,
            IconView.Icon.WIFI,
            IconView.Icon.PHONE,
            IconView.Icon.CAST,
        )

        private val STEP_TITLE_RES = intArrayOf(
            R.string.connect_step_choose,
            R.string.connect_step_hotspot,
            R.string.connect_step_phone,
            R.string.connect_step_linking,
        )

        private val PANEL_IDS = intArrayOf(
            R.id.panel_choose,
            R.id.panel_hotspot,
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
