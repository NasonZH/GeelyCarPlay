package com.geely.carplay.ui

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** 视频输出随页面创建和销毁 会话由后台服务持有 */
class CarPlayActivity : Activity() {
    private lateinit var videoContainer: FrameLayout
    private lateinit var video: View
    private var surface: Surface? = null
    private var ownsSurface = false
    private var usingTextureView = false
    private var fallbackUsed = false
    private var textureUpdated = false
    private var frameAvailable = false
    private var sawConnected = false
    private val observer: () -> Unit = { onSessionChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.load(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carplay)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!AirPlayPersistence.loadHideTopBar(this)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        videoContainer = findViewById(R.id.carplay_video_container)
        CarPlaySessionManager.noteScreenCreated()
        if (AirPlayPersistence.loadTextureViewEnabled(this)) {
            showTextureView()
        } else {
            try {
                showSurfaceView()
            } catch (error: RuntimeException) {
                fallbackToTextureView("SurfaceView 创建失败 ${error.javaClass.simpleName}")
            }
        }
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        CarPlaySessionManager.noteScreenResumed(true)
        if (CarPlaySessionManager.isExiting) {
            finish()
            return
        }
        CarPlaySessionManager.observe(observer)
    }

    override fun onPause() {
        CarPlaySessionManager.noteScreenResumed(false)
        CarPlaySessionManager.removeObserver(observer)
        super.onPause()
    }

    private fun showSurfaceView() {
        val view = SurfaceView(this)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (video === view) bindSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (video === view) fitVideo()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (video === view) releaseSurface()
            }
        })
        installVideoView(view)
    }

    private fun showTextureView() {
        val view = TextureView(this)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (video === view) bindSurface(Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                if (video === view) fitVideo()
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (video !== view) return
                textureUpdated = true
                renderStatus()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (video === view) releaseSurface()
                return true
            }
        }
        installVideoView(view)
    }

    private fun installVideoView(view: View) {
        releaseSurface()
        videoContainer.removeAllViews()
        video = view
        usingTextureView = view is TextureView
        frameAvailable = false
        textureUpdated = false
        view.setOnTouchListener { _, event ->
            CarPlaySessionManager.sendTouch(event, view.width, view.height)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
            true
        }
        videoContainer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER,
        ))
        CarPlaySessionManager.noteVideoOutput(if (usingTextureView) "使用 TextureView" else "使用 SurfaceView")
    }

    private fun bindSurface(value: Surface) {
        if (surface === value) return
        if (!value.isValid) {
            if (usingTextureView) value.release()
            requestFallback("视频输出 Surface 无效")
            return
        }
        releaseSurface()
        surface = value
        ownsSurface = usingTextureView
        frameAvailable = false
        textureUpdated = false
        CarPlaySessionManager.noteSurfaceReady()
        try {
            CarPlaySessionManager.attachSurface(value) { reason ->
                if (surface === value) requestFallback(reason)
            }
        } catch (error: RuntimeException) {
            requestFallback("视频输出绑定失败 ${error.javaClass.simpleName}")
        }
        fitVideo()
        renderStatus()
    }

    private fun fitVideo() {
        if (videoContainer.width <= 0 || videoContainer.height <= 0) return
        val layout = CarPlayVideoLayout.fit(
            CarPlaySessionManager.videoWidth, CarPlaySessionManager.videoHeight,
            videoContainer.width, videoContainer.height,
        )
        if (video.layoutParams.width == layout.width.toInt() && video.layoutParams.height == layout.height.toInt()) return
        video.layoutParams = FrameLayout.LayoutParams(
            layout.width.toInt(), layout.height.toInt(), android.view.Gravity.CENTER,
        )
    }

    private fun requestFallback(reason: String) {
        if (usingTextureView || fallbackUsed) {
            CarPlaySessionManager.noteVideoOutput("兼容输出仍有错误: $reason")
            return
        }
        val failedView = video
        val failedSurface = surface
        video.post {
            if (video === failedView && surface === failedSurface) fallbackToTextureView(reason)
        }
    }

    private fun fallbackToTextureView(reason: String) {
        if (usingTextureView || fallbackUsed || isFinishing || isDestroyed) return
        fallbackUsed = true
        CarPlaySessionManager.noteVideoOutput("回退 TextureView: $reason")
        showTextureView()
        renderStatus()
    }

    override fun onDestroy() {
        releaseSurface()
        super.onDestroy()
    }

    private fun releaseSurface() {
        val previous = surface ?: return
        val releaseOwned = ownsSurface
        surface = null
        ownsSurface = false
        frameAvailable = false
        textureUpdated = false
        CarPlaySessionManager.detachSurface(previous)
        // SurfaceView 的输出由 SurfaceHolder 释放 这里只释放自己创建的包装对象
        if (releaseOwned) previous.release()
    }

    /** 会话结束后回首页 保留未连接时手动查看投屏页的行为 */
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
        if (!frameAvailable && surface != null && CarPlaySessionManager.hasSurfaceFrameRendered() &&
            (!usingTextureView || textureUpdated)) {
            frameAvailable = true
            if (usingTextureView) CarPlaySessionManager.noteFirstTextureUpdated()
            else CarPlaySessionManager.noteSurfaceViewFrameSubmitted()
        }
        findViewById<TextView>(R.id.carplay_status).apply {
            text = if (CarPlaySessionManager.isConnected && !frameAvailable) {
                getString(R.string.carplay_waiting_video)
            } else CarPlaySessionManager.statusText
            visibility = if (CarPlaySessionManager.isConnected && frameAvailable) View.GONE else View.VISIBLE
        }
    }
}
