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

    // Virtualized Selection: Store IDs instead of objects to prevent OOM
    private val _selectedIds = HashSet<Long>()

    // Current transformation applied to the selection (transient)
    private val transformMatrix = Matrix()

    // Bounding box of the original selection (before transform)
    private val selectionBounds = RectF()

    // Imposter Bitmap for High-Performance Rendering
    private var imposterBitmap: android.graphics.Bitmap? = null
    private val imposterMatrix = Matrix()
    private var _isGeneratingImposter = false

    var isGeneratingImposter: Boolean
        get() = synchronized(lock) { _isGeneratingImposter }
        set(value) = synchronized(lock) { _isGeneratingImposter = value }

    fun getSelectedIds(): Set<Long> = synchronized(lock) { _selectedIds.toSet() }

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

    fun setImposter(
        bitmap: android.graphics.Bitmap,
        matrix: Matrix,
    ) {
        synchronized(lock) {
            // Recycle old bitmap if strictly different
            if (imposterBitmap != null && imposterBitmap !== bitmap) {
                imposterBitmap?.recycle()
            }
            imposterBitmap = bitmap
            imposterMatrix.set(matrix)
        }
    }

    fun getImposter(): Pair<android.graphics.Bitmap, Matrix>? {
        synchronized(lock) {
            val bmp = imposterBitmap ?: return null
            return Pair(bmp, Matrix(imposterMatrix))
        }
    }

    fun clearImposter() {
        synchronized(lock) {
            imposterBitmap?.recycle()
            imposterBitmap = null
            imposterMatrix.reset()
        }
    }

    fun select(item: CanvasItem) {
        synchronized(lock) {
            if (_selectedIds.isEmpty()) {
                selectionBounds.set(item.bounds)
            } else {
                selectionBounds.union(item.bounds)
            }
            _selectedIds.add(item.order)
        }
    }

    fun selectAll(items: List<CanvasItem>) {
        synchronized(lock) {
            items.forEach { item ->
                if (_selectedIds.isEmpty()) {
                    selectionBounds.set(item.bounds)
                } else {
                    selectionBounds.union(item.bounds)
                }
                _selectedIds.add(item.order)
            }
        }
    }

    // Optimized for direct ID addition when bounds are known
    fun addSelection(
        id: Long,
        bounds: RectF,
    ) {
        synchronized(lock) {
            if (_selectedIds.isEmpty()) {
                selectionBounds.set(bounds)
            } else {
                selectionBounds.union(bounds)
            }
            _selectedIds.add(id)
        }
    }

    fun deselect(item: CanvasItem) {
        synchronized(lock) {
            _selectedIds.remove(item.order)
            // Note: We do not shrink bounds on deselect to avoid OOM from re-querying.
            // Bounds will be conservative (larger than necessary) until cleared.
            if (_selectedIds.isEmpty()) {
                selectionBounds.setEmpty()
            }
        }
    }

    fun clearSelection() {
        synchronized(lock) {
            _selectedIds.clear()
            transformMatrix.reset()
            selectionBounds.setEmpty()
            clearImposter()
            _isGeneratingImposter = false
        }
    }

    fun hasSelection(): Boolean = synchronized(lock) { _selectedIds.isNotEmpty() }

    fun isSelected(item: CanvasItem): Boolean = synchronized(lock) { _selectedIds.contains(item.order) }

    fun isSelected(id: Long): Boolean = synchronized(lock) { _selectedIds.contains(id) }

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
