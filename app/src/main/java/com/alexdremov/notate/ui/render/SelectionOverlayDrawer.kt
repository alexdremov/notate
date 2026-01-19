package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.alexdremov.notate.controller.SelectionManager
import com.alexdremov.notate.util.StrokeRenderer

/**
 * Responsible for rendering the selection visual state:
 * 1. The "lifted" strokes (transformed).
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

    // Reuse objects to avoid allocation
    private val screenMatrix = Matrix()
    private val path = Path()
    private val screenCorners = FloatArray(8)
    private val screenCenter = FloatArray(2)

    fun draw(
        canvas: Canvas,
        viewMatrix: Matrix,
        currentScale: Float,
    ) {
        if (!selectionManager.hasSelection()) return

        // 1. Draw Transformed Strokes (Visual Lift)
        // We draw them here because they are temporarily removed from the main tile model during interaction
        if (selectionManager.selectedStrokes.isNotEmpty()) {
            canvas.save()
            // Apply View Matrix (World -> Screen)
            canvas.concat(viewMatrix)
            // Apply Selection Transform (Original -> Current)
            canvas.concat(selectionManager.getTransform())

            selectionManager.selectedStrokes.forEach { stroke ->
                renderer.drawStrokeToCanvas(canvas, stroke)
            }
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

        // Scale stroke width relative to screen, usually fixed size is better for UI
        boxPaint.strokeWidth = 2f // Fixed screen pixels
        canvas.drawPath(path, boxPaint)

        // Draw Handles
        val handleRadius = 15f // Fixed screen pixels

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
        // Midpoint of Top-Left (0,1) and Top-Right (2,3)
        val mx = (corners[0] + corners[2]) / 2f
        val my = (corners[1] + corners[3]) / 2f

        // Vector along top edge
        val dx = corners[2] - corners[0]
        val dy = corners[3] - corners[1]
        val len = kotlin.math.hypot(dx, dy)

        if (len < 0.1f) return

        // Perpendicular vector (pointing "up" / away from center)
        // In screen coords, Y is down.
        // If unrotated: TL(0,0) -> TR(10,0). dx=10, dy=0. Up is (0, -1).
        // (dy, -dx) = (0, -10). Normalized: (0, -1). Correct.
        val ux = dy / len
        val uy = -dx / len

        val handleOffset = 50f
        val rhx = mx + ux * handleOffset
        val rhy = my + uy * handleOffset

        // Line to handle
        canvas.drawLine(mx, my, rhx, rhy, boxPaint)
        // Handle circle
        canvas.drawCircle(rhx, rhy, radius, handlePaint)
        canvas.drawCircle(rhx, rhy, radius, handleBorderPaint)
    }
}
