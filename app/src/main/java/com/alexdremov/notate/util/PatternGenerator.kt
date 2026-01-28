package com.alexdremov.notate.util

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object PatternGenerator {
    enum class PatternType {
        FRACTAL,
        SQUARES,
        HANDWRITING,
    }

    fun generateStrokes(
        type: PatternType,
        intensity: Float, // 0.0 to 1.0
        visibleRect: RectF,
    ): Sequence<Stroke> =
        sequence {
            when (type) {
                PatternType.FRACTAL -> generateFractalTree(intensity, visibleRect)
                PatternType.SQUARES -> generateSquares(intensity, visibleRect)
                PatternType.HANDWRITING -> generateHandwriting(intensity, visibleRect)
            }
        }

    private suspend fun SequenceScope<Stroke>.generateFractalTree(
        intensity: Float,
        bounds: RectF,
    ) {
        // Root at bottom center
        val cx = bounds.centerX()
        val cy = bounds.bottom - (bounds.height() * 0.1f) // Start slightly up from bottom

        // Initial length based on height
        val len = bounds.height() * (0.25f + (intensity * 0.1f)) // 25-35% of height

        // Depth based on intensity
        val depth = (5 + (intensity * 10)).toInt() // 5 to 15

        suspend fun SequenceScope<Stroke>.drawBranch(
            x1: Float,
            y1: Float,
            angle: Double,
            length: Float,
            currentDepth: Int,
        ) {
            if (currentDepth == 0) return

            val x2 = x1 + (cos(Math.toRadians(angle)) * length).toFloat()
            val y2 = y1 + (sin(Math.toRadians(angle)) * length).toFloat()

            yield(createLineStroke(x1, y1, x2, y2))

            drawBranch(x2, y2, angle - 20, length * 0.7f, currentDepth - 1)
            drawBranch(x2, y2, angle + 20, length * 0.7f, currentDepth - 1)
        }

        // Start from bottom-center relative to cursor, growing up (-90 deg)
        drawBranch(cx, cy, -90.0, len, depth)
    }

    private suspend fun SequenceScope<Stroke>.generateSquares(
        intensity: Float,
        bounds: RectF,
    ) {
        val spacing = 50f * (1.5f - intensity) // High intensity = smaller spacing

        val startX = bounds.left
        val startY = bounds.top

        val cols = (bounds.width() / spacing).toInt()
        val rows = (bounds.height() / spacing).toInt()

        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val x = startX + i * spacing + (spacing * 0.1f)
                val y = startY + j * spacing + (spacing * 0.1f)
                val size = spacing * 0.8f
                yield(createRectStroke(x, y, size, size))
            }
        }
    }

    private suspend fun SequenceScope<Stroke>.generateHandwriting(
        intensity: Float,
        bounds: RectF,
    ) {
        val lineHeight = 40f * (1.5f - intensity) // High intensity = smaller lines
        val lines = (bounds.height() / lineHeight).toInt()

        val startX = bounds.left + 20f
        val endX = bounds.right - 20f
        val startY = bounds.top + 40f

        for (i in 0 until lines) {
            val y = startY + i * lineHeight
            if (y > bounds.bottom - 20f) break

            var curX = startX

            // Random start indentation for paragraphs
            if (Random.nextFloat() > 0.8f) curX += 100f

            while (curX < endX) {
                // Word length
                val wordLen = Random.nextFloat() * 80f + 20f
                if (curX + wordLen > endX) break

                // Draw word (sine wave approximation)
                val freq = Random.nextFloat() * 0.1f + 0.05f
                val amp = lineHeight * 0.25f
                val points = ArrayList<TouchPoint>()

                for (k in 0..wordLen.toInt() step 2) {
                    val px = curX + k
                    val py = y + sin(k * freq) * amp + (Random.nextFloat() * 4f - 2f) // Noise
                    val pressure = Random.nextFloat() * 0.5f + 0.3f
                    points.add(TouchPoint(px.toFloat(), py.toFloat(), pressure, 1f, System.currentTimeMillis()))
                }

                curX += wordLen + 20f

                if (points.isNotEmpty()) {
                    yield(createStrokeFromPoints(points))
                }
            }
        }
    }

    private fun createLineStroke(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Stroke {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)

        val points =
            listOf(
                TouchPoint(x1, y1, 0.5f, 1f, System.currentTimeMillis()),
                TouchPoint(x2, y2, 0.5f, 1f, System.currentTimeMillis()),
            )

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val w = 2f
        bounds.inset(-w, -w)

        return Stroke(
            path = path,
            points = points,
            color = Color.BLACK,
            width = w,
            style = StrokeType.FOUNTAIN,
            bounds = bounds,
            strokeOrder = 0,
        )
    }

    private fun createRectStroke(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): Stroke {
        val path = Path()
        path.addRect(x, y, x + w, y + h, Path.Direction.CW)

        val points =
            listOf(
                TouchPoint(x, y, 0.5f, 1f, System.currentTimeMillis()),
                TouchPoint(x + w, y, 0.5f, 1f, System.currentTimeMillis()),
                TouchPoint(x + w, y + h, 0.5f, 1f, System.currentTimeMillis()),
                TouchPoint(x, y + h, 0.5f, 1f, System.currentTimeMillis()),
                TouchPoint(x, y, 0.5f, 1f, System.currentTimeMillis()),
            )

        val bounds = RectF(x, y, x + w, y + h)
        val width = 2f
        bounds.inset(-width, -width)

        return Stroke(
            path = path,
            points = points,
            color = Color.BLACK,
            width = width,
            style = StrokeType.FINELINER,
            bounds = bounds,
            strokeOrder = 0,
        )
    }

    private fun createStrokeFromPoints(points: List<TouchPoint>): Stroke {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val width = 2f
        bounds.inset(-width, -width)

        return Stroke(
            path = path,
            points = ArrayList(points),
            color = Color.BLUE, // Make handwriting blue for fun
            width = width,
            style = StrokeType.BALLPOINT,
            bounds = bounds,
            strokeOrder = 0,
        )
    }
}
