package com.geely.carplay.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.geely.carplay.ui.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * 线性图标：所有图形按 24x24 视口描述后等比缩放，避免引入位图资源
 */
class IconView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val path = Path()
    private val shape = RectF()

    private var icon = Icon.PHONE

    init {
        if (attrs != null) {
            val typed = context.obtainStyledAttributes(attrs, R.styleable.IconView)
            icon = Icon.values()[typed.getInt(R.styleable.IconView_icon, 0)]
            val tint = typed.getColor(R.styleable.IconView_iconTint, 0xFFFFFFFF.toInt())
            strokePaint.color = tint
            fillPaint.color = tint
            typed.recycle()
        }
    }

    fun setIcon(newIcon: Icon, tintColor: Int) {
        icon = newIcon
        applyTint(tintColor)
    }

    fun setIconTint(tintColor: Int) {
        applyTint(tintColor)
    }

    private fun applyTint(tintColor: Int) {
        strokePaint.color = tintColor
        fillPaint.color = tintColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        buildPath()
        val scale = size / VIEWPORT
        canvas.save()
        canvas.translate(Math.round((width - size) / 2f).toFloat(), Math.round((height - size) / 2f).toFloat())
        canvas.scale(scale, scale)
        canvas.drawPath(path, if (icon == Icon.ARROW_LEFT) fillPaint else strokePaint)
        drawSolidMarks(canvas)
        canvas.restore()
    }

    private fun buildPath() {
        path.reset()
        when (icon) {
            Icon.PHONE -> {
                shape.set(7f, 2.5f, 17f, 21.5f)
                path.addRoundRect(shape, 2.2f, 2.2f, Path.Direction.CW)
                path.moveTo(10.6f, 18.6f)
                path.lineTo(13.4f, 18.6f)
            }
            Icon.USB -> {
                // 三叉造型: 主干 + 左右分支, 端点用圆环
                path.moveTo(12f, 8.2f)
                path.lineTo(12f, 15.2f)
                path.addCircle(12f, 5f, 1.9f, Path.Direction.CW)
                path.moveTo(12f, 12.4f)
                path.lineTo(7.2f, 9f)
                path.addCircle(7.2f, 9f, 1.7f, Path.Direction.CW)
                path.moveTo(12f, 10.2f)
                path.lineTo(16.8f, 6.8f)
                path.addCircle(16.8f, 6.8f, 1.7f, Path.Direction.CW)
            }
            Icon.GEAR -> {
                path.addCircle(12f, 12f, 3.6f, Path.Direction.CW)
                path.addCircle(12f, 12f, 7.2f, Path.Direction.CW)
                for (index in 0 until 8) {
                    val angle = Math.toRadians((index * 45).toDouble())
                    val nearX = 12f + (cos(angle) * 7.2f).toFloat()
                    val nearY = 12f + (sin(angle) * 7.2f).toFloat()
                    val farX = 12f + (cos(angle) * 9.6f).toFloat()
                    val farY = 12f + (sin(angle) * 9.6f).toFloat()
                    path.moveTo(nearX, nearY)
                    path.lineTo(farX, farY)
                }
            }
            Icon.WIFI -> {
                for (radius in floatArrayOf(11.5f, 7.5f, 3.8f)) {
                    shape.set(12f - radius, 20.5f - radius, 12f + radius, 20.5f + radius)
                    path.addArc(shape, 225f, 90f)
                }
            }
            Icon.BLUETOOTH -> {
                path.moveTo(8.2f, 7.6f)
                path.lineTo(15.8f, 16.4f)
                path.lineTo(12f, 20.2f)
                path.lineTo(12f, 3.8f)
                path.lineTo(15.8f, 7.6f)
                path.lineTo(8.2f, 16.4f)
            }
            Icon.CAST -> {
                shape.set(2.6f, 4f, 21.4f, 16.6f)
                path.addRoundRect(shape, 2f, 2f, Path.Direction.CW)
                shape.set(3f - 6.5f, 21f - 6.5f, 3f + 6.5f, 21f + 6.5f)
                path.addArc(shape, 270f, 90f)
                shape.set(3f - 10.5f, 21f - 10.5f, 3f + 10.5f, 21f + 10.5f)
                path.addArc(shape, 270f, 90f)
            }
            Icon.DISPLAY -> {
                shape.set(3f, 4f, 21f, 16.4f)
                path.addRoundRect(shape, 2f, 2f, Path.Direction.CW)
                path.moveTo(12f, 16.4f)
                path.lineTo(12f, 20f)
                path.moveTo(8.2f, 20f)
                path.lineTo(15.8f, 20f)
            }
            Icon.VOLUME -> {
                path.moveTo(4f, 9.6f)
                path.lineTo(7.6f, 9.6f)
                path.lineTo(11.6f, 5.6f)
                path.lineTo(11.6f, 18.4f)
                path.lineTo(7.6f, 14.4f)
                path.lineTo(4f, 14.4f)
                path.close()
                shape.set(11.6f - 4.6f, 12f - 4.6f, 11.6f + 4.6f, 12f + 4.6f)
                path.addArc(shape, -55f, 110f)
                shape.set(11.6f - 8.2f, 12f - 8.2f, 11.6f + 8.2f, 12f + 8.2f)
                path.addArc(shape, -55f, 110f)
            }
            Icon.LOCATION -> {
                path.addCircle(12f, 9.4f, 5.2f, Path.Direction.CW)
                path.moveTo(7.6f, 13.2f)
                path.lineTo(12f, 21.2f)
                path.lineTo(16.4f, 13.2f)
            }
            Icon.FILE -> {
                shape.set(5f, 3f, 19f, 21f)
                path.addRoundRect(shape, 2.2f, 2.2f, Path.Direction.CW)
                path.moveTo(8.6f, 8.6f)
                path.lineTo(15.4f, 8.6f)
                path.moveTo(8.6f, 12.4f)
                path.lineTo(15.4f, 12.4f)
                path.moveTo(8.6f, 16.2f)
                path.lineTo(12.8f, 16.2f)
            }
            Icon.INFO -> {
                path.addCircle(12f, 12f, 8.8f, Path.Direction.CW)
                path.moveTo(12f, 11.2f)
                path.lineTo(12f, 16.4f)
            }
            Icon.THEME -> {
                path.addCircle(12f, 12f, 4.6f, Path.Direction.CW)
                for (index in 0 until 8) {
                    val angle = Math.toRadians((index * 45).toDouble())
                    path.moveTo(12f + (cos(angle) * 7f).toFloat(), 12f + (sin(angle) * 7f).toFloat())
                    path.lineTo(12f + (cos(angle) * 9.6f).toFloat(), 12f + (sin(angle) * 9.6f).toFloat())
                }
            }
            Icon.CHECK -> {
                path.moveTo(5f, 12.4f)
                path.lineTo(10f, 17.4f)
                path.lineTo(19f, 6.8f)
            }
            Icon.ARROW_RIGHT -> {
                path.moveTo(9f, 5f)
                path.lineTo(16f, 12f)
                path.lineTo(9f, 19f)
            }
            Icon.LOCK -> {
                shape.set(5f, 10.4f, 19f, 21f)
                path.addRoundRect(shape, 2.2f, 2.2f, Path.Direction.CW)
                shape.set(7.8f, 4.4f, 16.2f, 12.8f)
                path.addArc(shape, 180f, 180f)
            }
            Icon.SLIDERS -> {
                path.moveTo(4f, 8.2f)
                path.lineTo(20f, 8.2f)
                path.moveTo(4f, 15.8f)
                path.lineTo(20f, 15.8f)
            }
            Icon.ARROW_LEFT -> {
                path.moveTo(2.4f, 12f)
                path.lineTo(10.6f, 4.6f)
                path.lineTo(10.6f, 9.4f)
                path.lineTo(21f, 9.4f)
                path.lineTo(21f, 14.6f)
                path.lineTo(10.6f, 14.6f)
                path.lineTo(10.6f, 19.4f)
                path.close()
            }
            Icon.REFRESH -> {
                shape.set(4.2f, 4.2f, 19.8f, 19.8f)
                path.addArc(shape, -50f, 290f)
                path.moveTo(16.4f, 3.2f)
                path.lineTo(19.6f, 6.6f)
                path.lineTo(15.4f, 8.2f)
            }
            Icon.PLAY -> {
                path.moveTo(8.4f, 5.2f)
                path.lineTo(19f, 12f)
                path.lineTo(8.4f, 18.8f)
                path.close()
            }
            Icon.LINK -> {
                // 左右两个相对的 C 形, 中间横杠相连
                shape.set(3.6f, 6.6f, 13.6f, 17.4f)
                path.addArc(shape, 90f, 180f)
                shape.set(10.4f, 6.6f, 20.4f, 17.4f)
                path.addArc(shape, 270f, 180f)
                path.moveTo(10.4f, 12f)
                path.lineTo(13.6f, 12f)
            }
            Icon.EXIT -> {
                path.moveTo(11.6f, 3.8f)
                path.lineTo(4f, 3.8f)
                path.lineTo(4f, 20.2f)
                path.lineTo(11.6f, 20.2f)
                path.moveTo(9.4f, 12f)
                path.lineTo(20.6f, 12f)
                path.moveTo(16.6f, 8f)
                path.lineTo(20.6f, 12f)
                path.lineTo(16.6f, 16f)
            }
            Icon.POWER -> {
                shape.set(4.4f, 4.4f, 19.6f, 19.6f)
                path.addArc(shape, -60f, 300f)
                path.moveTo(12f, 3.2f)
                path.lineTo(12f, 11.2f)
            }
        }
    }

    private fun drawSolidMarks(canvas: Canvas) {
        when (icon) {
            Icon.USB -> canvas.drawCircle(12f, 18.4f, 2.6f, fillPaint)
            Icon.WIFI -> canvas.drawCircle(12f, 19.4f, 1.5f, fillPaint)
            Icon.INFO -> canvas.drawCircle(12f, 7.8f, 1.2f, fillPaint)
            Icon.SLIDERS -> {
                canvas.drawCircle(9f, 8.2f, 2.2f, fillPaint)
                canvas.drawCircle(15f, 15.8f, 2.2f, fillPaint)
            }
            else -> Unit
        }
    }

    enum class Icon {
        PHONE, USB, GEAR, WIFI, BLUETOOTH, CAST, DISPLAY, VOLUME,
        LOCATION, FILE, INFO, THEME, CHECK, ARROW_RIGHT, LOCK, SLIDERS,
        ARROW_LEFT, REFRESH, PLAY, POWER, LINK, EXIT,
    }

    private companion object {
        const val VIEWPORT = 24f
        const val STROKE_WIDTH = 2f
    }
}
