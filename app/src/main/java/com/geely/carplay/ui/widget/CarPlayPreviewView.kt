package com.geely.carplay.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.geely.carplay.ui.R

/**
 * CarPlay 画面占位：屏幕 + 侧边栏 + 应用网格 + dock
 * 配色来自主题资源，浅色主题下为浅色界面
 */
class CarPlayPreviewView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activeTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rect = RectF()
    private val path = Path()

    private val symbolColor: Int

    init {
        screenPaint.color = color(R.color.preview_screen)
        tilePaint.color = color(R.color.preview_tile)
        dockPaint.color = color(R.color.preview_dock)
        activeTilePaint.color = color(R.color.brand)
        symbolColor = color(R.color.preview_symbol)
    }

    @Suppress("DEPRECATION")
    private fun color(colorResId: Int): Int {
        return context.resources.getColor(colorResId)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val corner = height * 0.06f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, screenPaint)

        drawSidebar(canvas)
        drawGrid(canvas)
        drawDock(canvas)
    }

    private fun drawSidebar(canvas: Canvas) {
        val columnWidth = width * 0.09f
        val top = height * 0.16f
        val gap = height * 0.15f
        val radius = minOf(columnWidth * 0.26f, gap * 0.34f)
        repeat(SIDEBAR_COUNT) { index ->
            canvas.drawCircle(
                columnWidth * 0.55f,
                top + index * gap,
                radius,
                if (index == 0) activeTilePaint else tilePaint,
            )
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val left = width * 0.17f
        val right = width * 0.96f
        val top = height * 0.14f
        val cellWidth = (right - left) / GRID_COLUMNS
        val rowStep = height * 0.52f / GRID_ROWS
        val size = minOf(cellWidth * 0.6f, rowStep * 0.8f)
        val tileRadius = size * 0.22f

        for (row in 0 until GRID_ROWS) {
            for (column in 0 until GRID_COLUMNS) {
                val index = row * GRID_COLUMNS + column
                val centerX = left + column * cellWidth + cellWidth / 2f
                val centerY = top + row * rowStep + size / 2f
                rect.set(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f)
                canvas.drawRoundRect(rect, tileRadius, tileRadius, if (index == 0) activeTilePaint else tilePaint)
                drawTileSymbol(canvas, index, centerX, centerY, size)
            }
        }
    }

    private fun drawTileSymbol(canvas: Canvas, index: Int, centerX: Float, centerY: Float, size: Float) {
        symbolPaint.strokeWidth = size * 0.06f
        symbolPaint.color = if (index == 0) Color.WHITE else symbolColor

        when (index % SYMBOL_COUNT) {
            0 -> {
                path.reset()
                path.moveTo(centerX - size * 0.20f, centerY + size * 0.16f)
                path.lineTo(centerX - size * 0.04f, centerY - size * 0.04f)
                path.lineTo(centerX + size * 0.08f, centerY + size * 0.06f)
                path.lineTo(centerX + size * 0.22f, centerY - size * 0.18f)
                canvas.drawPath(path, symbolPaint)
            }
            1 -> {
                canvas.drawCircle(centerX - size * 0.10f, centerY + size * 0.12f, size * 0.09f, symbolPaint)
                path.reset()
                path.moveTo(centerX - size * 0.015f, centerY + size * 0.12f)
                path.lineTo(centerX - size * 0.015f, centerY - size * 0.16f)
                path.lineTo(centerX + size * 0.16f, centerY - size * 0.10f)
                canvas.drawPath(path, symbolPaint)
            }
            2 -> {
                path.reset()
                path.moveTo(centerX - size * 0.18f, centerY + size * 0.14f)
                path.quadTo(centerX, centerY - size * 0.04f, centerX + size * 0.18f, centerY + size * 0.14f)
                canvas.drawPath(path, symbolPaint)
            }
            else -> {
                rect.set(
                    centerX - size * 0.19f,
                    centerY - size * 0.16f,
                    centerX + size * 0.19f,
                    centerY + size * 0.08f,
                )
                canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, symbolPaint)
                path.reset()
                path.moveTo(centerX - size * 0.06f, centerY + size * 0.08f)
                path.lineTo(centerX - size * 0.06f, centerY + size * 0.19f)
                path.lineTo(centerX + size * 0.05f, centerY + size * 0.08f)
                canvas.drawPath(path, symbolPaint)
            }
        }
    }

    private fun drawDock(canvas: Canvas) {
        val size = height * 0.11f
        val gap = size * 0.6f
        val totalWidth = DOCK_COUNT * size + (DOCK_COUNT - 1) * gap
        val startX = (width - totalWidth) / 2f
        val top = height - size - height * 0.06f
        val tileRadius = size * 0.3f

        for (index in 0 until DOCK_COUNT) {
            val x = startX + index * (size + gap)
            rect.set(x, top, x + size, top + size)
            canvas.drawRoundRect(rect, tileRadius, tileRadius, dockPaint)
        }
    }

    private companion object {
        const val SIDEBAR_COUNT = 5
        const val GRID_COLUMNS = 4
        const val GRID_ROWS = 2
        const val DOCK_COUNT = 4
        const val SYMBOL_COUNT = 4
    }
}
