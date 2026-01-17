package com.alexdremov.notate.ui.input

import android.graphics.Color
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StrokeBuilderTest {
    @Test
    fun `test lifecycle start add build`() {
        val builder = StrokeBuilder()
        val start = TouchPoint(0f, 0f, 1f, 1f, 0L)
        val end = TouchPoint(10f, 10f, 1f, 1f, 10L)

        builder.start(start)
        // logic says hasPoints checks size >= 2

        builder.addPoint(end)
        assertTrue(builder.hasPoints())

        val stroke = builder.build(Color.BLACK, 5f, StrokeType.FINELINER)
        assertNotNull(stroke)
        assertEquals(2, stroke!!.points.size)
        assertEquals(Color.BLACK, stroke.color)
        assertEquals(5f, stroke.width, 0.0f)
    }

    @Test
    fun `test build fails with insufficient points`() {
        val builder = StrokeBuilder()
        val start = TouchPoint(0f, 0f, 1f, 1f, 0L)

        builder.start(start)
        assertNull(builder.build(Color.BLACK, 5f, StrokeType.FINELINER))
    }

    @Test
    fun `test buildSegment`() {
        val builder = StrokeBuilder()
        val start = TouchPoint(0f, 0f, 1f, 1f, 0L)
        val end = TouchPoint(10f, 10f, 1f, 1f, 10L)

        val stroke = builder.buildSegment(start, end, 10f, Color.RED, StrokeType.HIGHLIGHTER)

        assertEquals(2, stroke.points.size)
        assertEquals(Color.RED, stroke.color)
        assertEquals(StrokeType.HIGHLIGHTER, stroke.style)
    }
}
