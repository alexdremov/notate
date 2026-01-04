package com.example.notate.util

import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.*

object ShapeRecognizer {
    enum class RecognizedShape {
        NONE,
        LINE,
        CIRCLE,
        TRIANGLE,
        SQUARE,
        PENTAGON,
        HEXAGON,
    }

    data class RecognitionResult(
        val shape: RecognizedShape,
        val segments: List<List<PointF>>, // Segments for separate strokes
        val path: Path, // Combined path for preview
    )

    fun recognize(points: List<TouchPoint>): RecognitionResult? {
        if (points.size < 10) {
            Log.d("ShapeRecognizer", "Recognition aborted: too few points (${points.size})")
            return null
        }

        val simplePoints = points.map { PointF(it.x, it.y) }
        val start = simplePoints.first()
        val end = simplePoints.last()

        // --- Line Detection ---
        val distStartEnd = hypot(end.x - start.x, end.y - start.y)
        var pathLength = 0f
        for (i in 0 until simplePoints.size - 1) {
            pathLength += hypot(simplePoints[i + 1].x - simplePoints[i].x, simplePoints[i + 1].y - simplePoints[i].y)
        }

        val linearityRatio = if (pathLength > 0) distStartEnd / pathLength else 0f
        Log.d("ShapeRecognizer", "Linearity check: ratio=$linearityRatio (threshold=0.92), pathLength=$pathLength")

        // Linearity check: If straight distance is > 92% of path length
        if (distStartEnd > pathLength * 0.92) {
            Log.d("ShapeRecognizer", "Recognized as LINE")
            val path = Path()
            path.moveTo(start.x, start.y)
            path.lineTo(end.x, end.y)
            return RecognitionResult(RecognizedShape.LINE, listOf(listOf(start, end)), path)
        }

        // --- Closed Shape Check ---
        if (pathLength < 20f) {
            Log.d("ShapeRecognizer", "Recognition aborted: path too short ($pathLength)")
            return null
        }

        val closureRatio = if (pathLength > 0) distStartEnd / pathLength else 1.0f
        Log.d("ShapeRecognizer", "Closure check: ratio=$closureRatio (threshold=0.30)")

        if (distStartEnd > pathLength * 0.30) {
            Log.d("ShapeRecognizer", "Open shape detected, ratio $closureRatio > 0.30. Not a line or closed shape.")
            return null
        }

        // Close the loop for calculation
        val closedPoints = simplePoints.toMutableList()
        val closeX = (start.x + end.x) / 2
        val closeY = (start.y + end.y) / 2
        closedPoints[0] = PointF(closeX, closeY)
        closedPoints[closedPoints.size - 1] = PointF(closeX, closeY)

        // --- Metrics Calculation ---

        // 1. Circle Metrics
        var cx = 0f
        var cy = 0f
        for (p in closedPoints) {
            cx += p.x
            cy += p.y
        }
        cx /= closedPoints.size
        cy /= closedPoints.size

        val radii = closedPoints.map { hypot(it.x - cx, it.y - cy) }
        val avgRadius = radii.average().toFloat()

        // Mean Absolute Deviation (Circle Error)
        val circleError = radii.map { abs(it - avgRadius) }.average().toFloat()

        // 2. Polygon Metrics (Douglas-Peucker)
        // Use 18% of radius as tolerance (tunable)
        val tolerance = avgRadius * 0.18f
        val simplified = douglasPeucker(closedPoints, tolerance)

        // Calculate true vertex count (remove duplicate end point)
        var vertexCount = simplified.size
        if (simplified.size > 1 && simplified.first().equals(simplified.last())) {
            vertexCount--
        }

        // Polygon Error: Average distance from original points to simplified edges
        val polyError = calculatePolygonError(closedPoints, simplified)

        Log.d("ShapeRecognizer", "Scores - VertexCount: $vertexCount, CircleErr: $circleError, PolyErr: $polyError, AvgRad: $avgRadius")

        // --- Decision Logic ---

        val polyScore = polyError
        val circleScore = circleError

        if (vertexCount == 3) {
            // It's a triangle candidate.
            if (polyScore < circleScore) {
                Log.d("ShapeRecognizer", "Recognized as TRIANGLE (Score: $polyScore vs $circleScore)")
                return createPolygonResult(RecognizedShape.TRIANGLE, simplified)
            }
        } else if (vertexCount == 4) {
            // Square vs Circle.
            if (polyScore < circleScore) {
                Log.d("ShapeRecognizer", "Recognized as SQUARE (Score: $polyScore vs $circleScore)")
                return createRectangleResult(simplified)
            } else {
                Log.d("ShapeRecognizer", "Recognized as CIRCLE (Override 4-vertex) (Score: $circleScore vs $polyScore)")
                return createCircleResult(cx, cy, avgRadius)
            }
        } else if (vertexCount == 5) {
            // Pentagon vs Circle
            if (polyScore < circleScore) {
                Log.d("ShapeRecognizer", "Recognized as PENTAGON (Score: $polyScore vs $circleScore)")
                return createRegularPolygonResult(RecognizedShape.PENTAGON, simplified)
            } else {
                Log.d("ShapeRecognizer", "Recognized as CIRCLE (Override 5-vertex) (Score: $circleScore vs $polyScore)")
                return createCircleResult(cx, cy, avgRadius)
            }
        } else if (vertexCount == 6) {
            // Hexagon vs Circle
            if (polyScore < circleScore) {
                Log.d("ShapeRecognizer", "Recognized as HEXAGON (Score: $polyScore vs $circleScore)")
                return createRegularPolygonResult(RecognizedShape.HEXAGON, simplified)
            } else {
                Log.d("ShapeRecognizer", "Recognized as CIRCLE (Override 6-vertex) (Score: $circleScore vs $polyScore)")
                return createCircleResult(cx, cy, avgRadius)
            }
        } else {
            // Many vertices -> Likely a Circle or complex shape
            if (circleError < avgRadius * 0.20) {
                Log.d("ShapeRecognizer", "Recognized as CIRCLE (Vertices: $vertexCount) (Err: $circleError)")
                return createCircleResult(cx, cy, avgRadius)
            }
        }

        Log.d("ShapeRecognizer", "Recognition failed: No matching shape found for vertexCount=$vertexCount")
        return null
    }

    private fun createRegularPolygonResult(
        type: RecognizedShape,
        points: List<PointF>,
    ): RecognitionResult {
        // Remove duplicate end point
        val uniquePoints =
            if (points.size > 1 && points.first().equals(points.last())) {
                points.dropLast(1)
            } else {
                points
            }

        val sides = if (type == RecognizedShape.PENTAGON) 5 else 6

        // Calculate Centroid
        var cx = 0f
        var cy = 0f
        for (p in uniquePoints) {
            cx += p.x
            cy += p.y
        }
        cx /= uniquePoints.size
        cy /= uniquePoints.size

        // Calculate average radius
        var avgRadius = 0f
        for (p in uniquePoints) {
            avgRadius += hypot(p.x - cx, p.y - cy)
        }
        avgRadius /= uniquePoints.size

        // Determine rotation based on the first vertex
        val firstPoint = uniquePoints[0]
        val startAngle = atan2(firstPoint.y - cy, firstPoint.x - cx)

        val segments = mutableListOf<List<PointF>>()
        val path = Path()

        val vertices = mutableListOf<PointF>()
        for (i in 0 until sides) {
            val angle = startAngle + i * (2 * PI / sides)
            val px = (cx + avgRadius * cos(angle)).toFloat()
            val py = (cy + avgRadius * sin(angle)).toFloat()
            vertices.add(PointF(px, py))
        }

        // Build Segments
        for (i in 0 until vertices.size) {
            val start = vertices[i]
            val end = vertices[(i + 1) % vertices.size]
            segments.add(listOf(start, end))

            if (i == 0) path.moveTo(start.x, start.y)
            path.lineTo(end.x, end.y)
        }
        path.close()

        return RecognitionResult(type, segments, path)
    }

    private fun createRectangleResult(points: List<PointF>): RecognitionResult {
        val uniquePoints =
            if (points.size > 1 && points.first().equals(points.last())) {
                points.dropLast(1)
            } else {
                points
            }

        if (uniquePoints.size != 4) return createPolygonResult(RecognizedShape.SQUARE, points)

        // Centroid
        var cx = 0f
        var cy = 0f
        for (p in uniquePoints) {
            cx += p.x
            cy += p.y
        }
        cx /= 4f
        cy /= 4f

        // Robust Orientation Calculation: Quadruple Angle Vector Sum
        // Maps 0, 90, 180, 270 degrees to the same vector direction.
        var sumCos4 = 0f
        var sumSin4 = 0f

        for (i in 0 until 4) {
            val p1 = uniquePoints[i]
            val p2 = uniquePoints[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val length = hypot(dx, dy)
            val angle = atan2(dy, dx)

            // Weight by length so longer sides dominate the decision
            sumCos4 += length * cos(4 * angle)
            sumSin4 += length * sin(4 * angle)
        }

        val avg4Angle = atan2(sumSin4, sumCos4)
        var baseAngle = avg4Angle / 4f
        // baseAngle is now in range (-PI/4, PI/4], i.e., (-45, 45] degrees

        // Snap to axis (0 degrees) if within 5 degrees
        val snapThreshold = 5.0 * PI / 180.0
        if (abs(baseAngle) < snapThreshold) {
            baseAngle = 0f
        }

        val cosA = cos(baseAngle)
        val sinA = sin(baseAngle)

        // Dimensions: Average width and height relative to the baseAngle
        // We project the sides onto the baseAngle vector (Width) and perpendicular (Height)
        var sumW = 0f
        var sumH = 0f
        for (i in 0 until 4) {
            val p1 = uniquePoints[i]
            val p2 = uniquePoints[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y

            // Rotate the side vector back by baseAngle to align with axes
            val rx = dx * cosA + dy * sinA
            val ry = -dx * sinA + dy * cosA

            sumW += abs(rx)
            sumH += abs(ry)
        }

        // Each dimension is contributed to by 2 sides (approx)
        val avgW = sumW / 2f
        val avgH = sumH / 2f

        // Construct 4 vertices for a rectangle centered at cx, cy
        val vertices = mutableListOf<PointF>()
        val localOffsets =
            arrayOf(
                PointF(-avgW / 2, -avgH / 2),
                PointF(avgW / 2, -avgH / 2),
                PointF(avgW / 2, avgH / 2),
                PointF(-avgW / 2, avgH / 2),
            )

        for (lp in localOffsets) {
            val rx = lp.x * cosA - lp.y * sinA
            val ry = lp.x * sinA + lp.y * cosA
            vertices.add(PointF(cx + rx, cy + ry))
        }

        val segments = mutableListOf<List<PointF>>()
        val path = Path()
        path.moveTo(vertices[0].x, vertices[0].y)

        for (i in 0 until vertices.size) {
            val start = vertices[i]
            val end = vertices[(i + 1) % vertices.size]
            segments.add(listOf(start, end))
            path.lineTo(end.x, end.y)
        }
        path.close()

        return RecognitionResult(RecognizedShape.SQUARE, segments, path)
    }

    private fun createPolygonResult(
        type: RecognizedShape,
        points: List<PointF>,
    ): RecognitionResult {
        // Ensure unique points (Douglas Peucker sometimes leaves dupe start/end)
        val uniquePoints =
            if (points.size > 1 && points.first().equals(points.last())) {
                points.dropLast(1)
            } else {
                points
            }

        val segments = mutableListOf<List<PointF>>()
        val path = Path()

        if (uniquePoints.isNotEmpty()) {
            path.moveTo(uniquePoints[0].x, uniquePoints[0].y)
            for (i in 0 until uniquePoints.size) {
                val start = uniquePoints[i]
                val end = uniquePoints[(i + 1) % uniquePoints.size]
                segments.add(listOf(start, end))
                path.lineTo(end.x, end.y)
            }
            path.close()
        }

        return RecognitionResult(type, segments, path)
    }

    private fun createCircleResult(
        cx: Float,
        cy: Float,
        radius: Float,
    ): RecognitionResult {
        val path = Path()
        path.addCircle(cx, cy, radius, Path.Direction.CW)

        val circlePoints = mutableListOf<PointF>()
        val steps = 60
        for (i in 0..steps) {
            val angle = 2.0 * PI * i / steps
            circlePoints.add(
                PointF((cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat()),
            )
        }
        // Circle is a single continuous segment
        return RecognitionResult(RecognizedShape.CIRCLE, listOf(circlePoints), path)
    }

    private fun calculatePolygonError(
        original: List<PointF>,
        simplified: List<PointF>,
    ): Float {
        if (simplified.size < 2) return Float.MAX_VALUE
        var totalError = 0f

        for (p in original) {
            var minD = Float.MAX_VALUE
            for (i in 0 until simplified.size - 1) {
                val d = pointSegmentDist(p, simplified[i], simplified[i + 1])
                if (d < minD) minD = d
            }
            // Check closing segment if implies closed
            if (simplified.first().equals(simplified.last())) {
                // already handled by loop
            } else {
                val d = pointSegmentDist(p, simplified.last(), simplified.first())
                if (d < minD) minD = d
            }
            totalError += minD
        }
        return totalError / original.size
    }

    private fun pointSegmentDist(
        p: PointF,
        a: PointF,
        b: PointF,
    ): Float {
        val l2 = (a.x - b.x).pow(2) + (a.y - b.y).pow(2)
        if (l2 == 0f) return hypot(p.x - a.x, p.y - a.y)

        var t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2
        t = t.coerceIn(0f, 1f)

        val projX = a.x + t * (b.x - a.x)
        val projY = a.y + t * (b.y - a.y)
        return hypot(p.x - projX, p.y - projY)
    }

    // Simple Ramer-Douglas-Peucker implementation
    private fun douglasPeucker(
        points: List<PointF>,
        epsilon: Float,
    ): List<PointF> {
        if (points.size < 3) return points

        var dmax = 0f
        var index = 0
        val end = points.size - 1

        for (i in 1 until end) {
            val d = pointLineDist(points[i], points[0], points[end])
            if (d > dmax) {
                index = i
                dmax = d
            }
        }

        if (dmax > epsilon) {
            val res1 = douglasPeucker(points.subList(0, index + 1), epsilon)
            val res2 = douglasPeucker(points.subList(index, points.size), epsilon)
            return res1.dropLast(1) + res2
        } else {
            return listOf(points[0], points[end])
        }
    }

    private fun pointLineDist(
        p: PointF,
        a: PointF,
        b: PointF,
    ): Float {
        val num = abs((b.y - a.y) * p.x - (b.x - a.x) * p.y + b.x * a.y - b.y * a.x)
        val den = hypot(b.y - a.y, b.x - a.x)
        if (den == 0f) {
            return hypot(p.x - a.x, p.y - a.y)
        }
        return num / den
    }
}
