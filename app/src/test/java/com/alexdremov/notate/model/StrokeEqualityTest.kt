package com.alexdremov.notate.model

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StrokeEqualityTest {
    @Test
    fun `Strokes with same content but different paths are equal`() {
        val points =
            listOf(
                TouchPoint(0f, 0f, 0.5f, 1f, 0, 0, 1000L),
                TouchPoint(10f, 10f, 0.5f, 1f, 0, 0, 1010L),
            )
        val bounds = RectF(0f, 0f, 10f, 10f)

        // Two different path instances
        val path1 = mockk<Path>(relaxed = true)
        val path2 = mockk<Path>(relaxed = true)

        val stroke1 =
            Stroke(
                path = path1,
                points = points,
                color = Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        val stroke2 =
            Stroke(
                path = path2,
                points = points,
                color = Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        assertEquals("Strokes should be equal despite different Path objects", stroke1, stroke2)
        assertEquals("HashCodes should match", stroke1.hashCode(), stroke2.hashCode())
    }

    @Test
    fun `Strokes with different points are not equal`() {
        val points1 = listOf(TouchPoint(0f, 0f, 0.5f, 1f, 0, 0, 1000L))
        val points2 = listOf(TouchPoint(1f, 1f, 0.5f, 1f, 0, 0, 1000L))
        val bounds = RectF(0f, 0f, 10f, 10f)
        val path = mockk<Path>(relaxed = true)

        val stroke1 =
            Stroke(
                path = path,
                points = points1,
                color = Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        val stroke2 =
            Stroke(
                path = path,
                points = points2,
                color = Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        assertNotEquals("Strokes with different points should not be equal", stroke1, stroke2)
    }
}
