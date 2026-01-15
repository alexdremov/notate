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

    // Threshold: If we need to draw more than this many primitives, switch to Bitmap Cache
    // to save CPU cycles. Otherwise, use Vector to save GPU Fill Rate.
    private const val MAX_PRIMITIVES = 10000
    private const val VECTOR_RENDER_THRESHOLD = 4000

    // Component for handling BitmapShader caching
    private val patternCache = BackgroundPatternCache()

    fun draw(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        forceVector: Boolean = false,
    ) {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.right.isFinite() || !rect.top.isFinite() || !rect.bottom.isFinite()) return

        // Rendering Strategy:
        // 1. Force Vector (PDF Export): Always use vector primitives.
        // 2. Dense Pattern (Screen): Use Bitmap Cache to avoid thousands of CPU draw calls.
        // 3. Sparse Pattern (Screen): Use Vector to avoid expensive GPU full-screen texture fills.

        val useCache = !forceVector && shouldUseCache(style, rect)

        when (style) {
            is BackgroundStyle.Blank -> {
                // No pattern to draw
            }

            is BackgroundStyle.Dots -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    canvas.save()
                    canvas.clipRect(rect)
                    drawDotsVector(canvas, style, rect, offsetX, offsetY)
                    canvas.restore()
                }
            }

            is BackgroundStyle.Lines -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    canvas.save()
                    canvas.clipRect(rect)
                    drawLinesVector(canvas, style, rect, offsetX, offsetY)
                    canvas.restore()
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

    private fun shouldUseCache(
        style: BackgroundStyle,
        rect: RectF,
    ): Boolean {
        val width = rect.width()
        val height = rect.height()

        if (width <= 0 || height <= 0) return false

        return when (style) {
            is BackgroundStyle.Dots -> {
                if (style.spacing <= 0.1f) return true
                val cols = width / style.spacing
                val rows = height / style.spacing
                (cols * rows) > VECTOR_RENDER_THRESHOLD
            }

            is BackgroundStyle.Lines -> {
                if (style.spacing <= 0.1f) return true
                val rows = height / style.spacing
                rows > VECTOR_RENDER_THRESHOLD
            }

            is BackgroundStyle.Grid -> {
                if (style.spacing <= 0.1f) return true
                val cols = width / style.spacing
                val rows = height / style.spacing
                (cols + rows) > VECTOR_RENDER_THRESHOLD
            }

            else -> {
                false
            }
        }
    }

    // --- Vector Renderers ---

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
