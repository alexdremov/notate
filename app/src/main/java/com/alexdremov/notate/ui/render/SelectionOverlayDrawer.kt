package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.alexdremov.notate.controller.SelectionManager

/**
 * Responsible for rendering the selection visual state:
 * 1. The "lifted" items (via Imposter Bitmap).
 * 2. The bounding box.
 * 3. The manipulation handles.
 */
class SelectionOverlayDrawer(
    private val selectionManager: SelectionManager,
    private val renderer: CanvasRenderer,
) {
    private val boxPaint =
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            isAntiAlias = true
        }

    private val handlePaint =
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    private val handleBorderPaint =
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

    private val bitmapPaint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

    private val loadingPaint =
        Paint().apply {
            color = Color.argb(64, 0, 0, 255) // Semi-transparent blue
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    // Reuse objects to avoid allocation
    private val path = Path()
    private val screenCorners = FloatArray(8)

    fun draw(
        canvas: Canvas,
        viewMatrix: Matrix,
        currentScale: Float,
    ) {
        if (!selectionManager.hasSelection()) return

        // 1. Draw Imposter Bitmap (High Performance)
        val imposter = selectionManager.getImposter()
        if (imposter != null) {
            val (bitmap, offsetMatrix) = imposter
            canvas.save()
            // World -> Screen
            canvas.concat(viewMatrix)
            // Original -> Transformed
            canvas.concat(selectionManager.getTransform())
            // Bitmap -> Original
            canvas.concat(offsetMatrix)

            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            canvas.restore()
        }

        // 2. Draw Selection Box & Handles
        val corners = selectionManager.getTransformedCorners() // World Coords [x0,y0...]

        // Transform corners to Screen Space
        viewMatrix.mapPoints(screenCorners, corners)

        // Draw Box (Polygon)
        path.reset()
        path.moveTo(screenCorners[0], screenCorners[1])
        path.lineTo(screenCorners[2], screenCorners[3])
        path.lineTo(screenCorners[4], screenCorners[5])
        path.lineTo(screenCorners[6], screenCorners[7])
        path.close()

        if (selectionManager.isGeneratingImposter && imposter == null) {
            canvas.drawPath(path, loadingPaint)
        }

        // Scale stroke width relative to screen
        boxPaint.strokeWidth = 2f
        canvas.drawPath(path, boxPaint)

        // Draw Handles
        val handleRadius = 15f

        for (i in 0 until 4) {
            val hx = screenCorners[i * 2]
            val hy = screenCorners[i * 2 + 1]
            canvas.drawCircle(hx, hy, handleRadius, handlePaint)
            canvas.drawCircle(hx, hy, handleRadius, handleBorderPaint)
        }

        // Draw Rotate Handle (Top-Center Knob)
        drawRotateHandle(canvas, screenCorners, handleRadius)
    }

    private fun drawRotateHandle(
        canvas: Canvas,
        corners: FloatArray,
        radius: Float,
    ) {
        val mx = (corners[0] + corners[2]) / 2f
        val my = (corners[1] + corners[3]) / 2f

        val dx = corners[2] - corners[0]
        val dy = corners[3] - corners[1]
        val len = kotlin.math.hypot(dx, dy)

        if (len < 0.1f) return

        val ux = dy / len
        val uy = -dx / len

        val handleOffset = 50f
        val rhx = mx + ux * handleOffset
        val rhy = my + uy * handleOffset

        canvas.drawLine(mx, my, rhx, rhy, boxPaint)
        canvas.drawCircle(rhx, rhy, radius, handlePaint)
        canvas.drawCircle(rhx, rhy, radius, handleBorderPaint)
    }
}
