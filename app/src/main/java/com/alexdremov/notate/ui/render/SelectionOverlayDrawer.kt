package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.ui.controller.SelectionManager

/**
 * Responsible for rendering the selection visual state:
 * 1. The "lifted" items (via Imposter Bitmap or direct vector fallback).
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

        // 1. Draw Lifted Items
        val imposter = selectionManager.getImposter()
        if (imposter != null) {
            // High Performance: Use pre-rendered bitmap
            val (bitmap, offsetMatrix) = imposter
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.concat(selectionManager.getTransform())
            canvas.concat(offsetMatrix)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            canvas.restore()
        } else {
            // Fallback: Direct Vector Rendering if imposter is missing or generating
            val combinedMatrix = Matrix(viewMatrix)
            combinedMatrix.preConcat(selectionManager.getTransform())

            // 1. Calculate Viewport in World Coordinates
            val visibleRect = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            val inverseView = Matrix()
            viewMatrix.invert(inverseView)
            inverseView.mapRect(visibleRect)

            // 2. Calculate Query Rect in Original Item Coordinates (Reverse Selection Transform)
            // We need items that, when transformed, land in the visibleRect.
            val queryRect = RectF(visibleRect)
            val inverseSelection = Matrix()
            selectionManager.getTransform().invert(inverseSelection)
            inverseSelection.mapRect(queryRect)

            val ids = selectionManager.getSelectedIds()
            renderer.renderDirectVectorsSync(
                canvas,
                combinedMatrix,
                queryRect,
                RenderQuality.HIGH,
            ) { item -> ids.contains(item.order) }
        }

        // 2. Draw Selection Box & Handles
        val corners = selectionManager.getTransformedCorners()

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
