package com.alexdremov.notate.data.region

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
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
     * Before the fix, two threads could simultaneously:
     * 1. See cache miss
     * 2. Both load from disk (or create new)
     * 3. Both put to cache (second overwrites first)
     * 
     * With the fix, only one thread loads and caches, others wait.
     */
    @Test
    fun `concurrent getRegion for same region returns consistent data`() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val startedCount = AtomicInteger(0)
        
        // All regions returned should be the same instance
        val regions = ConcurrentHashMap<Int, RegionData>()
        
        val regionId = RegionId(0, 0)
        
        // First, add an item to the region so it has content
        val testStroke = createTestStroke(0f, 0f)
        manager.addItem(testStroke)
        manager.saveAll()
        
        // Clear cache to force reload
        manager.clear()
        
        // Now have multiple threads try to get the same region simultaneously
        for (i in 0 until threadCount) {
            executor.execute {
                startedCount.incrementAndGet()
                latch.await() // Wait for all threads to be ready
                
                val region = manager.getRegion(regionId)
                regions[i] = region
            }
        }
        
        // Wait for all threads to be ready
        while (startedCount.get() < threadCount) {
            Thread.sleep(10)
        }
        
        // Start all threads simultaneously
        latch.countDown()
        
        executor.shutdown()
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS))
        
        // All threads should have received the same RegionData instance
        val uniqueRegions = regions.values.toSet()
        assertEquals("All threads should get the same region instance", 1, uniqueRegions.size)
    }
    
    /**
     * Test that concurrent addItem operations don't corrupt data.
     */
    @Test
    fun `concurrent addItem operations preserve all items`() {
        val threadCount = 10
        val itemsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val completedCount = AtomicInteger(0)
        
        for (i in 0 until threadCount) {
            executor.execute {
                latch.await()
                
                for (j in 0 until itemsPerThread) {
                    // Each thread adds items in a different area to stress the system
                    val x = (i * 100 + j).toFloat()
                    val y = (i * 100 + j).toFloat()
                    val stroke = createTestStroke(x, y)
                    manager.addItem(stroke)
                }
                
                completedCount.incrementAndGet()
            }
        }
        
        latch.countDown()
        
        executor.shutdown()
        assertTrue("Executor should terminate", executor.awaitTermination(30, TimeUnit.SECONDS))
        
        assertEquals("All threads should complete", threadCount, completedCount.get())
        
        // Verify all items were added
        val allItems = manager.getRegionsInRect(RectF(-10000f, -10000f, 10000f, 10000f))
            .flatMap { it.items }
        
        assertEquals("All items should be present", threadCount * itemsPerThread, allItems.size)
    }
    
    /**
     * Test that concurrent read and write operations don't corrupt data.
     */
    @Test
    fun `concurrent read and write operations`() {
        val iterations = 50
        val executor = Executors.newFixedThreadPool(4)
        val errors = AtomicInteger(0)
        
        // Add some initial data
        for (i in 0 until 10) {
            manager.addItem(createTestStroke(i.toFloat() * 10, i.toFloat() * 10))
        }
        
        val latch = CountDownLatch(iterations * 2)
        
        // Mix of readers and writers
        for (i in 0 until iterations) {
            // Writer
            executor.execute {
                try {
                    val stroke = createTestStroke(i.toFloat() * 100, i.toFloat() * 100)
                    manager.addItem(stroke)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
            
            // Reader
            executor.execute {
                try {
                    val regions = manager.getRegionsInRect(RectF(-10000f, -10000f, 10000f, 10000f))
                    // Just verify we can read without exceptions
                    regions.forEach { region ->
                        region.items.size // Access items
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue("Operations should complete", latch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        
        assertEquals("No errors should occur", 0, errors.get())
    }
    
    /**
     * Test that saveAll during concurrent modifications doesn't lose data.
     */
    @Test
    fun `saveAll during concurrent modifications preserves data integrity`() {
        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val itemsAdded = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)
        
        // One thread does saves
        executor.execute {
            try {
                for (i in 0 until 10) {
                    Thread.sleep(10)
                    manager.saveAll()
                }
            } finally {
                latch.countDown()
            }
        }
        
        // Other threads add items
        for (t in 1 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until 50) {
                        val stroke = createTestStroke((t * 1000 + i).toFloat(), 0f)
                        manager.addItem(stroke)
                        itemsAdded.incrementAndGet()
                        Thread.sleep(1)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue("Operations should complete", latch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        
        // Final save
        manager.saveAll()
        
        // Verify all items are present
        val allItems = manager.getRegionsInRect(RectF(-100000f, -100000f, 100000f, 100000f))
            .flatMap { it.items }
        
        assertEquals("All items should be saved", itemsAdded.get(), allItems.size)
    }
    
    private fun createTestStroke(x: Float, y: Float): Stroke {
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
