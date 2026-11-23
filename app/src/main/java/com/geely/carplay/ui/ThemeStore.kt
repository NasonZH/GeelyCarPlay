package com.geely.carplay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.LongSparseArray

/**
 * 主题状态：默认浅色，持久化到 SharedPreferences
 * 切换后清掉 Resources 的 drawable 缓存并重建界面，不需要重启进程
 */
object ThemeStore {

    private const val PREFS_NAME = "ui_prefs"
    private const val KEY_NIGHT = "night_theme"

    var isNight: Boolean = false
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isNight = prefs.getBoolean(KEY_NIGHT, false)
        applyTo(context.resources)
    }

    @SuppressLint("ApplySharedPref")
    fun setNight(context: Context, night: Boolean) {
        isNight = night
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NIGHT, night).commit()
        applyTo(context.resources)
        clearDrawableCache(context.resources)
    }

    private fun applyTo(resources: Resources) {
        val targetMode = if (isNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(resources.configuration)
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == targetMode) return

        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or targetMode
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * API18 的 Resources 用 mDrawableCache 缓存已解析的 drawable 且键里不含 night 配置
     * 不清掉会出现文字变色、卡片背景没变的情况；没有公开接口, 只能反射清理
     */
    private fun clearDrawableCache(resources: Resources) {
        for (fieldName in CACHE_FIELDS) {
            try {
                val field = Resources::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                val cache = field.get(resources) as? LongSparseArray<*> ?: continue
                cache.clear()
            } catch (ignored: Throwable) {
                // 字段不存在或不可访问时忽略, 由调用方决定是否仍需重建进程
            }
        }
    }

    private val CACHE_FIELDS = arrayOf("mDrawableCache", "mColorStateListCache")
}
