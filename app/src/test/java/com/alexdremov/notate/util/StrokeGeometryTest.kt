package com.alexdremov.notate.util

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StrokeGeometryTest {
    private fun createStroke(
        points: List<TouchPoint>,
        width: Float = 10f,
    ): Stroke {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.inset(-width, -width)

        return Stroke(
            path = path,
            points = points,
            color = 0,
            width = width,
            style = StrokeType.FINELINER,
            bounds = bounds,
        )
    }

    @Test
    fun `test splitStroke creates valid strokes`() {
        // Create a horizontal line from 0 to 100
        val targetPoints =
            listOf(
                TouchPoint(0f, 0f, 0.5f, 1f, 0),
                TouchPoint(50f, 0f, 0.5f, 1f, 0),
                TouchPoint(100f, 0f, 0.5f, 1f, 0),
            )
        val target = createStroke(targetPoints, width = 2f)

        // Create an eraser that erases only the middle point (50, 0)
        // Eraser at (50, 0) with radius 5
        val eraserPoints = listOf(TouchPoint(50f, 0f, 0.5f, 1f, 0))
        val eraser = createStroke(eraserPoints, width = 10f) // radius 5

        val result = StrokeGeometry.splitStroke(target, eraser)

        // Due to interpolation, we expect the segments A->nearB and nearB->C to be preserved.
        assertFalse("Result should not be empty (interpolation preserves partial strokes)", result.isEmpty())

        for (stroke in result) {
            assertTrue("Each resulting stroke must have at least 2 points", stroke.points.size >= 2)
        }
    }

    @Test
    fun `test distPointToStroke`() {
        val points =
            listOf(
                TouchPoint(0f, 0f, 1f, 1f, 0L),
                TouchPoint(100f, 0f, 1f, 1f, 0L),
            )
        val stroke = createStroke(points)

        // Point exactly on line
        assertEquals(0f, StrokeGeometry.distPointToStroke(50f, 0f, stroke), 0.1f)

        // Point 10 units away
        assertEquals(10f, StrokeGeometry.distPointToStroke(50f, 10f, stroke), 0.1f)

        // Point beyond end
        assertEquals(10f, StrokeGeometry.distPointToStroke(110f, 0f, stroke), 0.1f)
    }

    @Test
    fun `test intersection basic crossing`() {
        // Horizontal line
        val s1 =
            createStroke(
                listOf(
                    TouchPoint(0f, 50f, 1f, 1f, 0L),
                    TouchPoint(100f, 50f, 1f, 1f, 0L),
                ),
            )

        // Vertical line crossing it
        val s2 =
            createStroke(
                listOf(
                    TouchPoint(50f, 0f, 1f, 1f, 0L),
                    TouchPoint(50f, 100f, 1f, 1f, 0L),
                ),
            )

        assertTrue(StrokeGeometry.strokeIntersects(s1, s2))
    }

    @Test
    fun `test intersection no overlap`() {
        val s1 =
            createStroke(
                listOf(
                    TouchPoint(0f, 0f, 1f, 1f, 0L),
                    TouchPoint(10f, 0f, 1f, 1f, 0L),
                ),
            )

        val s2 =
            createStroke(
                listOf(
                    TouchPoint(0f, 100f, 1f, 1f, 0L),
                    TouchPoint(10f, 100f, 1f, 1f, 0L),
                ),
            )

        assertFalse(StrokeGeometry.strokeIntersects(s1, s2))
    }

    @Test
    fun `test isPointInPolygon`() {
        val polygon =
            listOf(
                TouchPoint(0f, 0f, 0f, 0f, 0L),
                TouchPoint(100f, 0f, 0f, 0f, 0L),
                TouchPoint(100f, 100f, 0f, 0f, 0L),
                TouchPoint(0f, 100f, 0f, 0f, 0L),
            )

        assertTrue(StrokeGeometry.isPointInPolygon(50f, 50f, polygon))
        assertFalse(StrokeGeometry.isPointInPolygon(150f, 50f, polygon))
    }
}
