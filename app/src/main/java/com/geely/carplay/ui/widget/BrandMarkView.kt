package com.geely.carplay.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.geely.carplay.ui.R

/**
 * 顶栏品牌标记 自绘绘制 任意密度下边缘都清晰
 * 由品牌色圆角底 白色 C 形弧 半透明播放三角组成
 */
class BrandMarkView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66FFFFFF
    }

    private val rect = RectF()
    private val path = Path()

    private val brandColor = color(R.color.brand)
    private val brandDeepColor = color(R.color.brand_deep)

    @Suppress("DEPRECATION")
    private fun color(resId: Int): Int = context.resources.getColor(resId)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val size = minOf(width, height).toFloat()
        bodyPaint.shader = LinearGradient(0f, 0f, size, size, brandColor, brandDeepColor, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val corner = size * 0.24f
        rect.set(0f, 0f, size, size)
        canvas.drawRoundRect(rect, corner, corner, bodyPaint)

        val center = size / 2f
        val ringRadius = size * 0.29f
        ringPaint.strokeWidth = size * 0.075f
        rect.set(center - ringRadius, center - ringRadius, center + ringRadius, center + ringRadius)
        canvas.drawArc(rect, -55f, 215f, false, ringPaint)

        val playSize = size * 0.18f
        path.reset()
        path.moveTo(center - playSize * 0.45f, center - playSize)
        path.lineTo(center + playSize * 0.78f, center)
        path.lineTo(center - playSize * 0.45f, center + playSize)
        path.close()
        canvas.drawPath(path, playPaint)
    }
}
