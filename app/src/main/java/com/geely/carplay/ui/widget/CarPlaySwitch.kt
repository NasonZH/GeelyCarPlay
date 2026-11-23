package com.geely.carplay.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.geely.carplay.ui.R

/**
 * 车机风格开关：轨道加滑块，点击由所在设置行代理，避免小触点误触
 */
class CarPlaySwitch(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val trackRect = RectF()
    private var fraction = 0f

    private val trackOffColor: Int
    private val trackOnColor: Int

    init {
        @Suppress("DEPRECATION")
        trackOffColor = context.resources.getColor(R.color.stroke)
        @Suppress("DEPRECATION")
        trackOnColor = context.resources.getColor(R.color.brand)
    }

    var isChecked: Boolean = false
        private set

    fun updateChecked(checked: Boolean) {
        if (checked == isChecked) return
        isChecked = checked
        animateKnob(if (checked) 1f else 0f)
    }

    private fun animateKnob(target: Float) {
        ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        trackPaint.color = blend(trackOffColor, trackOnColor, fraction)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val knobRadius = radius - KNOB_INSET * height
        val knobInset = radius
        val knobCenterX = knobInset + (width - 2 * knobInset) * fraction
        canvas.drawCircle(knobCenterX, radius, knobRadius, knobPaint)
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val red = Color.red(from) + (Color.red(to) - Color.red(from)) * ratio
        val green = Color.green(from) + (Color.green(to) - Color.green(from)) * ratio
        val blue = Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio
        return Color.rgb(red.toInt(), green.toInt(), blue.toInt())
    }

    private companion object {
        const val KNOB_INSET = 0.10f
    }
}
