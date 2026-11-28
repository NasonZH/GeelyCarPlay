package com.geely.carplay.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import com.geely.carplay.ui.widget.LoadingTrackView
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * 启动页：环境检测动画结束后进入首页
 */
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private val enterHome = Runnable {
        if (CarPlaySessionManager.isExiting) return@Runnable
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private val markReady = Runnable {
        findViewById<TextView>(R.id.splash_status).setText(R.string.splash_ready)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        playEntrance()
        prepareHotspot()
        findViewById<LoadingTrackView>(R.id.splash_progress).animateProgress(SPLASH_DURATION_MS)
        handler.postDelayed(markReady, SPLASH_DURATION_MS - READY_LEAD_MS)
        handler.postDelayed(enterHome, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(enterHome)
        handler.removeCallbacks(markReady)
        super.onDestroy()
    }

    /** 启动页后台准备热点 车机热点与 Wi-Fi 客户端互斥 */
    private fun prepareHotspot() {
        val appContext = applicationContext
        Thread({
            runCatching {
                if (!AirPlayPersistence.loadWirelessEnabled(appContext)) return@runCatching
                HotspotSettings.prepare(appContext) { message ->
                    Log.i("GeelyCarPlay", "启动热点: $message")
                }
            }.onFailure {
                Log.w("GeelyCarPlay", "启动热点失败: ${it.message}")
            }
        }, "carplay-splash-hotspot").start()
    }

    private fun playEntrance() {
        val mark = findViewById<View>(R.id.splash_mark)
        val title = findViewById<View>(R.id.splash_title)
        val tagline = findViewById<View>(R.id.splash_tagline)

        for ((index, view) in arrayOf(mark, title, tagline).withIndex()) {
            view.alpha = 0f
            view.translationY = 18f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 110L)
                .setDuration(420L)
                .start()
        }
    }

    private companion object {
        const val SPLASH_DURATION_MS = 1800L
        const val READY_LEAD_MS = 320L
    }
}
