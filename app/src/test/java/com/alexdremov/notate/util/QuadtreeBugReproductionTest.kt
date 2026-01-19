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
class QuadtreeBugReproductionTest {
    private fun createStroke(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Stroke {
        val rect = RectF(left, top, right, bottom)
        val path = Path()
        path.addRect(rect, Path.Direction.CW)
        val points = listOf(TouchPoint(left, top, 0.5f, 1.0f, 0L))
        return Stroke(
            path = path,
            points = points,
            color = -16777216,
            width = 2f,
            style = StrokeType.FINELINER,
            bounds = rect,
        )
    }

    @Test
    fun `reproduce removal failure after growth`() {
        // Initial bounds [0, 0, 100, 100]
        var quadtree = Quadtree(0, RectF(0f, 0f, 100f, 100f))

        // This stroke is in the bottom-right quadrant of [0, 0, 100, 100]
        // Center is 50, 50.
        // Stroke is [60, 60, 80, 80]
        val stroke1 = createStroke(60f, 60f, 80f, 80f)
        quadtree = quadtree.insert(stroke1)

        // Now grow the tree by inserting something far away
        // say at [150, 150]
        // This will make [0, 0, 100, 100] the TOP-LEFT quadrant (index 0) of [0, 0, 200, 200]
        val stroke2 = createStroke(150f, 150f, 160f, 160f)
        quadtree = quadtree.insert(stroke2)

        // Now try to remove stroke1
        val removed = quadtree.remove(stroke1)
        assertTrue("Stroke 1 should be removed", removed)
    }

    @Test
    fun `reproduce removal failure after growth - boundary case`() {
        // Initial bounds [0, 0, 100, 100]. Center (50, 50).
        var quadtree = Quadtree(0, RectF(0f, 0f, 100f, 100f))

        // This stroke is in the bottom-right quadrant of [0, 0, 100, 100]
        // Stroke is [60, 60, 100, 100]. It touches the outer boundary of the root.
        val stroke1 = createStroke(60f, 60f, 100f, 100f)
        quadtree = quadtree.insert(stroke1)

        // Now grow the tree. We want [0, 0, 100, 100] to become the TOP-LEFT quadrant of [0, 0, 200, 200].
        // To do this, we insert something at [150, 150].
        val stroke2 = createStroke(150f, 150f, 160f, 160f)
        quadtree = quadtree.insert(stroke2)

        // Root is now [0, 0, 200, 200]. Center (100, 100).
        // stroke1 is [60, 60, 100, 100].
        // stroke1.right is 100, which is exactly the midpoint.
        // getIndex(stroke1.bounds) will return -1 at the root.

        val removed = quadtree.remove(stroke1)
        assertTrue("Stroke 1 should be removed even if it touches new midpoints", removed)
    }

    @Test
    fun `test removal when item spans multiple quadrants`() {
        // Root bounds [0, 0, 100, 100]. Center (50, 50).
        val quadtree = Quadtree(0, RectF(0f, 0f, 100f, 100f))

        // Spans multiple quadrants of root
        val stroke = createStroke(40f, 40f, 60f, 60f)
        quadtree.insert(stroke)

        val removed = quadtree.remove(stroke)
        assertTrue("Spanning stroke should be removed", removed)
    }
}
