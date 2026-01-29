package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
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
class RegionManagerOverflowTest {
    private lateinit var storage: RegionStorage
    private lateinit var regionManager: RegionManager
    private lateinit var tempDir: File

    // Configuration
    // Limit 4000 bytes (~4KB). maxKb will be 3.
    // Each region (1 stroke) is ~1.8KB -> 1KB in LruCache accounting.
    // 3 regions fit (3KB <= 3KB). 4th triggers eviction.
    // Overflow limit is 2000 bytes (~2KB). 1 region (1.8KB) fits.
    private val MEMORY_LIMIT = 4000L

    @Before
    fun setup() {
        tempDir = File(RuntimeEnvironment.getApplication().cacheDir, "overflow_test_${System.nanoTime()}")
        tempDir.mkdirs()
        storage = RegionStorage(tempDir)
        storage.init()

        // Initialize RegionManager with restricted memory
        regionManager = RegionManager(storage, CanvasConfig.DEFAULT_REGION_SIZE, MEMORY_LIMIT)
    }

    private fun createHeavyRegion(
        id: RegionId,
        pointCount: Int = 10,
    ): RegionData {
        val regionSize = CanvasConfig.DEFAULT_REGION_SIZE
        val xOff = id.x * regionSize + 10f
        val yOff = id.y * regionSize + 10f

        val points =
            (0 until pointCount).map {
                TouchPoint(xOff + it, yOff + it, 0.5f, 1f, 0, 0, System.currentTimeMillis())
            }
        val path = android.graphics.Path()

        val stroke =
            Stroke(
                path = path,
                points = points,
                color = -16777216,
                width = 5f,
                style = StrokeType.BALLPOINT,
                bounds = RectF(xOff, yOff, xOff + 10f, yOff + 10f),
                strokeOrder = System.nanoTime(),
            )

        val region = RegionData(id)
        region.items.add(stroke)
        return region
    }

    @Test
    fun `test pinned regions move to overflow on eviction`() =
        runBlocking {
            val r1 = RegionId(0, 0)
            val r2 = RegionId(0, 1)
            val r3 = RegionId(0, 2)
            val r4 = RegionId(0, 3) // Will force eviction of R1

            // 1. Add R1, R2, R3 (fills 3KB / 3KB limit)
            regionManager.addItem(createHeavyRegion(r1).items[0])
            regionManager.addItem(createHeavyRegion(r2).items[0])
            regionManager.addItem(createHeavyRegion(r3).items[0])

            // Pin R1
            regionManager.setPinnedRegions(setOf(r1))

            // 2. Add R4. This exceeds limit. R1 (LRU) should be evicted.
            regionManager.addItem(createHeavyRegion(r4).items[0])

            // Verify R1 is still accessible in memory (via getRegion) without disk load
            storage.deleteRegion(r1)

            val fetchedR1 = regionManager.getRegion(r1)
            assertNotNull(fetchedR1)
            assertFalse("Should be loaded from memory (overflow), not disk", fetchedR1.items.isEmpty())
        }

    @Test
    fun `test unpinned regions are dropped on eviction`() =
        runBlocking {
            val r1 = RegionId(0, 0)
            val r2 = RegionId(0, 1)
            val r3 = RegionId(0, 2)
            val r4 = RegionId(0, 3)

            // 1. Add R1, R2, R3
            regionManager.addItem(createHeavyRegion(r1).items[0])
            regionManager.addItem(createHeavyRegion(r2).items[0])
            regionManager.addItem(createHeavyRegion(r3).items[0])

            // No pins.

            // 2. Add R4. R1 (LRU) evicted.
            regionManager.addItem(createHeavyRegion(r4).items[0])

            // Verify R1 is saved to disk and removed from cache
            // We use a retry loop because saving is asynchronous (Dispatchers.IO)
            var found = false
            for (i in 0 until 20) {
                if (File(tempDir, "r_0_0.bin").exists()) {
                    found = true
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            assertTrue("Evicted dirty region should be saved to disk", found)
        }

    @Test
    fun `test overflow limit enforces eviction of oldest pinned items`() =
        runBlocking {
            val r1 = RegionId(0, 0) // Pin 1
            val r2 = RegionId(0, 1) // Pin 2
            val r3 = RegionId(0, 2) // Pin 3
            val r4 = RegionId(0, 3) // Filler
            val r5 = RegionId(0, 4) // Filler
            val r6 = RegionId(0, 5) // Filler

            // Pin them
            regionManager.setPinnedRegions(setOf(r1, r2, r3))

            // 1. Fill Cache with pinned
            regionManager.addItem(createHeavyRegion(r1).items[0])
            regionManager.addItem(createHeavyRegion(r2).items[0])
            regionManager.addItem(createHeavyRegion(r3).items[0])

            // 2. Add Fillers to force pinned into overflow
            regionManager.addItem(createHeavyRegion(r4).items[0]) // Evicts R1 -> Overflow: [R1]
            regionManager.addItem(createHeavyRegion(r5).items[0]) // Evicts R2 -> Overflow: [R1, R2]

            // 3. One more filler -> Evicts R3 from cache
            // R3 added to Overflow. Total bytes exceeds 2KB. MUST evict R1 and R2.
            regionManager.addItem(createHeavyRegion(r6).items[0])

            // Verify R1 and R2 are NOT in memory
            storage.deleteRegion(r1)
            storage.deleteRegion(r2)

            val r1Data = regionManager.getRegion(r1)
            assertTrue("R1 should have been evicted from overflow", r1Data.items.isEmpty())

            val r2Data = regionManager.getRegion(r2)
            assertTrue("R2 should have been evicted from overflow", r2Data.items.isEmpty())

            // Verify R3 is still in memory (overflow)
            storage.deleteRegion(r3)
            val r3Data = regionManager.getRegion(r3)
            assertFalse("R3 should still be in overflow", r3Data.items.isEmpty())
        }

    @Test
    fun `test unpinning moves items back to cache`() =
        runBlocking {
            val r1 = RegionId(0, 0)
            val r2 = RegionId(0, 1)
            val r3 = RegionId(0, 2)
            val r4 = RegionId(0, 3)

            // 1. Add R1 (Pinned)
            regionManager.setPinnedRegions(setOf(r1))
            regionManager.addItem(createHeavyRegion(r1).items[0])

            // 2. Force R1 into overflow by adding fillers
            regionManager.addItem(createHeavyRegion(r2).items[0])
            regionManager.addItem(createHeavyRegion(r3).items[0])
            regionManager.addItem(createHeavyRegion(r4).items[0]) // Evicts R1 to overflow

            // Verify R1 is in overflow
            storage.deleteRegion(r1)
            val r1Check = regionManager.getRegion(r1)
            assertFalse(r1Check.items.isEmpty())

            // 3. Unpin R1
            regionManager.setPinnedRegions(emptySet())

            // Should move back to cache.
            val r1Check2 = regionManager.getRegion(r1)
            assertFalse(r1Check2.items.isEmpty())
        }
}
