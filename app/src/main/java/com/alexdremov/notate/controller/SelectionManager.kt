package com.alexdremov.notate.controller

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem

/**
 * Manages the state of the active selection.
 * Holds the selected items and the transient transformation matrix.
 * Thread-safe.
 */
class SelectionManager {
    private val lock = Any()
    private val _selectedItems = HashSet<CanvasItem>()
    val selectedItems: Set<CanvasItem>
        get() = synchronized(lock) { _selectedItems.toSet() }

    // Backwards compatibility for callers expecting Stroke
    val selectedStrokes: Set<com.alexdremov.notate.model.Stroke>
        get() = synchronized(lock) { _selectedItems.filterIsInstance<com.alexdremov.notate.model.Stroke>().toSet() }

    // Current transformation applied to the selection (transient)
    private val transformMatrix = Matrix()

    // Bounding box of the original selection (before transform)
    private val selectionBounds = RectF()

    /**
     * Returns a defensive copy of the current transformation matrix.
     *
     * Note: this allocates a new [Matrix] on every call. For performance-critical,
     * read-only access that avoids allocations, use [withTransformReadLocked].
     */
    fun getTransform(): Matrix {
        synchronized(lock) {
            return Matrix(transformMatrix)
        }
    }

    /**
     * Executes [block] while holding the internal lock, providing direct read-only
     * access to the current transformation matrix without creating a copy.
     *
     * Callers must not mutate [Matrix] inside [block]. Violating this contract can
     * break invariants and thread-safety guarantees of [SelectionManager].
     */
    fun <T> withTransformReadLocked(block: (Matrix) -> T): T {
        synchronized(lock) {
            return block(transformMatrix)
        }
    }
    fun resetTransform() {
        synchronized(lock) {
            transformMatrix.reset()
        }
    }

    fun select(item: CanvasItem) {
        synchronized(lock) {
            _selectedItems.add(item)
            recomputeBoundsInternal()
        }
    }

    fun selectAll(items: List<CanvasItem>) {
        synchronized(lock) {
            _selectedItems.addAll(items)
            recomputeBoundsInternal()
        }
    }

    fun deselect(item: CanvasItem) {
        synchronized(lock) {
            _selectedItems.remove(item)
            recomputeBoundsInternal()
        }
    }

    fun clearSelection() {
        synchronized(lock) {
            _selectedItems.clear()
            transformMatrix.reset()
            selectionBounds.setEmpty()
        }
    }

    fun hasSelection(): Boolean = synchronized(lock) { _selectedItems.isNotEmpty() }

    fun isSelected(item: CanvasItem): Boolean = synchronized(lock) { _selectedItems.contains(item) }

    private fun recomputeBounds() {
        synchronized(lock) {
            recomputeBoundsInternal()
        }
    }

    private fun recomputeBoundsInternal() {
        if (_selectedItems.isEmpty()) {
            selectionBounds.setEmpty()
            return
        }

        val tempBounds = RectF()
        var first = true
        for (item in _selectedItems) {
            if (first) {
                tempBounds.set(item.bounds)
                first = false
            } else {
                tempBounds.union(item.bounds)
            }
        }
        selectionBounds.set(tempBounds)
    }

    /**
     * Returns the bounding box of the selection with the current transform applied.
     * Note: This is an AABB (Axis Aligned Bounding Box) of the transformed shape.
     */
    fun getTransformedBounds(): RectF {
        synchronized(lock) {
            val r = RectF(selectionBounds)
            transformMatrix.mapRect(r)
            return r
        }
    }

    /**
     * Returns the 4 corners of the selection box in World coordinates,
     * with the current transform applied.
     * Order: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    fun getTransformedCorners(): FloatArray {
        synchronized(lock) {
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
    }

    /**
     * Returns the center of the selection in World coordinates,
     * with the current transform applied.
     */
    fun getSelectionCenter(): FloatArray {
        synchronized(lock) {
            val pts = floatArrayOf(selectionBounds.centerX(), selectionBounds.centerY())
            transformMatrix.mapPoints(pts)
            return pts
        }
    }

    fun translate(
        dx: Float,
        dy: Float,
    ) {
        synchronized(lock) {
            transformMatrix.postTranslate(dx, dy)
        }
    }

    fun applyTransform(matrix: Matrix) {
        synchronized(lock) {
            transformMatrix.postConcat(matrix)
        }
    }
}
