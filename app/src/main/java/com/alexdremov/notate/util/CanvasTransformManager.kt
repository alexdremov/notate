package com.alexdremov.notate.util

import android.graphics.Matrix
import android.graphics.RectF

/**
 * Manages the Screen <-> World coordinate transformations.
 * Centralizes the Matrix state to ensure consistency and performance
 * by caching the inverse matrix.
 */
class CanvasTransformManager {
    private val matrix = Matrix()
    private val inverseMatrix = Matrix()
    private val scratchPoints = FloatArray(2)
    private var isInverseDirty = false

    /**
     * Returns the raw World -> Screen matrix.
     * Note: If you modify this directly, you MUST call [notifyMatrixChanged].
     */
    fun getMatrix(): Matrix = matrix

    /**
     * Returns the cached Screen -> World matrix.
     * Recomputes if dirty.
     */
    fun getInverseMatrix(): Matrix {
        if (isInverseDirty) {
            matrix.invert(inverseMatrix)
            isInverseDirty = false
        }
        return inverseMatrix
    }

    /**
     * Call this after modifying the underlying matrix directly.
     */
    fun notifyMatrixChanged() {
        isInverseDirty = true
    }

    /**
     * Maps a point from Screen coordinates to World coordinates.
     * Thread-safe (allocates new array).
     */
    fun screenToWorld(
        screenX: Float,
        screenY: Float,
    ): FloatArray {
        val pts = floatArrayOf(screenX, screenY)
        getInverseMatrix().mapPoints(pts)
        return pts
    }

    /**
     * Maps a point from Screen coordinates to World coordinates.
     * Non-allocation version (uses provided array).
     */
    fun screenToWorld(
        screenX: Float,
        screenY: Float,
        result: FloatArray,
    ) {
        result[0] = screenX
        result[1] = screenY
        getInverseMatrix().mapPoints(result)
    }

    /**
     * Maps a point from World coordinates to Screen coordinates.
     */
    fun worldToScreen(
        worldX: Float,
        worldY: Float,
    ): FloatArray {
        val pts = floatArrayOf(worldX, worldY)
        matrix.mapPoints(pts)
        return pts
    }

    /**
     * Calculates the Visible World Bounds based on the view dimensions.
     */
    fun getVisibleWorldBounds(
        viewWidth: Float,
        viewHeight: Float,
    ): RectF {
        val screenRect = RectF(0f, 0f, viewWidth, viewHeight)
        val worldRect = RectF()
        getInverseMatrix().mapRect(worldRect, screenRect)
        return worldRect
    }

    /**
     * Resets the matrix to identity.
     */
    fun reset() {
        matrix.reset()
        inverseMatrix.reset()
        isInverseDirty = false
    }

    /**
     * Post-concatenates the matrix with the specified translation.
     */
    fun postTranslate(
        dx: Float,
        dy: Float,
    ) {
        matrix.postTranslate(dx, dy)
        notifyMatrixChanged()
    }

    /**
     * Post-concatenates the matrix with the specified scale.
     */
    fun postScale(
        sx: Float,
        sy: Float,
    ) {
        matrix.postScale(sx, sy)
        notifyMatrixChanged()
    }

    fun postScale(
        sx: Float,
        sy: Float,
        px: Float,
        py: Float,
    ) {
        matrix.postScale(sx, sy, px, py)
        notifyMatrixChanged()
    }

    /**
     * Helper to get current scale (assuming uniform scale).
     */
    fun getScaleX(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
    }
}
