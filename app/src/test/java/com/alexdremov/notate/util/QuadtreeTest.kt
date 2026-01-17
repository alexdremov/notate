package com.alexdremov.notate.util

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuadtreeTest {
    private fun createStroke(
        x: Float,
        y: Float,
        width: Float = 10f,
    ): Stroke {
        val rect = RectF(x, y, x + width, y + width)
        val path = Path()
        path.addRect(rect, Path.Direction.CW)

        // Mock points
        val points = listOf(TouchPoint(x, y, 0.5f, 1.0f, 0L))

        return Stroke(
            path = path,
            points = points,
            color = -16777216, // Black
            width = 2f,
            style = StrokeType.FINELINER,
            bounds = rect,
        )
    }

    @Test
    fun `test insert and retrieve single item`() {
        val rootBounds = RectF(0f, 0f, 1000f, 1000f)
        val quadtree = Quadtree(0, rootBounds)
        val stroke = createStroke(100f, 100f)

        quadtree.insert(stroke)

        val results = ArrayList<CanvasItem>()
        quadtree.retrieve(results, RectF(0f, 0f, 200f, 200f))

        assertEquals(1, results.size)
        assertEquals(stroke, results[0])
    }

    @Test
    fun `test retrieve returns empty for non-intersecting viewport`() {
        val rootBounds = RectF(0f, 0f, 1000f, 1000f)
        val quadtree = Quadtree(0, rootBounds)
        val stroke = createStroke(100f, 100f)

        quadtree.insert(stroke)

        val results = ArrayList<CanvasItem>()
        quadtree.retrieve(results, RectF(500f, 500f, 600f, 600f))

        assertEquals(0, results.size)
    }

    @Test
    fun `test auto growth`() {
        val rootBounds = RectF(0f, 0f, 100f, 100f)
        var quadtree = Quadtree(0, rootBounds)

        // Insert stroke FAR outside bounds
        val outsideStroke = createStroke(500f, 500f)

        // This should trigger growth and return a new root
        quadtree = quadtree.insert(outsideStroke)

        val results = ArrayList<CanvasItem>()
        // Search around the new area
        quadtree.retrieve(results, RectF(400f, 400f, 600f, 600f))

        assertEquals(1, results.size)
        assertEquals(outsideStroke, results[0])

        // Ensure bounds grew
        assertTrue(quadtree.getBounds().width() > 100f)
    }

    @Test
    fun `test remove stroke`() {
        val rootBounds = RectF(0f, 0f, 1000f, 1000f)
        val quadtree = Quadtree(0, rootBounds)
        val stroke = createStroke(100f, 100f)

        quadtree.insert(stroke)

        val removed = quadtree.remove(stroke)
        assertTrue(removed)

        val results = ArrayList<CanvasItem>()
        quadtree.retrieve(results, rootBounds)
        assertTrue(results.isEmpty())
    }
}
