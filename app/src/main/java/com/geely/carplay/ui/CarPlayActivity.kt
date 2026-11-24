package com.geely.carplay.ui

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** 真实视频 Surface 随页面创建和销毁, 会话由后台服务持有 */
class CarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var video: TextureView
    private var surface: Surface? = null
    private var frameAvailable = false
    private var sawConnected = false
    private val observer: () -> Unit = { onSessionChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carplay)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!com.shilapi.xcertplay.AirPlayPersistence.loadHideTopBar(this)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        video = findViewById(R.id.carplay_video)
        video.surfaceTextureListener = this
        video.setOnTouchListener { _, event ->
            CarPlaySessionManager.sendTouch(event, video.width, video.height)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) video.performClick()
            true
        }
        CarPlaySessionManager.noteScreenCreated()
    }

    override fun onResume() {
        super.onResume()
        if (CarPlaySessionManager.isExiting) {
            finish()
            return
        }
        CarPlaySessionManager.observe(observer)
    }

    override fun onPause() {
        CarPlaySessionManager.removeObserver(observer)
        super.onPause()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        frameAvailable = false
        renderStatus()
        CarPlaySessionManager.noteSurfaceReady()
        val value = Surface(texture)
        surface = value
        CarPlaySessionManager.attachSurface(value)
        val parent = video.parent as FrameLayout
        val layout = CarPlayVideoLayout.fit(CarPlaySessionManager.videoWidth, CarPlaySessionManager.videoHeight, parent.width, parent.height)
        video.layoutParams = FrameLayout.LayoutParams(layout.width.toInt(), layout.height.toInt(), android.view.Gravity.CENTER)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        CarPlaySessionManager.noteFirstTextureUpdated()
        if (frameAvailable) return
        frameAvailable = true
        renderStatus()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }

    override fun onDestroy() {
        releaseSurface()
        super.onDestroy()
    }

    private fun releaseSurface() {
        val previous = surface ?: return
        surface = null
        frameAvailable = false
        CarPlaySessionManager.detachSurface(previous)
        previous.release()
    }

    /**
     * 会话断开后回到首页 只有本页确认过连接成功才触发
     * 未连接时手动进入投屏页查看 以及连接失败时 都停留在本页
     */
    private fun onSessionChanged() {
        if (CarPlaySessionManager.isConnected) {
            sawConnected = true
        } else if (sawConnected) {
            returnToHome()
            return
        }
        renderStatus()
    }

    private fun returnToHome() {
        if (isFinishing) return
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun renderStatus() {
        findViewById<TextView>(R.id.carplay_status).apply {
            text = if (CarPlaySessionManager.isConnected && !frameAvailable) {
                getString(R.string.carplay_waiting_video)
            } else CarPlaySessionManager.statusText
            visibility = if (CarPlaySessionManager.isConnected && frameAvailable) android.view.View.GONE else android.view.View.VISIBLE
        }
    }
}
