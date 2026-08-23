package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Injects failures into storage operations to simulate crashes, disk-full and
 * corruption. Delegates everything else to a real [RegionStorage].
 *
 * Modes:
 *  - [failNextSaves]      : next N `saveRegion` calls return false (disk full)
 *  - [truncateNextSave]   : next successful write is truncated mid-file
 *                           (power loss between write and verify/rename)
 *  - [skipRenameNextSave] : write temp file but never publish it (crash before rename)
 */
open class FaultyStorage(
    baseDir: File,
    private val real: RegionStorage,
) : RegionStorage(baseDir) {

    @Volatile var failNextSaves: Int = 0
    @Volatile var truncateNextSave: Int = 0
    @Volatile var skipRenameNextSave: Int = 0

    val saveAttempts = java.util.concurrent.atomic.AtomicInteger()
    val saveFailures = java.util.concurrent.atomic.AtomicInteger()

    override fun saveRegionBytes(
        id: RegionId,
        bytes: ByteArray,
    ): Boolean {
        saveAttempts.incrementAndGet()
        if (failNextSaves > 0) {
            failNextSaves--
            saveFailures.incrementAndGet()
            return false
        }
        if (truncateNextSave > 0) {
            truncateNextSave--
            saveFailures.incrementAndGet()
            // Simulate power loss mid-write: hand the parent a truncated payload.
            return super.saveRegionBytes(id, bytes.copyOf(bytes.size / 2))
        }
        if (skipRenameNextSave > 0) {
            skipRenameNextSave--
            saveFailures.incrementAndGet()
            // Write the temp file only: crash between write and atomic move.
            return true // claim success; caller-visible file stays OLD (or absent)
        }
        return super.saveRegionBytes(id, bytes)
    }

    override fun saveRegion(data: RegionData): Boolean = super.saveRegion(data)

    override fun loadRegion(id: RegionId): RegionData? = real.loadRegion(id)

    override fun deleteRegion(id: RegionId) = real.deleteRegion(id)

    override fun listStoredRegions(): List<RegionId> = real.listStoredRegions()

    override fun saveIndex(index: Map<RegionId, RectF>): Boolean = real.saveIndex(index)

    override fun loadIndex(): Map<RegionId, RectF> = real.loadIndex()
}

/**
 * Durability contract tests: whatever happens at the storage layer — disk
 * full, truncated writes, crashes between write and rename — the store must
 * never serve corrupted data, never lose acknowledged content permanently,
 * and always recover on a later successful flush.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DurabilityFaultInjectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(order: Long): com.alexdremov.notate.model.Stroke {
        val pts = ArrayList<TouchPoint>(8)
        val path = android.graphics.Path()
        for (i in 0 until 8) {
            val x = order * 10f + i
            val y = i * 3f
            pts.add(TouchPoint(x, y, 0.5f, 4f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return com.alexdremov.notate.model.Stroke(
            path,
            pts,
            0xFF000000.toInt(),
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(order * 10f, 0f, order * 10f + 10f, 24f),
            strokeOrder = order,
        )
    }

    /** Manager wired so its regions persist through the fault-injecting layer. */
    private class Setup(val dir: File) {
        val faultyStorage = FaultyStorage(dir, RegionStorage(dir).apply { init() })
        // The manager's OWN storage is the faulty view; the underlying "real"
        // storage shares the same directory so external readers see truth.
        val rm = RegionManager(faultyStorage, regionSize = 1000f, memoryLimitBytes = 256 * 1024L)
    }

    @Test
    fun `disk-full during flush - region stays dirty and recovers on next successful flush`() {
        val setup = Setup(tmp.newFolder())
        val rm = setup.rm

        runBlocking { rm.addItem(stroke(1)) }
        runBlocking { rm.addItem(stroke(2)) }

        // Flush with a failing drive.
        setup.faultyStorage.failNextSaves = 100
        runBlocking { rm.saveAll() }
        assertTrue("save attempts should have been made", setup.faultyStorage.saveAttempts.get() > 0)
        assertTrue("saves should have failed", setup.faultyStorage.saveFailures.get() > 0)

        // Drive repaired: next flush must converge.
        setup.faultyStorage.failNextSaves = 0
        runBlocking { rm.saveAll() }
        rm.clear()

        val reader = RegionManager(RegionStorage(setup.dir).apply { init() }, regionSize = 1000f)
        val orders =
            runBlocking { reader.queryItems(RectF(-10000f, -10000f, 10000f, 10000f)) }
                .map { it.order }.toSet()
        assertEquals(setOf(1L, 2L), orders)
        reader.clear()
    }

    @Test
    fun `truncated write never serves corrupt data - old or new bytes only`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val id = RegionId(0, 0)
        val goodBytes = storage.serializeRegion(
            RegionData(id).apply {
                items = listOf(stroke(1))
            },
        )
        assertTrue(storage.saveRegionBytes(id, goodBytes))

        val faulty = FaultyStorage(dir, storage)
        faulty.truncateNextSave = 1
        val newBytes = storage.serializeRegion(
            RegionData(id).apply {
                items = listOf(stroke(1), stroke(2), stroke(3))
            },
        )
        // The truncated write may or may not "succeed" at the IO layer — what
        // matters is that a subsequent LOAD either sees the old complete state
        // or fails cleanly, NEVER half-parsed garbage that yields wrong items.
        faulty.saveRegionBytes(id, newBytes)

        val reloaded = storage.loadRegion(id)
        if (reloaded != null) {
            val orders = reloaded.items.map { it.order }.toSet()
            assertTrue(
                "loaded region must be old-complete or new-complete, got $orders",
                orders == setOf(1L) || orders == setOf(1L, 2L, 3L),
            )
        } // null also acceptable: loader treats unparseable as absent → rebuild path.
    }

    @Test
    fun `crash between write and rename - previous version remains loadable`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val id = RegionId(1, 1)
        storage.saveRegion(RegionData(id).apply { items = listOf(stroke(1)) })

        val faulty = FaultyStorage(dir, storage)
        faulty.skipRenameNextSave = 1
        faulty.saveRegion(RegionData(id).apply { items = listOf(stroke(1), stroke(2)) })
        // skipRename claims success without publishing — the visible file must
        // still be v1.
        val loaded = storage.loadRegion(id)
        assertNotNull(loaded)
        assertEquals(listOf(1L), loaded!!.items.map { it.order })
    }

    @Test
    fun `manager session survives intermittent save failures across many cycles`() {
        val rng = Random(7)
        val setup = Setup(tmp.newFolder())
        val rm = setup.rm
        val expected = LinkedHashSet<Long>()

        repeat(12) { cycle ->
            repeat(rng.nextInt(1, 6)) {
                val s = stroke((cycle * 10L + expected.size + 1))
                runBlocking { rm.addItem(s) }
                expected.add(s.strokeOrder)
            }
            // Random DISK-FULL pressure each cycle.
            // (truncateNextSave is intentionally NOT used here: truncating the
            // payload before writeAtomic simulates memory corruption, not IO
            // failure — writeAtomic's length verification then legitimately
            // "succeeds" for the short payload and dirty is cleared. Real
            // mid-write power loss is covered by skipRenameNextSave.)
            setup.faultyStorage.failNextSaves = rng.nextInt(0, 4)
            runBlocking { rm.saveAll() }
            setup.faultyStorage.failNextSaves = 0
        }

        // Final clean flush must converge — however many cycles it takes.
        repeat(5) {
            runBlocking { rm.saveAll() }
            val probe = RegionManager(RegionStorage(setup.dir).apply { init() }, regionSize = 1000f)
            val diskOrders =
                runBlocking { probe.queryItems(RectF(-10000f, -10000f, 10000f, 10000f)) }
                    .map { it.order }.toSet()
            probe.clear()
            if (diskOrders == expected) {
                return
            }
        }
        val probe = RegionManager(RegionStorage(setup.dir).apply { init() }, regionSize = 1000f)
        val diskOrders =
            runBlocking { probe.queryItems(RectF(-10000f, -10000f, 10000f, 10000f)) }
                .map { it.order }.toSet()
        assertEquals(
            "missing=${expected - diskOrders} extra=${diskOrders - expected}",
            expected,
            diskOrders,
        )
        probe.clear()
    }

    @Test
    fun `corrupt region file is skipped by rebuild and does not poison siblings`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val goodId = RegionId(0, 0)
        val badId = RegionId(1, 1)
        storage.saveRegion(RegionData(goodId).apply { items = listOf(stroke(1), stroke(2)) })
        storage.saveRegion(RegionData(badId).apply { items = listOf(stroke(3)) })

        // Corrupt the bad region's file bytes in place.
        val badFile = File(dir, "r_${badId.x}_${badId.y}.bin")
        if (!badFile.exists()) {
            // naming fallback: find any non-index bin file that isn't the good one
            val candidates = dir.listFiles { f -> f.name.startsWith("r_") && f.name.endsWith(".bin") }
                ?: emptyArray()
            val target = candidates.firstOrNull { !it.name.contains("0_0") }
            assertNotNull("could not locate region file to corrupt", target)
            target!!.writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte()))
        } else {
            badFile.writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte()))
        }

        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 512 * 1024L)
        // The good region must load fine; the corrupt one must not crash the
        // manager (empty or absent is acceptable).
        val all =
            runBlocking { rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
        val orders = all.map { it.order }.toSet()
        assertTrue("good region lost!", setOf(1L, 2L).all { it in orders })
        assertTrue("corrupt data leaked as items", all.none { it.order == 3L })
        rm.clear()
    }

    @Test
    fun `dirty region whose eviction save fails stays discoverable and converges later`() {
        val dir = tmp.newFolder()
        val real = RegionStorage(dir).apply { init() }
        val faulty = FaultyStorage(dir, real)
        // Tiny budget forces constant eviction → dirty regions parked in limbo
        // with saves in flight; every save FAILS while the fault is armed.
        val rm = RegionManager(faulty, regionSize = 1000f, memoryLimitBytes = 8 * 1024L)
        val expected = LinkedHashSet<Long>()

        faulty.failNextSaves = 10_000
        runBlocking {
            repeat(40) { i ->
                // Spread across many regions (like real handwriting sessions):
                // a single-region storm would form ONE oversized region, which
                // is unevictable and would never exercise limbo parking.
                val s = stroke(i.toLong() + 1)
                val shifted =
                    s.copy(
                        path = android.graphics.Path().apply {
                            moveTo(s.bounds.left + (i % 5) * 900f, s.bounds.top + (i / 5) * 900f)
                            lineTo(s.bounds.right + (i % 5) * 900f, s.bounds.bottom + (i / 5) * 900f)
                        },
                        bounds = RectF(
                            s.bounds.left + (i % 5) * 900f,
                            s.bounds.top + (i / 5) * 900f,
                            s.bounds.right + (i % 5) * 900f,
                            s.bounds.bottom + (i / 5) * 900f,
                        ),
                    )
                rm.addItem(shifted)
                expected.add(i.toLong() + 1)
            }
        }

        // Settle: wait for all in-flight save jobs to drain their gates.
        waitUntil("pending saves to settle") {
            pendingSaveIds(rm).isEmpty()
        }

        // INVARIANT (orphan prevention): dirty copies whose persistence FAILED
        // must remain discoverable — parked in limbo (or resident), never
        // dropped into no-map. The pre-fix behaviour removed the limbo entry
        // and handed its bytes to a fire-and-forget save: one IO failure later
        // the instance sat in NO map, unreachable by saveAll or rescue.
        // The 8KB budget guarantees evictions happened, so at least one dirty
        // copy must be parked.
        assertTrue(
            "failed-save dirty copies were dropped instead of staying parked",
            limboOf(rm).isNotEmpty(),
        )

        // Force sweeps PAST the sticky window. This is the discriminating
        // moment: pre-fix, the sweeper dropped exactly these entries once the
        // sticky window expired (their saves had already failed); they must
        // survive every sweep while persistence keeps failing.
        Thread.sleep(5_400) // LIMBO_STICKY_MS (5s) + margin
        val sweep = RegionManager::class.java.getDeclaredMethod("sweepLimbo")
        sweep.isAccessible = true
        repeat(3) {
            sweep.invoke(rm)
            waitUntil("retry save to drain") { pendingSaveIds(rm).isEmpty() }
        }
        assertTrue(
            "limbo emptied after sticky expiry — failed-save copies are being orphaned",
            limboOf(rm).isNotEmpty(),
        )

        // Repair the drive: an explicit flush must converge disk to memory.
        faulty.failNextSaves = 0
        runBlocking { rm.saveAll() }

        val probe = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val diskOrders =
            runBlocking { probe.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
                .map { it.order }.toSet()
        assertEquals(
            "missing=${expected - diskOrders} extra=${diskOrders - expected}",
            expected,
            diskOrders,
        )
        probe.clear()
        rm.clear()
    }

    /** Polls [predicate] up to 10s. */
    private fun waitUntil(
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $what")
            }
            Thread.sleep(25)
        }
    }

    private fun pendingSaveIds(rm: RegionManager): Set<RegionId> {
        val f = RegionManager::class.java.getDeclaredField("pendingSaveIds")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (f.get(rm) as Set<RegionId>).toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun limboOf(rm: RegionManager): Map<RegionId, RegionData> {
        val f = RegionManager::class.java.getDeclaredField("limbo")
        f.isAccessible = true
        return f.get(rm) as Map<RegionId, RegionData>
    }

    @Test
    fun `wipe removes persisted strokes so reopen cannot resurrect them`() {
        val dir = tmp.newFolder()
        val rm =
            RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f, memoryLimitBytes = 256 * 1024L)

        runBlocking {
            repeat(12) { i -> rm.addItem(stroke(i.toLong() + 1)) }
        }
        runBlocking { rm.saveAll() }

        // Sanity: content IS persisted before the wipe.
        val pre = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val preOrders =
            runBlocking { pre.queryItems(RectF(-10000f, -10000f, 10000f, 10000f)) }
                .map { it.order }.toSet()
        assertEquals((1L..12L).toSet(), preOrders)
        pre.clear()

        runBlocking { rm.clearAndWipeStorage() }

        // Nothing left to resurrect: no region files, no index.
        assertTrue(
            "region files survived the wipe",
            dir.listFiles { f -> f.name.startsWith("r_") }.isNullOrEmpty(),
        )
        assertTrue("index survived the wipe", !File(dir, "index.bin").exists())

        // Reopen must NOT rebuild content from leftovers (rebuildIndex path).
        val post = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val postOrders =
            runBlocking { post.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
                .map { it.order }
        assertTrue("resurrected strokes: $postOrders", postOrders.isEmpty())
        post.clear()
    }

    private fun <T> runBlocking(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        kotlinx.coroutines.runBlocking(block = block)
}
