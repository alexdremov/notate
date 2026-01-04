package com.example.notate.util

import android.graphics.Path
import android.graphics.RectF
import com.example.notate.model.Stroke
import com.example.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot

object StrokeGeometry {
    // Very simplified check: do bounding boxes intersect?
    // For Stroke Eraser, we ideally want path intersection.
    // Implementing robust Path-Path intersection is complex.
    // We will use a "Points near Path" check.
    fun strokeIntersects(
        s1: Stroke,
        eraser: Stroke,
    ): Boolean {
        // Fast bounding box check
        if (!RectF.intersects(s1.bounds, eraser.bounds)) return false

        // Check 1: Exact Segment-Segment Intersection
        // This handles cases where strokes cross but their vertices are far apart (sparse data).
        if (checkSegmentsIntersection(s1.points, eraser.points)) return true

        val threshold = (s1.width + eraser.width) / 2f

        // Check 2: Do any of s1's points lie close to eraser's segments?
        // This handles "Sparse Eraser" vs "Dense Stroke"
        if (checkPointsVsSegments(s1.points, eraser.points, threshold)) return true

        // Check 3: Do any of eraser's points lie close to s1's segments?
        // This handles "Sparse Stroke" vs "Dense Eraser" (Common case: fast stroke, slow erase)
        if (checkPointsVsSegments(eraser.points, s1.points, threshold)) return true

        return false
    }

    private fun checkSegmentsIntersection(
        points1: List<TouchPoint>,
        points2: List<TouchPoint>,
    ): Boolean {
        if (points1.size < 2 || points2.size < 2) return false

        // Optimization: Don't check every segment against every segment if N*M is huge.
        // But for scribbles (usually < 100 points) and strokes (usually < 1000), it's okay.
        // Can optionally use bounding box pre-checks for segments.

        for (i in 0 until points1.size - 1) {
            val p1 = points1[i]
            val p2 = points1[i + 1]

            // Segment 1 Bounding Box
            val minX1 = minOf(p1.x, p2.x)
            val maxX1 = maxOf(p1.x, p2.x)
            val minY1 = minOf(p1.y, p2.y)
            val maxY1 = maxOf(p1.y, p2.y)

            for (j in 0 until points2.size - 1) {
                val p3 = points2[j]
                val p4 = points2[j + 1]

                // Fast Segment AABB check
                if (maxX1 < minOf(p3.x, p4.x) || minX1 > maxOf(p3.x, p4.x) ||
                    maxY1 < minOf(p3.y, p4.y) || minY1 > maxOf(p3.y, p4.y)
                ) {
                    continue
                }

                if (segmentsIntersect(p1, p2, p3, p4)) {
                    return true
                }
            }
        }
        return false
    }

    private fun segmentsIntersect(
        p1: TouchPoint,
        p2: TouchPoint,
        p3: TouchPoint,
        p4: TouchPoint,
    ): Boolean {
        fun ccw(
            a: TouchPoint,
            b: TouchPoint,
            c: TouchPoint,
        ): Float = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        val d1 = ccw(p3, p4, p1)
        val d2 = ccw(p3, p4, p2)
        val d3 = ccw(p1, p2, p3)
        val d4 = ccw(p1, p2, p4)

        // Strict intersection (straddle)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun checkPointsVsSegments(
        points: List<TouchPoint>,
        segments: List<TouchPoint>,
        threshold: Float,
    ): Boolean {
        if (segments.size < 2) return false // No segments

        // Optimization: iterate points, check if close to any segment
        for (p in points) {
            for (i in 0 until segments.size - 1) {
                val p1 = segments[i]
                val p2 = segments[i + 1]
                if (distPointToSegment(p.x, p.y, p1.x, p1.y, p2.x, p2.y) < threshold) {
                    return true
                }
            }
        }
        return false
    }

    private fun distPointToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        if (l2 == 0f) return hypot(px - x1, py - y1)

        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = t.coerceIn(0f, 1f)

        val projX = x1 + t * (x2 - x1)
        val projY = y1 + t * (y2 - y1)
        return hypot(px - projX, py - projY)
    }

    // Ray-Casting algorithm to check if point is inside polygon
    fun isPointInPolygon(
        x: Float,
        y: Float,
        polygon: List<TouchPoint>,
    ): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersect =
                ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun computeStrokeBounds(
        path: Path,
        width: Float,
        type: StrokeType,
    ): RectF {
        val bounds = RectF()
        path.computeBounds(bounds, true)

        // Determine expansion factor based on stroke type
        // standard expansion is 0.5 * width (radius).
        // We over-estimate to avoid clipping.
        val multiplier =
            when (type) {
                com.example.notate.model.StrokeType.HIGHLIGHTER -> 1.5f

                com.example.notate.model.StrokeType.CHARCOAL -> 2.0f

                // Texture scatter
                com.example.notate.model.StrokeType.BRUSH -> 1.2f

                else -> 1.0f // Safe double margin for standard strokes (Fountain, etc)
            }

        val expansion = width * multiplier
        // Additional constant padding for anti-aliasing and rounding errors
        val padding = 5f

        bounds.inset(-(expansion + padding), -(expansion + padding))
        return bounds
    }

    // Standard Eraser: Remove points covered by eraser
    fun splitStroke(
        target: Stroke,
        eraser: Stroke,
    ): List<Stroke> {
        var modificationHappened = false
        val newStrokes = ArrayList<Stroke>()
        val currentPoints = ArrayList<TouchPoint>()
        val threshold = (target.width + eraser.width) / 2f // Radius sum

        if (target.points.isEmpty()) return listOf(target)

        // Helper to check erasure of a single point
        fun isPointErased(p: TouchPoint): Boolean {
            if (!eraser.bounds.contains(p.x, p.y)) return false

            // Check distance to eraser segments (handles sparse eraser)
            if (eraser.points.size >= 2) {
                for (i in 0 until eraser.points.size - 1) {
                    val ep1 = eraser.points[i]
                    val ep2 = eraser.points[i + 1]
                    if (distPointToSegment(p.x, p.y, ep1.x, ep1.y, ep2.x, ep2.y) < threshold) {
                        return true
                    }
                }
            } else if (eraser.points.isNotEmpty()) {
                // Single point eraser
                val ep = eraser.points[0]
                if (hypot(p.x - ep.x, p.y - ep.y) < threshold) return true
            }
            return false
        }

        // Process first point
        var previousPoint = target.points[0]
        if (isPointErased(previousPoint)) {
            modificationHappened = true
        } else {
            currentPoints.add(previousPoint)
        }

        // Iterate remaining points
        for (i in 1 until target.points.size) {
            val currentPoint = target.points[i]
            val dist = hypot(currentPoint.x - previousPoint.x, currentPoint.y - previousPoint.y)

            // Subdivide if segment is long and close to eraser
            // Use 0.5 * threshold as step size for high precision
            val step = threshold / 2f

            if (dist > step && segmentIntersectsBounds(previousPoint, currentPoint, eraser.bounds)) {
                val steps = (dist / step).toInt()
                // Check intermediate points
                for (j in 1..steps) {
                    val t = j.toFloat() / (steps + 1)
                    val interpX = previousPoint.x + (currentPoint.x - previousPoint.x) * t
                    val interpY = previousPoint.y + (currentPoint.y - previousPoint.y) * t
                    val interpP = previousPoint.pressure + (currentPoint.pressure - previousPoint.pressure) * t
                    val interpS = previousPoint.size + (currentPoint.size - previousPoint.size) * t
                    val interpTime = previousPoint.timestamp + ((currentPoint.timestamp - previousPoint.timestamp) * t).toLong()

                    val p = TouchPoint(interpX, interpY, interpP, interpS, interpTime)

                    if (isPointErased(p)) {
                        modificationHappened = true
                        if (currentPoints.isNotEmpty()) {
                            newStrokes.add(createSubStroke(target, currentPoints))
                            currentPoints.clear()
                        }
                    } else {
                        currentPoints.add(p)
                    }
                }
            }

            if (isPointErased(currentPoint)) {
                modificationHappened = true
                if (currentPoints.isNotEmpty()) {
                    newStrokes.add(createSubStroke(target, currentPoints))
                    currentPoints.clear()
                }
            } else {
                currentPoints.add(currentPoint)
            }
            previousPoint = currentPoint
        }

        if (!modificationHappened) {
            return listOf(target)
        }

        // Add final segment
        if (currentPoints.isNotEmpty()) {
            newStrokes.add(createSubStroke(target, currentPoints))
        }

        return newStrokes
    }

    private fun segmentIntersectsBounds(
        p1: TouchPoint,
        p2: TouchPoint,
        bounds: RectF,
    ): Boolean {
        val minX = minOf(p1.x, p2.x)
        val maxX = maxOf(p1.x, p2.x)
        val minY = minOf(p1.y, p2.y)
        val maxY = maxOf(p1.y, p2.y)
        return minX < bounds.right && maxX > bounds.left && minY < bounds.bottom && maxY > bounds.top
    }

    private fun createSubStroke(
        original: Stroke,
        points: List<TouchPoint>,
    ): Stroke {
        if (points.size < 2) {
            // Single point stroke? Maybe just a dot.
            // Reconstruct path
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
            val bounds = computeStrokeBounds(path, original.width, original.style)
            return original.copy(
                path = path,
                points = ArrayList(points),
                bounds = bounds,
                strokeOrder = 0, // Will be assigned later
            )
        }

        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val cx = (p1.x + p2.x) / 2
            val cy = (p1.y + p2.y) / 2
            path.quadTo(p1.x, p1.y, cx, cy)
        }
        path.lineTo(points.last().x, points.last().y)

        val bounds = computeStrokeBounds(path, original.width, original.style)

        return original.copy(
            path = path,
            points = ArrayList(points),
            bounds = bounds,
            strokeOrder = 0, // Will be assigned later
        )
    }
}
