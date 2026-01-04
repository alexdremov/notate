package com.example.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class CursorView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private var cursorX = -1f
        private var cursorY = -1f
        private var cursorRadius = 10f
        private var isCursorVisible = false
        private var isLassoMode = false
        private val lassoPath = Path()

        private val circlePaint =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }

        private val dashedPaint =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
            }

        private var previewShapePath: Path? = null
        private val previewShapePaint =
            Paint().apply {
                color = Color.DKGRAY
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }

        fun update(
            x: Float,
            y: Float,
            radius: Float,
        ) {
            cursorX = x
            cursorY = y
            cursorRadius = radius
            isLassoMode = false
            isCursorVisible = true
            postInvalidate()
        }

        fun updateLassoPath(path: Path) {
            lassoPath.set(path)
            isLassoMode = true
            isCursorVisible = true
            postInvalidate()
        }

        fun showShapePreview(path: Path) {
            previewShapePath = Path(path)
            postInvalidate()
        }

        fun hideShapePreview() {
            previewShapePath = null
            postInvalidate()
        }

        fun hide() {
            if (isCursorVisible || previewShapePath != null) {
                isCursorVisible = false
                previewShapePath = null
                lassoPath.reset()
                postInvalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw preview shape first (bottom layer) or last (top layer).
            // Top layer ensures visibility over the cursor if they overlap.
            previewShapePath?.let {
                canvas.drawPath(it, previewShapePaint)
            }

            if (!isCursorVisible) return

            if (isLassoMode) {
                canvas.drawPath(lassoPath, dashedPaint)
            } else if (cursorX >= 0 && cursorY >= 0) {
                canvas.drawCircle(cursorX, cursorY, cursorRadius, circlePaint)
            }
        }
    }
