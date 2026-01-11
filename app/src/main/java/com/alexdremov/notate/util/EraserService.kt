package com.alexdremov.notate.util

import android.graphics.RectF
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.HistoryAction
import com.alexdremov.notate.model.Stroke
import java.util.ArrayList

/**
 * Service dedicated to calculating erasure operations.
 * Decouples geometric intersection logic from the InfiniteCanvasModel.
 */
object EraserService {
    /**
     * Calculates the actions (Remove/Replace) needed to perform an erasure.
     * This is a computationally intensive operation that should ideally happen
     * outside the main write lock if possible.
     *
     * @param candidates Strokes potentially affected by the erasure (from Quadtree).
     * @param eraserStroke The path/shape of the eraser.
     * @param type The erasure strategy.
     * @return Pair of (Actions to Apply, Invalidated Bounds).
     */
    fun calculateErasure(
        candidates: List<Stroke>,
        eraserStroke: Stroke,
        type: EraserType,
    ): Pair<List<HistoryAction>, RectF> {
        val actionsToApply = ArrayList<HistoryAction>()
        val boundsToInvalidate = RectF()

        when (type) {
            EraserType.STROKE -> {
                val toRemove =
                    candidates.filter { stroke ->
                        RectF.intersects(stroke.bounds, eraserStroke.bounds) &&
                            StrokeGeometry.strokeIntersects(stroke, eraserStroke)
                    }
                if (toRemove.isNotEmpty()) {
                    actionsToApply.add(HistoryAction.Remove(toRemove))
                    boundsToInvalidate.union(calculateBounds(toRemove))
                }
            }

            EraserType.LASSO -> {
                val toRemove =
                    candidates.filter { stroke ->
                        if (!eraserStroke.bounds.contains(stroke.bounds)) return@filter false
                        StrokeGeometry.isPointInPolygon(stroke.points[0].x, stroke.points[0].y, eraserStroke.points) &&
                            stroke.points.all { p ->
                                StrokeGeometry.isPointInPolygon(p.x, p.y, eraserStroke.points)
                            }
                    }
                if (toRemove.isNotEmpty()) {
                    actionsToApply.add(HistoryAction.Remove(toRemove))
                    boundsToInvalidate.union(calculateBounds(toRemove))
                }
            }

            EraserType.STANDARD -> {
                val removed = ArrayList<Stroke>()
                val added = ArrayList<Stroke>()

                candidates.forEach { target ->
                    if (RectF.intersects(target.bounds, eraserStroke.bounds)) {
                        val newParts = StrokeGeometry.splitStroke(target, eraserStroke)
                        if (newParts.size != 1 || newParts[0] !== target) {
                            removed.add(target)
                            added.addAll(newParts)
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    actionsToApply.add(HistoryAction.Replace(removed, added))
                    boundsToInvalidate.union(calculateBounds(removed))
                    boundsToInvalidate.union(calculateBounds(added))
                }
            }
        }

        return Pair(actionsToApply, boundsToInvalidate)
    }

    private fun calculateBounds(strokes: List<Stroke>): RectF {
        val r = RectF()
        if (strokes.isNotEmpty()) {
            r.set(strokes[0].bounds)
            for (i in 1 until strokes.size) {
                r.union(strokes[i].bounds)
            }
        }
        return r
    }
}
