package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.StrokeType.FOUNTAIN
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Time-to-first-render contract of the OPEN path.
 *
 * `nextStrokeOrder` from manifest.bin is AUTHORITATIVE: opening a modern
 * container must not deserialize region content to resume order assignment.
 * The previous unconditional maxItemOrder() scan loaded every region serially
 * on the critical path before the first frame could paint. Legacy containers
 * (field absent → 0) keep the eager scan exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenTimeToFirstRenderTest {
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
        return Stroke(p, pts, 0xFF101010.toInt(), width = 2f, style = FOUNTAIN, bounds = RectF(x, y, x + 40f, y + 30f), strokeOrder = order)
    }

    /** Builds `regions`×`per` strokes spread across the grid, flushes to disk. */
    private fun buildDocument(
        dir: java.io.File,
        regions: Int,
        per: Int,
    ): Long {
        val rm = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        var order = 100L
        runBlocking {
            for (r in 0 until regions) {
                for (j in 0 until per) {
                    rm.addItem(stroke(order++, r * 1000f + 50f + j * 10f, (r % 7) * 1000f + 50f))
                }
            }
        }
        rm.saveAll()
        rm.clear() // release memory; disk intact — next manager starts cold
        return order // next free order (100..219 assigned for regions=24,per=5 etc.)
    }

    private fun freshManager(dir: java.io.File): RegionManager = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)

    @Test
    fun `authoritative metadata opens without deserializing any region`() {
        val dir = tmp.newFolder()
        val nextFree = buildDocument(dir, regions = 24, per = 25)

        val fresh = freshManager(dir)
        val loaded =
            java.util.concurrent.atomic
                .AtomicInteger()
        fresh.onRegionLoaded = { loaded.incrementAndGet() }

        val model = InfiniteCanvasModel()
        val persisted = CanvasData(nextStrokeOrder = nextFree)
        runBlocking {
            // App flow: initializeSession(metadata.nextStrokeOrder) then loadMetadata.
            model.initializeSession(fresh, persisted.nextStrokeOrder)
            model.loadFromCanvasData(persisted)
        }

        assertEquals(
            "metadata was authoritative yet the open path loaded ${loaded.get()} regions",
            0,
            loaded.get(),
        )

        // First new stroke continues the persisted sequence — no collision.
        val added = runBlocking { model.addStroke(stroke(0L, 30000f, 30000f)) }!!
        assertEquals(nextFree, added.strokeOrder)
        fresh.clear()
    }

    @Test
    fun `legacy container without field keeps eager scan and lands above persisted orders`() {
        val dir = tmp.newFolder()
        buildDocument(dir, regions = 6, per = 20) // orders 100..219

        val fresh = freshManager(dir)
        val model = InfiniteCanvasModel()
        runBlocking { model.initializeSession(fresh) }

        val added = runBlocking { model.addStroke(stroke(0L, 8000f, 8000f)) }!!
        assertEquals(220L, added.strokeOrder)

        // A legacy (0) metadata payload must NOT clobber the scanned seed.
        runBlocking { model.loadFromCanvasData(CanvasData(nextStrokeOrder = 0)) }
        val second = runBlocking { model.addStroke(stroke(0L, 8100f, 8100f)) }!!
        assertEquals(221L, second.strokeOrder)
        fresh.clear()
    }
}
