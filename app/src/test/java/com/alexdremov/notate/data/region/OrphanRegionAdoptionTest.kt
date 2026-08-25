package com.alexdremov.notate.data.region

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Crash-divergence durability: region bytes can persist WITHOUT a matching
 * index entry (index save skipped when indexVersion moved mid-flush, or the
 * process died after the last region write). A non-empty index is
 * authoritative, so orphans were invisible until an unrelated mutation
 * re-indexed their region — "strokes vanished after crash, resurrected when
 * I drew there".
 *
 * Contract now: orphan adoption at startup makes such content discoverable
 * automatically, without any user interaction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrphanRegionAdoptionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(
        order: Long,
        x: Float,
        y: Float,
    ): Stroke {
        val pts =
            listOf(
                TouchPoint(x, y, 0.5f, 3f, 0L),
                TouchPoint(x + 40f, y + 30f, 0.5f, 3f, 1L),
            )
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x + 40f, y + 30f)
        return Stroke(
            p,
            pts,
            0xFF101010.toInt(),
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(x, y, x + 40f, y + 30f),
            strokeOrder = order,
        )
    }

    private fun newManager(dir: File): RegionManager = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)

    /**
     * Simulates the post-crash state: regions persisted, but the persisted
     * index covers only PART of them (the last index write predates the last
     * region writes).
     */
    private fun divergeIndexFromContent(
        dir: File,
        rm: RegionManager,
        keepIds: Set<RegionId>,
    ) {
        rm.saveAll() // ensure all content is on disk
        val storage = RegionStorage(dir).apply { init() }
        assertTrue("test setup: nothing persisted", storage.listStoredRegions().isNotEmpty())
        assertTrue("test setup: divergence must drop something", storage.listStoredRegions().size > keepIds.size)
        val partial =
            keepIds.associateWith { id ->
                val region = requireNotNull(storage.loadRegion(id))
                region.rebuildQuadtree(1000f)
                RectF(region.contentBounds)
            }
        storage.saveIndex(partial)
    }

    @Test
    fun `content missing from persisted index becomes discoverable without user interaction`() {
        val dir = tmp.newFolder()
        val writer = newManager(dir)
        runBlocking {
            // Two far-apart areas → distinct regions.
            writer.addItem(stroke(1L, 500f, 500f))
            writer.addItem(stroke(2L, 500f, 1500f))
            writer.addItem(stroke(3L, 500f, 2500f))
            writer.saveAll()
        }
        val storage = RegionStorage(dir).apply { init() }
        val storedIds = storage.listStoredRegions()
        assertEquals(3, storedIds.size)

        // Crash divergence: index remembers only ONE of the three regions.
        divergeIndexFromContent(dir, writer, keepIds = setOf(storedIds.first()))
        writer.clear()

        // Reopen like the app does after a crash.
        val reopened = newManager(dir)
        try {
            val visible =
                runBlocking {
                    withTimeout(10_000) {
                        var found: List<com.alexdremov.notate.model.CanvasItem> = emptyList()
                        while (found.size < 3) {
                            delay(100) // adoption runs in background (250ms delay + loads)
                            found = reopened.queryItems(RectF(0f, 0f, 2000f, 4000f))
                        }
                        found
                    }
                }
            val orders = visible.mapNotNull { (it as? Stroke)?.strokeOrder }.toSet()
            assertEquals("orphaned strokes must become visible without any user edit", setOf(1L, 2L, 3L), orders)

            // And the healed index is discoverable by a THIRD session too.
            reopened.saveAll()
            reopened.clear()
            val third = newManager(dir)
            val rechecked =
                runBlocking {
                    third.queryItems(RectF(0f, 0f, 2000f, 4000f))
                }
            assertEquals(3, rechecked.size)
            third.clear()
        } finally {
            reopened.clear()
        }
    }

    @Test
    fun `adoption does not duplicate items when user draws into an adopted region`() {
        val dir = tmp.newFolder()
        val writer = newManager(dir)
        runBlocking {
            writer.addItem(stroke(1L, 500f, 500f))
            writer.addItem(stroke(2L, 520f, 1540f))
            writer.saveAll()
        }
        val storage = RegionStorage(dir).apply { init() }
        val storedIds = storage.listStoredRegions()
        divergeIndexFromContent(dir, writer, keepIds = setOf(storedIds.last()))
        writer.clear()

        val reopened = newManager(dir)
        try {
            // Wait for adoption to settle.
            runBlocking {
                withTimeout(10_000) {
                    while (reopened.queryItems(RectF(0f, 0f, 2000f, 3000f)).size < 2) delay(50)
                }
            }
            // User adds a stroke to the same area.
            runBlocking { reopened.addItem(stroke(99L, 600f, 600f)) }
            val items =
                runBlocking {
                    reopened.queryItems(RectF(0f, 0f, 2000f, 3000f))
                }
            assertEquals(3, items.size)
        } finally {
            reopened.clear()
        }
    }
}
