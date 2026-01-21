package com.alexdremov.notate.data.region

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RegionModelsTest {
    @Test
    fun `RegionId toString and fromString`() {
        val id = RegionId(5, -3)
        val str = id.toString()
        assertEquals("5_-3", str)

        val parsed = RegionId.fromString(str)
        assertEquals(id, parsed)
    }

    @Test
    fun `RegionId fromString invalid`() {
        assertNull(RegionId.fromString("invalid"))
        assertNull(RegionId.fromString("5_"))
        assertNull(RegionId.fromString("_3"))
        assertNull(RegionId.fromString("5_-3_2"))
    }

    @Test
    fun `RegionId getBounds`() {
        val id = RegionId(1, 1)
        val size = 2048f
        val bounds = id.getBounds(size)

        assertEquals(RectF(2048f, 2048f, 4096f, 4096f), bounds)
    }

    @Test
    fun `RegionData sizeBytes calculation`() {
        val id = RegionId(0, 0)
        val region = RegionData(id)

        // Base overhead
        assertEquals(1024L, region.sizeBytes())

        // Add a stroke
        val strokePoints = 10
        val stroke = createTestStroke(strokePoints)
        region.items.add(stroke)

        // Expected size: Base + (10 points * 28 bytes)
        // 28 bytes = 7 floats * 4 bytes
        val expectedSize = 1024L + (strokePoints * 28L)
        assertEquals(expectedSize, region.sizeBytes())

        // Add an image
        val image =
            com.alexdremov.notate.model.CanvasImage(
                uri = "content://test",
                bounds = RectF(0f, 0f, 100f, 100f),
            )
        region.items.add(image)

        // Expected size: Previous + (256L + length * 2)
        val imageSize = 256L + (image.uri.length * 2L)
        assertEquals(expectedSize + imageSize, region.sizeBytes())
    }

    private fun createTestStroke(pointCount: Int): Stroke {
        val points = ArrayList<TouchPoint>()
        for (i in 0 until pointCount) {
            points.add(TouchPoint(i.toFloat(), i.toFloat(), 0.5f, 1.0f, 0, 0, 0L))
        }
        return Stroke(
            path = Path(),
            points = points,
            color = 0,
            width = 1f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(),
        )
    }
}
