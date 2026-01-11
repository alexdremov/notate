package com.alexdremov.notate.ui.render.background

import android.graphics.Canvas
import android.graphics.Paint
import com.alexdremov.notate.model.BackgroundStyle

/**
 * Stateless renderer for background primitives (Dots, Lines, Grid).
 * Used by both direct vector rendering and bitmap cache generation.
 */
object BackgroundPrimitiveRenderer {
    private val strokePaint =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    private val fillPaint =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /**
     * Draws the primitives for a single tile cell (0,0) to (S,S).
     * Used for generating the cached bitmap pattern.
     * Handles wrapping (drawing at multiple corners) to ensure seamless tiling.
     */
    fun drawTilePrimitives(
        canvas: Canvas,
        style: BackgroundStyle,
        spacing: Float,
    ) {
        when (style) {
            is BackgroundStyle.Dots -> {
                fillPaint.color = style.color
                // Draw at all 4 corners to handle clipping
                canvas.drawCircle(0f, 0f, style.radius, fillPaint)
                canvas.drawCircle(spacing, 0f, style.radius, fillPaint)
                canvas.drawCircle(0f, spacing, style.radius, fillPaint)
                canvas.drawCircle(spacing, spacing, style.radius, fillPaint)
            }

            is BackgroundStyle.Lines -> {
                strokePaint.color = style.color
                strokePaint.strokeWidth = style.thickness
                // Horizontal lines at y=0 and y=spacing
                canvas.drawLine(0f, 0f, spacing, 0f, strokePaint)
                canvas.drawLine(0f, spacing, spacing, spacing, strokePaint)
            }

            is BackgroundStyle.Grid -> {
                strokePaint.color = style.color
                strokePaint.strokeWidth = style.thickness
                // Vertical at x=0 and x=spacing
                canvas.drawLine(0f, 0f, 0f, spacing, strokePaint)
                canvas.drawLine(spacing, 0f, spacing, spacing, strokePaint)

                // Horizontal at y=0 and y=spacing
                canvas.drawLine(0f, 0f, spacing, 0f, strokePaint)
                canvas.drawLine(0f, spacing, spacing, spacing, strokePaint)
            }

            else -> {}
        }
    }
}
