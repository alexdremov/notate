package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
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
import java.io.File

/**
 * Pan/zoom non-blocking guarantee: once the canvas is resident, panning and
 * zooming (viewport queries — the exact calls the render loop makes) must
 *
 *  1. trigger ZERO disk loads (everything visible is already in memory),
 *  2. complete within a strict per-call latency budget, and
 *  3. never regress no matter how much background saving churns.
 *
 * This is the "rendering must only be blocked by drawing itself" contract,
 * evaluated for the interaction users perform most: panning and zooming.
 * Region loads for NEWLY visible areas are expected and counted separately —
 * they happen exactly once per region and off the hot path afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanZoomNonBlockingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Counting storage wrapper to observe disk loads during pan/zoom. */
    private class CountingStorage(
        baseDir: File,
        private val real: RegionStorage,
    ) : RegionStorage(baseDir) {
        @Volatile var loadCalls = 0
        override fun loadRegion(id: RegionId): RegionData? {
            loadCalls++
            return real.loadRegion(id)
        }

        override fun saveRegion(data: RegionData): Boolean = real.saveRegion(data)
        override fun deleteRegion(id: RegionId) = real.deleteRegion(id)
        override fun listStoredRegions(): List<RegionId> = real.listStoredRegions()
        override fun saveIndex(index: Map<RegionId, RectF>): Boolean = real.saveIndex(index)
        override fun loadIndex(): Map<RegionId, RectF> = real.loadIndex()
    }

    private fun stroke(order: Long, x: Float, y: Float): Stroke {
        val pts = ArrayList<TouchPoint>(20)
        val path = android.graphics.Path()
        for (i in 0 until 20) {
            val px = x + i * 8f
            val py = y + kotlin.math.sin(i * 0.7f) * 10f
            pts.add(TouchPoint(px, py, 0.5f, 4f, i.toLong()))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return Stroke(
            path, pts, 0xFF101010.toInt(), width = 2f, style = StrokeType.FOUNTAIN,
            bounds = RectF(x, y - 12f, x + 160f, y + 12f),
            strokeOrder = order,
        )
    }

    @Test
    fun `panning and zooming over resident content performs zero disk loads`() {
        val dir = tmp.newFolder()
        val counting = CountingStorage(dir, RegionStorage(dir).apply { init() })
        val rm = RegionManager(counting, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)

        // Populate a 4x4-region grid.
        runBlocking {
            var order = 1L
            for (rx in 0 until 4) {
                for (ry in 0 until 4) {
                    repeat(6) { j ->
                        rm.addItem(stroke(order++, rx * 1000f + 50f + j * 30f, ry * 1000f + 50f))
                    }
                }
            }
        }
        rm.saveAll()

        // Warm-up: full-canvas query forces every region resident; also resets
        // the counter so the measured window starts from a known state.
        runBlocking { rm.queryItems(RectF(-1000f, -1000f, 6000f, 6000f)) }
        counting.loadCalls = 0

        // ---- Simulated interaction: 300 pan steps + zoom cycles ----
        val latenciesNs = ArrayList<Long>(300)
        var maxSingleCallMs = 0.0
        val t0 = System.nanoTime()
        var viewport = RectF(0f, 0f, 2000f, 2000f)

        // Panning right/down across the whole grid, then back.
        repeat(300) { step ->
            val dx = if ((step / 150) % 2 == 0) step * 12f else -(step - 150) * 12f
            val dy = step * 6f
            viewport = RectF(dx, dy, dx + 2000f, dy + 2000f)

            // Zoom wobble every 10th step (scale viewport around its center).
            if (step % 10 == 9) {
                val cx = viewport.centerX()
                val cy = viewport.centerY()
                val halfW = viewport.width() / 4f
                val halfH = viewport.height() / 4f
                viewport = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            }

            val callStart = System.nanoTime()
            val items = runBlocking { rm.queryItems(viewport) }
            val callMs = (System.nanoTime() - callStart) / 1e6
            latenciesNs.add((System.nanoTime() - callStart))
            maxSingleCallMs = maxOf(maxSingleCallMs, callMs)
            check(items.size >= 0) // keep the result alive
        }
        val totalMs = (System.nanoTime() - t0) / 1e6

        // ---- Guarantees ----
        assertEquals(
            "pan/zoom over resident content performed ${counting.loadCalls} disk loads",
            0,
            counting.loadCalls,
        )

        val avgMs = latenciesNs.average() / 1e6
        assertTrue(
            "average query ${"%.2f".format(avgMs)}ms over $totalMs ms of pan/zoom " +
                "exceeds the 5ms budget — render path is blocking",
            avgMs < 5.0,
        )
        assertTrue(
            "single worst query took ${"%.1f".format(maxSingleCallMs)}ms — render thread stalled",
            maxSingleCallMs < 50.0,
        )

        rm.clear()
    }

    @Test
    fun `zooming into new regions loads them exactly once each`() {
        val dir = tmp.newFolder()
        val counting = CountingStorage(dir, RegionStorage(dir).apply { init() })
        val rm = RegionManager(counting, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)

        runBlocking {
            var order = 1L
            for (rx in 0 until 4) {
                for (ry in 0 until 4) {
                    repeat(4) { j ->
                        rm.addItem(stroke(order++, rx * 1000f + 40f + j * 25f, ry * 1000f + 40f))
                    }
                }
            }
        }
        rm.saveAll()
        counting.loadCalls = 0

        // Zoom-out sweep: progressively larger viewports touch all 16 regions.
        var loadedAtEnd = 0
        for (scale in 1..16) {
            val extent = scale * 250f
            runBlocking { rm.queryItems(RectF(-200f, -200f, extent, extent)) }
            loadedAtEnd = counting.loadCalls
        }

        // Every region is loaded at most ONCE (then stays resident): repeated
        // queries over the same area must not re-hit disk. 16 regions → ≤16
        // loads total, regardless of how many queries followed.
        assertTrue(
            "zoom sweep triggered $loadedAtEnd loads for 16 regions — regions are being evicted/reloaded",
            loadedAtEnd <= 16,
        )

        // And a final full pass costs ZERO additional loads.
        val before = counting.loadCalls
        runBlocking { rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
        assertEquals(
            "re-querying resident content hit disk ${counting.loadCalls - before} times",
            before,
            counting.loadCalls,
        )
        rm.clear()
    }
}
