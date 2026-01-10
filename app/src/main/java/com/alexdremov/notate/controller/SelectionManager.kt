package com.alexdremov.notate.controller

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the state of the active selection.
 * Holds the selected strokes and the transient transformation matrix.
 * Thread-safe.
 */
class SelectionManager {
    private val _selectedStrokes = ConcurrentHashMap.newKeySet<Stroke>()
    val selectedStrokes: Set<Stroke> get() = _selectedStrokes

    // Current transformation applied to the selection (transient)
    val transformMatrix = Matrix()

    // Bounding box of the original selection (before transform)
    private val selectionBounds = RectF()

    fun select(stroke: Stroke) {
        _selectedStrokes.add(stroke)
        recomputeBounds()
    }
    
    fun selectAll(strokes: List<Stroke>) {
        _selectedStrokes.addAll(strokes)
        recomputeBounds()
    }

    fun deselect(stroke: Stroke) {
        _selectedStrokes.remove(stroke)
        recomputeBounds()
    }

    fun clearSelection() {
        _selectedStrokes.clear()
        transformMatrix.reset()
        selectionBounds.setEmpty()
    }

    fun hasSelection() = _selectedStrokes.isNotEmpty()

    fun isSelected(stroke: Stroke) = _selectedStrokes.contains(stroke)

    private fun recomputeBounds() {
        if (_selectedStrokes.isEmpty()) {
            selectionBounds.setEmpty()
            return
        }
        val iter = _selectedStrokes.iterator()
        if (iter.hasNext()) {
            selectionBounds.set(iter.next().bounds)
        }
        while (iter.hasNext()) {
            selectionBounds.union(iter.next().bounds)
        }
    }
    
    /**
     * Returns the bounding box of the selection with the current transform applied.
     * Note: This is an AABB (Axis Aligned Bounding Box) of the transformed shape.
     */
    fun getTransformedBounds(): RectF {
        val r = RectF(selectionBounds)
        transformMatrix.mapRect(r)
        return r
    }
    
    /**
     * Returns the 4 corners of the selection box in World coordinates, 
     * with the current transform applied.
     * Order: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    fun getTransformedCorners(): FloatArray {
        val pts = floatArrayOf(
            selectionBounds.left, selectionBounds.top,     // TL
            selectionBounds.right, selectionBounds.top,    // TR
            selectionBounds.right, selectionBounds.bottom, // BR
            selectionBounds.left, selectionBounds.bottom   // BL
        )
        transformMatrix.mapPoints(pts)
        return pts
    }
    
    /**
     * Returns the center of the selection in World coordinates,
     * with the current transform applied.
     */
    fun getSelectionCenter(): FloatArray {
        val pts = floatArrayOf(selectionBounds.centerX(), selectionBounds.centerY())
        transformMatrix.mapPoints(pts)
        return pts
    }
    
    fun translate(dx: Float, dy: Float) {
        transformMatrix.postTranslate(dx, dy)
    }
    
    fun applyTransform(matrix: Matrix) {
        transformMatrix.postConcat(matrix)
    }
}