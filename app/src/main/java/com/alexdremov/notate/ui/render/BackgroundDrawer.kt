package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.render.background.BackgroundPatternCache
import kotlin.math.floor

object BackgroundDrawer {
    private val paint =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    private val fillPaint =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    private const val MAX_PRIMITIVES = 10000

    // Component for handling BitmapShader caching
    private val patternCache = BackgroundPatternCache()

    fun draw(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        zoomLevel: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.right.isFinite() || !rect.top.isFinite() || !rect.bottom.isFinite()) return

        // Hybrid Rendering Strategy:
        // High Zoom (> 2.0): Use Vector Loops. Items are sparse, loops are fast, quality is perfect.
        // Low Zoom (<= 2.0): Use Bitmap Cache. Items are dense, loops are slow.
        val useCache = zoomLevel <= 2.0f

        when (style) {
            is BackgroundStyle.Blank -> {
                // No pattern to draw
            }

            is BackgroundStyle.Dots -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawDotsVector(canvas, style, rect, offsetX, offsetY)
                }
            }

            is BackgroundStyle.Lines -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawLinesVector(canvas, style, rect, offsetX, offsetY)
                }
            }

            is BackgroundStyle.Grid -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawGridVector(canvas, style, rect, offsetX, offsetY)
                }
            }
        }
    }

    // --- Vector Renderers (Iterative Loops) ---

    private fun drawDotsVector(
        canvas: Canvas,
        style: BackgroundStyle.Dots,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        fillPaint.color = style.color

        val spacing = style.spacing
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        var primitivesDrawn = 0
        var x = startX
        while (x < rect.right + spacing) {
            var y = startY
            while (y < rect.bottom + spacing) {
                if (y >= rect.top - spacing && y <= rect.bottom + spacing &&
                    x >= rect.left - spacing && x <= rect.right + spacing
                ) {
                    canvas.drawCircle(x, y, style.radius, fillPaint)
                    primitivesDrawn++
                    if (primitivesDrawn > MAX_PRIMITIVES) return
                }
                y += spacing
            }
            x += spacing
            if (primitivesDrawn > MAX_PRIMITIVES) return
        }
    }

    private fun drawLinesVector(
        canvas: Canvas,
        style: BackgroundStyle.Lines,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        paint.color = style.color
        paint.strokeWidth = style.thickness

        val spacing = style.spacing
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        var y = startY
        var linesDrawn = 0
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                canvas.drawLine(rect.left, y, rect.right, y, paint)
                linesDrawn++
                if (linesDrawn > MAX_PRIMITIVES) return
            }
            y += spacing
        }
    }

    private fun drawGridVector(
        canvas: Canvas,
        style: BackgroundStyle.Grid,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        paint.color = style.color
        paint.strokeWidth = style.thickness

        var primitivesDrawn = 0
        val spacing = style.spacing

        // Vertical Lines
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        var x = startX
        while (x < rect.right + spacing) {
            if (x >= rect.left - spacing) {
                canvas.drawLine(x, rect.top, x, rect.bottom, paint)
                primitivesDrawn++
                if (primitivesDrawn > MAX_PRIMITIVES) return
            }
            x += spacing
        }

        // Horizontal Lines
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
        var y = startY
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                canvas.drawLine(rect.left, y, rect.right, y, paint)
                primitivesDrawn++
                if (primitivesDrawn > MAX_PRIMITIVES) return
            }
            y += spacing
        }
    }
}
