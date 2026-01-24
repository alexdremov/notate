package com.alexdremov.notate.data.region

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RegionStorageTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var storage: RegionStorage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        testDir = File(context.cacheDir, "region_test")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
        storage = RegionStorage(testDir)
        storage.init()
    }

    @Test
    fun `save and load empty region`() {
        val id = RegionId(1, 2)
        val data = RegionData(id)

        storage.saveRegion(data)

        val loaded = storage.loadRegion(id)
        assertNotNull(loaded)
        assertEquals(id, loaded!!.id)
        assertTrue(loaded.items.isEmpty())
    }

    @Test
    fun `save and load region with strokes`() {
        val id = RegionId(-1, 5)
        val data = RegionData(id)

        val stroke1 = createTestStroke(10, 0)
        val stroke2 = createTestStroke(5, 1)

        data.items.add(stroke1)
        data.items.add(stroke2)

        storage.saveRegion(data)

        val loaded = storage.loadRegion(id)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.items.size)

        val loadedStroke1 = loaded.items[0] as Stroke
        val loadedStroke2 = loaded.items[1] as Stroke

        verifyStroke(stroke1, loadedStroke1)
        verifyStroke(stroke2, loadedStroke2)
    }

    @Test
    fun `save and load region with images`() {
        val id = RegionId(0, 0)
        val data = RegionData(id)

        val image =
            com.alexdremov.notate.model.CanvasImage(
                uri = "file://test/image.png",
                bounds = RectF(10f, 20f, 110f, 120f),
                zIndex = 5f,
                order = 100,
                rotation = 45f,
                opacity = 0.8f,
            )

        data.items.add(image)
        storage.saveRegion(data)

        val loaded = storage.loadRegion(id)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.items.size)

        val loadedImage = loaded.items[0] as com.alexdremov.notate.model.CanvasImage
        assertEquals(image.uri, loadedImage.uri)
        assertEquals(image.bounds, loadedImage.bounds)
        assertEquals(image.zIndex, loadedImage.zIndex, 0.01f)
        assertEquals(image.order, loadedImage.order)
        assertEquals(image.rotation, loadedImage.rotation, 0.01f)
        assertEquals(image.opacity, loadedImage.opacity, 0.01f)
    }

    @Test
    fun `delete region`() {
        val id = RegionId(3, 3)
        val data = RegionData(id)
        storage.saveRegion(data)

        assertTrue(File(testDir, "r_3_3.bin").exists())

        storage.deleteRegion(id)

        assertTrue(!File(testDir, "r_3_3.bin").exists())
        assertNull(storage.loadRegion(id))
    }

    @Test
    fun `load non-existent region returns null`() {
        val loaded = storage.loadRegion(RegionId(99, 99))
        assertNull(loaded)
    }

    @Test
    fun `save and load index`() {
        val index =
            mapOf(
                RegionId(0, 0) to RectF(0f, 0f, 100f, 100f),
                RegionId(1, 1) to RectF(200f, 200f, 300f, 300f),
            )

        storage.saveIndex(index)
        val loaded = storage.loadIndex()

        assertEquals(2, loaded.size)
        assertEquals(index[RegionId(0, 0)], loaded[RegionId(0, 0)])
        assertEquals(index[RegionId(1, 1)], loaded[RegionId(1, 1)])
    }

    private fun createTestStroke(
        pointCount: Int,
        offset: Int,
    ): Stroke {
        val points = ArrayList<TouchPoint>()
        for (i in 0 until pointCount) {
            points.add(
                TouchPoint(
                    i.toFloat() + offset,
                    i.toFloat() + offset,
                    0.5f,
                    2.0f,
                    0,
                    0,
                    i * 100L,
                ),
            )
        }

        val path = Path() // In Robolectric, Path is mocked/shadowed but functional enough for properties?
        // We generally rely on CanvasSerializer rebuilding the path from points.

        return Stroke(
            path = path,
            points = points,
            color = -123456,
            width = 2.5f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(0f, 0f, 100f, 100f), // Dummy bounds
            strokeOrder = offset.toLong(),
            zIndex = 1.0f,
        )
    }

    private fun verifyStroke(
        expected: Stroke,
        actual: Stroke,
    ) {
        assertEquals(expected.points.size, actual.points.size)
        assertEquals(expected.color, actual.color)
        assertEquals(expected.width, actual.width, 0.01f)
        assertEquals(expected.style, actual.style)
        assertEquals(expected.strokeOrder, actual.strokeOrder)
        assertEquals(expected.zIndex, actual.zIndex, 0.01f)

        for (i in expected.points.indices) {
            val p1 = expected.points[i]
            val p2 = actual.points[i]
            assertEquals(p1.x, p2.x, 0.01f)
            assertEquals(p1.y, p2.y, 0.01f)
            assertEquals(p1.pressure, p2.pressure, 0.01f)
            assertEquals(p1.size, p2.size, 0.01f)
            assertEquals(p1.tiltX, p2.tiltX)
            assertEquals(p1.tiltY, p2.tiltY)
            assertEquals(p1.timestamp, p2.timestamp)
        }
    }
}
