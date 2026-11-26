package com.geely.carplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.geely.carplay.ui.widget.IconView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotTools

/**
 * CarPlay 连接日志页
 * 首页选好连接方式和设备后进入本页 页面负责建立连接并展示过程日志
 */
class ConnectActivity : Activity() {

    private var mode = Mode.WIRELESS
    private var connected = false
    private var renderedLogMessages: List<String> = emptyList()
    private var renderedStateSignature: Int? = null
    private var preparing = false
    private var vpnPending = false
    private var attemptGeneration = 0

    private val connectionObserver: () -> Unit = {
        val justConnected = !connected && CarPlaySessionManager.isConnected
        connected = CarPlaySessionManager.isConnected
        if (CarPlaySessionManager.isRunning) preparing = false
        render()
        renderLogs()
        if (justConnected) startCarPlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        mode = if (CarPlaySessionManager.isRunning) {
            if (AirPlayPersistence.loadWirelessEnabled(this)) Mode.WIRELESS else Mode.USB
        } else {
            readMode(intent)
        }

        findViewById<View>(R.id.connect_back).setOnClickListener { finish() }
        findViewById<View>(R.id.connect_action).setOnClickListener { onActionClick() }

        connected = CarPlaySessionManager.isConnected
        vpnPending = savedInstanceState?.getBoolean(STATE_VPN_PENDING) ?: false
        preparing = vpnPending
        render()
        if (savedInstanceState == null && !CarPlaySessionManager.isRunning && shouldStart(intent)) {
            beginConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        CarPlaySessionManager.observe(connectionObserver)
    }

    override fun onPause() {
        CarPlaySessionManager.removeObserver(connectionObserver)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_VPN_PENDING, vpnPending)
        super.onSaveInstanceState(outState)
    }

    private fun beginConnect() {
        if (CarPlaySessionManager.isExiting || CarPlaySessionManager.isRunning || preparing) return
        attemptGeneration++
        preparing = true
        render()
        if (mode == Mode.USB) {
            prepareConnection()
            return
        }
        ensureHotspotConfigured { prepareConnection() }
    }

    /** 无线连接前确认热点信息可用 系统可读时静默保存 读不到才要求手动填写 */
    private fun ensureHotspotConfigured(onReady: () -> Unit) {
        val token = attemptGeneration
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
                if (token != attemptGeneration || isFinishing || isDestroyed) return@runOnUiThread
                if (configured) {
                    onReady()
                } else {
                    preparing = false
                    render()
                    promptHotspotSettings()
                }
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

    private fun onActionClick() {
        when {
            connected -> startCarPlay()
            preparing || CarPlaySessionManager.isRunning -> {
                attemptGeneration++
                preparing = false
                vpnPending = false
                CarPlaySessionManager.cancelConnection(this)
                render()
            }
            else -> beginConnect()
        }
    }

    private fun prepareConnection() {
        AirPlayPersistence.saveWirelessEnabled(this, mode == Mode.WIRELESS)
        if (mode == Mode.USB) {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                vpnPending = true
                startActivityForResult(consent, VPN_REQUEST)
                return
            }
        }
        CarPlaySessionManager.connect(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST || !vpnPending || isFinishing || isDestroyed) return
        vpnPending = false
        if (resultCode == RESULT_OK) {
            CarPlaySessionManager.connect(this)
        } else {
            preparing = false
            render()
            Toast.makeText(this, R.string.connect_usb_vpn_denied, Toast.LENGTH_LONG).show()
        }
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
        val signature = stateSignature()
        if (signature == renderedStateSignature) return
        renderedStateSignature = signature
        renderModeState()
        renderLinkState()
        renderAction()
    }

    private fun stateSignature(): Int {
        var result = mode.ordinal
        result = result * 31 + if (connected) 1 else 0
        result = result * 31 + if (preparing) 1 else 0
        result = result * 31 + if (CarPlaySessionManager.isRunning) 1 else 0
        result = result * 31 + CarPlaySessionManager.statusText.hashCode()
        return result
    }

    private fun renderModeState() {
        findViewById<TextView>(R.id.connect_mode_label).setText(
            if (mode == Mode.USB) R.string.connect_usb_mode else R.string.connect_wireless_title
        )
    }

    private fun renderAction() {
        val actionView = findViewById<View>(R.id.connect_action)
        val actionLabelView = findViewById<TextView>(R.id.connect_action_label)
        val hintView = findViewById<TextView>(R.id.connect_action_hint)
        val connecting = (preparing || CarPlaySessionManager.isRunning) && !connected

        actionView.visibility = View.VISIBLE
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
        actionView.contentDescription = actionLabelView.text
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
            titleView.setText(if (preparing || CarPlaySessionManager.isRunning) R.string.connect_step_linking else R.string.connect_not_connected)
            hintView.text = CarPlaySessionManager.statusText
            iconView.setIcon(IconView.Icon.CAST, color(R.color.brand))
        }
    }

    private fun startCarPlay() {
        if (CarPlaySessionManager.isExiting) return
        startActivity(Intent(this, CarPlayActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun color(colorResId: Int): Int {
        return resources.getColor(colorResId)
    }

    private enum class Mode { WIRELESS, USB }

    companion object {
        private const val STATE_VPN_PENDING = "vpn_pending"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_START = "extra_start"
        private const val MODE_WIRELESS = "wireless"
        private const val MODE_USB = "usb"
        private const val VPN_REQUEST = 101

        fun wirelessIntent(context: Context): Intent {
            return Intent(context, ConnectActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_WIRELESS)
                .putExtra(EXTRA_START, true)
        }

        fun usbIntent(context: Context): Intent {
            return Intent(context, ConnectActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_USB)
                .putExtra(EXTRA_START, true)
        }

        private fun readMode(intent: Intent?): Mode {
            val raw = intent?.getStringExtra(EXTRA_MODE)
            return if (raw == MODE_USB) Mode.USB else Mode.WIRELESS
        }

        private fun shouldStart(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(EXTRA_START, false) == true
        }
    }
}
