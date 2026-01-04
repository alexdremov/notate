package com.example.notate.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.notate.model.BackgroundStyle
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

    fun draw(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        zoomLevel: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.right.isFinite() || !rect.top.isFinite() || !rect.bottom.isFinite()) return

        // Optimization: Skip drawing if the pattern would be too dense (e.g. < 5px on screen)
        // spacing (world units) * zoomLevel = pixels on screen
        val minScreenPixelSpacing = 2f

        when (style) {
            is BackgroundStyle.Blank -> {
                // No pattern to draw
            }

            is BackgroundStyle.Dots -> {
                if (style.spacing <= 0.1f) return
                if (style.spacing * zoomLevel < 25f) return
                drawDots(canvas, style, rect, offsetX, offsetY)
            }

            is BackgroundStyle.Lines -> {
                if (style.spacing <= 0.1f) return
                if (style.spacing * zoomLevel < minScreenPixelSpacing) return
                drawLines(canvas, style, rect, offsetX, offsetY)
            }

            is BackgroundStyle.Grid -> {
                if (style.spacing <= 0.1f) return
                if (style.spacing * zoomLevel < minScreenPixelSpacing) return
                drawGrid(canvas, style, rect, offsetX, offsetY)
            }
        }
    }

    private fun drawDots(
        canvas: Canvas,
        style: BackgroundStyle.Dots,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        fillPaint.color = style.color

        // Align to the pattern origin (offsetX, offsetY)
        val spacing = style.spacing
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        var primitivesDrawn = 0

        // We draw slightly outside the visible rect to ensure continuity during pan
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
            // Safety check against infinite outer loop
            if (primitivesDrawn > MAX_PRIMITIVES) return
        }
    }

    private fun drawLines(
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

    private fun drawGrid(
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
