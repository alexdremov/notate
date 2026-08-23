package com.alexdremov.notate.data.region

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hard concurrency tests for the region cache under REAL eviction pressure.
 *
 * These tests exercise the RegionManager's core promise: a canvas whose working
 * set is LARGER than the region memory budget must behave correctly — queries
 * return every item, hit tests find every stroke, and reader-retained regions
 * are never destructively recycled while in use.
 *
 * The regression scenario: naive "prime all regions, then read from cache"
 * query implementations silently drop regions that were evicted (and recycled)
 * by later loads. See [queryItems returns ALL items when working set exceeds memory budget].
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RegionEvictionStressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newStorage(): RegionStorage = RegionStorage(tmp.newFolder()).apply { init() }

    /**
     * A "complicated" stroke: a zigzag with [pointCount] points spanning
     * [width] x [height] world units — enough points to make the serialized
     * region size meaningful for eviction pressure.
     */
    private fun complicatedStroke(
        centerX: Float,
        centerY: Float,
        width: Float = 800f,
        height: Float = 800f,
        pointCount: Int = 300,
        color: Int = 0xFF000000.toInt(),
        order: Long = 0,
    ): Stroke {
        val points = ArrayList<TouchPoint>(pointCount)
        val path = Path()
        for (i in 0 until pointCount) {
            val t = i.toFloat() / (pointCount - 1)
            val x = centerX - width / 2 + width * t
            val y = centerY + (if (i % 2 == 0) -height / 2 else height / 2) * (0.5f + 0.5f * t)
            points.add(TouchPoint(x, y, 0.5f, 5f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val bounds = RectF(
            centerX - width / 2,
            centerY - height / 2,
            centerX + width / 2,
            centerY + height / 2,
        )
        return Stroke(path, points, color, width = 2f, style = StrokeType.FOUNTAIN, bounds = bounds, strokeOrder = order)
    }

    // ------------------------------------------------------------------
    // 1. THE regression: queries under eviction pressure
    // ------------------------------------------------------------------

    @Test
    fun `queryItems returns ALL items when working set exceeds memory budget`() = runBlocking {
        val storage = newStorage()
        // 8 KB budget: with ~300-point strokes each region far exceeds it, so
        // at most ONE region is resident at any time.
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 8 * 1024)

        val cols = 6
        val rows = 4
        val expected = cols * rows
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val stroke = complicatedStroke(
                    centerX = col * 1000f + 500f,
                    centerY = row * 1000f + 500f,
                    order = (row * cols + col).toLong(),
                )
                rm.addItem(stroke)
            }
        }

        // The query rect covers ALL regions. Loading region k+1 must evict
        // (demote) region k — but never lose its items from the result.
        val all = rm.queryItems(RectF(0f, 0f, cols * 1000f, rows * 1000f))
        assertEquals(
            "queryItems must see every item even when the working set far exceeds the memory budget",
            expected,
            all.distinctBy { it.order }.size,
        )

        // hitTest must likewise find strokes in long-evicted regions.
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val hit = rm.hitTest(col * 1000f + 500f, row * 1000f + 500f, tolerance = 100f)
                assertNotNull("hitTest missed stroke at region ($col,$row) — silent eviction loss", hit)
            }
        }
        Unit
    }

    // ------------------------------------------------------------------
    // 2. Reader references survive eviction (refcounted demotion)
    // ------------------------------------------------------------------

    @Test
    fun `acquired region survives eviction and recycle until released`() = runBlocking {
        val storage = newStorage()
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 8 * 1024)

        rm.addItem(complicatedStroke(500f, 500f, order = 0))
        for (i in 1 until 12) {
            rm.addItem(complicatedStroke(i * 1000f + 500f, 500f, order = i.toLong()))
        }

        val acquired = rm.acquireRegion(RegionId(0, 0))
        assertNotNull(acquired)
        val retained = acquired!!
        val itemsBefore = retained.items.size
        assertTrue(itemsBefore > 0)

        // Hammer the cache with loads that must evict (demote) our region.
        repeat(3) {
            rm.queryItems(RectF(0f, 0f, 12000f, 2000f))
        }

        // The retained reference must be fully intact — this is the core
        // safety property. Before refcounting, eviction recycled the region
        // out from under readers (items cleared, quadtree nulled).
        assertTrue("region was destructively recycled while retained", !retained.isRecycled)
        assertEquals(itemsBefore, retained.items.size)
        assertNotNull("quadtree was nulled while retained", retained.quadtree)
        val stillQueryable = ArrayList<com.alexdremov.notate.model.CanvasItem>()
        retained.quadtree!!.retrieve(stillQueryable, RectF(0f, 0f, 1000f, 1000f))
        assertTrue("quadtree unusable while retained", stillQueryable.isNotEmpty())

        println("PRE-RELEASE evicted=" + retained.isEvicted + " dirty=" + retained.isDirty)
        rm.releaseRegion(retained)
        println("POST-RELEASE")

        // NON-DESTRUCTIVE EVICTION CONTRACT (RegionStore redesign): release
        // never destroys. The instance stays frozen-but-valid forever; memory
        // is reclaimed by GC once references drop. Assert the region remains
        // fully readable after eviction + release — the stronger guarantee.
        assertEquals(itemsBefore, retained.items.size)
        assertTrue("region unusable after release", retained.items.isNotEmpty())
        Unit
    }

    // ------------------------------------------------------------------
    // 3. Concurrent complicated commits — no lost strokes, valid index
    // ------------------------------------------------------------------

    @Test
    fun `concurrent complicated commits never lose strokes`() = runBlocking {
        val storage = newStorage()
        val rm = RegionManager(storage, regionSize = 1000f)
        val model = InfiniteCanvasModel()
        model.initializeSession(rm)

        val writers = 8
        val strokesPerWriter = 60
        val expected = writers * strokesPerWriter

        val jobs = (0 until writers).map { w ->
            async(Dispatchers.IO) {
                for (i in 0 until strokesPerWriter) {
                    // Long diagonals crossing 2-3 regions: exercises multi-region
                    // indexing under concurrent mutation.
                    val x0 = (w * 137 + i * 31) % 5000f
                    val y0 = (w * 251 + i * 17) % 4000f
                    val stroke = complicatedStroke(
                        centerX = x0 + 400f,
                        centerY = y0 + 400f,
                        width = 1600f,
                        height = 1200f,
                        pointCount = 80,
                        color = 0xFF000000.toInt() or (w * 30),
                    )
                    val added = model.addItem(stroke)
                    check(added != null) { "addItem returned null for stroke w=$w i=$i" }
                }
            }
        }
        jobs.awaitAll()

        val all = model.queryItems(RectF(-2000f, -2000f, 12000f, 12000f))
        val distinct = all.distinctBy { it.order }
        assertEquals("lost strokes under concurrent commit", expected, distinct.size)

        // Every stroke must be reachable through the spatial index (quadtree),
        // not just present in some region's flat item list.
        val quadtreeReachable = HashSet<Long>()
        for (item in distinct) {
            val hits = model.queryItems(RectF(item.bounds).apply { inset(-1f, -1f) })
            if (hits.any { it.order == item.order }) quadtreeReachable.add(item.order)
        }
        assertEquals("spatial index lost items under concurrent commit", expected, quadtreeReachable.size)
        Unit
    }

    // ------------------------------------------------------------------
    // 4. Lock-free saveAll: concurrent mutation during flush is not lost
    // ------------------------------------------------------------------

    @Test
    fun `saveAll under concurrent mutation persists every stroke`() = runBlocking {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val rm = RegionManager(storage, regionSize = 1000f)
        val model = InfiniteCanvasModel()
        model.initializeSession(rm)

        // Seed data.
        repeat(30) { i ->
            model.addItem(complicatedStroke(i * 300f + 500f, 500f, width = 600f, height = 400f, pointCount = 60))
        }

        // Writer keeps mutating WHILE saveAll cycles run — this is the race the
        // modCount protocol protects: a region serialized, then mutated, must
        // NOT have its dirty flag cleared by the in-flight save.
        val done = AtomicBoolean(false)
        val totalWritten = java.util.concurrent.atomic.AtomicInteger(0)
        val writer = launch(Dispatchers.IO) {
            var i = 0
            while (!done.get() && i < 150) {
                model.addItem(complicatedStroke((i % 40) * 300f + 500f, 2000f, width = 500f, height = 300f, pointCount = 40))
                totalWritten.incrementAndGet()
                i++
                delay(2)
            }
        }

        repeat(12) { rm.saveAll() }
        done.set(true)
        writer.join()
        rm.saveAll() // final flush

        // Reload from disk through a completely fresh stack and verify.
        val storage2 = RegionStorage(dir).apply { init() }
        val rm2 = RegionManager(storage2, regionSize = 1000f)
        val expected = 30 + totalWritten.get()
        val reloaded = rm2.queryItems(RectF(-1000f, -1000f, 15000f, 5000f))
        assertEquals(
            "saveAll under concurrent mutation lost strokes on disk",
            expected,
            reloaded.distinctBy { it.order }.size,
        )
        Unit
    }

    @After
    fun cleanUp() {
        // TemporaryFolder cleans itself; nothing to do explicitly.
        File(System.getProperty("java.io.tmpdir"), "unused").let { }
    }
}
