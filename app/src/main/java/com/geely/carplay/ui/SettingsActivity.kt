package com.geely.carplay.ui

import android.app.AlertDialog
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import android.net.Uri
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.network.CarHotspotConfiguration
import com.shilapi.xcertplay.network.CarHotspotFailure
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotTools
import com.geely.carplay.ui.widget.IconView
import com.geely.carplay.ui.widget.SettingRowView
import com.geely.carplay.ui.widget.SyncScrollView

/**
 * 设置页：左侧分类，右侧滚动列表，滚动时左栏选中项跟随
 * 选项写入处理层配置, 连接参数在下次连接时生效
 */
class SettingsActivity : Activity() {

    private lateinit var categoryColumn: LinearLayout
    private lateinit var groupColumn: LinearLayout
    private lateinit var scrollView: SyncScrollView

    private val categoryHolders = mutableListOf<CategoryHolder>()
    private val groupHeaders = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private var hotspotRow: SettingRowView? = null
    private var hotspotReading = false

    private var selectedCategory = 0

    private val syncCategory = Runnable { syncCategoryWithScroll() }

    private val groups = listOf(
        SettingsGroup(
            R.string.settings_cat_connection,
            IconView.Icon.CAST,
            listOf(
                SettingsItem.Toggle(R.string.settings_auto_connect, R.string.settings_auto_connect_desc),
                SettingsItem.Toggle(R.string.settings_boot_start, R.string.settings_boot_start_desc),
                SettingsItem.Link(R.string.settings_hotspot, R.string.settings_hotspot_desc, LinkAction.HOTSPOT),
                SettingsItem.Toggle(R.string.settings_geely_bluetooth, R.string.settings_geely_bluetooth_desc),
            ),
        ),
        SettingsGroup(
            R.string.settings_cat_display,
            IconView.Icon.DISPLAY,
            listOf(
                SettingsItem.Theme,
                SettingsItem.Choice(
                    R.string.settings_ui_scale,
                    R.string.settings_ui_scale_desc,
                    R.array.settings_ui_scale_values,
                ),
                SettingsItem.Choice(
                    R.string.settings_resolution,
                    R.string.settings_resolution_desc,
                    R.array.settings_resolution_values,
                ),
                SettingsItem.Choice(
                    R.string.settings_music_buffer,
                    R.string.settings_music_buffer_desc,
                    R.array.settings_music_buffer_values,
                ),
                SettingsItem.Choice(
                    R.string.settings_frame_rate,
                    R.string.settings_frame_rate_desc,
                    R.array.settings_frame_rate_values,
                ),
                SettingsItem.Choice(
                    R.string.settings_video_output,
                    R.string.settings_video_output_desc,
                    R.array.settings_video_output_values,
                ),
                SettingsItem.Toggle(R.string.settings_hevc, R.string.settings_hevc_desc),
                SettingsItem.Toggle(R.string.settings_right_hand_drive, R.string.settings_right_hand_drive_desc),
                SettingsItem.Toggle(R.string.settings_fullscreen, R.string.settings_fullscreen_desc),
            ),
        ),
        SettingsGroup(
            R.string.settings_cat_audio,
            IconView.Icon.VOLUME,
            listOf(
                SettingsItem.Toggle(R.string.settings_geely_audio, R.string.settings_geely_audio_desc),
                SettingsItem.Choice(R.string.settings_call_mode, R.string.settings_call_mode_desc, R.array.settings_call_mode_values),
                SettingsItem.Toggle(R.string.settings_audio_focus, R.string.settings_audio_focus_desc),
                SettingsItem.Toggle(R.string.settings_advanced_audio, R.string.settings_advanced_audio_desc),
                SettingsItem.Choice(
                    R.string.settings_media_stream,
                    R.string.settings_media_stream_desc,
                    R.array.settings_audio_stream_values,
                ),
                SettingsItem.Choice(
                    R.string.settings_nav_stream,
                    R.string.settings_nav_stream_desc,
                    R.array.settings_audio_stream_values,
                ),
            ),
        ),
        SettingsGroup(
            R.string.settings_cat_location,
            IconView.Icon.LOCATION,
            listOf(
                SettingsItem.Toggle(R.string.settings_report_location, R.string.settings_report_location_desc),
                SettingsItem.Link(R.string.settings_permissions, R.string.settings_permissions_desc, LinkAction.PERMISSIONS),
                SettingsItem.Link(R.string.settings_bluetooth, R.string.settings_bluetooth_desc, LinkAction.BLUETOOTH),
            ),
        ),
        SettingsGroup(
            R.string.settings_cat_diagnostics,
            IconView.Icon.FILE,
            listOf(
                SettingsItem.Toggle(R.string.settings_verbose_log, R.string.settings_verbose_log_desc),
                SettingsItem.Link(R.string.settings_save_report, R.string.settings_save_report_desc, LinkAction.REPORT),
            ),
        ),
        SettingsGroup(
            R.string.settings_cat_about,
            IconView.Icon.INFO,
            listOf(
                SettingsItem.Link(R.string.settings_version, R.string.settings_version_value, LinkAction.VERSION),
                SettingsItem.Link(R.string.settings_device, R.string.settings_device_value, LinkAction.DEVICE),
                SettingsItem.Link(R.string.settings_licenses, R.string.settings_licenses_desc, LinkAction.LICENSES),
            ),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        categoryColumn = findViewById(R.id.settings_categories)
        groupColumn = findViewById(R.id.settings_groups)
        scrollView = findViewById(R.id.settings_scroll)

        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        scrollView.onScrolled = { onListScrolled() }

        renderGroups()
        updateHotspotRow()
        renderCategories()
        applyCategorySelection(0)
        if (intent.getBooleanExtra(EXTRA_OPEN_HOTSPOT, false)) openHotspotSettings()
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncCategory)
        super.onDestroy()
    }

    private fun renderGroups() {
        for (group in groups) {
            val header = layoutInflater.inflate(R.layout.item_settings_group_header, groupColumn, false) as TextView
            header.setText(group.titleResId)
            groupColumn.addView(header)
            groupHeaders += header

            for (item in group.items) {
                val rowParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                groupColumn.addView(createRow(item), rowParams)
            }
        }
    }

    private fun createRow(item: SettingsItem): SettingRowView {
        val row = SettingRowView(this)
        when (item) {
            is SettingsItem.Toggle -> row.bindToggle(item.titleResId, item.descResId, loadToggle(item.titleResId)) {
                saveToggle(item.titleResId, it)
                if (item.titleResId == R.string.settings_verbose_log) {
                    Toast.makeText(this, "已保存, 详细日志立即生效", Toast.LENGTH_SHORT).show()
                } else {
                    showReconnectHint()
                }
            }.also {
                if (item.titleResId == R.string.settings_hevc && !com.shilapi.xcertplay.media.H52VideoCapabilities.supportsHevc()) {
                    row.isEnabled = false
                    row.updateDescription("车机没有可用的 HEVC 解码器, 使用 H.264")
                }
            }
            is SettingsItem.Link -> {
                row.bindLink(item.titleResId, item.descResId) { openLink(item.action) }
                if (item.action == LinkAction.HOTSPOT) hotspotRow = row
            }
            is SettingsItem.Theme -> row.bindChoice(
                R.string.settings_theme,
                R.string.settings_theme_desc,
                themeLabel(),
            ) { switchTheme() }
            is SettingsItem.Choice -> {
                val values = resources.getStringArray(item.valueArrayResId)
                var index = choiceIndex(item.titleResId).coerceIn(0, values.lastIndex)
                row.bindChoice(item.titleResId, item.descResId, values[index]) {
                    AlertDialog.Builder(this).setTitle(item.titleResId)
                        .setSingleChoiceItems(values, index) { dialog, selected ->
                            index = selected
                            saveChoice(item.titleResId, index)
                            row.updateValue(values[index])
                            dialog.dismiss()
                            if (item.titleResId == R.string.settings_video_output) {
                                Toast.makeText(this, "已保存, 重新进入投屏页后生效", Toast.LENGTH_SHORT).show()
                            } else showReconnectHint()
                        }.setNegativeButton("取消", null).show()
                }
            }
        }
        return row
    }

    private fun renderCategories() {
        for ((index, group) in groups.withIndex()) {
            val container = layoutInflater.inflate(R.layout.item_settings_category, categoryColumn, false) as LinearLayout
            val iconView = container.findViewById<IconView>(R.id.category_icon)
            val titleView = container.findViewById<TextView>(R.id.category_title)

            iconView.setIcon(group.icon, color(R.color.text_secondary))
            titleView.setText(group.titleResId)
            container.setOnClickListener { selectCategory(index) }

            categoryColumn.addView(container)
            categoryHolders += CategoryHolder(container, iconView, titleView)
        }
    }

    private fun selectCategory(index: Int) {
        applyCategorySelection(index)
        val target = groupHeaders[index]
        scrollView.post { scrollView.smoothScrollTo(0, target.top) }
    }

    private fun applyCategorySelection(index: Int) {
        selectedCategory = index
        for ((position, holder) in categoryHolders.withIndex()) {
            val selected = position == index
            holder.container.setBackgroundResource(if (selected) R.drawable.bg_category_selected else R.drawable.bg_row)
            holder.titleView.setTextColor(color(if (selected) R.color.text_primary else R.color.text_secondary))
            holder.iconView.setIconTint(color(if (selected) R.color.brand else R.color.text_secondary))
        }
    }

    private fun onListScrolled() {
        handler.removeCallbacks(syncCategory)
        handler.postDelayed(syncCategory, SCROLL_SYNC_DELAY_MS)
    }

    private fun syncCategoryWithScroll() {
        val threshold = scrollView.scrollY + SCROLL_SYNC_OFFSET
        var index = 0
        for ((position, header) in groupHeaders.withIndex()) {
            if (header.top > threshold) break
            index = position
        }
        if (index != selectedCategory) applyCategorySelection(index)
    }

    private fun switchTheme() {
        ThemeStore.setNight(this, !ThemeStore.isNight)
        CarPlaySessionManager.syncTheme()
        recreate()
    }

    private fun showReconnectHint() {
        Toast.makeText(this, "已保存, 连接参数将在下次连接时生效", Toast.LENGTH_SHORT).show()
    }

    private fun loadToggle(title: Int): Boolean = when (title) {
        R.string.settings_auto_connect -> DiPlayPreferences.autoConnect(this)
        R.string.settings_boot_start -> AirPlayPersistence.loadAutoStartOnBoot(this)
        R.string.settings_hevc -> AirPlayPersistence.loadHevcEnabled(this)
        R.string.settings_right_hand_drive -> AirPlayPersistence.loadRightHandDrive(this)
        R.string.settings_fullscreen -> AirPlayPersistence.loadHideTopBar(this)
        R.string.settings_audio_focus -> AirPlayPersistence.loadAudioFocusEnabled(this)
        R.string.settings_advanced_audio -> AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        R.string.settings_report_location -> AirPlayPersistence.loadLocationReportingEnabled(this)
        R.string.settings_geely_bluetooth -> AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
        R.string.settings_geely_audio -> AirPlayPersistence.loadGeelyAudioRouting(this)
        R.string.settings_verbose_log -> AirPlayPersistence.loadDebugLogsEnabled(this)
        else -> error("未绑定的设置项")
    }

    private fun saveToggle(title: Int, value: Boolean) {
        when (title) {
            R.string.settings_auto_connect -> DiPlayPreferences.saveAutoConnect(this, value)
            R.string.settings_boot_start -> AirPlayPersistence.saveAutoStartOnBoot(this, value)
            R.string.settings_hevc -> AirPlayPersistence.saveHevcEnabled(this, value)
            R.string.settings_right_hand_drive -> AirPlayPersistence.saveRightHandDrive(this, value)
            R.string.settings_fullscreen -> AirPlayPersistence.saveHideTopBar(this, value)
            R.string.settings_audio_focus -> AirPlayPersistence.saveAudioFocusEnabled(this, value)
            R.string.settings_advanced_audio -> AirPlayPersistence.saveAdvancedAudioChannelMapping(this, value)
            R.string.settings_report_location -> AirPlayPersistence.saveLocationReportingEnabled(this, value)
            R.string.settings_geely_bluetooth -> AirPlayPersistence.saveGeelyBluetoothConnectionEnabled(this, value)
            R.string.settings_geely_audio -> AirPlayPersistence.saveGeelyAudioRouting(this, value)
            R.string.settings_verbose_log -> {
                AirPlayPersistence.saveDebugLogsEnabled(this, value)
                com.shilapi.xcertplay.diagnostics.DiagnosticLog.verbose = value
            }
            else -> error("未绑定的设置项")
        }
    }

    private fun choiceIndex(title: Int): Int = when (title) {
        R.string.settings_ui_scale -> intArrayOf(85, 100, 115).indexOf(AirPlayPersistence.loadUiScalePercent(this)).coerceAtLeast(0)
        R.string.settings_resolution -> if (AirPlayPersistence.loadDisplayScaleTenths(this) < 10) 2 else 0
        R.string.settings_music_buffer -> MediaAudioBuffer.presets
            .indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)
        R.string.settings_frame_rate -> when (AirPlayPersistence.loadFps(this)) {
            60 -> 1
            20 -> 2
            else -> 0
        }
        R.string.settings_video_output -> if (AirPlayPersistence.loadTextureViewEnabled(this)) 1 else 0
        R.string.settings_media_stream -> AirPlayPersistence.loadMediaAudioChannel(this)
        R.string.settings_nav_stream -> AirPlayPersistence.loadNavigationAudioChannel(this)
        R.string.settings_call_mode -> AirPlayPersistence.loadCallMode(this).coerceIn(0, 1)
        else -> error("未绑定的设置项")
    }

    private fun saveChoice(title: Int, index: Int) {
        when (title) {
            R.string.settings_ui_scale -> AirPlayPersistence.saveUiScalePercent(this, intArrayOf(85, 100, 115)[index])
            R.string.settings_resolution -> AirPlayPersistence.saveDisplayScaleTenths(this, if (index == 2) 7 else 10)
            R.string.settings_music_buffer -> AirPlayPersistence.saveMediaBufferMillis(this, MediaAudioBuffer.presets[index])
            R.string.settings_frame_rate -> AirPlayPersistence.saveFps(this, when (index) {
                1 -> 60
                2 -> 20
                else -> 30
            })
            R.string.settings_video_output -> AirPlayPersistence.saveTextureViewEnabled(this, index == 1)
            R.string.settings_media_stream -> AirPlayPersistence.saveMediaAudioChannel(this, index)
            R.string.settings_nav_stream -> AirPlayPersistence.saveNavigationAudioChannel(this, index)
            R.string.settings_call_mode -> {
                AirPlayPersistence.saveCallMode(this, index)
                AirPlayPersistence.saveCarMicrophoneEnabled(this, index == 1)
            }
            else -> error("未绑定的设置项")
        }
    }

    private fun openLink(action: LinkAction) {
        when (action) {
            LinkAction.HOTSPOT -> openHotspotSettings()
            LinkAction.PERMISSIONS -> openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            LinkAction.BLUETOOTH -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            LinkAction.REPORT -> CarPlaySessionManager.saveReport(this) { file, error ->
                if (file != null) {
                    showMessage("诊断报告", "报告已保存到 ${file.absolutePath}")
                } else {
                    showMessage("保存失败", error?.message ?: "无法写入报告")
                }
            }
            LinkAction.VERSION -> {
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "1.0.1"
                showMessage("版本", "CarPlay $version")
            }
            LinkAction.DEVICE -> showMessage("设备", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid ${android.os.Build.VERSION.RELEASE}")
            LinkAction.LICENSES -> showMessage("开源许可", assets.open("open-source-notices.txt").bufferedReader().use { it.readText() })
        }
    }

    private fun openSystem(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: android.content.ActivityNotFoundException) {
            showMessage("无法打开", "车机未提供该设置入口")
        }
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("确定", null).show()
    }

    /** 点击热点信息时先读系统配置 读到直接保存 读不到再让用户手动填写 */
    private fun openHotspotSettings() {
        if (hotspotReading) return
        hotspotReading = true
        val app = applicationContext
        Thread({
            val result = runCatching { CarHotspotTools.read(app) }.getOrNull()
            runOnUiThread {
                hotspotReading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CarHotspotReadResult.Available -> {
                        HotspotSettings.saveDetected(this, result.configuration)
                        updateHotspotRow()
                        showHotspotDialog(result.configuration, null)
                    }
                    is CarHotspotReadResult.Failure -> showHotspotDialog(null, result.reason)
                    null -> showHotspotDialog(null, CarHotspotFailure.OPERATION_FAILED)
                }
            }
        }, "settings-hotspot-read").start()
    }

    private fun showHotspotDialog(detected: CarHotspotConfiguration?, failure: CarHotspotFailure?) {
        val builder = AlertDialog.Builder(
            this,
            if (ThemeStore.isNight) AlertDialog.THEME_HOLO_DARK else AlertDialog.THEME_HOLO_LIGHT,
        )
        val form = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_hotspot, null)
        val name = form.findViewById<EditText>(R.id.hotspot_name_input)
        val password = form.findViewById<EditText>(R.id.hotspot_password_input)
        val help = form.findViewById<TextView>(R.id.hotspot_dialog_help)
        name.setText(detected?.ssid ?: AirPlayPersistence.loadManualHotspotSsid(this))
        password.setText(detected?.passphrase ?: AirPlayPersistence.loadManualHotspotPassphrase(this))
        help.text = if (failure == null) {
            getString(R.string.settings_hotspot_read_ok)
        } else {
            getString(R.string.settings_hotspot_read_failed, HotspotSettings.failureMessage(failure))
        }
        val dialog = builder.setTitle(R.string.hotspot_manual_title)
            .setView(form)
            .setPositiveButton(R.string.hotspot_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString()
                val passphrase = password.text.toString()
                try {
                    // 读取成功时已保存系统配置 字段没改就不重复写入 避免覆盖频段信息
                    val unchanged = detected != null && ssid == detected.ssid && passphrase == detected.passphrase
                    if (!unchanged) HotspotSettings.saveManual(this, ssid, passphrase)
                    updateHotspotRow()
                    dialog.dismiss()
                    Toast.makeText(this, R.string.hotspot_saved_hint, Toast.LENGTH_LONG).show()
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun updateHotspotRow() {
        val ssid = AirPlayPersistence.loadManualHotspotSsid(this)
        hotspotRow?.updateDescription(
            if (ssid.isBlank()) getString(R.string.settings_hotspot_missing)
            else getString(R.string.settings_hotspot_saved, ssid),
        )
    }

    private fun themeLabel(): String {
        return getString(if (ThemeStore.isNight) R.string.theme_dark else R.string.theme_light)
    }

    @Suppress("DEPRECATION")
    private fun color(colorResId: Int): Int {
        return resources.getColor(colorResId)
    }

    private enum class LinkAction { HOTSPOT, PERMISSIONS, BLUETOOTH, REPORT, VERSION, DEVICE, LICENSES }

    private sealed class SettingsItem {
        object Theme : SettingsItem()
        class Toggle(val titleResId: Int, val descResId: Int) : SettingsItem()
        class Choice(
            val titleResId: Int,
            val descResId: Int,
            val valueArrayResId: Int,
            ) : SettingsItem()
        class Link(val titleResId: Int, val descResId: Int, val action: LinkAction) : SettingsItem()
    }

    private class SettingsGroup(val titleResId: Int, val icon: IconView.Icon, val items: List<SettingsItem>)

    private class CategoryHolder(val container: LinearLayout, val iconView: IconView, val titleView: TextView)

    companion object {
        private const val EXTRA_OPEN_HOTSPOT = "open_hotspot"
        private const val SCROLL_SYNC_DELAY_MS = 120L
        private const val SCROLL_SYNC_OFFSET = 60

        fun hotspotIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_OPEN_HOTSPOT, true)
    }
}
