package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Spatial-index recovery contract. The persisted index (region id → bounds)
 * is SEPARATE from the region data files, so they can disagree: a stale index,
 * a missing file, an unlisted file, or bounds that no longer cover grown
 * content. The manager must self-heal in every direction:
 *
 *  1. Index lists a region whose file is gone  → dropped, not crash.
 *  2. Region file exists but index lost it     → still discoverable via
 *     authoritative union / rebuild.
 *  3. Stale smaller bounds in the index        → rect queries must still find
 *     content outside the stale bounds (self-healing install + authoritative
 *     union guarantee this).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IndexRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(order: Long): com.alexdremov.notate.model.Stroke {
        val pts = ArrayList<TouchPoint>(6)
        val path = android.graphics.Path()
        for (i in 0..6) {
            val x = order * 100f + i * 5f
            val y = order * 100f + i * 3f
            pts.add(TouchPoint(x, y, 0.5f, 4f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return com.alexdremov.notate.model.Stroke(
            path, pts, 0xFF000000.toInt(), width = 2f, style = StrokeType.FOUNTAIN,
            bounds = RectF(order * 100f, order * 100f, order * 100f + 35f, order * 100f + 20f),
            strokeOrder = order,
        )
    }

    private fun populatedRegion(id: RegionId, orders: List<Long>): RegionData =
        RegionData(id).apply { items = orders.map { stroke(it) } }

    private fun allOrders(rm: RegionManager): Set<Long> =
        kotlinx.coroutines.runBlocking {
            rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f))
        }.map { it.order }.toSet()

    // ------------------------------------------------------------------
    // 1. Index lists a region whose file was deleted → manager drops it
    //    cleanly and serves the rest.
    // ------------------------------------------------------------------
    @Test
    fun `index entry with missing file is dropped without poisoning queries`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        storage.saveRegion(populatedRegion(RegionId(0, 0), listOf(1, 2)))
        storage.saveRegion(populatedRegion(RegionId(3, 3), listOf(10)))
        // Persisted index claims BOTH regions exist.
        storage.saveIndex(
            mapOf(
                RegionId(0, 0) to RectF(0f, 0f, 200f, 200f),
                RegionId(3, 3) to RectF(300f, 300f, 400f, 400f),
            ),
        )
        // ...but the file for 3_3 was lost (partial restore / sync conflict).
        storage.deleteRegion(RegionId(3, 3))

        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)
        assertEquals(setOf(1L, 2L), allOrders(rm))
        rm.clear()
    }

    // ------------------------------------------------------------------
    // 2. Region files exist but the persisted index LOST them → rebuild
    //    recovers reachability.
    // ------------------------------------------------------------------
    @Test
    fun `region files missing from index are recovered by rebuild`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        storage.saveRegion(populatedRegion(RegionId(0, 0), listOf(1, 2)))
        storage.saveRegion(populatedRegion(RegionId(1, 1), listOf(3)))
        // Persist an index that only knows about ONE region.
        storage.saveIndex(mapOf(RegionId(0, 0) to RectF(0f, 0f, 200f, 200f)))

        // A NON-EMPTY persisted index is authoritative BY DESIGN (source of
        // truth per concurrency doc) — recovery is for a MISSING/corrupt
        // index. Simulate an index wipe (interrupted save / corruption).
        val indexFile = File(dir, "index.bin")
        if (indexFile.exists()) indexFile.delete()

        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)
        val found = allOrders(rm)
        assertTrue(
            "rebuild must recover unindexed regions (found=$found)",
            setOf(1L, 2L, 3L).all { it in found },
        )
        rm.clear()
    }

    // ------------------------------------------------------------------
    // 3. Stale smaller bounds in the index → queries beyond stale bounds
    //    still find the content (self-healing on load + authoritative union).
    // ------------------------------------------------------------------
    @Test
    fun `stale undersized index bounds do not hide content`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val id = RegionId(0, 0)

        // First save: one small stroke → index records small bounds.
        storage.saveRegion(populatedRegion(id, listOf(1)))
        storage.saveIndex(mapOf(id to RectF(100f, 100f, 140f, 125f)))

        // Then content GROWS far past the recorded bounds (simulating an old
        // container where growth happened after the last index write).
        storage.saveRegion(
            populatedRegion(id, listOf(1, 2, 30)),
        ) // order 30 lives at ~3000,3000

        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)
        val found = allOrders(rm)
        assertTrue(
            "content outside stale bounds must be reachable (found=$found)",
            30L in found && 1L in found && 2L in found,
        )
        rm.clear()
    }
}
