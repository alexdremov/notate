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

    // Threshold: Use Batched Vector rendering for up to this many primitives.
    // Batched rendering (drawLines/drawPoints) is extremely fast on Android.
    private const val MAX_PRIMITIVES = 20000
    private const val VECTOR_RENDER_THRESHOLD = 20000

    // Component for handling BitmapShader caching (Fallback for extreme density)
    private val patternCache = BackgroundPatternCache()

    fun draw(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        zoomLevel: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        forceVector: Boolean = false,
    ) {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.right.isFinite() || !rect.top.isFinite() || !rect.bottom.isFinite()) return

        // 0. Density Check (LOD)
        // If the pattern is too dense (visually < 10px), it creates noise and kills performance.
        val spacing =
            when (style) {
                is BackgroundStyle.Dots -> style.spacing
                is BackgroundStyle.Lines -> style.spacing
                is BackgroundStyle.Grid -> style.spacing
                else -> 0f
            }

        if (spacing > 0 && (spacing * zoomLevel) < 10f) {
            return
        }

        // Rendering Strategy:
        // 1. Batched Vector: Default for Screen & PDF. Uses drawLines/drawPoints for O(1) JNI calls.
        // 2. Bitmap Cache: Fallback if primitive count exceeds buffer limits (20k).

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
                    drawDotsVector(canvas, style, rect, offsetX, offsetY, forceVector)
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
                    drawLinesVector(canvas, style, rect, offsetX, offsetY, forceVector)
                    canvas.restore()
                }
            }

            is BackgroundStyle.Grid -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    patternCache.drawCached(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawGridVector(canvas, style, rect, offsetX, offsetY, forceVector)
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

        // Calculate estimated primitives
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

    // --- Vector Renderers (Batched) ---

    private fun drawDotsVector(
        canvas: Canvas,
        style: BackgroundStyle.Dots,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
        force: Boolean,
    ) {
        val spacing = style.spacing
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        // Estimate count to allocate buffer
        val cols = ((rect.right + spacing - startX) / spacing).toLong().coerceAtLeast(0) + 1
        val rows = ((rect.bottom + spacing - startY) / spacing).toLong().coerceAtLeast(0) + 1
        val maxPoints = cols * rows

        if (!force && maxPoints > MAX_PRIMITIVES) return // Should have used cache
        if (maxPoints > Int.MAX_VALUE / 2) return // Prevent OOM

        val pts = FloatArray((maxPoints * 2).toInt())
        var count = 0

        var x = startX
        while (x < rect.right + spacing) {
            var y = startY
            while (y < rect.bottom + spacing) {
                if (y >= rect.top - spacing && y <= rect.bottom + spacing &&
                    x >= rect.left - spacing && x <= rect.right + spacing
                ) {
                    if (count + 1 < pts.size) {
                        pts[count++] = x
                        pts[count++] = y
                    }
                }
                y += spacing
            }
            x += spacing
        }

        // Draw batched points as circles
        paint.color = style.color
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = style.radius * 2
        canvas.drawPoints(pts, 0, count, paint)
    }

    private fun drawLinesVector(
        canvas: Canvas,
        style: BackgroundStyle.Lines,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
        force: Boolean,
    ) {
        val spacing = style.spacing
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        val rows = ((rect.bottom + spacing - startY) / spacing).toInt().coerceAtLeast(0) + 1
        if (!force && rows > MAX_PRIMITIVES) return

        val pts = FloatArray(rows * 4)
        var count = 0

        var y = startY
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                if (count + 3 < pts.size) {
                    pts[count++] = rect.left
                    pts[count++] = y
                    pts[count++] = rect.right
                    pts[count++] = y
                }
            }
            y += spacing
        }

        paint.color = style.color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = style.thickness
        canvas.drawLines(pts, 0, count, paint)
    }

    private fun drawGridVector(
        canvas: Canvas,
        style: BackgroundStyle.Grid,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
        force: Boolean,
    ) {
        val spacing = style.spacing

        // Vertical
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        val cols = ((rect.right + spacing - startX) / spacing).toInt().coerceAtLeast(0) + 1

        // Horizontal
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
        val rows = ((rect.bottom + spacing - startY) / spacing).toInt().coerceAtLeast(0) + 1

        val totalLines = cols + rows
        if (!force && totalLines > MAX_PRIMITIVES) return

        val pts = FloatArray(totalLines * 4)
        var count = 0

        // Vertical Loop
        var x = startX
        while (x < rect.right + spacing) {
            if (x >= rect.left - spacing) {
                if (count + 3 < pts.size) {
                    pts[count++] = x
                    pts[count++] = rect.top
                    pts[count++] = x
                    pts[count++] = rect.bottom
                }
            }
            x += spacing
        }

        // Horizontal Loop
        var y = startY
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                if (count + 3 < pts.size) {
                    pts[count++] = rect.left
                    pts[count++] = y
                    pts[count++] = rect.right
                    pts[count++] = y
                }
            }
            y += spacing
        }

        paint.color = style.color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = style.thickness
        canvas.drawLines(pts, 0, count, paint)
    }
}
