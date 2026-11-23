package com.geely.carplay.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.geely.carplay.ui.R

/**
 * 细长加载轨道：浅色轨道加品牌色进度条，用于启动页的环境检测提示
 */
class LoadingTrackView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        @Suppress("DEPRECATION")
        val brand = context.resources.getColor(R.color.brand)
        fillPaint.color = brand
        trackPaint.color = brand and 0x33FFFFFF
    }

    private val trackRect = RectF()
    private var progress = 0f

    fun animateProgress(durationMs: Long) {
        ValueAnimator.ofFloat(progress, 1f).apply {
            this.duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val filledWidth = width * progress
        if (filledWidth <= 0f) return

        trackRect.set(0f, 0f, filledWidth, height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, fillPaint)
    }
}
