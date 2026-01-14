package com.alexdremov.notate.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.alexdremov.notate.model.Stroke
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.abs

/**
 * Ballpoint Pen Renderer.
 *
 * Characteristics:
 * - Consistent width with slight pressure variation (Rolling ball style).
 * - Variable opacity based on pressure (lighter touch = lighter ink).
 * - High performance using segmented native Path rendering.
 * - Smooth joins and caps using Paint settings.
 * - Segmented caching to support variable properties without mesh artifacts.
 */
object BallpointPenRenderer {
    private const val TAG = "BallpointRenderer"

    // Thresholds for merging segments to optimize draw calls
    private const val WIDTH_THRESHOLD = 0.5f
    private const val ALPHA_THRESHOLD = 5

    private data class BallpointSegment(
        val path: Path,
        val width: Float,
        val alpha: Int,
    )

    fun render(
        canvas: Canvas,
        paint: Paint,
        stroke: Stroke,
        maxPressure: Float,
    ) {
        val segments = getOrValidateSegments(stroke, maxPressure) ?: return

        val originalColor = paint.color
        val originalStrokeWidth = paint.strokeWidth
        val baseAlpha = Color.alpha(originalColor)

        try {
            // Ensure round caps/joins for the rolling ball look
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            for (segment in segments) {
                // Apply segment specific properties
                paint.strokeWidth = segment.width

                // Modulate alpha: Combine base color alpha with segment alpha
                // segment.alpha is 0-255 representing the pressure modulation
                // baseAlpha is the tool's opacity
                val finalAlpha = (segment.alpha * (baseAlpha / 255f)).toInt().coerceIn(0, 255)
                paint.color = (originalColor and 0x00FFFFFF) or (finalAlpha shl 24)

                canvas.drawPath(segment.path, paint)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering ballpoint", e)
        } finally {
            // Restore original paint properties
            paint.color = originalColor
            paint.strokeWidth = originalStrokeWidth
        }
    }

    private fun getOrValidateSegments(
        stroke: Stroke,
        maxPressure: Float,
    ): List<BallpointSegment>? {
        @Suppress("UNCHECKED_CAST")
        val cached = stroke.renderCache as? List<BallpointSegment>
        if (cached != null) {
            return cached
        }

        val points = stroke.points
        if (points.isEmpty()) return null

        val segments = ArrayList<BallpointSegment>()

        // Ballpoint dynamics configuration
        val baseWidth = stroke.width
        val minWidthScale = 0.9f
        val maxWidthScale = 1.0f
        val minAlpha = 225
        val pressureAlphaRange = 255 - minAlpha

        // Helper to calculate properties for a point
        fun getProps(p: TouchPoint): Pair<Float, Int> {
            val pressure = if (maxPressure > 0) p.pressure / maxPressure else 0.5f
            val widthScale = minWidthScale + (maxWidthScale - minWidthScale) * pressure
            val width = baseWidth * widthScale

            val alphaFactor = pressure // Linear or could be squared
            val alphaRaw = (minAlpha + (alphaFactor * pressureAlphaRange)).toInt().coerceIn(0, 255)

            return width to alphaRaw
        }

        if (points.size < 3) {
            // Simple case for dots/small lines
            val path = Path()
            val start = points[0]
            path.moveTo(start.x, start.y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            if (points.size == 1) {
                path.lineTo(start.x, start.y)
            }
            val (w, a) = getProps(start)
            segments.add(BallpointSegment(path, w, a))
        } else {
            // Smoothed segments
            var currentPath = Path()
            var p0 = points[0]

            currentPath.moveTo(p0.x, p0.y)

            // Initial properties
            var (currentWidth, currentAlpha) = getProps(p0)

            // Running sums for averaging segment properties
            var propSumWidth = currentWidth
            var propSumAlpha = currentAlpha.toFloat()
            var propCount = 1

            for (i in 1 until points.size) {
                val p1 = points[i]
                val midX = (p0.x + p1.x) / 2f
                val midY = (p0.y + p1.y) / 2f

                // Target properties for this new curve segment
                val (targetWidth, targetAlpha) = getProps(p1)

                // Check if we should break the segment
                val avgWidth = propSumWidth / propCount
                val avgAlpha = (propSumAlpha / propCount).toInt()

                val widthDiff = abs(targetWidth - avgWidth)
                val alphaDiff = abs(targetAlpha - avgAlpha)

                if (widthDiff > WIDTH_THRESHOLD || alphaDiff > ALPHA_THRESHOLD) {
                    // Flush current segment
                    segments.add(BallpointSegment(currentPath, avgWidth, avgAlpha))

                    // Start new segment
                    currentPath = Path()
                    currentPath.moveTo(p0.x, p0.y) // Continuity: Start where last one effectively ended (conceptually)
                    // Actually, for quadTo, we are at p0. The previous curve ended at (p-1 + p0)/2.
                    // To avoid gaps, we must ensure the new path starts exactly where the old one ended.
                    // The 'currentPath' state in Path tracks the last point.
                    // But since we created a NEW Path object, we need to moveTo the start.
                    // The previous curve was quadTo(p0.x, p0.y, midX, midY).
                    // So the visual stroke ends at midX, midY.
                    // However, we are iterating.

                    // Let's refine the loop strategy:
                    // We are drawing curve: PrevMid -> Control(p0) -> CurrentMid
                    // But here p0 is the 'start' of the iteration segment.

                    // Correct Midpoint Logic:
                    // Curve i connects Mid(i-1) to Mid(i) using Point(i) as control.
                    // We need to look one step back.
                }

                // We handle the loop slightly differently to support splitting correctly.
                // It's easier to decide "append" or "split" before adding the curve.
            }

            // --- Re-implementation of the Loop for correct splitting ---
            segments.clear()

            p0 = points[0]
            var (activeWidth, activeAlpha) = getProps(p0)
            var activePath = Path()
            activePath.moveTo(p0.x, p0.y)

            for (i in 1 until points.size) {
                val p1 = points[i]
                val midX = (p0.x + p1.x) / 2f
                val midY = (p0.y + p1.y) / 2f

                val (ptWidth, ptAlpha) = getProps(p1) // Properties at the control/end point

                // If diff is large, push old path and start new one
                if (abs(ptWidth - activeWidth) > WIDTH_THRESHOLD || abs(ptAlpha - activeAlpha) > ALPHA_THRESHOLD) {
                    // Add current active path to segments
                    segments.add(BallpointSegment(activePath, activeWidth, activeAlpha))

                    // Start new path
                    activePath = Path()
                    // Must move to the exact end point of the previous path to avoid gaps
                    // The previous segment ended at the PREVIOUS midX, midY.
                    // Wait, if we split, we need to start from where we left off.
                    // If we just appended a quadTo, the path is at (midX, midY).
                    // If we swap the path object, we must moveTo(midX, midY).
                    // BUT, we haven't added the CURRENT quadTo yet.
                    // The previous command ended at the previous midpoint.
                    // We need to know the start point of this new curve.

                    // To simplify: The current path's last point is valid.
                    // If we create a NEW path, we need to move to the current pen position.
                    // The pen position is the *start* of the curve we are about to draw.
                    // For i=1, start is p0. For i>1, start is (p_prev + p0)/2.
                }

                if (activePath.isEmpty) {
                    if (i == 1) {
                        activePath.moveTo(p0.x, p0.y)
                    } else {
                        val pPrev = points[i - 1]
                        val prevMidX = (pPrev.x + p0.x) / 2f
                        val prevMidY = (pPrev.y + p0.y) / 2f
                        activePath.moveTo(prevMidX, prevMidY)
                    }
                    // Reset active properties
                    activeWidth = ptWidth
                    activeAlpha = ptAlpha
                } else {
                    // Averaging for smooth transitions
                    activeWidth = (activeWidth + ptWidth) / 2f
                    activeAlpha = (activeAlpha + ptAlpha) / 2
                }

                if (i == 1) {
                    activePath.lineTo(midX, midY)
                } else {
                    activePath.quadTo(p0.x, p0.y, midX, midY)
                }

                p0 = p1
            }

            // Final line to last point
            activePath.lineTo(p0.x, p0.y)
            segments.add(BallpointSegment(activePath, activeWidth, activeAlpha))
        }

        stroke.renderCache = segments
        return segments
    }
}
