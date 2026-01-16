package com.alexdremov.notate.controller

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the state of the active selection.
 * Holds the selected items and the transient transformation matrix.
 * Thread-safe.
 */
class SelectionManager {
    private val _selectedItems = ConcurrentHashMap.newKeySet<CanvasItem>()
    val selectedItems: Set<CanvasItem> get() = _selectedItems

    // Backwards compatibility for callers expecting Stroke
    val selectedStrokes: Set<com.alexdremov.notate.model.Stroke>
        get() = _selectedItems.filterIsInstance<com.alexdremov.notate.model.Stroke>().toSet()

    // Current transformation applied to the selection (transient)
    val transformMatrix = Matrix()

    // Bounding box of the original selection (before transform)
    private val selectionBounds = RectF()

    fun select(item: CanvasItem) {
        _selectedItems.add(item)
        recomputeBounds()
    }

    fun selectAll(items: List<CanvasItem>) {
        _selectedItems.addAll(items)
        recomputeBounds()
    }

    fun deselect(item: CanvasItem) {
        _selectedItems.remove(item)
        recomputeBounds()
    }

    fun clearSelection() {
        _selectedItems.clear()
        transformMatrix.reset()
        selectionBounds.setEmpty()
    }

    fun hasSelection() = _selectedItems.isNotEmpty()

    fun isSelected(item: CanvasItem) = _selectedItems.contains(item)

    private fun recomputeBounds() {
        if (_selectedItems.isEmpty()) {
            selectionBounds.setEmpty()
            return
        }
        val iter = _selectedItems.iterator()
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
        val pts =
            floatArrayOf(
                selectionBounds.left,
                selectionBounds.top, // TL
                selectionBounds.right,
                selectionBounds.top, // TR
                selectionBounds.right,
                selectionBounds.bottom, // BR
                selectionBounds.left,
                selectionBounds.bottom, // BL
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

    fun translate(
        dx: Float,
        dy: Float,
    ) {
        transformMatrix.postTranslate(dx, dy)
    }

    fun applyTransform(matrix: Matrix) {
        transformMatrix.postConcat(matrix)
    }
}
