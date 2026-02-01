package com.alexdremov.notate.data.region

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RegionManagerLogicTest {
    private lateinit var storage: RegionStorage
    private lateinit var regionManager: RegionManager
    private lateinit var tempDir: File
    private val regionSize = 1000f

    @Before
    fun setup() {
        tempDir = File(RuntimeEnvironment.getApplication().cacheDir, "logic_test_${System.nanoTime()}")
        tempDir.mkdirs()
        storage = RegionStorage(tempDir)
        storage.init()
        regionManager = RegionManager(storage, regionSize)
    }

    private fun createTestStroke(
        order: Long,
        targetBounds: RectF,
    ): Stroke {
        val points =
            listOf(
                TouchPoint(targetBounds.left, targetBounds.top, 0.5f, 5f, 100L),
                TouchPoint(targetBounds.right, targetBounds.bottom, 0.5f, 5f, 110L),
            )
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        path.lineTo(points[1].x, points[1].y)

        // Use standard computation to avoid mismatches during deserialization
        val bounds = StrokeGeometry.computeStrokeBounds(path, 2f, StrokeType.FOUNTAIN)

        return Stroke(
            path = path,
            points = points,
            color = 0,
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = bounds,
            strokeOrder = order,
        )
    }

    @Test
    fun `findItem should find item in its primary region`() =
        runBlocking {
            val stroke = createTestStroke(1L, RectF(10f, 10f, 20f, 20f))
            regionManager.addItem(stroke)

            val found = regionManager.findItem(1L, stroke.bounds)
            assertEquals(stroke, found)
        }

    @Test
    fun `findItem should find item in overlapping region`() =
        runBlocking {
            // Stroke that crosses region boundary (0,0) and (1,0)
            val stroke = createTestStroke(1L, RectF(990f, 10f, 1010f, 20f))
            regionManager.addItem(stroke)

            val found = regionManager.findItem(1L, stroke.bounds)
            assertEquals(stroke, found)
        }

    @Test
    fun `visitItemsInRect should visit all items in range`() =
        runBlocking {
            val s1 = createTestStroke(1L, RectF(10f, 10f, 20f, 20f))
            val s2 = createTestStroke(2L, RectF(1100f, 10f, 1120f, 20f))
            regionManager.addItem(s1)
            regionManager.addItem(s2)

            val visited = mutableListOf<CanvasItem>()
            regionManager.visitItemsInRect(RectF(0f, 0f, 2000f, 100f)) {
                visited.add(it)
            }

            assertEquals(2, visited.size)
            assertTrue(visited.any { it.order == 1L })
            assertTrue(visited.any { it.order == 2L })
        }

    @Test
    fun `stash and unstash items should preserve data`() =
        runBlocking {
            val s1 = createTestStroke(1L, RectF(10f, 10f, 20f, 20f))
            val s2 = createTestStroke(2L, RectF(30f, 30f, 40f, 40f))
            regionManager.addItem(s1)
            regionManager.addItem(s2)

            val expectedUnion = RectF(s1.bounds)
            expectedUnion.union(s2.bounds)

            val stashFile = File(tempDir, "stash.bin")
            val queryRect = RectF(-1000f, -1000f, 5000f, 5000f)

            val stashedCount = regionManager.stashSelectedItems(queryRect, setOf(1L, 2L), stashFile)
            assertEquals(2, stashedCount)

            // Verify they are removed from manager
            val remaining = regionManager.getRegionsInRect(queryRect).flatMap { it.items }
            assertTrue(remaining.isEmpty())

            // Unstash with identity matrix
            val (addedIds, bounds) = regionManager.unstashItems(stashFile, Matrix())
            assertEquals(setOf(1L, 2L), addedIds)
            assertEquals(expectedUnion, bounds)

            // Verify they are back
            val restored = regionManager.getRegionsInRect(queryRect).flatMap { it.items }
            assertEquals(2, restored.size)
        }

    @Test
    fun `removeItemsByIds should remove specified items`() =
        runBlocking {
            val s1 = createTestStroke(1L, RectF(10f, 10f, 20f, 20f))
            val s2 = createTestStroke(2L, RectF(30f, 30f, 40f, 40f))
            regionManager.addItem(s1)
            regionManager.addItem(s2)

            regionManager.removeItemsByIds(RectF(0f, 0f, 100f, 100f), setOf(1L))

            val remaining = regionManager.getRegionsInRect(RectF(0f, 0f, 100f, 100f)).flatMap { it.items }
            assertEquals(1, remaining.size)
            assertEquals(2L, remaining[0].order)
        }

    @Test
    fun `getRegionThumbnail should generate and cache bitmap`() =
        runBlocking {
            val stroke = createTestStroke(1L, RectF(10f, 10f, 20f, 20f))
            regionManager.addItem(stroke)

            val context = RuntimeEnvironment.getApplication()
            val bitmap = regionManager.getRegionThumbnail(RegionId(0, 0), context)

            assertNotNull(bitmap)

            // Check if it's in storage
            val thumbFile = File(tempDir, "thumbnails/t_0_0.png")
            assertTrue(thumbFile.exists())

            // Subsequent call should be fast (cached)
            val bitmap2 = regionManager.getRegionThumbnail(RegionId(0, 0), context)
            assertSame(bitmap, bitmap2)
        }
}
