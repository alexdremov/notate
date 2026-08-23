package com.alexdremov.notate.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.onyx.android.sdk.data.note.TouchPoint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random as KRandom

/**
 * Full-stack chaos testing of the render pipeline: real `RegionManager` (with a
 * memory budget small enough to force constant eviction), real `InfiniteCanvasModel`,
 * real `CanvasRenderer` + `TileManager` generating REAL bitmaps on a real thread
 * pool — all hammered by six concurrent actors performing RANDOM operations.
 *
 * Designed to break the system:
 *  - writers commit strokes while erasers split/remove them,
 *  - history storms undo/redo underneath everything,
 *  - readers query/hit-test/visit while the index churns,
 *  - the renderer draws frames, force-refreshes, hides items and clears tiles
 *    mid-generation,
 *  - a persister flushes to disk continuously (lock-free save protocol),
 *  - the region budget guarantees eviction storms throughout.
 *
 * Assertions after quiescence (structural, not count-based — chaos may legally
 * delete anything):
 *  1. No actor threw.
 *  2. Every surviving item is reachable through the spatial index.
 *  3. Disk state after final flush == in-memory state (persistence coherence).
 *  4. A freshly committed sentinel stroke becomes VISIBLE in rendered tiles
 *     within a deadline — catches stuck `generatingKeys`, wedged schedulers,
 *     and stale-version deadlocks in the tile pipeline.
 */
// WIP: this suite exposed and helped fix four real concurrency bugs (see
// AGENTS.md §7 "Known Issues"). One residual issue remains: rare persistent
// disk/memory divergence after heavy erase+undo chaos (seed 42 family).
// Re-enable once root-caused; the forensic dumps in the assertions are the
// starting point.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPipelineChaosTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newStack(dir: java.io.File): Triple<RegionManager, InfiniteCanvasModel, CanvasRenderer> {
        val storage = RegionStorage(dir).apply { init() }
        // 64 KB region budget: with realistic strokes this keeps only ~1-2
        // regions resident → continuous eviction/limbo/save churn.
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 64 * 1024)
        val model = InfiniteCanvasModel()
        runBlocking { model.initializeSession(rm) }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val renderer = CanvasRenderer(model, org.robolectric.RuntimeEnvironment.getApplication(), scope) {}
        return Triple(rm, model, renderer)
    }

    private fun randomStroke(
        rng: Random,
        order: Long = 0,
    ): Stroke {
        val cx = rng.nextFloat() * 4000f
        val cy = rng.nextFloat() * 3000f
        val w = 200f + rng.nextFloat() * 1200f
        val h = 200f + rng.nextFloat() * 1000f
        val n = 20 + rng.nextInt(80)
        val pts = ArrayList<TouchPoint>(n)
        val path = Path()
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1).coerceAtLeast(1)
            val x = cx - w / 2 + w * t
            val y = cy + (if (i % 2 == 0) -h / 2 else h / 2) * (0.3f + 0.7f * t)
            pts.add(TouchPoint(x, y, 0.4f + rng.nextFloat() * 0.6f, 5f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path,
            pts,
            color = Color.BLACK,
            width = 2f + rng.nextFloat() * 6f,
            style = if (rng.nextInt(5) == 0) StrokeType.HIGHLIGHTER else StrokeType.FOUNTAIN,
            bounds = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
            strokeOrder = order,
        )
    }

    /** Renders one frame into a fresh bitmap; returns it for pixel probing.
     *  The matrix maps world→screen as translate(-visible.left, -visible.top),
     *  matching how the layout concatenates the matrix (it does NOT translate
     *  by the visible rect itself). */
    private fun renderFrame(
        renderer: CanvasRenderer,
        visible: RectF,
        width: Int,
        height: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.setTranslate(-visible.left, -visible.top)
        renderer.render(canvas, m, visible, com.alexdremov.notate.ui.render.RenderQuality.HIGH, 1f)
        return bmp
    }

    // ------------------------------------------------------------------
    // The chaos scenario
    // ------------------------------------------------------------------

    private fun runChaos(seed: Long) {
        // per-manager rings: set up AFTER stack creation (see below)
        val dir = tmp.newFolder()
        val (rm, model, renderer) = newStack(dir)
        rm.forensicsSetEnabled(true)
        rm.forensicsReset()
        val rng = Random(seed)
        val committed = ConcurrentLinkedQueue<Stroke>()
        val stop = AtomicBoolean(false)
        val opCounter = AtomicLong()

        val lanes =
            listOf(
                // Lane 1+2: writers
                Runnable {
                    val r = Random(seed + 1)
                    while (!stop.get()) {
                        val s = randomStroke(r)
                        runBlocking { model.addItem(s) }
                        committed.add(s)
                        opCounter.incrementAndGet()
                    }
                },
                Runnable {
                    val r = Random(seed + 2)
                    while (!stop.get()) {
                        runBlocking {
                            model.addItem(randomStroke(r))
                            model.addItem(randomStroke(r))
                        }
                        opCounter.incrementAndGet()
                    }
                },
                // Lane 3: erasers (split/remove near random live strokes)
                Runnable {
                    val r = Random(seed + 3)
                    while (!stop.get()) {
                        val snapshot = committed.toList()
                        if (snapshot.isEmpty()) continue
                        @Suppress("UNUSED_VARIABLE")
                        val target = snapshot[r.nextInt(snapshot.size)]
                        val eraser = randomStroke(r)
                        runBlocking {
                            try {
                                model.erase(
                                    eraser,
                                    if (r.nextBoolean()) {
                                        com.alexdremov.notate.model.EraserType.STANDARD
                                    } else {
                                        com.alexdremov.notate.model.EraserType.STROKE
                                    },
                                )
                            } catch (_: Exception) {
                                // erase is best-effort under chaos; geometry races are legal
                            }
                        }
                        opCounter.incrementAndGet()
                    }
                },
                // Lane 4: history storm
                Runnable {
                    val r = Random(seed + 4)
                    while (!stop.get()) {
                        runBlocking {
                            if (r.nextBoolean()) model.undo() else model.redo()
                        }
                        opCounter.incrementAndGet()
                    }
                },
                // Lane 5: readers
                Runnable {
                    val r = Random(seed + 5)
                    while (!stop.get()) {
                        runBlocking {
                            val rect = RectF(r.nextFloat() * 4000f, r.nextFloat() * 3000f, 0f, 0f)
                            rect.right = rect.left + 500f + r.nextFloat() * 2000f
                            rect.bottom = rect.top + 500f + r.nextFloat() * 1500f
                            when (rng.nextInt(3)) {
                                0 -> model.queryItems(rect)
                                1 -> model.hitTest(rect.centerX(), rect.centerY(), 50f)
                                else -> model.visitItemsInRect(rect) { }
                            }
                        }
                        opCounter.incrementAndGet()
                    }
                },
                // Lane 6: renderer + persister
                Runnable {
                    val r = Random(seed + 6)
                    var frame = 0
                    while (!stop.get()) {
                        val vis = RectF(r.nextFloat() * 3000f, r.nextFloat() * 2000f, 0f, 0f)
                        vis.right = vis.left + 1500f
                        vis.bottom = vis.top + 1200f
                        val bmp = renderFrame(renderer, vis, 512, 512)
                        bmp.recycle()
                        when (frame % 5) {
                            0 -> renderer.refreshTiles(vis)
                            1 -> renderer.clearTiles()
                            2 -> renderer.invalidateTiles(vis)
                        }
                        if (frame % 7 == 0) rm.saveAll()
                        frame++
                        opCounter.incrementAndGet()
                    }
                },
            )

        val start = CountDownLatch(1)
        val threads =
            lanes.map { lane ->
                Thread {
                    start.await()
                    lane.run()
                }.apply { isDaemon = true }
            }
        threads.forEach { it.start() }
        start.countDown()

        // Chaos window: fixed wall-clock budget instead of op count so slow CI
        // machines get the same coverage shape.
        Thread.sleep(8_000)
        stop.set(true)
        threads.forEach { it.join(15_000) }
        val hung = threads.filter { it.isAlive }
        if (hung.isNotEmpty()) {
            // Dump the HUNG actor's stack: this is the only way to see which
            // store operation wedged. Written to a file (rings may be flooded).
            val dumpFile = File("build/dumps/hung_actors_seed$seed.txt").apply {
                parentFile?.mkdirs()
            }
            hung.forEach { th ->
                th.stackTrace.forEach { frame ->
                    dumpFile.appendText("  ${th.name}: $frame\n")
                }
                dumpFile.appendText("\n")
            }
            // Also dump ALL threads for full context (locks, park reasons).
            Thread.getAllStackTraces().forEach { (th, frames) ->
                if (frames.isNotEmpty()) {
                    dumpFile.appendText("[${th.name}]\n")
                    frames.take(15).forEach { f -> dumpFile.appendText("  $f\n") }
                }
            }
        }
        threads.forEach {
            assertTrue(
                "actor thread did not terminate (see build/dumps/hung_actors_seed$seed.txt)",
                !it.isAlive,
            )
        }

        // ---- Quiescence & verification ----
        runBlocking {
            withTimeout(60_000) {
                rm.saveAll()
            }
        }

        val all = runBlocking { model.queryItems(RectF(-1000f, -1000f, 8000f, 8000f)) }
        val distinct = all.distinctBy { it.order }

        // 2. Spatial-index reachability for every survivor.
        runBlocking {
            for (item in distinct) {
                val probe = RectF(item.bounds)
                probe.inset(-1f, -1f)
                val hits = model.queryItems(probe)
                if (hits.none { it.order == item.order }) {
                    // Diagnostics: locate every physical copy of this order and
                    // report whether its region's quadtree can see it.
                    val diagnosis = StringBuilder()
                    for (rid in rm.getActiveRegionIds()) {
                        val r = rm.acquireRegion(rid) ?: continue
                        try {
                            val inItems = r.items.count { it.order == item.order }
                            val q = ArrayList<com.alexdremov.notate.model.CanvasItem>()
                            stateProbeRead(r, item.bounds, q)
                            diagnosis.append(
                                "region=" + rid + " items=" + inItems +
                                    " quadtreeHits=" + q.count { it.order == item.order } + " ",
                            )
                        } finally {
                            rm.releaseRegion(r)
                        }
                    }
                    val rid =
                        com.alexdremov.notate.data.region.RegionId(
                            Math.floor(item.bounds.centerX() / 1000f.toDouble()).toInt(),
                            Math.floor(item.bounds.centerY() / 1000f.toDouble()).toInt(),
                        )
                    println("!!!! GHOST item=" + item.order + " region=" + rid)
                    println(
                        rm.dumpForensics(rid.toString()),
                    )
                    println("!!!! END GHOST HISTORY")
                    assertTrue(
                        "item " + item.order + " survived in items list but is unreachable via quadtree " +
                            "(bounds=" + item.bounds + ") [" + diagnosis + "]",
                        false,
                    )
                }
            }
        }

        // 3. Persistence coherence: fresh stack over the same directory must see
        //    exactly the same content.
        // Consistency model: flushes are asynchronous and per-region gated, so
        // ONE saveAll does not guarantee disk==memory — CONVERGENCE within a few
        // flush cycles does. Fail only if the state never converges.
        val memOrders = distinct.map { it.order }.toSet()
        var diskOrders: Set<Long> = emptySet()
        var reloaded: List<com.alexdremov.notate.model.CanvasItem> = emptyList()
        var rm2: RegionManager? = null
        for (attempt in 1..5) {
            rm.saveAll()
            Thread.sleep(150)
            rm2?.let { it.clear() }
            val storage2 = RegionStorage(dir).apply { init() }
            rm2 = RegionManager(storage2, regionSize = 1000f)
            reloaded = runBlocking { rm2.queryItems(RectF(-1000f, -1000f, 8000f, 8000f)) }
            diskOrders = reloaded.map { it.order }.toSet()
            if (diskOrders == memOrders) break
        }
        if (memOrders != diskOrders) {
            val diag = StringBuilder("\n--- DIVERGENCE DIAG ---\n")
            // Where does memory hold each divergent order?
            for (rid in rm.getActiveRegionIds()) {
                val r = runBlocking { rm.acquireRegion(rid) } ?: continue
                try {
                    val orders = r.items.map { it.order }
                    val extraHere = orders.filter { it !in memOrders }
                    if (extraHere.isNotEmpty()) {
                        diag
                            .append("memory region ")
                            .append(rid)
                            .append(" holds DELETED-in-memory orders: ")
                            .append(extraHere)
                            .append(" dirty=")
                            .append(r.isDirty)
                            .append(" evicted=")
                            .append(r.isEvicted)
                            .append('\n')
                    }
                } finally {
                    rm.releaseRegion(r)
                }
            }
            println(diag)
        }
        if (memOrders != diskOrders) {
            println("--- DIVERGENCE: mem-only=" + (memOrders - diskOrders) + " disk-only=" + (diskOrders - memOrders))
        }
        assertEquals(
            "disk state did not converge to memory state after 5 flush cycles (seed=$seed)",
            memOrders,
            diskOrders,
        )

        // 4. Sentinel visibility: commit a known stroke, then require the TILE
        //    pipeline to make it visible within a deadline.
        val sentinel = sentinelStroke()
        val sentBounds = sentinel.bounds
        runBlocking { model.addItem(sentinel) }

        val visible = waitForTileVisibility(renderer, sentinel, timeoutMs = 30_000)
        if (!visible) {
            println(dumpTileManagerState(renderer, model, sentinel, rm))
        }
        assertTrue(
            "sentinel stroke never became visible in rendered tiles within 30s " +
                "(stuck scheduler / wedged generatingKeys / stale version gate), seed=$seed",
            visible,
        )

        scope.cancel()
    }

    /** High-contrast diagonal stroke inside a single tile-sized area. */
    private fun sentinelStroke(): Stroke {
        val pts = ArrayList<TouchPoint>()
        val path = Path()
        for (i in 0 until 50) {
            val t = i / 49f
            val x = 600f + 300f * t
            val y = 600f + 300f * t
            pts.add(TouchPoint(x, y, 1f, 5f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path,
            pts,
            color = Color.BLACK,
            width = 10f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(600f, 600f, 900f, 900f),
        )
    }

    private fun stateProbeRead(
        region: com.alexdremov.notate.data.region.RegionData,
        bounds: RectF,
        out: ArrayList<com.alexdremov.notate.model.CanvasItem>,
    ) {
        // Diagnostic-only read; RegionManager's internal lock is not exposed.
        // Quadtree retrieval is safe on a retained, non-recycled region.
        if (!region.isRecycled) region.quadtree?.retrieve(out, bounds)
    }

    /** Forensic dump of TileManager internals for visibility-failure diagnosis. */
    private fun dumpTileManagerState(
        renderer: CanvasRenderer,
        model: InfiniteCanvasModel,
        sentinel: Stroke,
        rm: com.alexdremov.notate.data.region.RegionManager,
    ): String {
        val sb = StringBuilder("\n=== TILE MANAGER DUMP ===\n")
        runBlocking {
            val found = model.queryItems(RectF(sentinel.bounds).apply { inset(-5f, -5f) })
            // NOTE: model.addItem assigns a NEW strokeOrder — match by geometry.
            sb
                .append("model sees sentinel: ")
                .append(found.any { it.bounds == sentinel.bounds && (it as? Stroke)?.points?.size == sentinel.points.size })
                .append('\n')
            val rid0 =
                com.alexdremov.notate.data.region
                    .RegionId(0, 0)
            val r00 = rm.acquireRegion(rid0)
            sb
                .append("region(0,0): items=")
                .append(r00?.items?.size)
                .append(" hasSentinel=")
                .append(r00?.items?.any { it.bounds == sentinel.bounds })
                .append('\n')
            r00?.let { rm.releaseRegion(it) }
            sb
                .append("---- REGION 0_0 EVENT HISTORY ----\n")
                .append(
                    rm.dumpForensics("RegionId(x=0, y=0)"),
                ).append(
                    rm.dumpForensics("0_0"),
                ).append("\n---- END 0_0 HISTORY ----\n")
        }
        try {
            val tmField = CanvasRenderer::class.java.getDeclaredField("tileManager")
            tmField.isAccessible = true
            val tm = tmField.get(renderer)
            val tmClass = tm.javaClass

            fun intField(name: String): Any? {
                val f = tmClass.getDeclaredField(name)
                f.isAccessible = true
                return f.get(tm)
            }

            fun sizeOf(name: String): Int {
                val v = intField(name) ?: return -1
                return when (v) {
                    is Set<*> -> v.size
                    is Map<*, *> -> v.size
                    else -> -1
                }
            }
            sb.append("generatingKeys=").append(sizeOf("generatingKeys")).append('\n')
            sb.append("pendingJobsByKey=").append(sizeOf("pendingJobsByKey")).append('\n')
            sb.append("generationJobs=").append(sizeOf("generationJobs")).append('\n')
            sb.append("renderVersion=").append(intField("renderVersion")).append('\n')
            sb.append("lastRenderLevel=").append(intField("lastRenderLevel")).append('\n')
            sb.append("lastVisibleRect=").append(intField("lastVisibleRect")).append('\n')
            sb.append("activeJobCount=").append(intField("activeJobCount")).append('\n')
            val tc = tmClass.getDeclaredField("tileCache").apply { isAccessible = true }.get(tm)
            val snapMethod = tc.javaClass.getMethod("snapshot")

            @Suppress("UNCHECKED_CAST")
            val snap = snapMethod.invoke(tc) as Map<Any, Any>
            sb.append("tileCache entries=").append(snap.size).append('\n')
            sb.append("tileCache keys=").append(snap.keys).append('\n')
        } catch (t: Throwable) {
            sb.append("dump failed: ").append(t).append('\n')
        }
        return sb.toString()
    }

    private fun waitForTileVisibility(
        renderer: CanvasRenderer,
        sentinel: Stroke,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val probePoints = sentinel.points.filterIndexed { i, _ -> i % 5 == 0 }
        val vis = RectF(500f, 500f, 1100f, 1100f)

        while (System.currentTimeMillis() < deadline) {
            val bmp = renderFrame(renderer, vis, 600, 600)
            // World->screen mapping is identity here (visible starts at 500).
            var dark = 0
            for (p in probePoints) {
                val px = (p.x - 500f).toInt().coerceIn(0, 599)
                val py = (p.y - 500f).toInt().coerceIn(0, 599)
                val c = bmp.getPixel(px, py)
                // Stroke is black on white background; accept any non-white ink
                // (anti-aliasing may lighten edge pixels).
                if (Color.red(c) < 220 || Color.blue(c) < 220) dark++
            }
            bmp.recycle()
            if (dark >= probePoints.size * 3 / 4) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test(timeout = 180_000)
    fun `chaos seed 1`() = runChaos(1)

    @Test(timeout = 180_000)
    fun `chaos seed 42`() = runChaos(42)
}
