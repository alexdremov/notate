package com.alexdremov.notate.data

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.TextItem
import com.alexdremov.notate.util.StrokeGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasSerializerRefactorTest {
    @Test
    fun `test CanvasImage serialization roundtrip`() {
        val logical = RectF(10f, 20f, 110f, 120f)
        val rotation = 30f
        val aabb = StrokeGeometry.computeRotatedBounds(logical, rotation)

        val original =
            CanvasImage(
                uri = "content://test",
                logicalBounds = logical,
                bounds = aabb,
                rotation = rotation,
                zIndex = 5f,
                order = 123L,
            )

        val data = CanvasSerializer.toCanvasImageData(original)

        // Assert serialization uses logical bounds
        assertEquals(10f, data.x, 0.01f)
        assertEquals(20f, data.y, 0.01f)
        assertEquals(100f, data.width, 0.01f)
        assertEquals(100f, data.height, 0.01f)
        assertEquals(rotation, data.rotation, 0.01f)

        val restored = CanvasSerializer.fromCanvasImageData(data)

        assertEquals(original.uri, restored.uri)
        assertEquals(original.logicalBounds, restored.logicalBounds)
        assertEquals(original.rotation, restored.rotation, 0.01f)
        assertEquals(original.bounds.left, restored.bounds.left, 0.1f)
        assertEquals(original.bounds.top, restored.bounds.top, 0.1f)
        assertEquals(original.bounds.right, restored.bounds.right, 0.1f)
        assertEquals(original.bounds.bottom, restored.bounds.bottom, 0.1f)
        assertEquals(original.zIndex, restored.zIndex, 0.01f)
        assertEquals(original.order, restored.order)
    }

    @Test
    fun `test TextItem serialization roundtrip`() {
        val logical = RectF(50f, 50f, 250f, 100f)
        val rotation = -45f
        val aabb = StrokeGeometry.computeRotatedBounds(logical, rotation)

        val original =
            TextItem(
                text = "Hello World",
                fontSize = 24f,
                color = 0xFF0000,
                logicalBounds = logical,
                bounds = aabb,
                rotation = rotation,
                order = 456L,
            )

        val data = CanvasSerializer.toTextItemData(original)
        val restored = CanvasSerializer.fromTextItemData(data)

        assertEquals(original.text, restored.text)
        assertEquals(original.fontSize, restored.fontSize, 0.01f)
        assertEquals(original.logicalBounds, restored.logicalBounds)
        assertEquals(original.rotation, restored.rotation, 0.01f)
        assertEquals(original.bounds.left, restored.bounds.left, 0.1f)
        assertEquals(original.order, restored.order)
    }
}
