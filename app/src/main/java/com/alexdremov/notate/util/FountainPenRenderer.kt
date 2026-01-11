package com.alexdremov.notate.util

import android.graphics.Path
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.Stroke
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Improved Fountain Pen Renderer using Catmull-Rom splines and Velocity-Pressure dynamics.
 *
 * Algorithm:
 * 1. Trajectory Analysis: Calculates velocity and smoothed pressure for input points.
 * 2. Spline Interpolation: Uses Centripetal Catmull-Rom splines to generate a dense, smooth path
 *    that passes through the original points (essential for handwriting accuracy).
 * 3. Dynamic Width: Modulates width based on Pressure (primary) and Velocity (secondary).
 *    - Faster strokes -> Thinner (simulating limited ink flow).
 *    - Harder presses -> Thicker.
 * 4. Geometry Construction: Generates a high-fidelity envelope (outline) from the interpolated data.
 */
object FountainPenRenderer {
    fun getPath(
        stroke: Stroke,
        maxPressure: Float,
    ): Path {
        val cached = stroke.renderCache
        if (cached is Path) {
            return cached
        }

        if (stroke.points.size < 2) return Path()

        // 1. Build Control Points (Trajectory)
        val controlPoints = buildControlPoints(stroke.points, stroke.width, maxPressure)
        if (controlPoints.size < 2) return Path()

        // 2. Interpolate Spline
        val splinePoints = interpolateSpline(controlPoints)
        if (splinePoints.size < 2) return Path()

        // 3. Generate Outline
        val generated = generateOutline(splinePoints)

        stroke.renderCache = generated
        return generated
    }

    // --- Data Structures ---

    private data class ControlPoint(
        val x: Float,
        val y: Float,
        val width: Float,
    )

    private data class RenderPoint(
        val x: Float,
        val y: Float,
        val w: Float, // Calculated half-width at this point
        val angle: Float, // Tangent angle
    )

    // --- Step 1: Trajectory Analysis ---

    private fun buildControlPoints(
        rawPoints: List<TouchPoint>,
        baseWidth: Float,
        maxPressure: Float,
    ): List<ControlPoint> {
        val result = ArrayList<ControlPoint>(rawPoints.size)
        val adjustedBaseWidth = baseWidth * CanvasConfig.FOUNTAIN_BASE_WIDTH_MULTIPLIER

        // Smoothing state
        var lastPressure = if (rawPoints.isNotEmpty()) rawPoints[0].pressure / maxPressure else 0.5f
        // Avoid division by zero if maxPressure is 0
        val safeMaxPressure = if (maxPressure > 0) maxPressure else 4096f

        for (i in rawPoints.indices) {
            val p = rawPoints[i]

            // 1. Calculate Velocity (px/ms)
            var velocity = 0f
            if (i > 0) {
                val prev = rawPoints[i - 1]
                val dist = hypot(p.x - prev.x, p.y - prev.y)
                val dt = (p.timestamp - prev.timestamp).coerceAtLeast(1) // Avoid /0
                velocity = dist / dt
            } else if (rawPoints.size > 1) {
                // Estimate start velocity from second point
                val next = rawPoints[1]
                val dist = hypot(next.x - p.x, next.y - p.y)
                val dt = (next.timestamp - p.timestamp).coerceAtLeast(1)
                velocity = dist / dt
            }

            // Clamp velocity to reasonable range (0.0 - 5.0 px/ms is typical for writing)
            // Normalize to 0..1 for calculation (assuming max speed around 2.0 px/ms usually)
            val normVelocity = (velocity / 2.0f).coerceIn(0f, 1f)

            // 2. Smooth Pressure (EMA)
            val rawP = p.pressure / safeMaxPressure
            lastPressure = lastPressure * (1 - CanvasConfig.FOUNTAIN_PRESSURE_SMOOTHING_FACTOR) +
                rawP * CanvasConfig.FOUNTAIN_PRESSURE_SMOOTHING_FACTOR

            // 3. Calculate Target Width
            // W = Base * Pressure^Exp * (1 - k * Velocity)
            val pressureFactor = lastPressure.pow(CanvasConfig.FOUNTAIN_PRESSURE_POWER_EXPONENT)

            // Velocity thins the stroke: (1 - influence * normVel)
            // We clamp the thinning so it doesn't disappear completely
            val velocityFactor =
                (1.0f - (normVelocity * CanvasConfig.FOUNTAIN_VELOCITY_INFLUENCE))
                    .coerceIn(0.2f, 1.0f)

            var targetWidth = adjustedBaseWidth * pressureFactor * velocityFactor

            // Absolute min width clamp
            targetWidth = targetWidth.coerceAtLeast(CanvasConfig.FOUNTAIN_MIN_WIDTH)

            result.add(ControlPoint(p.x, p.y, targetWidth))
        }
        return result
    }

    // --- Step 2: Catmull-Rom Spline Interpolation ---

    private fun interpolateSpline(points: List<ControlPoint>): List<RenderPoint> {
        val result = ArrayList<RenderPoint>()
        if (points.isEmpty()) return result

        // Duplicate start and end points to ensure the spline passes through them
        // (Catmull-Rom requires p0, p1, p2, p3 to interpolate between p1 and p2)
        val fullList = ArrayList<ControlPoint>()
        fullList.add(points.first()) // p0 (dummy)
        fullList.addAll(points)
        fullList.add(points.last()) // pLast+1 (dummy)

        val steps = CanvasConfig.FOUNTAIN_SPLINE_STEPS

        // Iterate through segments
        for (i in 0 until fullList.size - 3) {
            val p0 = fullList[i]
            val p1 = fullList[i + 1]
            val p2 = fullList[i + 2]
            val p3 = fullList[i + 3]

            // Heuristic: If p1 and p2 are very close, skip interpolation to save perf
            // unless it's a very sharp turn
            val dist = hypot(p2.x - p1.x, p2.y - p1.y)
            val actualSteps = if (dist < 5f) (steps / 2).coerceAtLeast(1) else steps

            for (tStep in 0 until actualSteps) {
                val t = tStep / actualSteps.toFloat()

                // Centripetal Catmull-Rom Spline
                // Here using standard Catmull-Rom basis functions for simplicity/speed
                // x(t) = 0.5 * ( (2*P1) + (-P0 + P2)*t + (2*P0 - 5*P1 + 4*P2 - P3)*t^2 + (-P0 + 3*P1 - 3*P2 + P3)*t^3 )

                val tt = t * t
                val ttt = tt * t

                val q0 = -0.5f * t + tt - 0.5f * ttt
                val q1 = 1.0f - 2.5f * tt + 1.5f * ttt
                val q2 = 0.5f * t + 2.0f * tt - 1.5f * ttt
                val q3 = -0.5f * tt + 0.5f * ttt

                val tx = (p0.x * q0 + p1.x * q1 + p2.x * q2 + p3.x * q3)
                val ty = (p0.y * q0 + p1.y * q1 + p2.y * q2 + p3.y * q3)

                // Linear interpolation for width
                val tw = p1.width + (p2.width - p1.width) * t

                // Calculate tangent angle for this point
                // Derivative x'(t)
                val dq0 = -0.5f + 2f * t - 1.5f * tt
                val dq1 = -5f * t + 4.5f * tt
                val dq2 = 0.5f + 4f * t - 4.5f * tt
                val dq3 = -t + 1.5f * tt

                val dx = (p0.x * dq0 + p1.x * dq1 + p2.x * dq2 + p3.x * dq3)
                val dy = (p0.y * dq0 + p1.y * dq1 + p2.y * dq2 + p3.y * dq3)
                val angle = atan2(dy, dx)

                result.add(RenderPoint(tx, ty, tw * 0.5f, angle))
            }
        }

        // Add the very last point
        // Calculate the true tangent at t=1.0 for the last segment
        if (fullList.size >= 4) {
            val p0 = fullList[fullList.size - 4]
            val p1 = fullList[fullList.size - 3]
            val p2 = fullList[fullList.size - 2]
            val p3 = fullList[fullList.size - 1]

            // Derivative at t=1.0
            val t = 1.0f
            val tt = t * t

            val dq0 = -0.5f + 2f * t - 1.5f * tt
            val dq1 = -5f * t + 4.5f * tt
            val dq2 = 0.5f + 4f * t - 4.5f * tt
            val dq3 = -t + 1.5f * tt

            val dx = (p0.x * dq0 + p1.x * dq1 + p2.x * dq2 + p3.x * dq3)
            val dy = (p0.y * dq0 + p1.y * dq1 + p2.y * dq2 + p3.y * dq3)
            val angle = atan2(dy, dx)

            val last = points.last()
            result.add(RenderPoint(last.x, last.y, last.width * 0.5f, angle))
        } else {
            // Fallback for tiny strokes (shouldn't happen due to checks)
            val last = points.last()
            val lastAngle = if (result.isNotEmpty()) result.last().angle else 0f
            result.add(RenderPoint(last.x, last.y, last.width * 0.5f, lastAngle))
        }

        return result
    }

    // --- Step 3: Envelope Generation ---

    private fun generateOutline(points: List<RenderPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        val leftSide = ArrayList<PointF>(points.size)
        val rightSide = ArrayList<PointF>(points.size)

        // Generate envelope points
        for (p in points) {
            // Normal vector is perpendicular to tangent (-y, x)
            val sinA = sin(p.angle)
            val cosA = cos(p.angle)

            // Offset
            val dx = p.w * sinA
            val dy = p.w * cosA

            // Standard Ribbon Construction
            // Left: (-dy, dx) -> actually (-sin, cos) represents normal angle + 90
            // Let's stick to simple trig:
            // Tangent vector T = (cosA, sinA)
            // Normal vector N = (-sinA, cosA)

            leftSide.add(PointF(p.x - dx, p.y + dy))
            rightSide.add(PointF(p.x + dx, p.y - dy))
        }

        // Construct Path
        path.moveTo(leftSide[0].x, leftSide[0].y)

        // Forward (Left Side)
        for (i in 1 until leftSide.size) {
            // Use simple lines because points are already dense from spline
            path.lineTo(leftSide[i].x, leftSide[i].y)
        }

        // Cap (End) - Round
        val lastL = leftSide.last()
        val lastR = rightSide.last()
        val lastP = points.last()

        // Control point for round cap
        val capEndDx = (lastP.w) * cos(lastP.angle)
        val capEndDy = (lastP.w) * sin(lastP.angle)

        path.cubicTo(
            lastL.x + capEndDx * 0.6f,
            lastL.y + capEndDy * 0.6f,
            lastR.x + capEndDx * 0.6f,
            lastR.y + capEndDy * 0.6f,
            lastR.x,
            lastR.y,
        )

        // Backward (Right Side)
        for (i in rightSide.size - 2 downTo 0) {
            path.lineTo(rightSide[i].x, rightSide[i].y)
        }

        // Cap (Start) - Round
        val firstL = leftSide.first()
        val firstR = rightSide.first()
        val firstP = points.first()

        val capStartDx = -(firstP.w) * cos(firstP.angle)
        val capStartDy = -(firstP.w) * sin(firstP.angle)

        path.cubicTo(
            firstR.x + capStartDx * 0.6f,
            firstR.y + capStartDy * 0.6f,
            firstL.x + capStartDx * 0.6f,
            firstL.y + capStartDy * 0.6f,
            firstL.x,
            firstL.y,
        )

        path.close()
        return path
    }

    private data class PointF(
        val x: Float,
        val y: Float,
    )
}
