package com.alexdremov.notate.controller

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import java.util.concurrent.ConcurrentHashMap

class SelectionManager {
    private val _selectedStrokes = ConcurrentHashMap.newKeySet<Stroke>()
    val selectedStrokes: Set<Stroke> get() = _selectedStrokes

    // Current transformation applied to the selection (delta)
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
    
    fun getTransformedBounds(): RectF {
        val r = RectF(selectionBounds)
        transformMatrix.mapRect(r)
        return r
    }
    
    fun translate(dx: Float, dy: Float) {
        transformMatrix.postTranslate(dx, dy)
    }

    fun applyTransformToStrokes(commit: (original: Stroke, transformed: Stroke) -> Unit) {
        // Apply the transform to all selected strokes and commit them
        // This usually involves creating new Stroke objects
        // We will need logic to transform Path and Points
    }
}
