package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.ref.WeakReference

/**
 * Memory-hygiene contract: after a session is CLOSED (saveAll + clear) and
 * the caller drops its references, every [RegionData] instance that belonged
 * to that session must be garbage-collectable.
 *
 * Non-destructive eviction means dropped-from-map instances are reclaimed by
 * GC — which makes leaks SILENT (no husk to trip over). These tests make them
 * loud again: any stray reference (lineage registries, load jobs, caches,
 * forensics structures) holding a closed-session region shows up as an
 * uncollectible weak reference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryHygieneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class LeakProbe(
        val dir: File,
        val strokes: Int,
    ) {
        /** Snapshot of every live RegionData at close-time, weakly held. */
        val refs = ArrayList<WeakReference<RegionData>>()
        var regionCountAtClose = -1

        fun run() {
            val storage = RegionStorage(dir).apply { init() }
            val rm =
                RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 48 * 1024L)
            runBlocking {
                repeat(strokes) { i ->
                    val pts = ArrayList<TouchPoint>(20)
                    val path = android.graphics.Path()
                    for (p in 0 until 20) {
                        val x = (i % 5) * 900f + p * 10f
                        val y = (i / 5) * 900f + p * 4f
                        pts.add(TouchPoint(x, y, 0.5f, 4f, p.toLong()))
                        if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    rm.addItem(
                        com.alexdremov.notate.model.Stroke(
                            path,
                            pts,
                            0xFF000000.toInt(),
                            width = 2f,
                            style = StrokeType.FOUNTAIN,
                            bounds = RectF((i % 5) * 900f, (i / 5) * 900f, (i % 5) * 900f + 200f, (i / 5) * 900f + 80f),
                            strokeOrder = (i + 1).toLong(),
                        ),
                    )
                }
            }

            // Force every region through residency at least once so instances exist.
            runBlocking { rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }

            // Record ALL instances reachable from the manager's maps.
            collect(rm)

            rm.saveAll()
            rm.clear()
            regionCountAtClose = refs.size
        }

        private fun collect(rm: RegionManager) {
            fun add(region: RegionData?) {
                if (region != null) refs.add(WeakReference(region))
            }
            fun field(name: String): Any {
                val f = RegionManager::class.java.getDeclaredField(name)
                f.isAccessible = true
                return f.get(rm)
            }
            val rc = field("regionCache")
            val mmapField = rc.javaClass.getDeclaredField("map").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val cmap = mmapField.get(rc) as Map<RegionId, RegionData>
            cmap.forEach { (_, v) -> add(v) }

            @Suppress("UNCHECKED_CAST")
            val ovf = field("overflowRegions") as Map<RegionId, RegionData>
            ovf.values.forEach { add(it) }

            @Suppress("UNCHECKED_CAST")
            val limbo = field("limbo") as Map<RegionId, RegionData>
            limbo.values.forEach { add(it) }

            @Suppress("UNCHECKED_CAST")
            val lineage = field("liveLineage") as Map<RegionId, RegionData>
            lineage.values.forEach { add(it) }
        }
    }

    private fun forceGc() {
        repeat(5) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
    }

    @Test
    fun `closed sessions release all region instances`() {
        val probes = ArrayList<LeakProbe>()
        for (cycle in 1..3) {
            val probe = LeakProbe(tmp.newFolder(), strokes = 40)
            probe.run()
            probes.add(probe)
            assertTrue(
                "cycle $cycle recorded no instances — collection broken",
                probe.regionCountAtClose > 0,
            )
        }

        // Sessions are fully out of scope here. GC must reclaim everything.
        forceGc()
        val leaked = probes.flatMap { p ->
            p.refs.withIndex().filter { it.value.get() != null }.map { "${p.hashCode()}#${it.index}" }
        }
        assertTrue(
            "closed sessions still hold ${leaked.size} region instances: $leaked",
            leaked.isEmpty(),
        )
    }

    @Test
    fun `repeated reopen cycles do not accumulate instances`() {
        val dir = tmp.newFolder()
        var lastCount = -1
        val totals = ArrayList<Int>()
        for (cycle in 1..6) {
            val probe = LeakProbe(dir, strokes = 25) // SAME document reopened
            probe.run()
            probes_all.addAll(probe.refs)
            totals.add(probe.regionCountAtClose)
            lastCount = probe.regionCountAtClose
        }
        forceGc()
        val leaked = probes_all.count { it.get() != null }
        assertTrue(
            "repeated reopen cycles accumulated $leaked uncollectible instances " +
                "(per-cycle counts=$totals)",
            leaked == 0,
        )
    }

    private val probes_all = ArrayList<WeakReference<RegionData>>()
}
