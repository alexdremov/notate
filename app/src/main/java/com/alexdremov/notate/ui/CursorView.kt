package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

        private var shapePath: Path? = null
        private var selectionRect: RectF? = null

        private val cursorPaint =
            Paint().apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 2f
                isAntiAlias = true
            }

        private val shapePaint =
            Paint().apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 3f
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                isAntiAlias = true
            }

        private val selectionPaint =
            Paint().apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
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
            shapePath = path
            isCursorVisible = true
            postInvalidate()
        }

        fun showSelectionRect(rect: RectF) {
            selectionRect = rect
            isCursorVisible = true
            postInvalidate()
        }

        fun hideShapePreview() {
            shapePath = null
            if (cursorRadius <= 0 && selectionRect == null && !isLassoMode) isCursorVisible = false
            postInvalidate()
        }

        fun hideSelectionRect() {
            selectionRect = null
            if (cursorRadius <= 0 && shapePath == null && !isLassoMode) isCursorVisible = false
            postInvalidate()
        }

        fun hide() {
            isCursorVisible = false
            shapePath = null
            selectionRect = null
            lassoPath.reset()
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!isCursorVisible) return

            shapePath?.let {
                canvas.drawPath(it, shapePaint)
            }

            selectionRect?.let {
                canvas.drawRect(it, selectionPaint)
            }

            if (isLassoMode) {
                canvas.drawPath(lassoPath, shapePaint)
            } else if (cursorRadius > 0) {
                canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorPaint)
            }
        }
    }
