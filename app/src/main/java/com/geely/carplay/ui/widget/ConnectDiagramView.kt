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
 * 首页连接示意图 左侧手机 右侧车机中控屏 中间用连线表示连接状态
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
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
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
        val top = layout.top
        val bottom = top + layout.deviceHeight
        val linkY = top + layout.deviceHeight * 0.44f

        outlinePaint.color = color(if (connected) R.color.brand else R.color.stroke)
        outlinePaint.strokeWidth = dp(if (connected) 1.5f else 1f)

        drawPhone(canvas, layout.phoneLeft, layout.phoneLeft + layout.phoneWidth, top, bottom)
        drawCar(canvas, layout.carLeft, layout.carLeft + layout.carWidth, top, bottom)
        drawLink(canvas, layout.phoneLeft + layout.phoneWidth, layout.carLeft, linkY)
    }

    private class DiagramLayout(
        val phoneLeft: Float,
        val top: Float,
        val deviceHeight: Float,
        val phoneWidth: Float,
        val carWidth: Float,
        val gap: Float,
    ) {
        val carLeft: Float get() = phoneLeft + phoneWidth + gap
    }

    private fun measureLayout(): DiagramLayout {
        val maxDeviceHeight = height * 0.58f
        val maxWidth = width * 0.88f
        val scale = PHONE_RATIO + GAP_RATIO + CAR_RATIO
        var deviceHeight = maxDeviceHeight
        if (deviceHeight * scale > maxWidth) {
            deviceHeight = maxWidth / scale
        }
        val phoneWidth = deviceHeight * PHONE_RATIO
        val carWidth = deviceHeight * CAR_RATIO
        val gap = deviceHeight * GAP_RATIO
        val totalWidth = phoneWidth + gap + carWidth
        val left = (width - totalWidth) / 2f
        val top = (height - deviceHeight) / 2f
        return DiagramLayout(left, top, deviceHeight, phoneWidth, carWidth, gap)
    }

    private fun drawPhone(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
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

    /** 横向中控屏 屏幕内保留应用网格和 dock */
    private fun drawCar(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val heightPx = bottom - top
        val bodyRadius = heightPx * 0.07f
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, bodyPaint)
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, outlinePaint)

        val inset = heightPx * 0.09f
        screen.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(screen, heightPx * 0.04f, heightPx * 0.04f, monitorScreenPaint)
        drawAppGrid(canvas, screen, topFactor = 0.10f, bottomReserve = 0.26f, highlightFirst = true)
        drawDock(canvas, screen)
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
            drawConnectedLink(canvas, startX, endX, centerY)
        } else {
            drawBrokenLink(canvas, startX, endX, centerY)
        }
    }

    /** 已连接 品牌色实线加粗 两端实心点 中点对勾带光圈 */
    private fun drawConnectedLink(canvas: Canvas, startX: Float, endX: Float, centerY: Float) {
        linkPaint.pathEffect = null
        linkPaint.strokeWidth = dp(3.5f)
        linkPaint.color = color(R.color.brand)
        canvas.drawLine(startX, centerY, endX, centerY, linkPaint)

        badgePaint.color = linkPaint.color
        canvas.drawCircle(startX, centerY, dp(4.5f), badgePaint)
        canvas.drawCircle(endX, centerY, dp(4.5f), badgePaint)

        drawCheckBadge(canvas, (startX + endX) / 2f, centerY)
    }

    /** 未连接 两端各一段虚线 中间留缺口 端点用空心圆环表示断开 */
    private fun drawBrokenLink(canvas: Canvas, startX: Float, endX: Float, centerY: Float) {
        val half = (endX - startX) / 2f
        val gapHalf = half * 0.24f
        val leftEnd = startX + half - gapHalf
        val rightStart = endX - half + gapHalf

        linkPaint.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
        linkPaint.strokeWidth = dp(2f)
        linkPaint.color = color(R.color.text_tertiary)
        canvas.drawLine(startX, centerY, leftEnd, centerY, linkPaint)
        canvas.drawLine(rightStart, centerY, endX, centerY, linkPaint)
        linkPaint.pathEffect = null

        ringPaint.color = color(R.color.text_tertiary)
        canvas.drawCircle(startX, centerY, dp(4f), ringPaint)
        canvas.drawCircle(endX, centerY, dp(4f), ringPaint)
    }

    private fun drawCheckBadge(canvas: Canvas, centerX: Float, centerY: Float) {
        val radius = dp(13f)
        badgePaint.color = color(R.color.ok_soft)
        canvas.drawCircle(centerX, centerY, radius * 1.6f, badgePaint)

        badgePaint.color = color(R.color.ok)
        canvas.drawCircle(centerX, centerY, radius, badgePaint)

        path.reset()
        path.moveTo(centerX - radius * 0.42f, centerY)
        path.lineTo(centerX - radius * 0.10f, centerY + radius * 0.32f)
        path.lineTo(centerX + radius * 0.44f, centerY - radius * 0.28f)
        canvas.drawPath(path, checkPaint)
    }

    private companion object {
        const val PHONE_RATIO = 0.5f
        const val CAR_RATIO = 1.6f
        const val GAP_RATIO = 0.32f
        const val GRID_COLUMNS = 2
        const val GRID_ROWS = 2
        const val DOCK_COUNT = 3
    }
}
