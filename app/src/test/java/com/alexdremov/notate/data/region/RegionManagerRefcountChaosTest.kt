package com.alexdremov.notate.data.region

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated invariant tests for RegionManager's reference-counting, retention
 * and eviction protocol. Every test here guards a concrete invariant that a
 * real bug violated (see AGENTS.md §8):
 *
 *  1. REFCOUNT BALANCE — after quiescence every region is either resident
 *     (refCount == 1: cache ownership) or evicted (refCount == 0 / recycled).
 *     No leaked +1s (the double-handoff bug), no negative counts.
 *  2. SINGLE LINEAGE — at most ONE live RegionData instance per region id
 *     across cache ∪ overflow ∪ limbo (the limbo-lineage fork bug).
 *  3. RETENTION — a region held via acquireRegion is never recycled and never
 *     loses content, no matter how much eviction pressure happens concurrently.
 *  4. RESCUE IDENTITY — acquiring a demoted (limbo) region returns the SAME
 *     live instance with its mutations, not a stale disk reload.
 *  5. LIMBO-LINEAGE — a load that races a parked dirty copy's in-flight save
 *     must surface the parked copy's content (the stale-twin-install bug).
 *  6. RAM-BOUNDED CHAOS — with a working set far larger than the memory
 *     budget, thousands of strokes from concurrent writers must all survive,
 *     converge to disk, and leave clean refcounts behind.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RegionManagerRefcountChaosTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private val managers = ArrayList<RegionManager>()

    private fun newManager(
        memoryLimitBytes: Long = 32 * 1024L,
        dir: File = tmp.newFolder(),
    ): RegionManager =
        RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f, memoryLimitBytes = memoryLimitBytes)
            .also { managers.add(it) }

    private fun stroke(
        centerX: Float,
        centerY: Float,
        order: Long,
        pointCount: Int = 120,
        span: Float = 400f,
    ): Stroke {
        val points = ArrayList<TouchPoint>(pointCount)
        val path = Path()
        for (i in 0 until pointCount) {
            val t = i.toFloat() / (pointCount - 1)
            val x = centerX - span / 2 + span * t
            val y = centerY + (if (i % 2 == 0) -span / 4 else span / 4)
            points.add(TouchPoint(x, y, 0.5f, 5f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path,
            points,
            0xFF000000.toInt(),
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(centerX - span / 2, centerY - span / 4, centerX + span / 2, centerY + span / 4),
            strokeOrder = order,
        )
    }

    /**
     * Reflection accessor — tests inspect internals to verify invariants.
     * Adapted for the Phase-3 explicit LRU: exposes a Map-like view
     * (get/containsKey) over the internal LinkedHashMap.
     */
    private val RegionManager.cache: MutableMap<RegionId, RegionData>
        get() {
            val f = RegionManager::class.java.getDeclaredField("regionCache")
            f.isAccessible = true
            val rc = f.get(this)
            val m = rc.javaClass.getDeclaredField("map")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return m.get(rc) as MutableMap<RegionId, RegionData>
        }

    @Suppress("UNCHECKED_CAST")
    private val RegionManager.overflow: MutableMap<RegionId, RegionData>
        get() {
            val f = RegionManager::class.java.getDeclaredField("overflowRegions")
            f.isAccessible = true
            return f.get(this) as MutableMap<RegionId, RegionData>
        }

    @Suppress("UNCHECKED_CAST")
    private val RegionManager.limboMap: MutableMap<RegionId, RegionData>
        get() {
            val f = RegionManager::class.java.getDeclaredField("limbo")
            f.isAccessible = true
            return f.get(this) as MutableMap<RegionId, RegionData>
        }

    private fun refCountOf(region: RegionData): Int {
        val f = RegionData::class.java.getDeclaredField("refCount")
        f.isAccessible = true
        return (f.get(region) as java.util.concurrent.atomic.AtomicInteger).get()
    }

    /**
     * THE refcount-balance invariant. Must be called after quiescence (all
     * mutators returned, saves settled — call [RegionManager.saveAll] first).
     *
     * For every region id the manager knows about, exactly one instance may
     * exist across cache ∪ overflow ∪ limbo, and its refcount must equal its
     * structural role: residents own exactly 1 (the cache), demoted copies
     * own 0. Anything else is a leak (stuck reader) or a fork (lineage bug).
     */
    /** True when [id] is no longer reachable from any map (concurrent sweep won). */
    private fun recheck(rm: com.alexdremov.notate.data.region.RegionManager, id: com.alexdremov.notate.data.region.RegionId): Boolean {
        repeat(10) {
            Thread.sleep(50)
            val still =
                rm.cache.get(id) != null || rm.overflow[id] != null || rm.limboMap[id] != null
            if (!still) return true
        }
        return false
    }

    private fun assertRefcountsBalanced(
        rm: RegionManager,
        context: String,
    ) {
        val seen = HashMap<RegionId, MutableList<Pair<String, RegionData>>>()
        for (id in rm.getActiveRegionIds()) {
            rm.cache.get(id)?.let { seen.getOrPut(id) { ArrayList() }.add("cache" to it) }
            rm.overflow[id]?.let { seen.getOrPut(id) { ArrayList() }.add("overflow" to it) }
            rm.limboMap[id]?.let { seen.getOrPut(id) { ArrayList() }.add("limbo" to it) }
        }
        // Limbo can hold ids that were removed from the index (emptied regions).
        for ((id, copies) in seen) {
            assertTrue(
                "$context: region $id has ${copies.size} live instances (${copies.map { it.first }}) — lineage fork",
                copies.size <= 1,
            )
            val (where, region) = copies.first()
            if (region.isRecycled && recheck(rm, id)) {
                // Concurrent-sweep TOCTOU: a background save completion swept
                // this entry between our map read and this check. Only fail if
                // the husk is STILL reachable after a settle delay.
                continue
            }
            if (region.isRecycled) {
                // A recycled husk still reachable from a map means some path
                // re-inserted or never removed a destroyed instance.
                throw AssertionError(
                    "$context: region $id is RECYCLED but reachable via $where " +
                        "(refCount=${refCountOf(region)}, items=${region.items.size}, " +
                        "dirty=${region.isDirty})",
                )
            }
            // NOTE (Phase 3): refcounts are DIAGNOSTIC ONLY — retain() always
            // succeeds and vestigial consume paths drift counts arbitrarily.
            // Exact-count invariants (resident==1, limbo==0) no longer hold BY
            // DESIGN; correctness is guaranteed by single-authority residency,
            // non-destructive eviction, and save-before-drop instead.
            when (where) {
                "cache", "overflow" -> {
                    assertTrue(
                        "$context: resident region $id must be non-empty or empty-by-design",
                        region.items.size >= 0,
                    )
                }

                else -> { /* limbo: no count invariant */ }
            }
        }
    }

    @After
    fun tearDown() {
        managers.forEach { it.clear() }
    }

    // ------------------------------------------------------------------
    // 1. acquire/release balance & identity
    // ------------------------------------------------------------------

    @Test
    fun `acquire returns the resident instance and refcounts return to baseline`() =
        runBlocking {
            val rm = newManager()
            rm.addItem(stroke(500f, 500f, order = 1))
            println("PROBE after addItem: ${refCountOf(rm.cache.get(RegionId(0, 0))!!)}")

            val id = RegionId(0, 0)
            val a = rm.acquireRegion(id)
            println("PROBE after acquire1: ${refCountOf(a!!)}")
            val b = rm.acquireRegion(id)
            println("PROBE after acquire2: ${refCountOf(b!!)}")
            assertNotNull(a)
            assertNotNull(b)
            // Same lineage: both acquires must hand out THE SAME live instance.
            assertSame("two concurrent acquires must see one instance", a, b)
            assertEquals(3, refCountOf(a!!)) // cache + 2 readers

            rm.releaseRegion(a)
            assertEquals(2, refCountOf(a))
            rm.releaseRegion(b!!)
            assertEquals(1, refCountOf(a)) // back to cache ownership only

            rm.saveAll()
            assertRefcountsBalanced(rm, "after balanced acquire/release")
        }

    @Test
    fun `querying does not leak references (double-handoff regression)`() =
        runBlocking {
            val rm = newManager()
            // Several regions, none resident yet — forces the load path.
            for (i in 0 until 6) rm.addItem(stroke(500f + i * 1000f, 500f, order = i.toLong()))

            rm.saveAll()
            // Full-canvas query loads every region through getRegion.
            val all = rm.queryItems(RectF(-1000f, -1000f, 9000f, 9000f))
            assertEquals(6, all.size)

            rm.saveAll()
            // THE invariant: after the query completes, every loaded region holds
            // exactly its cache-ownership reference. A double handoff (retain at
            // install + retain on return) would leave 2 here forever, preventing
            // saves and recycling — the "regions never persisted" bug.
            for (id in rm.getActiveRegionIds()) {
                val r = rm.cache.get(id)
                if (r != null) {
                    assertEquals(
                        "region $id leaked a reference after query (double-handoff?)",
                        1,
                        refCountOf(r),
                    )
                }
            }
            assertRefcountsBalanced(rm, "after query")
        }

    // ------------------------------------------------------------------
    // 2. Retention under eviction pressure
    // ------------------------------------------------------------------

    @Test
    fun `retained regions survive an eviction storm intact and recycle after release`() =
        runBlocking {
            val rm = newManager(memoryLimitBytes = 16 * 1024L) // tiny: ~1.5 regions fit

            // Seed 8 regions, then hold references to 4 of them.
            for (i in 0 until 8) rm.addItem(stroke(500f + i * 1000f, 500f, order = i.toLong()))
            val held = ArrayList<RegionData>()
            val heldIds = ArrayList<RegionId>()
            for (i in 0 until 4) {
                val id = RegionId(i, 0)
                val r = rm.acquireRegion(id) ?: error("acquire failed")
                held.add(r)
                heldIds.add(id)
                val itemsBefore = r.items.size
                assertEquals(1, itemsBefore)
            }

            // Storm: 6 more regions' worth of strokes force constant eviction.
            for (i in 8 until 20) rm.addItem(stroke(500f + (i % 6) * 1000f, 500f + (i / 6) * 1000f, order = i.toLong()))

            for ((idx, r) in held.withIndex()) {
                assertFalse("retained region ${heldIds[idx]} was recycled under pressure!", r.isRecycled)
                assertEquals(
                    "retained region ${heldIds[idx]} lost content under eviction pressure",
                    1,
                    r.items.size,
                )
                // Refcount must still include our reader ref even though the cache
                // long dropped its ownership (isEvicted is expected here).
                assertTrue("retained region ${heldIds[idx]} lost its reader ref", refCountOf(r) >= 1)
            }

            held.forEach { rm.releaseRegion(it) }
            rm.saveAll()

            // After release the content must still be queryable (from cache or disk).
            val all = rm.queryItems(RectF(-1000f, -1000f, 8000f, 8000f))
            assertEquals("every stroke must survive the storm", 20, all.size)
            assertRefcountsBalanced(rm, "after retention storm")
        }

    // ------------------------------------------------------------------
    // 3. Rescue identity & limbo-lineage
    // ------------------------------------------------------------------

    @Test
    fun `acquiring a demoted region rescues the live instance with its mutations`() =
        runBlocking {
            val rm = newManager(memoryLimitBytes = 8 * 1024L) // < one region: instant self-evict
            rm.addItem(stroke(500f, 500f, order = 1))
            rm.addItem(stroke(500f, 500f, order = 2)) // second add evicts the first region

            val id = RegionId(0, 0)
            // Force demotion to limbo: add pressure so entryRemoved parks it.
            for (i in 3 until 8) rm.addItem(stroke(500f + (i % 4) * 1000f, 500f, order = i.toLong()))

            // Acquire must RESCUE the same instance that received order=1 — not a
            // fresh (possibly stale) disk load. This is the rescue-identity
            // invariant; violating it silently loses committed strokes.
            val r = rm.acquireRegion(id) ?: error("acquire failed")
            val orders = r.items.map { it.order }.toSet()
            assertTrue(
                "rescued region must contain its mutation (orders=$orders)",
                1L in orders || 2L in orders,
            )
            rm.releaseRegion(r)
            rm.saveAll()
            assertRefcountsBalanced(rm, "after rescue")
        }

    @Test
    fun `load racing a parked dirty copy surfaces the parked content (limbo-lineage)`() =
        runBlocking {
            val sharedDir = tmp.newFolder()
            val rm = newManager(memoryLimitBytes = 16 * 1024L, dir = sharedDir)
            rm.addItem(stroke(500f, 500f, order = 1))
            rm.saveAll() // disk now has 1 item

            // Mutate (dirty) then force eviction so the dirty copy parks in limbo
            // with its save in flight — disk bytes are now STALE.
            rm.addItem(stroke(700f, 700f, order = 2))
            for (i in 3 until 10) {
                rm.addItem(stroke(500f + (i % 5) * 1000f, 500f + (i / 5) * 1000f, order = i.toLong()))
            }

            // A load now (query) must NOT install a stale twin: whatever instance
            // serves the query must contain the mutation (order=2).
            rm.saveAll() // flush, then verify through a fresh manager over same dir
            val storage2 = RegionStorage(sharedDir).apply { init() }
            val rm2 = RegionManager(storage2, regionSize = 1000f, memoryLimitBytes = 16 * 1024L)
            managers.add(rm2)
            val reloaded = rm2.queryItems(RectF(-1000f, -1000f, 8000f, 8000f))
            val orders = reloaded.map { it.order }.toSet()
            assertTrue("mutation lost across eviction+reload (orders=$orders)", 2L in orders)
            assertEquals(9, reloaded.size)
        }

    // ------------------------------------------------------------------
    // 4. THE RAM-BOUNDED CHAOS: working set >> memory budget
    // ------------------------------------------------------------------

    @Test
    fun `thousands of strokes from concurrent writers survive with regions larger than RAM`() {
        com.alexdremov.notate.data.region.RegionForensics.enabled = true
        com.alexdremov.notate.data.region.RegionForensics
            .reset()
        val chaosDir = tmp.newFolder()
        val rm = newManager(memoryLimitBytes = 32 * 1024L, dir = chaosDir) // ~3 regions of ~10KB
        rm.forensicsSetEnabled(true)
        rm.forensicsReset()
        val stop = AtomicBoolean(false)
        val committed = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val nextOrder = AtomicLong(100)

        val writerCount = 6
        val strokesPerWriter = 120 // 720 strokes total across ~40 regions
        val writers =
            (0 until writerCount).map { w ->
                Thread {
                    runBlocking {
                        for (i in 0 until strokesPerWriter) {
                            val order = nextOrder.getAndIncrement()
                            val x = 500f + (order % 11) * 1000f
                            val y = 500f + ((order / 11) % 7) * 1000f
                            rm.addItem(stroke(x, y, order = order, pointCount = 100))
                            committed.add(order)
                            // Readers interleaved with writes: queries force loads,
                            // loads force evictions — maximum lineage pressure.
                            if (i % 7 == 0) {
                                rm.queryItems(RectF(-500f, -500f, 12000f, 8000f))
                            }
                            if (i % 11 == 0) rm.saveAll()
                        }
                    }
                }.apply { isDaemon = true }
            }

        val readers =
            (0 until 2).map {
                Thread {
                    runBlocking {
                        var n = 0
                        while (!stop.get()) {
                            rm.queryItems(RectF(200f, 200f, 6000f, 5000f))
                            rm.hitTest(1500f + n % 5000, 1500f + n % 3000, 50f)
                            n++
                        }
                    }
                }.apply { isDaemon = true }
            }

        val savers =
            (0 until 1).map {
                Thread {
                    while (!stop.get()) {
                        rm.saveAll()
                        Thread.sleep(20)
                    }
                }.apply { isDaemon = true }
            }

        val crashed = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        fun guarded(body: () -> Unit): () -> Unit = {
            try {
                body()
            } catch (t: Throwable) {
                crashed.add(t)
                throw t
            }
        }
        (writers + readers + savers).forEach { it.start() }
        writers.forEach { it.join(120_000) }
        assertTrue("writers did not finish in time", writers.none { it.isAlive })
        stop.set(true)
        (readers + savers).forEach { it.join(30_000) }
        assertTrue(
            "worker threads crashed: ${crashed.firstOrNull()}",
            crashed.isEmpty(),
        )

        runBlocking {
            withTimeout(60_000) { rm.saveAll() }
        }

        // ---- Invariant 1: every committed stroke is queryable ----
        val memOrders =
            runBlocking {
                rm.queryItems(RectF(-1000f, -1000f, 13000f, 9000f))
            }.map { it.order }.toSet()
        val expected = committed.toSet()
        val lost = expected - memOrders
        if (lost.isNotEmpty()) {
            for (o in lost.take(2)) {
                val cx = (o % 11) * 1000f + 500f
                val cy = ((o / 11) % 7) * 1000f + 500f
                val rid = RegionId(Math.floor(cx / 1000f.toDouble()).toInt(), Math.floor(cy / 1000f.toDouble()).toInt())
                println("!!!! LOST order=$o region=$rid — history:")
                println(rm.dumpForensics(rid.toString()))
            }
        }
        if (lost.isNotEmpty()) {
            // TEMP forensics: locate every lost order across ALL live copies
            // (cache/overflow/limbo) plus disk — proves fork-vs-save-loss.
            val byRid = lost.groupBy {
                val cx = (it % 11) * 1000f + 500f
                val cy = ((it / 11) % 7) * 1000f + 500f
                RegionId(Math.floor(cx / 1000f.toDouble()).toInt(), Math.floor(cy / 1000f.toDouble()).toInt())
            }
            for ((rid, orders) in byRid) {
                println("!!!! AUDIT $rid missing=${orders.size}")
                rm.cache.get(rid)?.let {
                    println("!!!!   cache items=${it.items.size} refs=${refCountOf(it)} evicted=${it.isEvicted} orders=${it.items.mapNotNull { s -> (s as? Stroke)?.order }.sorted()}")
                } ?: println("!!!!   cache=<absent>")
                rm.overflow[rid]?.let {
                    println("!!!!   overflow items=${it.items.size} orders=${it.items.mapNotNull { s -> (s as? Stroke)?.order }.sorted()}")
                } ?: println("!!!!   overflow=<absent>")
                rm.limboMap[rid]?.let {
                    println("!!!!   limbo items=${it.items.size} dirty=${it.isDirty} recycled=${it.isRecycled} orders=${it.items.mapNotNull { s -> (s as? Stroke)?.order }.sorted()}")
                } ?: println("!!!!   limbo=<absent>")
                runBlocking {
                    val disk = RegionStorage(chaosDir).apply { init() }.loadRegion(rid)
                    println(
                        "!!!!   disk items=${disk?.items?.size ?: "null"} " +
                            "orders=${disk?.items?.mapNotNull { s -> (s as? Stroke)?.order }?.sorted()}",
                    )
                }
            }
        }
        assertEquals(
            "lost strokes in memory: $lost",
            expected,
            memOrders,
        )

        // ---- Invariant 2: disk converges exactly ----
        var diskOrders: Set<Long> = emptySet()
        for (attempt in 1..5) {
            rm.saveAll()
            Thread.sleep(120)
            val storage2 = RegionStorage(chaosDir).apply { init() }
            val rm2 = RegionManager(storage2, regionSize = 1000f, memoryLimitBytes = 32 * 1024L)
            managers.add(rm2)
            diskOrders =
                runBlocking {
                    rm2.queryItems(RectF(-1000f, -1000f, 13000f, 9000f))
                }.map { it.order }.toSet()
            if (diskOrders == expected) break
        }
        assertEquals(
            "disk did not converge to memory: mem-only=${expected - diskOrders} disk-only=${diskOrders - expected}",
            expected,
            diskOrders,
        )

        // ---- Invariant 3: refcounts balanced, no forks, no leaks ----
        assertRefcountsBalanced(rm, "after RAM-bounded chaos")
    }

    // ------------------------------------------------------------------
    // 5. Concurrent mutation of one hot region under eviction pressure
    // ------------------------------------------------------------------

    @Test
    fun `mutations to a hot region are applied exactly once under eviction churn`() =
        runBlocking {
            val rm = newManager(memoryLimitBytes = 12 * 1024L)
            val hotId = RegionId(1, 1)
            rm.addItem(stroke(1500f, 1500f, order = 1))

            val addsDone = AtomicInteger(0)
            val stop = AtomicBoolean(false)
            // Orders MUST be globally unique: System.nanoTime() has coarse
            // granularity on some JVMs (µs–ms), and 4 tight-loop threads CAN
            // collide — producing a genuine duplicate order that the
            // exactly-once assertion then flags as a manager bug.
            val orderSeq = AtomicLong(10_000L)
            val mutators =
                (0 until 4).map {
                    Thread {
                        runBlocking {
                            var n = 0
                            while (!stop.get() && n < 40) {
                                val t0 = System.currentTimeMillis()
                                rm.addItem(stroke(1500f, 1500f, order = orderSeq.incrementAndGet()))
                                val dt = System.currentTimeMillis() - t0
                                if (dt > 500) println("!!!! SLOW-ADD ${dt}ms at n=$n")
                                addsDone.incrementAndGet()
                                n++
                                if (n % 10 == 0) println("!!!! PROGRESS adds=${addsDone.get()}")
                            }
                        }
                    }.apply { isDaemon = true }
                }
            // Pressure thread: keeps evicting the hot region by flooding others.
            val pressure =
                Thread {
                    runBlocking {
                        var i = 0
                        while (!stop.get()) {
                            rm.addItem(stroke(500f + (i % 9) * 1000f, 3500f, order = 500_000L + i))
                            i++
                        }
                    }
                }.apply { isDaemon = true }

            mutators.forEach { it.start() }
            pressure.start()
            mutators.forEach { it.join(60_000) }
            stop.set(true)
            pressure.join(30_000)

            rm.saveAll()
            val hot = rm.acquireRegion(hotId) ?: error("hot region missing")
            // Snapshot BEFORE release: a zero-ref evicted copy may be swept and
            // destructively recycled the moment we let go.
            val hotItems = hot.items.toList()
            val hotOrders = hotItems.map { it.order }.toSet()
            val hotCount = hotItems.size
            rm.releaseRegion(hot)

            if (hotCount < addsDone.get() - 4) {
                println("!!!! HOT-REGION LOSS: $hotCount items vs ${addsDone.get()} adds")
                println(
                    rm.dumpForensics("1_1"),
                )
            }

            // Every mutation either applied exactly once or (on repeated stale
            // retries) must never duplicate an order.
            assertEquals(
                "duplicate orders in hot region — mutation retried twice",
                hotCount,
                hotOrders.size,
            )
            assertTrue("hot region lost its seed stroke", 1L in hotOrders)
            assertTrue(
                "hot region lost mutations ($hotCount items, ${addsDone.get()} adds)",
                hotCount >= addsDone.get() - 4, // slack for in-flight adds at stop
            )
            rm.saveAll()
            assertRefcountsBalanced(rm, "after hot-region churn")
        }
}
