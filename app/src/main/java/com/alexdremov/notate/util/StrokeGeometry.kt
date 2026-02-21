package com.alexdremov.notate.util

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.api.device.epd.EpdController
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

    fun distPointToStroke(
        x: Float,
        y: Float,
        stroke: Stroke,
    ): Float {
        if (!stroke.bounds.contains(x, y)) {
            // Optimization: If outside expanded bounds, return closest distance to bounds (approx)
            // or just a large number if we only care about hits.
            // Check bounding box distance first
            val dX = maxOf(stroke.bounds.left - x, 0f, x - stroke.bounds.right)
            val dY = maxOf(stroke.bounds.top - y, 0f, y - stroke.bounds.bottom)
            val boundDist = hypot(dX, dY)
            // If bounding box is far, we can return that. But stroke might be diagonal inside box.
            // So this is a lower bound. If lower bound > threshold, we can skip.
            // For now, let's just return boundDist if it is large, but to be precise we need segments.
        }

        var minDist = Float.MAX_VALUE
        val points = stroke.points
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) return hypot(x - points[0].x, y - points[0].y)

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val d = distPointToSegment(x, y, p1.x, p1.y, p2.x, p2.y)
            if (d < minDist) minDist = d
        }
        return minDist
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

    /**
     * Checks if a Rectangle is STRICTLY and FULLY contained within a Polygon.
     * Logic:
     * 1. All 4 corners of the Rect must be inside the Polygon.
     * 2. No edge of the Polygon must intersect the Rect (this handles "donut" or C-shape cases where corners are in but a hole is present).
     *
     * Note: This is an optimization. If it returns true, it's definitely inside.
     * If it returns false, it MIGHT still be inside (if logic is conservative), but for "Strict" selection of strokes,
     * if the bounding box is inside, the stroke is inside.
     */
    fun isRectFullyInPolygon(
        rect: RectF,
        polygon: List<TouchPoint>,
    ): Boolean {
        // 1. Check 4 Corners
        if (!isPointInPolygon(rect.left, rect.top, polygon)) return false
        if (!isPointInPolygon(rect.right, rect.top, polygon)) return false
        if (!isPointInPolygon(rect.right, rect.bottom, polygon)) return false
        if (!isPointInPolygon(rect.left, rect.bottom, polygon)) return false

        // 2. Check Edge Intersections
        // If any polygon edge crosses the rect, then the rect is not "fully, cleanly" inside
        // (or at least, we fallback to point check).
        // Actually, if corners are inside, an intersection implies the polygon goes *into* the rect
        // and potentially *out* (but corners are inside).
        // E.g. a C-shape wrapping around the center of the rect.
        for (i in 0 until polygon.size - 1) {
            if (doesSegmentIntersectRect(polygon[i], polygon[i + 1], rect)) return false
        }
        // Check closing segment
        if (polygon.isNotEmpty() && doesSegmentIntersectRect(polygon.last(), polygon.first(), rect)) return false

        return true
    }

    /**
     * Checks if a Stroke intersects a Rectangle precisely (Segment-Rect intersection).
     * 1. Fast Containment Check (Bounds)
     * 2. Point Inclusion Check
     * 3. Segment Intersection Check
     */
    fun strokeIntersectsRect(
        stroke: Stroke,
        rect: RectF,
    ): Boolean {
        // 1. Fast Bounds Check (Pre-condition, usually already done but safe to repeat)
        if (!RectF.intersects(stroke.bounds, rect)) return false

        // 2. Fast Containment Check
        // If selection rect contains the stroke bounds, it's a hit.
        if (rect.contains(stroke.bounds)) return true

        // 3. Point Inclusion Check
        // If any point of the stroke is inside, it's a hit.
        for (p in stroke.points) {
            if (rect.contains(p.x, p.y)) return true
        }

        // 4. Segment Intersection Check
        // If no points are inside, the stroke might "slice" through the rect.
        if (stroke.points.size < 2) return false

        for (i in 0 until stroke.points.size - 1) {
            if (doesSegmentIntersectRect(stroke.points[i], stroke.points[i + 1], rect)) return true
        }

        return false
    }

    private fun doesSegmentIntersectRect(
        p1: TouchPoint,
        p2: TouchPoint,
        rect: RectF,
    ): Boolean {
        // Cohen-Sutherland-like trivial reject/accept logic
        val minX = minOf(p1.x, p2.x)
        val maxX = maxOf(p1.x, p2.x)
        val minY = minOf(p1.y, p2.y)
        val maxY = maxOf(p1.y, p2.y)

        // Segment is completely outside
        if (maxX < rect.left || minX > rect.right || maxY < rect.top || minY > rect.bottom) return false

        // Check intersection with each of the 4 borders
        // Top
        if (linesIntersect(p1.x, p1.y, p2.x, p2.y, rect.left, rect.top, rect.right, rect.top)) return true
        // Bottom
        if (linesIntersect(p1.x, p1.y, p2.x, p2.y, rect.left, rect.bottom, rect.right, rect.bottom)) return true
        // Left
        if (linesIntersect(p1.x, p1.y, p2.x, p2.y, rect.left, rect.top, rect.left, rect.bottom)) return true
        // Right
        if (linesIntersect(p1.x, p1.y, p2.x, p2.y, rect.right, rect.top, rect.right, rect.bottom)) return true

        return false
    }

    private fun linesIntersect(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        x4: Float,
        y4: Float,
    ): Boolean {
        // Standard line-line intersection
        val d = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1)
        if (d == 0f) return false // Parallel

        val ua = ((x4 - x3) * (y1 - y3) - (y4 - y3) * (x1 - x3)) / d
        val ub = ((x2 - x1) * (y1 - y3) - (y2 - y1) * (x1 - x3)) / d

        return (ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1)
    }

    /**
     * Ramer-Douglas-Peucker simplification
     */
    fun simplifyPoints(
        points: List<TouchPoint>,
        epsilon: Float,
    ): List<TouchPoint> {
        if (points.size < 3) return points

        var dmax = 0f
        var index = 0
        val end = points.size - 1

        for (i in 1 until end) {
            val d = distPointToSegment(points[i].x, points[i].y, points[0].x, points[0].y, points[end].x, points[end].y)
            if (d > dmax) {
                dmax = d
                index = i
            }
        }

        if (dmax > epsilon) {
            val recResults1 = simplifyPoints(points.subList(0, index + 1), epsilon)
            val recResults2 = simplifyPoints(points.subList(index, end + 1), epsilon)

            val result = ArrayList<TouchPoint>(recResults1.size + recResults2.size - 1)
            result.addAll(recResults1.subList(0, recResults1.size - 1))
            result.addAll(recResults2)
            return result
        } else {
            return listOf(points[0], points[end])
        }
    }

    /**
     * Flattens a Path into a list of TouchPoints approximating the curve.
     * Useful for polygon containment checks.
     */
    fun flattenPath(
        path: Path,
        step: Float = 10f,
    ): List<TouchPoint> {
        val points = ArrayList<TouchPoint>()
        val pm = PathMeasure(path, false)
        val length = pm.length
        val coords = floatArrayOf(0f, 0f)
        var d = 0f
        while (d <= length) {
            pm.getPosTan(d, coords, null)
            points.add(TouchPoint(coords[0], coords[1], 0f, 0f, 0))
            d += step
        }
        // Ensure closed loop for polygon logic if start/end match
        if (points.isNotEmpty()) {
            val first = points.first()
            val last = points.last()
            if (hypot(first.x - last.x, first.y - last.y) > 1f) {
                // If path was supposed to be closed but isn't perfectly, close it?
                // For Lasso, we usually close it manually in the Path object.
                // But having the last point match the first is good for the loop.
                // We'll leave it as is, isPointInPolygon handles loose ends by closing implicitly (j=size-1).
            }
        }
        return points
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
                com.alexdremov.notate.model.StrokeType.HIGHLIGHTER -> 1.5f

                com.alexdremov.notate.model.StrokeType.CHARCOAL -> 2.0f

                // Texture scatter
                com.alexdremov.notate.model.StrokeType.BRUSH -> 1.2f

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
                    // Normalize tilt to Float during interpolation
                    val interpTX = previousPoint.tiltX.toFloat() + (currentPoint.tiltX.toFloat() - previousPoint.tiltX.toFloat()) * t
                    val interpTY = previousPoint.tiltY.toFloat() + (currentPoint.tiltY.toFloat() - previousPoint.tiltY.toFloat()) * t
                    val interpTime = previousPoint.timestamp + ((currentPoint.timestamp - previousPoint.timestamp) * t).toLong()

                    val p = TouchPoint(interpX, interpY, interpP, interpS, interpTX.toInt(), interpTY.toInt(), interpTime)

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
                if (currentPoints.size >= 2) {
                    newStrokes.add(createSubStroke(target, currentPoints))
                }
                currentPoints.clear()
            } else {
                currentPoints.add(currentPoint)
            }
            previousPoint = currentPoint
        }

        if (!modificationHappened) {
            return listOf(target)
        }

        // Add final segment
        if (currentPoints.size >= 2) {
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

    fun calculateLength(points: List<TouchPoint>): Float {
        if (points.size < 2) return 0f
        var length = 0f
        for (i in 0 until points.size - 1) {
            length += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return length
    }

    fun calculateAngle(
        p1: TouchPoint,
        p2: TouchPoint,
        p3: TouchPoint,
    ): Double {
        val dx1 = p2.x - p1.x
        val dy1 = p2.y - p1.y
        val dx2 = p3.x - p2.x
        val dy2 = p3.y - p2.y

        val dot = dx1 * dx2 + dy1 * dy2
        val cross = dx1 * dy2 - dy1 * dx2

        val angleRad = kotlin.math.atan2(cross.toDouble(), dot.toDouble())
        return kotlin.math.abs(Math.toDegrees(angleRad))
    }

    /**
     * Computes the true Axis-Aligned Bounding Box (AABB) of a rectangle after it has been rotated
     * around its center.
     */
    fun computeRotatedBounds(
        logicalBounds: RectF,
        rotation: Float,
    ): RectF {
        if (rotation % 360f == 0f) return RectF(logicalBounds)

        val matrix = Matrix()
        matrix.setRotate(rotation, logicalBounds.centerX(), logicalBounds.centerY())

        val corners =
            floatArrayOf(
                logicalBounds.left,
                logicalBounds.top,
                logicalBounds.right,
                logicalBounds.top,
                logicalBounds.right,
                logicalBounds.bottom,
                logicalBounds.left,
                logicalBounds.bottom,
            )

        matrix.mapPoints(corners)

        var minX = corners[0]
        var maxX = corners[0]
        var minY = corners[1]
        var maxY = corners[1]

        for (i in 1..3) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * Transforms an item's logical bounds and rotation by a given matrix, then computes the new AABB.
     * Logic:
     * 1. Extract translation by mapping the center point.
     * 2. Extract scaling by mapping local axis vectors.
     * 3. Extract rotation by mapping a unit vector.
     */
    fun transformItemLogicalBounds(
        originalLogicalBounds: RectF,
        originalRotation: Float,
        transform: Matrix,
    ): Triple<RectF, Float, RectF> { // newLogicalBounds, newRotation, newAabb
        // 1. Calculate new center in World Space
        val center = floatArrayOf(originalLogicalBounds.centerX(), originalLogicalBounds.centerY())
        transform.mapPoints(center)

        // 2. Calculate new width and height (extracting scaling from the matrix)
        val vW = floatArrayOf(originalLogicalBounds.width(), 0f)
        transform.mapVectors(vW)
        val newWidth = kotlin.math.hypot(vW[0], vW[1])

        val vH = floatArrayOf(0f, originalLogicalBounds.height())
        transform.mapVectors(vH)
        val newHeight = kotlin.math.hypot(vH[0], vH[1])

        // 3. Calculate rotation change
        val vRot = floatArrayOf(1f, 0f)
        transform.mapVectors(vRot)
        val deltaRot = Math.toDegrees(kotlin.math.atan2(vRot[1].toDouble(), vRot[0].toDouble())).toFloat()
        val newRotation = (originalRotation + deltaRot) % 360f

        // 4. Construct new logical bounds (axis-aligned in its local space)
        val newLogical =
            RectF(
                center[0] - newWidth / 2f,
                center[1] - newHeight / 2f,
                center[0] + newWidth / 2f,
                center[1] + newHeight / 2f,
            )

        val newAabb = computeRotatedBounds(newLogical, newRotation)

        return Triple(newLogical, newRotation, newAabb)
    }
}
