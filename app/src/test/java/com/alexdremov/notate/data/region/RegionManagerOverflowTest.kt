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
    // Region Base: ~256 bytes
    // Item Base: ~128 bytes
    // Points: 52 bytes each
    // 10 points = 520 bytes
    // Total per region with 1 stroke (10 pts) = 256 + 128 + 520 = ~904 bytes.
    // Let's assume ~1KB per region for calculation simplicity.
    
    // We set limit to 2048 bytes (2 regions).
    // Overflow limit will be 1024 bytes (1 region).
    private val MEMORY_LIMIT = 2048L

    @Before
    fun setup() {
        tempDir = File(RuntimeEnvironment.getApplication().cacheDir, "overflow_test_${System.nanoTime()}")
        tempDir.mkdirs()
        storage = RegionStorage(tempDir)
        storage.init()
        
        // Initialize RegionManager with restricted memory
        regionManager = RegionManager(storage, CanvasConfig.DEFAULT_REGION_SIZE, MEMORY_LIMIT)
    }

    private fun createHeavyRegion(id: RegionId, pointCount: Int = 10): RegionData {
        val regionSize = CanvasConfig.DEFAULT_REGION_SIZE
        val xOff = id.x * regionSize + 10f
        val yOff = id.y * regionSize + 10f
        
        val points = (0 until pointCount).map {
            TouchPoint(xOff + it, yOff + it, 0.5f, 1f, 0, 0, System.currentTimeMillis())
        }
        val path = android.graphics.Path() // Robolectric mocks this
        
        val stroke = Stroke(
            path = path,
            points = points,
            color = -16777216,
            width = 5f,
            style = StrokeType.BALLPOINT,
            bounds = RectF(xOff, yOff, xOff + 10f, yOff + 10f),
            strokeOrder = System.nanoTime()
        )
        
        val region = RegionData(id)
        region.items.add(stroke)
        return region
    }

    @Test
    fun `test pinned regions move to overflow on eviction`() = runBlocking {
        val r1 = RegionId(0, 0)
        val r2 = RegionId(0, 1)
        val r3 = RegionId(0, 2) // Will force eviction

        // 1. Add R1 and R2 (fills ~1800 bytes / 2048 limit)
        regionManager.addItem(createHeavyRegion(r1).items[0])
        regionManager.addItem(createHeavyRegion(r2).items[0])

        // Pin R1
        regionManager.setPinnedRegions(setOf(r1))

        // 2. Add R3. This exceeds 2048 bytes. R1 (LRU) should be evicted.
        // Since R1 is pinned, it should go to overflow.
        regionManager.addItem(createHeavyRegion(r3).items[0])

        // Verify R1 is still accessible in memory (via getRegion) without disk load
        // We delete the disk file to prove it comes from memory
        storage.deleteRegion(r1)
        
        val fetchedR1 = regionManager.getRegion(r1)
        assertNotNull(fetchedR1)
        assertFalse("Should be loaded from memory (overflow), not disk", fetchedR1.items.isEmpty()) // If loaded from disk (deleted), would be empty/new
    }

    @Test
    fun `test unpinned regions are dropped on eviction`() = runBlocking {
        val r1 = RegionId(0, 0)
        val r2 = RegionId(0, 1)
        val r3 = RegionId(0, 2)

        // 1. Add R1, R2
        regionManager.addItem(createHeavyRegion(r1).items[0])
        regionManager.addItem(createHeavyRegion(r2).items[0])

        // No pins.

        // 2. Add R3. R1 (LRU) evicted.
        regionManager.addItem(createHeavyRegion(r3).items[0])

        // Verify R1 is saved to disk and removed from cache
        // regionManager.getRegion(r1) would reload from disk.
        // We check if it WAS saved.
        assertTrue("Evicted dirty region should be saved to disk", File(tempDir, "r_0_0.bin").exists())
    }

    @Test
    fun `test overflow limit enforces eviction of oldest pinned items`() = runBlocking {
        // Limit 2048. Overflow Limit 1024 (1 region).
        
        val r1 = RegionId(0, 0) // Pin 1
        val r2 = RegionId(0, 1) // Pin 2 (Excess)
        val r3 = RegionId(0, 2) // Cache Filler
        val r4 = RegionId(0, 3) // Cache Filler

        // Pin R1 and R2
        regionManager.setPinnedRegions(setOf(r1, r2))

        // Add R1
        regionManager.addItem(createHeavyRegion(r1).items[0]) // Cache: [R1]
        
        // Add R3 (Filler)
        regionManager.addItem(createHeavyRegion(r3).items[0]) // Cache: [R1, R3] (Full)

        // Add R4 (Filler) -> Evicts R1 (Pinned) -> Overflow: [R1]
        regionManager.addItem(createHeavyRegion(r4).items[0]) // Cache: [R3, R4]. Overflow: [R1]

        // Add R2 (Pinned) -> Cache: [R3, R4, R2] -> Overflow limit exceeded?
        // Wait, adding R2 puts it in Cache. Total size > 2048.
        // Cache evicts R3 (Unpinned).
        // Cache: [R4, R2]. Overflow: [R1].
        // This fits! (Overflow 1 item < limit).
        
        // We need more pressure.
        val r5 = RegionId(0, 4)
        
        regionManager.addItem(createHeavyRegion(r2).items[0]) 
        // Cache: [R4, R2]. Overflow: [R1]
        
        // Add R5. Evicts R4 (Unpinned).
        regionManager.addItem(createHeavyRegion(r5).items[0])
        // Cache: [R2, R5]. Overflow: [R1].
        
        // Now add R6. Evicts R2 (Pinned).
        // R2 goes to Overflow. Overflow: [R1, R2].
        // Overflow Limit (1024) exceeded (2 items ~1800).
        // Should evict R1 (Oldest in Overflow).
        
        val r6 = RegionId(0, 5)
        regionManager.addItem(createHeavyRegion(r6).items[0])
        
        // Current State Expected:
        // Cache: [R5, R6]
        // Overflow: [R2] (R1 evicted)
        
        // Verify R1 is NOT in memory (we delete disk first)
        storage.deleteRegion(r1) // Ensure we don't reload from disk
        
        // Access R1. Since we deleted disk, if it's not in memory, we get empty region.
        // If it WAS in memory, we get the data.
        
        // But wait, getRegion reloads if not in cache.
        // If R1 was evicted from overflow, it was saved to disk (if dirty).
        // So checking disk existence is a good check for "Was it persisted?".
        // But we want to check if it's in RAM.
        
        // We can inspect internal state via reflection OR rely on behavior.
        // If we access R1 now, does it come from overflow?
        
        // Let's create a distinct item in R1 to track it.
        val r1Data = regionManager.getRegion(r1) // Should be empty/new because we deleted disk and it was evicted
        
        // If eviction worked, r1Data should be empty (newly created) because disk was deleted.
        // If it stuck in overflow, r1Data would have items.
        assertTrue("R1 should have been evicted from overflow", r1Data.items.isEmpty())
        
        // Verify R2 is still in overflow (not empty)
        storage.deleteRegion(r2)
        val r2Data = regionManager.getRegion(r2)
        assertFalse("R2 should be in overflow", r2Data.items.isEmpty())
    }

    @Test
    fun `test unpinning moves items back to cache`() = runBlocking {
        val r1 = RegionId(0, 0)
        val r2 = RegionId(0, 1) // Filler
        
        // 1. Add R1 (Pinned)
        regionManager.setPinnedRegions(setOf(r1))
        regionManager.addItem(createHeavyRegion(r1).items[0])
        
        // 2. Add R2 -> Evicts R1 to Overflow
        regionManager.addItem(createHeavyRegion(r2).items[0])
        
        // Verify R1 is in overflow (delete disk to be sure)
        storage.deleteRegion(r1)
        val r1Check = regionManager.getRegion(r1)
        assertFalse(r1Check.items.isEmpty())
        
        // 3. Unpin R1
        regionManager.setPinnedRegions(emptySet())
        
        // This should move R1 from Overflow to Cache.
        // Since Cache is full (R2), it might evict R2 (or R1 if R1 is older? R1 was just accessed? No, R1 was in overflow).
        // setPinnedRegions puts it back.
        
        // R1 should be in cache now.
        // Let's try to access it.
        val r1Check2 = regionManager.getRegion(r1)
        assertFalse(r1Check2.items.isEmpty())
    }
}
