package com.geely.carplay.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.geely.carplay.ui.R

/**
 * 首页连接示意图 左侧车机屏幕 右侧 iPhone 中间用连线表示连接状态
 * 未连接为灰色虚线 已连接为品牌色实线并在中点显示对勾
 */
class ConnectDiagramView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.surface_high)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = color(R.color.stroke)
    }
    private val monitorScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.preview_dock)
    }
    private val phoneScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.preview_screen)
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.preview_tile)
    }
    private val activeTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.brand)
    }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(R.color.preview_dock)
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.4f)
        color = Color.WHITE
    }

    private val rect = RectF()
    private val screen = RectF()
    private val path = Path()

    private var connected = false

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun color(resId: Int): Int = context.resources.getColor(resId)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val layout = measureLayout()
        val centerY = height * 0.5f
        val top = centerY - layout.deviceHeight * 0.5f
        val bottom = centerY + layout.deviceHeight * 0.5f

        outlinePaint.color = color(if (connected) R.color.brand else R.color.stroke)
        outlinePaint.strokeWidth = dp(if (connected) 1.5f else 1f)

        val phoneLeft = layout.startX + layout.monitorWidth + layout.gap
        drawMonitor(canvas, layout.startX, top, layout.startX + layout.monitorWidth, bottom)
        drawPhone(canvas, phoneLeft, top, phoneLeft + layout.phoneWidth, bottom)
        drawLink(canvas, layout.startX + layout.monitorWidth, phoneLeft, centerY)
    }

    private class DiagramLayout(
        val startX: Float,
        val deviceHeight: Float,
        val monitorWidth: Float,
        val phoneWidth: Float,
        val gap: Float,
    )

    private fun measureLayout(): DiagramLayout {
        val maxHeight = height * 0.46f
        val maxWidth = width * 0.84f
        val scale = MONITOR_RATIO + GAP_RATIO + PHONE_RATIO
        var deviceHeight = maxHeight
        if (deviceHeight * scale > maxWidth) {
            deviceHeight = maxWidth / scale
        }
        val monitorWidth = deviceHeight * MONITOR_RATIO
        val phoneWidth = deviceHeight * PHONE_RATIO
        val gap = deviceHeight * GAP_RATIO
        val totalWidth = monitorWidth + gap + phoneWidth
        return DiagramLayout((width - totalWidth) / 2f, deviceHeight, monitorWidth, phoneWidth, gap)
    }

    private fun drawMonitor(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val heightPx = bottom - top
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, heightPx * 0.08f, heightPx * 0.08f, bodyPaint)
        canvas.drawRoundRect(rect, heightPx * 0.08f, heightPx * 0.08f, outlinePaint)

        val inset = heightPx * 0.08f
        screen.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(screen, heightPx * 0.05f, heightPx * 0.05f, monitorScreenPaint)
        drawAppGrid(canvas, screen, topFactor = 0.08f, bottomReserve = 0.24f, highlightFirst = true)
        drawDock(canvas, screen)
    }

    private fun drawPhone(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val heightPx = bottom - top
        val bodyRadius = heightPx * 0.16f
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, bodyPaint)
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, outlinePaint)

        val inset = heightPx * 0.055f
        screen.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(screen, bodyRadius * 0.7f, bodyRadius * 0.7f, phoneScreenPaint)
        drawAppGrid(canvas, screen, topFactor = 0.12f, bottomReserve = 0.20f, highlightFirst = false)

        val notchWidth = (right - left) * 0.36f
        val notchHeight = heightPx * 0.045f
        val notchTop = screen.top + heightPx * 0.045f
        rect.set(
            (left + right) / 2f - notchWidth / 2f,
            notchTop,
            (left + right) / 2f + notchWidth / 2f,
            notchTop + notchHeight,
        )
        canvas.drawRoundRect(rect, notchHeight / 2f, notchHeight / 2f, notchPaint)

        val homeWidth = (right - left) * 0.30f
        val homeHeight = heightPx * 0.018f
        val homeBottom = screen.bottom - heightPx * 0.035f
        rect.set(
            (left + right) / 2f - homeWidth / 2f,
            homeBottom - homeHeight,
            (left + right) / 2f + homeWidth / 2f,
            homeBottom,
        )
        canvas.drawRoundRect(rect, homeHeight / 2f, homeHeight / 2f, notchPaint)
    }

    /** 屏幕内的应用网格 按宽高双约束取较小值 避免在窄屏溢出 */
    private fun drawAppGrid(
        canvas: Canvas,
        screen: RectF,
        topFactor: Float,
        bottomReserve: Float,
        highlightFirst: Boolean,
    ) {
        val gap = screen.width() * 0.07f
        val availableHeight = screen.height() * (1f - topFactor - bottomReserve)
        val byWidth = (screen.width() - gap * 3f) / 2f
        val byHeight = (availableHeight - gap) / 2f
        val size = minOf(byWidth, byHeight)
        if (size <= 0f) return

        val gridWidth = size * 2f + gap
        val startX = screen.left + (screen.width() - gridWidth) / 2f
        val startY = screen.top + screen.height() * topFactor
        for (row in 0 until GRID_ROWS) {
            for (column in 0 until GRID_COLUMNS) {
                val left = startX + column * (size + gap)
                val top = startY + row * (size + gap)
                rect.set(left, top, left + size, top + size)
                val paint = if (highlightFirst && row == 0 && column == 0) activeTilePaint else tilePaint
                canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, paint)
            }
        }
    }

    private fun drawDock(canvas: Canvas, screen: RectF) {
        val size = screen.height() * 0.09f
        val gap = size * 0.55f
        val totalWidth = size * DOCK_COUNT + gap * (DOCK_COUNT - 1)
        val startX = screen.left + (screen.width() - totalWidth) / 2f
        val top = screen.bottom - size - screen.height() * 0.06f
        for (index in 0 until DOCK_COUNT) {
            val left = startX + index * (size + gap)
            rect.set(left, top, left + size, top + size)
            canvas.drawRoundRect(rect, size * 0.28f, size * 0.28f, tilePaint)
        }
    }

    private fun drawLink(canvas: Canvas, fromX: Float, toX: Float, centerY: Float) {
        val padding = dp(10f)
        val startX = fromX + padding
        val endX = toX - padding
        if (endX <= startX) return

        if (connected) {
            linkPaint.pathEffect = null
            linkPaint.strokeWidth = dp(3f)
            linkPaint.color = color(R.color.brand)
        } else {
            linkPaint.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
            linkPaint.strokeWidth = dp(2f)
            linkPaint.color = color(R.color.text_tertiary)
        }
        canvas.drawLine(startX, centerY, endX, centerY, linkPaint)
        linkPaint.pathEffect = null

        badgePaint.color = linkPaint.color
        canvas.drawCircle(startX, centerY, dp(3.5f), badgePaint)
        canvas.drawCircle(endX, centerY, dp(3.5f), badgePaint)

        if (connected) {
            drawCheckBadge(canvas, (startX + endX) / 2f, centerY)
        }
    }

    private fun drawCheckBadge(canvas: Canvas, centerX: Float, centerY: Float) {
        val radius = dp(13f)
        badgePaint.color = color(R.color.ok)
        canvas.drawCircle(centerX, centerY, radius, badgePaint)

        path.reset()
        path.moveTo(centerX - radius * 0.42f, centerY)
        path.lineTo(centerX - radius * 0.10f, centerY + radius * 0.32f)
        path.lineTo(centerX + radius * 0.44f, centerY - radius * 0.28f)
        canvas.drawPath(path, checkPaint)
    }

    private companion object {
        const val MONITOR_RATIO = 1.6f
        const val PHONE_RATIO = 0.5f
        const val GAP_RATIO = 0.35f
        const val GRID_COLUMNS = 2
        const val GRID_ROWS = 2
        const val DOCK_COUNT = 3
    }
}
