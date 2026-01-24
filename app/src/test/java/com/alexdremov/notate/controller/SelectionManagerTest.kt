package com.alexdremov.notate.controller

import android.graphics.Matrix
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SelectionManagerTest {
    private fun createMockItem(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        order: Long = 0,
    ): CanvasItem {
        val rect = RectF(left, top, right, bottom)
        return Stroke(
            path = Path(),
            points = emptyList(),
            color = 0,
            width = 1f,
            style = StrokeType.FINELINER,
            bounds = rect,
            strokeOrder = order,
        )
    }

    @Test
    fun `test selection and bounds recomputation`() {
        val sm = SelectionManager()
        val item1 = createMockItem(0f, 0f, 10f, 10f, order = 1)
        val item2 = createMockItem(10f, 10f, 20f, 20f, order = 2)

        sm.select(item1)
        assertEquals(RectF(0f, 0f, 10f, 10f), sm.getTransformedBounds())

        sm.select(item2)
        assertEquals(RectF(0f, 0f, 20f, 20f), sm.getTransformedBounds())

        sm.deselect(item1)
        // Note: In virtualized mode, bounds remain conservative (don't shrink) on deselect
        assertEquals(RectF(0f, 0f, 20f, 20f), sm.getTransformedBounds())

        sm.clearSelection()
        assertTrue(sm.getTransformedBounds().isEmpty)
    }

    @Test
    fun `test concurrent selection`() {
        val sm = SelectionManager()
        val executor = Executors.newFixedThreadPool(10)
        val items =
            (0 until 100).map { i ->
                // Ensure unique IDs
                val item = createMockItem(i.toFloat(), i.toFloat(), (i + 1).toFloat(), (i + 1).toFloat())
                (item as Stroke).copy(strokeOrder = i.toLong())
            }

        for (item in items) {
            executor.execute {
                sm.select(item)
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(100, sm.getSelectedIds().size)
        assertEquals(RectF(0f, 0f, 100f, 100f), sm.getTransformedBounds())
    }

    @Test
    fun `test transformation synchronization`() {
        val sm = SelectionManager()
        sm.select(createMockItem(0f, 0f, 10f, 10f))

        val executor = Executors.newFixedThreadPool(10)
        for (i in 0 until 1000) {
            executor.execute {
                sm.translate(1f, 1f)
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val bounds = sm.getTransformedBounds()
        // Expected: 1000 translations of (1,1) -> (1000, 1000) offset
        assertEquals(1000f, bounds.left, 0.001f)
        assertEquals(1000f, bounds.top, 0.001f)
        assertEquals(1010f, bounds.right, 0.001f)
        assertEquals(1010f, bounds.bottom, 0.001f)
    }
}
