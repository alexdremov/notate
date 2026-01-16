package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import com.alexdremov.notate.ui.render.MinimapDrawer

class MinimapView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private var drawer: MinimapDrawer? = null
        private var canvasView: OnyxCanvasView? = null
        private val matrix = Matrix()
        private val inverseMatrix = Matrix()

        fun setup(canvasView: OnyxCanvasView) {
            this.canvasView = canvasView
            this.drawer =
                MinimapDrawer(
                    this,
                    canvasView.getModel(),
                    canvasView.getRenderer(),
                ) { invalidate() }

            canvasView.minimapDrawer = this.drawer

            // Listen to canvas events
            canvasView.onViewportChanged = {
                drawer?.show()
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cv = canvasView ?: return
            val dr = drawer ?: return

            cv.getViewportMatrix(matrix)
            matrix.invert(inverseMatrix)

            dr.draw(canvas, matrix, inverseMatrix, cv.getCurrentScale(), cv.width, cv.height)
        }

        fun setDirty() {
            drawer?.setDirty()
            invalidate()
        }
    }
