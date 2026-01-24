package com.alexdremov.notate.data.region

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for verifying thread safety of RegionManager operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RegionManagerConcurrencyTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var storage: RegionStorage
    private lateinit var manager: RegionManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        testDir = File(context.cacheDir, "region_concurrency_test")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
        storage = RegionStorage(testDir)
        storage.init()
        manager = RegionManager(storage, 2048f)
    }

    /**
     * Test that concurrent getRegion calls for the same region don't cause data loss.
     */
    @Test
    fun `concurrent getRegion for same region returns consistent data`() =
        runTest {
            val threadCount = 10
            val regions = ConcurrentHashMap<Int, RegionData>()

            val regionId = RegionId(0, 0)

            // First, add an item to the region so it has content
            val testStroke = createTestStroke(0f, 0f)
            manager.addItem(testStroke)
            manager.saveAll()

            // Clear cache to force reload
            manager.clear()

            // Now have multiple threads try to get the same region simultaneously
            val jobs =
                (0 until threadCount).map { i ->
                    launch {
                        val region = manager.getRegion(regionId)
                        regions[i] = region
                    }
                }
            jobs.forEach { it.join() }

            // All threads should have received the same RegionData instance
            val uniqueRegions = regions.values.toSet()
            assertEquals("All threads should get the same region instance", 1, uniqueRegions.size)
        }

    /**
     * Test that concurrent addItem operations don't corrupt data.
     */
    @Test
    fun `concurrent addItem operations preserve all items`() =
        runTest {
            val threadCount = 10
            val itemsPerThread = 100

            val jobs =
                (0 until threadCount).map { i ->
                    launch {
                        for (j in 0 until itemsPerThread) {
                            val x = (i * 100 + j).toFloat()
                            val y = (i * 100 + j).toFloat()
                            val stroke = createTestStroke(x, y)
                            manager.addItem(stroke)
                        }
                    }
                }
            jobs.forEach { it.join() }

            // Verify all items were added
            val allItems =
                manager
                    .getRegionsInRect(RectF(-10000f, -10000f, 10000f, 10000f))
                    .flatMap { it.items }

            assertEquals("All items should be present", threadCount * itemsPerThread, allItems.size)
        }

    /**
     * Test that concurrent read and write operations don't corrupt data.
     */
    @Test
    fun `concurrent read and write operations`() =
        runTest {
            val iterations = 50
            val errors = AtomicInteger(0)

            // Add some initial data
            for (i in 0 until 10) {
                manager.addItem(createTestStroke(i.toFloat() * 10, i.toFloat() * 10))
            }

            // Mix of readers and writers
            val jobs =
                (0 until iterations).flatMap { i ->
                    listOf(
                        launch {
                            try {
                                val stroke = createTestStroke(i.toFloat() * 100, i.toFloat() * 100)
                                manager.addItem(stroke)
                            } catch (e: Exception) {
                                errors.incrementAndGet()
                            }
                        },
                        launch {
                            try {
                                val regions = manager.getRegionsInRect(RectF(-10000f, -10000f, 10000f, 10000f))
                                regions.forEach { region ->
                                    region.items.size
                                }
                            } catch (e: Exception) {
                                errors.incrementAndGet()
                            }
                        },
                    )
                }
            jobs.forEach { it.join() }

            assertEquals("No errors should occur", 0, errors.get())
        }

    /**
     * Test that saveAll during concurrent modifications doesn't lose data.
     */
    @Test
    fun `saveAll during concurrent modifications preserves data integrity`() =
        runTest {
            val threadCount = 4
            val itemsAdded = AtomicInteger(0)

            val saveJob =
                launch {
                    for (i in 0 until 10) {
                        kotlinx.coroutines.delay(10)
                        manager.saveAll()
                    }
                }

            val addJobs =
                (1 until threadCount).map { t ->
                    launch {
                        for (i in 0 until 50) {
                            val stroke = createTestStroke((t * 1000 + i).toFloat(), 0f)
                            manager.addItem(stroke)
                            itemsAdded.incrementAndGet()
                            kotlinx.coroutines.delay(1)
                        }
                    }
                }

            saveJob.join()
            addJobs.forEach { it.join() }

            // Final save
            manager.saveAll()

            // Verify all items are present
            val allItems =
                manager
                    .getRegionsInRect(RectF(-100000f, -100000f, 100000f, 100000f))
                    .flatMap { it.items }

            assertEquals("All items should be saved", itemsAdded.get(), allItems.size)
        }

    private fun createTestStroke(
        x: Float,
        y: Float,
    ): Stroke {
        val points = ArrayList<TouchPoint>()
        for (i in 0 until 5) {
            points.add(TouchPoint(x + i, y + i, 0.5f, 1.0f, 0, 0, 0L))
        }
        val rect = RectF(x, y, x + 5, y + 5)
        return Stroke(
            path = Path().apply { addRect(rect, Path.Direction.CW) },
            points = points,
            color = -16777216,
            width = 2f,
            style = StrokeType.FINELINER,
            bounds = rect,
        )
    }
}
