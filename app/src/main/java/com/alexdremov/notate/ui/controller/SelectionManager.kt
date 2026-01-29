package com.alexdremov.notate.ui.controller

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem

/**
 * Manages the state of the active selection.
 * Holds the selected item IDs and their Original Bounds (packed) to ensure bulletproof retrieval.
 * Thread-safe.
 */
class SelectionManager {
    private val lock = Any()

    // Robust Architecture: Store IDs AND Bounds.
    // Storing Bounds allows us to pinpoint items in the spatial index (RegionManager)
    // without relying on error-prone global area queries.
    private val _ids = ArrayList<Long>()
    private val _idSet = HashSet<Long>()
    private var _bounds = FloatArray(1024) // Packed [left, top, right, bottom]
    private var _count = 0

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

    fun getSelectedIds(): Set<Long> = synchronized(lock) { HashSet(_idSet) }

    fun forEachSelected(action: (Long, RectF) -> Unit) {
        synchronized(lock) {
            val r = RectF()
            for (i in 0 until _count) {
                r.set(_bounds[i * 4], _bounds[i * 4 + 1], _bounds[i * 4 + 2], _bounds[i * 4 + 3])
                action(_ids[i], r)
            }
        }
    }

    /**
     * Returns a defensive copy of the current transformation matrix.
     */
    fun getTransform(): Matrix {
        synchronized(lock) {
            return Matrix(transformMatrix)
        }
    }

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

    private fun ensureCapacity(minCapacity: Int) {
        if (_bounds.size < minCapacity * 4) {
            val newSize = (_bounds.size * 2).coerceAtLeast(minCapacity * 4)
            val newArray = FloatArray(newSize)
            System.arraycopy(_bounds, 0, newArray, 0, _count * 4)
            _bounds = newArray
        }
    }

    fun select(item: CanvasItem) {
        synchronized(lock) {
            if (_count == 0) {
                selectionBounds.set(item.bounds)
            } else {
                selectionBounds.union(item.bounds)
            }

            // Check for duplicate ID to enforce set semantics
            if (!_idSet.contains(item.order)) {
                ensureCapacity(_count + 1)
                _ids.add(item.order)
                _idSet.add(item.order)
                val base = _count * 4
                _bounds[base] = item.bounds.left
                _bounds[base + 1] = item.bounds.top
                _bounds[base + 2] = item.bounds.right
                _bounds[base + 3] = item.bounds.bottom
                _count++
            }
        }
    }

    fun selectAll(items: List<CanvasItem>) {
        synchronized(lock) {
            ensureCapacity(_count + items.size)
            items.forEach { item ->
                if (_count == 0) {
                    selectionBounds.set(item.bounds)
                } else {
                    selectionBounds.union(item.bounds)
                }

                if (!_idSet.contains(item.order)) {
                    _ids.add(item.order)
                    _idSet.add(item.order)
                    val base = _count * 4
                    _bounds[base] = item.bounds.left
                    _bounds[base + 1] = item.bounds.top
                    _bounds[base + 2] = item.bounds.right
                    _bounds[base + 3] = item.bounds.bottom
                    _count++
                }
            }
        }
    }

    fun deselect(item: CanvasItem) {
        synchronized(lock) {
            val idx = _ids.indexOf(item.order)
            if (idx != -1) {
                _ids.removeAt(idx)
                _idSet.remove(item.order)

                // Shift bounds
                val remaining = _count - 1 - idx
                if (remaining > 0) {
                    System.arraycopy(_bounds, (idx + 1) * 4, _bounds, idx * 4, remaining * 4)
                }
                _count--

                if (_count == 0) {
                    selectionBounds.setEmpty()
                }
                // Note: We don't shrink selectionBounds on deselect (expensive)
            }
        }
    }

    fun clearSelection() {
        synchronized(lock) {
            _ids.clear()
            _idSet.clear()
            _count = 0
            transformMatrix.reset()
            selectionBounds.setEmpty()
            clearImposter()
            _isGeneratingImposter = false
        }
    }

    fun hasSelection(): Boolean = synchronized(lock) { _count > 0 }

    fun isSelected(item: CanvasItem): Boolean = synchronized(lock) { _idSet.contains(item.order) }

    fun isSelected(id: Long): Boolean = synchronized(lock) { _idSet.contains(id) }

    fun getOriginalBounds(): RectF = synchronized(lock) { RectF(selectionBounds) }

    fun getTransformedBounds(): RectF {
        synchronized(lock) {
            val r = RectF(selectionBounds)
            transformMatrix.mapRect(r)
            return r
        }
    }

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
