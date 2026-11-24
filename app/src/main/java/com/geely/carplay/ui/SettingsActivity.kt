package com.geely.carplay.ui

import android.app.AlertDialog
import android.app.Activity
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
import com.shilapi.xcertplay.orchestration.MfiTarget
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

    private var selectedCategory = 0

    private val syncCategory = Runnable { syncCategoryWithScroll() }

    private val groups = listOf(
        SettingsGroup(
            R.string.settings_cat_connection,
            IconView.Icon.CAST,
            listOf(
                SettingsItem.Toggle(R.string.settings_auto_connect, R.string.settings_auto_connect_desc),
                SettingsItem.Toggle(R.string.settings_boot_start, R.string.settings_boot_start_desc),
                SettingsItem.Link(R.string.settings_pick_iphone, R.string.settings_pick_iphone_desc, LinkAction.CONNECTION),
                SettingsItem.Toggle(R.string.settings_geely_bluetooth, R.string.settings_geely_bluetooth_desc),
                SettingsItem.Link(R.string.settings_authentication, R.string.settings_authentication_desc, LinkAction.AUTHENTICATION),
                SettingsItem.Link(R.string.settings_open_connection, R.string.settings_open_connection_desc, LinkAction.CONNECTION),
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
        renderCategories()
        applyCategorySelection(0)
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
                showReconnectHint()
            }.also {
                if (item.titleResId == R.string.settings_hevc && !com.shilapi.xcertplay.media.H52VideoCapabilities.supportsHevc()) {
                    row.isEnabled = false
                    row.updateDescription("车机没有可用的 HEVC 解码器, 使用 H.264")
                }
            }
            is SettingsItem.Link -> {
                row.bindLink(item.titleResId, item.descResId) { openLink(item.action) }
                if (item.titleResId == R.string.settings_pick_iphone) row.updateDescription(DiPlayPreferences.phoneName(this))
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
                            showReconnectHint()
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
        // 清掉 drawable 缓存后重建界面即可换肤, 不需要重启进程
        ThemeStore.setNight(this, !ThemeStore.isNight)
        CarPlaySessionManager.syncTheme()
        val intent = Intent(this, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
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
        else -> error("未绑定的设置项")
    }

    private fun saveToggle(title: Int, value: Boolean) {
        when (title) {
            R.string.settings_auto_connect -> DiPlayPreferences.saveAutoConnect(this, value)
            R.string.settings_boot_start -> AirPlayPersistence.saveAutoStartOnBoot(this, value)
            R.string.settings_hevc -> AirPlayPersistence.saveHevcEnabled(this, value)
            R.string.settings_right_hand_drive -> AirPlayPersistence.saveRightHandDrive(this, value)
            R.string.settings_fullscreen -> {
                AirPlayPersistence.saveHideTopBar(this, value)
                AirPlayPersistence.saveHideBottomBar(this, value)
            }
            R.string.settings_audio_focus -> AirPlayPersistence.saveAudioFocusEnabled(this, value)
            R.string.settings_advanced_audio -> AirPlayPersistence.saveAdvancedAudioChannelMapping(this, value)
            R.string.settings_report_location -> AirPlayPersistence.saveLocationReportingEnabled(this, value)
            R.string.settings_geely_bluetooth -> AirPlayPersistence.saveGeelyBluetoothConnectionEnabled(this, value)
            R.string.settings_geely_audio -> AirPlayPersistence.saveGeelyAudioRouting(this, value)
            else -> error("未绑定的设置项")
        }
    }

    private fun choiceIndex(title: Int): Int = when (title) {
        R.string.settings_ui_scale -> intArrayOf(85, 100, 115).indexOf(AirPlayPersistence.loadUiScalePercent(this)).coerceAtLeast(0)
        R.string.settings_resolution -> if (AirPlayPersistence.loadDisplayScaleTenths(this) < 10) 2 else 0
        R.string.settings_music_buffer -> MediaAudioBuffer.presets
            .indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)
        R.string.settings_frame_rate -> if (AirPlayPersistence.loadFps(this) == 60) 1 else 0
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
            R.string.settings_frame_rate -> AirPlayPersistence.saveFps(this, if (index == 1) 60 else 30)
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
            LinkAction.CONNECTION -> startActivity(ConnectActivity.wirelessIntent(this))
            LinkAction.PERMISSIONS -> openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            LinkAction.BLUETOOTH -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            LinkAction.AUTHENTICATION -> chooseAuthentication()
            LinkAction.REPORT -> {
                try {
                    val file = CarPlaySessionManager.saveReport(this)
                    showMessage("诊断报告", "报告已保存到 ${file.absolutePath}")
                } catch (error: Exception) {
                    showMessage("保存失败", error.message ?: "无法写入报告")
                }
            }
            LinkAction.VERSION -> showMessage("版本", "Geely CarPlay 1.0.0\n已接入 DiPlay 处理层")
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

    private fun chooseAuthentication() {
        val targets = MfiTarget.entries.toTypedArray()
        val labels = arrayOf("本地认证资产", "CH341 USB 认证设备", "I2C 认证设备", "远程认证服务")
        AlertDialog.Builder(this).setTitle("MFi 认证方式")
            .setSingleChoiceItems(labels, targets.indexOf(AirPlayPersistence.loadMfiTarget(this))) { dialog, index ->
                AirPlayPersistence.saveMfiTarget(this, targets[index])
                dialog.dismiss()
                when (targets[index]) {
                    MfiTarget.I2C -> editAuthenticationValue("I2C 设备路径", AirPlayPersistence.loadMfiI2cPath(this)) {
                        AirPlayPersistence.saveMfiI2cPath(this, it)
                    }
                    MfiTarget.REMOTE -> editAuthenticationValue("远程认证地址", AirPlayPersistence.loadRemoteMfiServer(this)) { server ->
                        AirPlayPersistence.saveRemoteMfiServer(this, server)
                        editAuthenticationValue("远程认证令牌", AirPlayPersistence.loadRemoteMfiToken(this)) {
                            AirPlayPersistence.saveRemoteMfiToken(this, it)
                        }
                    }
                    MfiTarget.LOCAL -> showMessage("本地认证", "当前应用已内置本地 MFi 认证资产, 重新连接后生效")
                    MfiTarget.USB_CH341 -> showReconnectHint()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun editAuthenticationValue(title: String, value: String, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(value)
            isSingleLine = true
            if (title.contains("令牌")) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()); showReconnectHint() }
            .setNegativeButton("取消", null).show()
    }

    private fun themeLabel(): String {
        return getString(if (ThemeStore.isNight) R.string.theme_dark else R.string.theme_light)
    }

    @Suppress("DEPRECATION")
    private fun color(colorResId: Int): Int {
        return resources.getColor(colorResId)
    }

    private enum class LinkAction { CONNECTION, PERMISSIONS, BLUETOOTH, AUTHENTICATION, REPORT, VERSION, DEVICE, LICENSES }

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

    private companion object {
        const val SCROLL_SYNC_DELAY_MS = 120L
        const val SCROLL_SYNC_OFFSET = 60
    }
}
