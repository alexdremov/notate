package com.alexdremov.notate.data.region

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.alexdremov.notate.BuildConfig
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasImageData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.LinkItemData
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.LinkItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.PerformanceProfiler
import com.alexdremov.notate.util.Quadtree
import com.alexdremov.notate.util.StrokeRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * Manages the spatial partitioning, caching, and persistence of canvas regions.
 *
 * ## CONCURRENCY MODEL (read this before touching locking)
 *
 * Three cooperating mechanisms guarantee the core invariant:
 *
 *   **A reference obtained via [acquireRegion] stays valid until
 *    [releaseRegion] — regardless of eviction, reloads, or cache size.**
 *
 * 1. **[stateLock] (ReentrantReadWriteLock)** — guards the index/cache/overflow
 *    maps and all quadtree/item mutations. Mutators hold WRITE; readers hold
 *    READ while extracting candidates from quadtrees (quadtrees are persistent,
 *    so reads never mutate).
 *
 * 2. **Reference counting + LIMBO** ([RegionData.retain]/[release], [limbo]) —
 *    eviction is a DEMOTION: the cache drops its ownership reference and
 *    zero-ref demotees are parked in limbo instead of recycled. Destruction
 *    ([RegionData.tryRecycle]) is a CAS against the zero-reference state, so a
 *    racing reader either pins the region or recycles it — never both.
 *
 * 3. **[pendingSaveIds]** — regions whose bytes are in-flight from an async
 *    eviction save. Reloads wait for them instead of misreading "file missing"
 *    as "region never existed" (which would amputate the spatial index).
 *
 * RULES for any code here:
 * - Readers: `acquireRegion(id)` → extract under `stateLock.read` →
 *   `releaseRegion(region)` in finally. Never walk quadtrees/items without a
 *   retained reference.
 * - Mutators: same, plus the mutation itself under `stateLock.write`, using
 *   [mutateDetachedFromCache] for the detach/reattach dance.
 * - Never call suspending/disk functions while holding [stateLock].
 */

/**
 * Chaos-forensics ring: a bounded event buffer plus a per-region dump API.
 * The FIRST time a region fails its items↔quadtree audit, the full event
 * history for that region id can be dumped, showing exactly which operation
 * introduced the inconsistency. Gated behind [enabled]; zero cost when off.
 */

/**
 * Note: this was historically a GLOBAL singleton keyed only by region id —
 * two managers running in one JVM (chaos seeds!) interleaved events for the
 * same id string, poisoning every dump analysis. Each manager now owns its
 * RegionManager owns an instance; [RegionForensics] remains as a shared
 * default for legacy static callers (RegionModels, RegionStorage, tests).
 */
open class RegionForensicsRing {
    @Volatile
    var enabled: Boolean = false

    /**
     * Per-operation churn logging (every acquire/release/handoff). Off by
     * default even when [enabled] — its volume multiplies suite runtime.
     * Enable only for targeted race forensics; decision-level events
     * (park/demote/save/install/adopt/sweep) stay on [enabled] alone.
     */
    @Volatile
    var verbose: Boolean = false

    @Volatile
    var divergenceSeen: Boolean = false

    private val CAP = 250_000
    private val PER_ID_CAP = 40_000
    private val events = ArrayDeque<String>()
    private val perId = HashMap<String, ArrayDeque<String>>()
    private val seq =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val dumpsIssued =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    private fun extractId(line: String): String? {
        val i = line.indexOf("id=")
        if (i < 0) return null
        val j = line.indexOf(' ', i)
        return line.substring(i + 3, if (j < 0) line.length else j)
    }

    fun log(line: String) {
        if (!enabled) return
        // Monotonic sequence + microsecond stamp: wall-clock millis jumble
        // under contention; seq guarantees total order, nanoTime gives real
        // elapsed time. Thread name includes @coroutine#N (kotlinx debug).
        val stamped =
            "${seq.incrementAndGet()} t${System.nanoTime() / 1_000 % 100_000_000_000} [${Thread.currentThread().name}] $line"
        synchronized(events) {
            if (events.size >= CAP) events.removeFirst()
            events.addLast(stamped)
        }
        extractId(line)?.let { id ->
            synchronized(perId) {
                val q = perId.getOrPut(id) { ArrayDeque() }
                if (q.size >= PER_ID_CAP) q.removeFirst()
                q.addLast(stamped)
            }
        }
    }

    fun reset() {
        divergenceSeen = false
        dumpsIssued.set(0)
        synchronized(events) { events.clear() }
    }

    fun dumpFor(id: String): String =
        synchronized(perId) {
            perId[id]?.joinToString("\n")
                ?: synchronized(events) { events.filter { "id=$id " in it }.joinToString("\n") }
        }

    /** Last [n] global events — for cross-region causality on failure. */
    fun dumpRecent(n: Int): String = synchronized(events) { events.takeLast(n).joinToString("\n") }
}

/**
 * Debug-only consistency audit: walks the region's quadtree and diffs its
 * contents against the items list. Ghosts = in items but not in tree
 * (invisible to queries, but persisted to disk). Phantoms = in tree but not
 * in items (zombie hits). Early-returns unless forensics are enabled.
 * Must be called while holding the state write lock (or on a quiesced region).
 */
private fun auditRegion(
    forensics: RegionForensicsRing,
    region: RegionData,
    op: String,
) {
    // RELEASE PERF: audits walk the full items list + quadtree per mutation —
    // strictly a debug tool. Zero cost when forensics disabled.
    if (!forensics.enabled) return
    if (!RegionForensics.enabled) return
    // Forensics must NEVER take down production paths. Some call sites (e.g.
    // acquireRegion's rescue) run outside the state write lock, so the tree
    // walk can race a concurrent insert (ConcurrentModificationException).
    // Swallow + record: an audit failure is itself diagnostic signal.
    try {
        auditRegionLocked(forensics, region, op)
    } catch (e: Exception) {
        // legacy global ring: audit failures are rare + printed by callers
        RegionForensics.log("AUDIT-FAILED op=$op id=${region.id}: $e")
    }
}

private fun auditRegionLocked(
    forensics: RegionForensicsRing,
    region: RegionData,
    op: String,
) {
    val treeOrders = HashSet<Long>()
    region.quadtree?.visit(RectF(-1e9f, -1e9f, 1e9f, 1e9f)) { treeOrders.add(it.order) }
    val itemOrders = region.items.map { it.order }
    val ghosts = itemOrders.filter { it !in treeOrders }
    val phantoms = treeOrders.filter { it !in itemOrders.toSet() }
    forensics.log(
        "$op id=${region.id} items=${itemOrders.size} tree=${treeOrders.size} " +
            "ghosts=$ghosts phantoms=$phantoms mod=${region.modCount} gen=${region.generation} " +
            "dirty=${region.isDirty} refs=${region.hasReferences()}",
    )
    val problem =
        ghosts.isNotEmpty() || phantoms.isNotEmpty() ||
            (region.quadtree == null && region.items.isNotEmpty())
    if (problem) {
        RegionForensics.divergenceSeen = true
        if (RegionForensics.dumpsIssued.getAndIncrement() < 5) {
            println(
                "!!!! DIVERGENCE #" + RegionForensics.dumpsIssued.get() + " op=$op id=${region.id} " +
                    "ghosts=$ghosts phantoms=$phantoms treeNull=${region.quadtree == null}",
            )
            println("---- EVENT HISTORY for ${region.id} ----")
            println(RegionForensics.dumpFor(region.id.toString()))
            println("---- END HISTORY ----")
        }
    }
}

/**
 * Region store: spatially-partitioned, RAM-bounded, persistently-backed canvas data.
 *
 * ## Concurrency model (Phase 3 redesign — the authoritative contract)
 *
 * **Single lock.** All residency decisions (install, evict, demote, promote)
 * happen under [stateLock]. The lock is never held across suspension or disk
 * IO; loads and saves run on worker coroutines and publish results back under
 * the lock. There is exactly one lock, so there is no lock-ordering surface.
 *
 * **Single authority (G1).** At most one live [RegionData] per [RegionId]
 * exists at any time. Every install is a locked compare-and-install; loaders
 * prefer any discovered live instance over their own disk bytes. Lineage
 * forks are structurally impossible.
 *
 * **Non-destructive eviction.** Eviction ([RegionCache.put] → [demote]) only
 * removes an instance from its residency map. A region held by a caller stays
 * frozen-but-valid forever; GC reclaims it when references drop. Nothing is
 * ever cleared in place — there are no "recycled husks" by construction.
 *
 * **Oversized regions** (larger than the cache budget) are UNEVICTABLE and do
 * not count against the budget: they stay resident as the single authority.
 * Total footprint = budget + Σ(oversized regions) — bounded by how many
 * pathologically large regions exist, which real-device RAM absorbs.
 *
 * **Copy-on-write reads (G2).** [RegionData.items] is published immutably;
 * readers may hold a reference to it forever with no coordination. Spatial
 * queries take the lock briefly and copy results out.
 *
 * **Durability (G5).** Dirty regions are persisted before their limbo entry
 * is dropped (save-before-drop). Loads await in-flight saves for the same id,
 * so stale bytes are never read. A save clears `dirty` only if `modCount`
 * is unchanged since its snapshot was captured (snapshot/mod pairing).
 *
 * **Exactly-once writes (G4).** Mutations run under the lock against a
 * guaranteed-resident instance; no acquire-then-recheck means no retry could
 * ever re-apply a committed item.
 *
 * ## Forensics
 * All instrumentation ([RegionForensicsRing], audits, invariant checks,
 * churn probes) is disabled unless [forensicsSetEnabled]`(true)` is called —
 * zero production cost, full diagnostics in tests/debug.
 */
@Suppress("TooManyFunctions")
class RegionManager(
    private val storage: RegionStorage,
    val regionSize: Float,
    private val memoryLimitBytes: Long =
        (
            Runtime.getRuntime().maxMemory() *
                com.alexdremov.notate.config.CanvasConfig.REGIONS_CACHE_MEMORY_PERCENT
        ).toLong(),
) {
    private val forensics = RegionForensicsRing()

    /**
     * Zero-cost-when-disabled logging. [RegionForensicsRing.log] checks the
     * flag itself, but a plain `logForensics { "..." }` call site builds its
     * message string EAGERLY — on hot paths (every acquire/release/mutate)
     * that meant allocations and identityHashCode calls in production. The
     * inline lambda is only evaluated when forensics are on.
     *
     * Use this for per-operation sites; cold IO-bound sites (one string per
     * disk load/save) may call [forensics.log] directly.
     */
    private inline fun logForensics(line: () -> String) {
        if (forensics.enabled) forensics.log(line())
    }

    /** Churn-level logging — see [RegionForensicsRing.verbose]. */
    private inline fun logVerbose(line: () -> String) {
        if (forensics.enabled && forensics.verbose) forensics.log(line())
    }

    /** Stable instance identity for forensics: distinguishes same-id twins. */
    private fun iid(r: RegionData?): String = if (r == null) "null" else Integer.toHexString(System.identityHashCode(r))

    private val RELEASE_SWEEP_INTERVAL_MS = 250L

    private val regionCache: RegionCache

    private val thumbnailCache =
        object : LruCache<RegionId, Bitmap>(20 * 1024) {
            override fun sizeOf(
                key: RegionId,
                value: Bitmap,
            ): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

    private val regionIndex: MutableMap<RegionId, RectF>

    @Volatile
    private var cachedActiveIds: Set<RegionId> = emptySet()

    @Volatile
    private var cachedContentBounds: RectF = RectF()

    private var skeletonQuadtree = Quadtree(0, RectF(-regionSize, -regionSize, regionSize, regionSize))
    private val regionProxies = HashMap<RegionId, RegionProxy>()

    private var pinnedIds: Set<RegionId> = emptySet()

    private val overflowRegions = java.util.LinkedHashMap<RegionId, RegionData>()
    private val maxOverflowBytes = memoryLimitBytes / 2
    private var currentOverflowBytes = 0L

    /**
     * Overflow byte accounting is DERIVED, never incremental: sizes change
     * under mutation ([RegionData.invalidateSize] recomputes them between an
     * add and a subtract), and every incremental scheme drifted negative —
     * permanently breaking the pinned re-home budget check (proven:
     * cur=-35272 in chaos forensics). Map truth is the single source.
     * MUST hold stateLock write.
     */
    private fun recomputeOverflowBytes() {
        currentOverflowBytes = overflowRegions.values.sumOf { it.getSizeCached() }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onRegionLoaded: ((RegionData) -> Unit)? = null

    // CRITICAL: this MUST be thread-local. A shared field was overwritten by
    // concurrent mutators on other threads, so a detach-remove fired its
    // eviction callback unsuppressed, dropped the instance's cache-ownership
    // ref mid-mutation, and drove the refcount negative — later retain()s
    // failed, loaders classified live regions as "recycled anomalies" and
    // installed empty twins over them (proven by forensics: count=-2,
    // recycled=false at INSTALL-PURGE). Write-locked mutations are
    // non-suspending, so at most one detach is active per thread.

    private val loadingJobs = ConcurrentHashMap<RegionId, Deferred<RegionData>>()

    /*
     * Regions whose persisted bytes are IN FLIGHT: demoted, dirty, and queued
     * for an asynchronous save that has not completed yet.
     *
     * This closes a real data-loss race: without it, a reload of a just-evicted
     * region could hit disk BEFORE the async save lands, conclude "file missing
     * -> region never existed" and call removeRegionIndex() — permanently
     * dropping live content from the spatial index.
     */
    private val pendingSaveIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<RegionId, Boolean>())

    /**
     * Regions whose newer content arrived while a save was in flight, keyed
     * by id -> the REQUESTING INSTANCE (identity). The running save may only
     * drain registrations for ITS OWN instance: with transient twins (an
     * orphan-with-readers plus a fresh load), draining another instance's
     * request re-snapshots THE RUNNER'S (older) items and regresses disk
     * past the twin's erase (proven: swizzle EXTRA=[35] on disk).
     */
    private val resaveNeeded =
        ConcurrentHashMap<RegionId, java.util.concurrent.ConcurrentHashMap<RegionData, Boolean>>()

    /*
     * Monotonic index version, bumped on every in-memory index mutation (under
     * the write lock). Persisting the index is last-writer-wins by nature; this
     * counter lets a flush DETECT that its snapshot went stale before it clobbers
     * a newer one on disk (two concurrent saveAll calls used to be able to write
     * their snapshots out of order — caught by RenderPipelineChaosTest).
     */
    private val indexVersion =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /*
     * LIMBO (Chromium-CC-style tile retirement):
     * Demoted (evicted) regions with zero references are parked here instead of
     * being recycled eagerly. A concurrent reader can still "rescue" the exact
     * live instance from limbo (retain + re-insert into cache); otherwise a
     * sweep persists-and-recycles it. This removes the acquire-vs-finalize race
     * entirely: there is never a window where a valid instance is unreachable
     * yet unrecyclable.
     */
    // LIMBO MULTISET: several live instances of one id can transiently exist
    // (an orphan-with-readers plus a freshly-loaded twin). A plain map let a
    // stale twin's park STEAL the slot from the parked live instance, which
    // then became unreachable and lost its mutations (proven: limbo slot
    // stolen mid-save; rescue resurrected the stale twin as authority).
    // Every unreferenced live instance stays listed until swept or rescued.
    private val limbo = java.util.concurrent.ConcurrentHashMap<RegionId, java.util.concurrent.CopyOnWriteArrayList<RegionData>>()

    /**
     * PER-ID write generations for loader seqlocks.
     *
     * Reading a region file while ITS save lands produces provably-stale
     * bytes (the old code detected only saves overlapping the pendingSaveIds
     * CHECK — a whole save fitting between the file READ and that check went
     * unnoticed, and the stale twin became the resident authority, permanently
     * diverging memory from disk; proven via forensics: LOAD-INSTALL items=15
     * over a just-written 1-item file). A reader pairs THIS REGION's
     * generation before/after its read; a mismatch forces a re-read.
     *
     * Per-id (not global): under a multi-region save storm a GLOBAL counter
     * moves during practically every read, starving loads with pointless
     * re-reads (proven: IO-storm perf test saturated protobuf decode).
     */
    private val diskWriteGens =
        java.util.concurrent.ConcurrentHashMap<RegionId, java.util.concurrent.atomic.AtomicLong>()

    private fun diskGenOf(id: RegionId): Long = diskWriteGens[id]?.get() ?: 0L

    private fun bumpDiskGen(id: RegionId) {
        diskWriteGens
            .computeIfAbsent(id) {
                java.util.concurrent.atomic
                    .AtomicLong()
            }.incrementAndGet()
    }

    private val stateLock = ReentrantReadWriteLock()

    companion object {
        private const val LIMBO_STICKY_MS = 5_000L

        /**
         * Max time a reload waits for an in-flight eviction save
         * (see [pendingSaveIds]). Deliberately SHORT: under autosave storms a
         * new save grabs the gate every few ms, so a waiter that tries to
         * catch a free window can wait FOREVER in effect — serialized 5s gate
         * waits produced 59s pan freezes (proven by the pan-sweep perf test).
         * Correctness does not depend on this wait at all: [stableLoadFromDisk]
         * re-reads whenever a save lands across the read window (per-id seqlock).
         * The wait is only an optimization to avoid reading bytes we would
         * have to re-read once.
         */
        private const val PENDING_SAVE_WAIT_MS = 250L

        /** Limbo size that triggers a sweep (see [parkInLimbo]). */
        private const val LIMBO_SWEEP_THRESHOLD = 16
    }

    // Diagnostics below are INSTANCE-scoped deliberately: they were once static,
    // which made two managers in one JVM (split screen, tests) collide on the
    // same (x, y) keys and retain each other's closed-session regions.

    /** Diagnostics: rescue frequency per region (reload-churn detector). */
    private val rescueCount = java.util.concurrent.ConcurrentHashMap<RegionId, kotlin.Long>()

    /**
     * Diagnostics only: last-known live instance per id. Nothing reads this for
     * correctness any more (single-authority installs under [stateLock] replaced
     * lineage consultation); it exists so forensics dumps can identify forked
     * instances. Cleared by [clear].
     */
    private val liveLineage = java.util.concurrent.ConcurrentHashMap<RegionId, RegionData>()

    /** Diagnostics: failed caller-side handoffs per region. */
    private val loadFailCount = java.util.concurrent.ConcurrentHashMap<RegionId, kotlin.Long>()

    /** Diagnostics: disk-load frequency per region. */
    private val loadCount = java.util.concurrent.ConcurrentHashMap<RegionId, kotlin.Long>()

    /**
     * Bumped by [clear]. Scheduled saves capture it when their coroutine starts
     * executing; a mismatch means the content was DISCARDED while the job sat
     * queued, and writing it back would resurrect cleared data on the next open.
     * Only the FIRST write round is gated: subsequent rounds drain explicit
     * resave registrations (e.g. a flush that found our gate held) and always run.
     */
    private val clearEpoch =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private class RegionProxy(
        val id: RegionId,
        override val bounds: RectF,
        override val zIndex: Float = 0f,
        override val order: Long = 0,
    ) : CanvasItem {
        override fun distanceToPoint(
            x: Float,
            y: Float,
        ): Float = if (bounds.contains(x, y)) 0f else Float.MAX_VALUE
    }

    init {
        regionIndex =
            storage
                .loadIndex()
                .mapValues { (_, rect) -> RectF(rect) }
                .toMutableMap()

        if (regionIndex.isEmpty()) {
            rebuildIndex()
        }

        rebuildSkeletonQuadtree()
        updateMetadataCache()

        regionCache = RegionCache(memoryLimitBytes)

        PerformanceProfiler.registerMemoryStats(
            "RegionManager",
            object : PerformanceProfiler.MemoryStatsProvider {
                override fun getStats(): Map<String, String> =
                    mapOf(
                        "Region Cache (MB)" to
                            "${stateLock.read { regionCache.bytes / 1024 }} / ${stateLock.read { regionCache.maxBytes / 1024 }}",
                        "Index" to "${stateLock.read { regionIndex.size }}",
                        "Loading Jobs" to "${loadingJobs.size}",
                    )
            },
        )
    }

    /**
     * Temporarily detaches [region] from the LRU while [block] mutates it, so
     * intermediate size spikes don't fire eviction callbacks mid-mutation,
     * then re-inserts it. MUST be called while holding [stateLock] write lock.
     *
     * STALENESS GUARD: if the resident instance for [id] is NOT [region], the
     * acquired instance went stale (it was evicted and a fresher instance was
     * loaded while we waited for the lock). Mutating + reinstalling the stale
     * instance would silently DISCARD the newer content and let older bytes
     * win persistence. In that case [block] is not run, the resident stays
     * untouched, and false is returned — callers must retry the whole
     * acquire→mutate cycle.
     */
    private inline fun <T> mutateDetachedFromCache(
        id: RegionId,
        region: RegionData,
        block: () -> T,
    ): T? {
        val current = regionCache.get(id)
        val ovf = overflowRegions[id]
        // STALENESS GUARD (essential — dropping it lets two threads ping-pong
        // stale instances forever): proceed ONLY if no FRESHER instance is
        // resident anywhere. Our own absence from the maps is NOT stale:
        // oversized regions (bigger than the whole budget) can never stay
        // resident — they bounce between limbo and transient resurrection,
        // and requiring residency here caused an unbounded retry livelock
        // (proven via jstack: endless deserialize/GC spin). Recycling cannot
        // race us: tryRecycle needs 0 refs and we hold one.
        if (current != null && current !== region) return null
        if (ovf != null && ovf !== region) return null
        val fromOverflow = ovf === region
        logVerbose {
            "MUT-BEGIN id=$id i=${iid(region)} items=${region.items.size} current=${iid(current)} " +
                "same=${current === region} fromOverflow=$fromOverflow"
        }
        if (current != null) {
            // Detach from the cache WITHOUT dropping the slot ref: removal is
            // plain (no callbacks in the explicit LRU), so the slot stays held
            // by us across the mutation and the re-insert below re-attaches it.
            regionCache.remove(id)
        }
        var result: T? = null
        try {
            result = block()
        } finally {
            when {
                region.isEvicted -> {
                    // Ownership was already dropped by a legitimate eviction
                    // during the detach window. Re-inserting would create a
                    // RESIDENT instance with NO slot ref; the next eviction
                    // callback would over-release and corrupt the count
                    // negative (proven: INSTALL-PURGE count=-2 → empty twin
                    // installed over live content).
                    //
                    // If our instance is the PARKED LIVE COPY (self-evicted
                    // oversized region: bigger than the whole budget it can
                    // never stay cache-resident), the block already mutated it
                    // and the parked copy IS the lineage — limbo-lineage rules
                    // hand it to the next acquirer and saveAll persists it.
                    // Treating this as stale would retry forever (proven:
                    // 100× ZERO-RESIDENT + unbounded ADD-STALE loop).
                    // The block ran on the live lineage copy (the guard
                    // proved no fresher instance is resident). Nulling here
                    // would retry forever on oversized regions (proven
                    // livelock).
                    //
                    // BUT success requires DISCOVERABILITY: if this copy sits
                    // in NO map, the next concurrent loader sees "nothing
                    // resident" and installs STALE disk bytes as a new lineage
                    // — the mutated copy is orphaned and its items lost
                    // (proven: ADD items=2 inLimbo=false → LOAD-INSTALL
                    // items=1 17ms later → middle strokes vanish). Re-park.
                    val discoverable =
                        regionCache.get(id) === region ||
                            overflowRegions[id] === region ||
                            (limbo[id]?.any { it === region } == true)
                    if (!discoverable && !region.isRecycled) {
                        region.touch()
                        parkInLimbo(id, region)
                        logForensics {
                            "MUT-REPARK id=$id i=${iid(region)} items=${region.items.size} dirty=${region.isDirty}"
                        }
                    } else {
                        logForensics {
                            "MUT-APPLIED-IN-EVICTED id=$id i=${iid(region)} items=${region.items.size} " +
                                "inLimbo=${limbo[id]?.any { it === region } == true}"
                        }
                    }
                }

                fromOverflow -> {
                    // Transfer residency overflow→cache: the old overflow slot
                    // IS the new cache slot (count-neutral). Evictees trimmed
                    // by this put MUST be demoted — dropping them silently
                    // strands dirty content in NO map where saveAll can never
                    // reach it (proven: RenderPipelineChaos disk divergence).
                    overflowRegions.remove(id)
                    recomputeOverflowBytes()
                    regionCache
                        .put(id, region)
                        .forEach { demote(it) }
                }

                else -> {
                    regionCache
                        .put(id, region)
                        .forEach { demote(it) } // slot continues across detach
                    // SELF-EVICT DURING RE-PUT (oversized region > whole
                    // budget): the suppression covered only the detach-remove;
                    // THIS put's trimToSize fired entryRemoved unsuppressed,
                    // consuming the slot (count now = reader refs only) and
                    // leaving us in NO map — the undiscoverable-copy fork
                    // condition (proven: OVER-RELEASE 0 -> -1 via entryRemoved
                    // <- trimToSize <- put <- getRegion). Ensure the mutated
                    // copy stays discoverable; do NOT re-grant (entryRemoved
                    // already balanced the books for the demotion).
                    if (regionCache.get(id) !== region && overflowRegions[id] !== region) {
                        if ((limbo[id]?.none { it === region } == true) && !region.isRecycled) {
                            region.touch()
                            parkInLimbo(id, region)
                        }
                        logForensics {
                            "MUT-SELF-EVICT-REPUT id=$id i=${iid(region)} items=${region.items.size} " +
                                "inLimbo=${limbo[id]?.any { it === region } == true}"
                        }
                    }
                }
            }
            logVerbose { "MUT-END id=$id i=${iid(region)} items=${region.items.size}" }
        }
        return result
    }

    private fun scheduleSave(region: RegionData) {
        // Acquire the per-id save gate BEFORE launching. If another save is
        // already in flight for this region we must NOT skip silently: that
        // save carries an OLDER item snapshot, and ours would never reach
        // disk (the sweeper would drop a still-dirty copy). Register a resave;
        // the running save picks it up before releasing the gate.
        if (!pendingSaveIds.add(region.id)) {
            logForensics {
                "SAVE-GATE-BUSY id=${region.id} i=${iid(region)} — registering resave, not launching"
            }
            resaveNeeded
                .computeIfAbsent(region.id) { java.util.concurrent.ConcurrentHashMap() }[region] = true
            return
        }
        logForensics { "SAVE-GATE-ACQUIRED id=${region.id} i=${iid(region)}" }
        // Snapshot items EAGERLY: the region may be mutated while this job sits
        // queued on IO. Forensics caught an in-flight save serializing stale or
        // cleared content (PARK items=3 → SAVE-PRE items=0 → LOAD-RAW items=0).
        // Capture modCount BEFORE copying items: a mutation landing between
        // the two bumps modCount above our baseline, so the dirty-clear guard
        // keeps the region dirty (safe). The reverse order cleared dirty for
        // snapshots missing the newest stroke — THE lost-stroke bug (proven:
        // memory==disk converge on bytes lacking exactly one order).
        var snapshotMod = region.modCount
        var snapshot = region.items.toMutableList()
        scope.launch(Dispatchers.IO) {
            // Read at EXECUTION start: [clear] bumps the epoch when it discards
            // content; a job that was queued across a clear must not write its
            // pre-clear bytes back (resurrection on next open). Later rounds in
            // this loop drain explicit resave registrations and always run.
            val epoch = clearEpoch.get()
            logForensics {
                "SAVE-JOB-START id=${region.id} i=${iid(region)} epochOk=${epoch == clearEpoch.get()} snapshotItems=${snapshot.size}"
            }
            var firstRound = true
            var rounds = 0
            try {
                while (true) {
                    // Skip the write itself when the job was queued across a
                    // clear — its content was discarded.
                    if (!firstRound || epoch == clearEpoch.get()) {
                        saveRegionInternal(region, snapshot, snapshotMod) {
                            // Re-check at EACH attempt: clear() may have fired
                            // while this job sat between rounds or mid-queue;
                            // writing after the wipe would recreate a file
                            // that clearAndWipeStorage just removed.
                            clearEpoch.get() == epoch
                        }
                    }
                    firstRound = false
                    // Still holding the gate: drain registrations FOR THIS
                    // INSTANCE only. Registrations from other (transient twin)
                    // instances are left queued — their own scheduleSave/sweep
                    // retry persists THEIR content; draining them here would
                    // re-snapshot THIS (possibly older) instance and regress
                    // the file past the twin's mutations.
                    val myResaves = resaveNeeded[region.id]
                    val needsResave = myResaves?.remove(region) == true
                    if (!needsResave) break
                    if (++rounds > 8 || region.isRecycled) break
                    // Instance verified not recycled: live items are valid.
                    // Re-pair mod with the NEW snapshot (mod first!).
                    val m = region.modCount
                    snapshot = region.items.toMutableList()
                    snapshotMod = m
                }
            } finally {
                pendingSaveIds.remove(region.id)
                logForensics {
                    "SAVE-JOB-END id=${region.id} i=${iid(region)} dirtyNow=${region.isDirty} modNow=${region.modCount}"
                }
                // The save may have been the last thing keeping this limbo
                // region un-recyclable — give the sweeper a chance to dispose it.
                if (limbo.isNotEmpty()) sweepLimbo()
            }
        }
    }

    /**
     * Parks a fully-unreferenced, evicted region in limbo and schedules its
     * persistence. Recycling happens later in [sweepLimbo], or never if a
     * reader rescues the instance first.
     */
    /**
     * Resurrect/install a slot-granted copy and keep it DISCOVERABLE even when
     * the put immediately self-evicts (region bigger than the whole budget).
     *
     * Without this, an oversized resurrected copy ends up in NO map (cache put
     * evicted it, reader ref blocked the limbo park) — invisible to loaders,
     * which then create a SECOND instance from disk. Two live copies both
     * claiming lineage = forked lineage = lost mutations (proven by
     * RefcountChaosTest "hot region lost mutations").
     */

    /**
     * Explicit byte-budgeted LRU (Phase 3 of the RegionStore redesign).
     * CONFINED TO [stateLock] — no internal synchronization. Unlike
     * android.util.LruCache, insertion NEVER fires callbacks: [put] returns
     * evicted entries (possibly including the inserted entry itself when it
     * exceeds the whole budget) and the CALLER drives demotion via [demote].
     * This deletes the reentrancy class of bugs: no callbacks firing mid-put,
     * no suppression ThreadLocal, no detach-vs-trim races.
     */
    private inner class RegionCache(
        val maxBytes: Long,
    ) {
        // Internal monitor REQUIRED despite stateLock confinement: get() is
        // called under stateLock.READ (shared), and an access-order
        // LinkedHashMap mutates its linked list on EVERY get — concurrent gets
        // would corrupt the chain and silently drop entries (proven: pinned
        // region vanished mid-test). The cacheLock is a leaf: nothing acquires
        // stateLock while holding it.
        private val lock = Any()
        private val map =
            object : LinkedHashMap<RegionId, RegionData>(16, 0.75f, true) {}
        var bytes = 0L
            private set

        /** Bytes of OVERSIZED entries (see [put]) — excluded from the budget. */
        private var oversizedBytes = 0L

        private fun isOversized(r: RegionData) = r.getSizeCached() > maxBytes

        fun get(id: RegionId): RegionData? = synchronized(lock) { map[id] }

        fun remove(id: RegionId): RegionData? =
            synchronized(lock) {
                val old = map.remove(id) ?: return null
                val sz = old.getSizeCached()
                bytes -= sz
                if (sz > maxBytes) oversizedBytes -= sz
                return old
            }

        /**
         * Inserts (refreshing recency), then evicts eldest until under budget.
         *
         * OVERSIZED REGIONS (larger than [maxBytes]) are UNEVICTABLE and do
         * NOT count against the budget: a region that can never fit must stay
         * resident — real devices have RAM for it, and non-residency meant
         * constant disk reloads with fork-prone lineage swaps (proven: the
         * 211KB region in RenderPipelineChaos churned PARK/SWEEP-DROP/reload
         * dozens of times per second and ghosted items). The budget therefore
         * bounds NORMAL regions; total footprint = budget + Σ oversized.
         *
         * Returns evicted entries for the caller to demote.
         */
        fun put(
            id: RegionId,
            region: RegionData,
        ): List<RegionData> =
            synchronized(lock) {
                val old = map.put(id, region)
                val sz = region.getSizeCached()
                bytes += sz - (old?.getSizeCached() ?: 0L)
                if (isOversized(region)) oversizedBytes += sz
                if (old != null && isOversized(old)) oversizedBytes -= old.getSizeCached()
                val evicted = ArrayList<RegionData>()
                val it2 = map.entries.iterator()
                while (bytes - oversizedBytes > maxBytes && it2.hasNext()) {
                    val e = it2.next()
                    if (isOversized(e.value)) continue // unevictable, not counted
                    it2.remove()
                    bytes -= e.value.getSizeCached()
                    evicted.add(e.value)
                }
                return evicted
            }

        fun evictAll(): List<RegionData> =
            synchronized(lock) {
                val all = map.values.toList()
                map.clear()
                bytes = 0
                oversizedBytes = 0
                return all
            }

        fun keySetSnapshot(): Set<RegionId> = synchronized(lock) { map.keys.toSet() }
    }

    /**
     * THE single demotion path (replaces LruCache.entryRemoved). Demotion,
     * NOT destruction: the cache drops its claim; zero-reference demotees go
     * to limbo so a racing reader can rescue the live instance; sweeps
     * persist + drop them. Pinned regions are re-homed into overflow instead
     * (their ownership transfers with them). MUST hold stateLock.
     */
    private fun demote(evictee: RegionData) {
        val wasPinned = pinnedIds.contains(evictee.id)
        if (wasPinned &&
            handleEviction(evictee.id, evictee)
        ) {
            logForensics {
                "DEMOTE-PINNED-HOMED id=${evictee.id} i=${iid(evictee)} items=${evictee.items.size}"
            }
            return // ownership transferred to the overflow map
        }
        logForensics {
            "DEMOTE id=${evictee.id} i=${iid(evictee)} items=${evictee.items.size} " +
                "dirty=${evictee.isDirty} wasPinned=$wasPinned"
        }
        evictee.markEvicted()
        evictee.releaseOwnership()
        // Park UNCONDITIONALLY (refcounts are diagnostic-only): gating on the
        // consume result orphaned instances with outstanding readers, where a
        // racing loader installed stale disk bytes over them (proven fork).
        // Parked copies double as an L2: a rescued clean copy avoids a disk
        // round-trip even when residency was lost to budget pressure. The
        // multiset limbo tolerates concurrent entries per id.
        parkInLimbo(evictee.id, evictee)
    }

    private fun putResidentAndParkIfSelfEvicted(
        id: RegionId,
        region: RegionData,
    ) {
        val wasEvicted = region.isEvicted
        // NOTE: evictees include `region` itself on self-eviction; demote()
        // handles it uniformly (pinned → overflow re-home, else limbo) —
        // exactly what entryRemoved used to do.
        regionCache.put(id, region).forEach { demote(it) }
        if (region.isRecycled || regionCache.get(id) === region) {
            logForensics {
                "PUTRES-RESIDENT id=$id i=${iid(region)} items=${region.items.size} wasEvicted=$wasEvicted"
            }
            return
        }
        // Self-evicted. entryRemoved already ran: for a fresh install it
        // consumed the slot AND parked the copy (releaseOwnership hit 0);
        // for a rescue-resurrection the reader ref blocked the auto-park.
        // NEVER release here ourselves — that double-consumed the slot and
        // drove counts NEGATIVE FROM BIRTH (proven: HANDOFF-FAIL refs=-1,
        // 128K reloads of one region).
        if (!region.isEvicted) region.markEvicted()
        region.touch() // sticky-limbo: protect until a caller claims it
        // A self-evicted copy in NO map is the fork condition; limbo listing
        // also keeps it servable (L2) until swept or rescued.
        logForensics {
            "PUTRES-SELF-EVICT id=$id i=${iid(region)} items=${region.items.size} dirty=${region.isDirty} — parking"
        }
        parkInLimbo(id, region)
    }

    private fun parkInLimbo(
        id: RegionId,
        region: RegionData,
    ) {
        if (region.isRecycled) {
            logForensics { "!!!! PARK-RECYCLED id=$id — parking a RECYCLED instance!" }
        }
        // computeIfAbsent, NOT getOrPut: getOrPut's check-then-put races two
        // concurrent parks of one id into SEPARATE buckets, and the losing
        // bucket's entries vanish from discoverability entirely (silent
        // orphaning — proven via forensics: parked-dirty copies disappeared
        // with zero removal events; UserSession missing-word class).
        val bucket = limbo.computeIfAbsent(id) { java.util.concurrent.CopyOnWriteArrayList() }
        if (bucket.none { it === region }) bucket.add(region)
        if (forensics.enabled && limbo.size % 5 == 0) {
            forensics.log(
                "PARKMAP-SNAPSHOT map=${System.identityHashCode(limbo)} size=${limbo.size} " +
                    "mgr=${System.identityHashCode(this@RegionManager)}",
            )
        }
        logForensics {
            "PARK id=$id i=${iid(region)} items=${region.items.size} dirty=${region.isDirty} " +
                "mod=${region.modCount} gen=${region.generation} bucket=${bucket.size}"
        }
        if (region.isDirty) {
            scheduleSave(region)
        }
        if (limbo.size > LIMBO_SWEEP_THRESHOLD) scheduleSweep()
    }

    /**
     * Freshest live parked instance for [id]: mutated (dirty) beats clean,
     * then highest mutation counter (content truth — [RegionData.modCount]
     * advances with every published mutation, while lastTouchMs tracks
     * scheduling luck: a stale twin merely TOUCHED later must never outrank
     * a copy whose content is newer — proven CI loss: stroke invisible in
     * memory because the freshest copy went clean after its save and lost
     * the recency race against an older sibling). Rescue, LIMBO-PREFER and
     * lineage adoption MUST all select through this so a stale sibling can
     * never outrank the live lineage.
     */
    private fun freshestInLimbo(id: RegionId): RegionData? {
        val bucket = limbo[id] ?: return null
        var best: RegionData? = null
        for (candidate in bucket) {
            if (candidate.isRecycled) continue
            val b = best
            best =
                when {
                    b == null -> {
                        candidate
                    }

                    candidate.isDirty != b.isDirty -> {
                        if (candidate.isDirty) candidate else b
                    }

                    candidate.modCount != b.modCount -> {
                        if (candidate.modCount > b.modCount) candidate else b
                    }

                    else -> {
                        if (candidate.lastTouchMs > b.lastTouchMs) candidate else b
                    }
                }
        }
        return best
    }

    /** Removes exactly [region] from [id]'s limbo bucket (identity-based). */
    private fun removeFromLimbo(
        id: RegionId,
        region: RegionData,
        via: String,
    ) {
        limbo[id]?.let { bucket ->
            val removed = bucket.removeIf { it === region }
            logForensics {
                "LIMBO-REMOVE id=$id i=${iid(region)} via=$via removed=$removed bucketLeft=${bucket.size}"
            }
            if (bucket.isEmpty()) {
                forensics.log("LIMBO-KEY-REMOVE id=$id via=$via")
                limbo.remove(id, bucket)
            }
        }
    }

    /**
     * Recycles limbo regions that nobody references and whose bytes are already
     * persisted (or that were clean). Regions mid-save stay parked until their
     * save lands (a later sweep re-checks).
     */
    private val lastReleaseSweepMs =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    /** At-most-one [sweepLimbo] per interval from the hot release path. */
    private fun maybeSweepLimbo() {
        val now = System.currentTimeMillis()
        val last = lastReleaseSweepMs.get()
        if (now - last >= RELEASE_SWEEP_INTERVAL_MS &&
            lastReleaseSweepMs.compareAndSet(last, now)
        ) {
            sweepLimbo()
        }
    }

    private val sweepScheduled =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Coalesced background [sweepLimbo]: at most one queued at a time, always
     * off the calling thread. Reader threads must never pay O(limbo) monitor
     * costs (measured: inline sweeping capped parallel read scaling at ~2x;
     * moving it here restored ~9x on 10 cores).
     */
    private fun scheduleSweep() {
        if (limbo.isEmpty()) return
        if (sweepScheduled.compareAndSet(false, true)) {
            scope.launch {
                try {
                    sweepLimbo()
                } finally {
                    sweepScheduled.set(false)
                }
            }
        }
    }

    private fun sweepLimbo() {
        // Serialized: scheduleSave completions re-enter this from IO threads
        // while other threads sweep from parkInLimbo/releaseRegion. Concurrent
        // sweeps racing on the same entry could recycle an instance another
        // sweep had already rescheduled for.
        synchronized(this) {
            val mapIt = limbo.entries.iterator()
            while (mapIt.hasNext()) {
                val entry = mapIt.next()
                val key = entry.key
                val bucket = entry.value
                // Bytes for this id still in flight — re-check next pass.
                if (pendingSaveIds.contains(key)) continue
                // Snapshot iteration; removal is atomic identity-based
                // (data-class equals must never decide which twin dies, and
                // rescue-promotion mutates buckets off the sweep monitor).
                for (region in bucket.toList()) {
                    // Atomic identity removal: rescue-promotion paths mutate
                    // this bucket WITHOUT the sweep monitor, so a snapshot
                    // index can go stale (proven AIOOBE). removeIf re-checks
                    // against the live list under COWAL's internal lock.
                    if (!bucket.removeIf { it === region }) {
                        continue // another actor removed it first
                    }
                    // NOTE: no hasReferences() gate — refcounts are diagnostic-
                    // only. A reader holding the instance keeps it valid
                    // regardless (non-destructive eviction).
                    if (!region.isDirty) {
                        // CLEAN copies must be dropped promptly: callers (e.g.
                        // EvictionStressTest) rely on prompt dispose-after-
                        // release, and a clean copy reloads from identical
                        // disk bytes anyway.
                        logForensics {
                            "SWEEP-DROP id=$key i=${iid(region)} items=${region.items.size} bucketLeft=${bucket.size}"
                        }
                        if (liveLineage[key] === region) {
                            logForensics { "LINEAGE-UNLINK id=$key old=${iid(liveLineage[key])} via=sweep-clean-drop" }
                            liveLineage.remove(key)
                        }
                        region.tryRecycle()
                        continue
                    }
                    // DIRTY copy — durability rule: it NEVER leaves limbo until
                    // its content is provably persisted (a failed save must not
                    // orphan newer-than-disk content). Stays parked; a save is
                    // (re)scheduled when none is in flight and the sticky
                    // window expired; touch() spaces persistent-failure retries
                    // ~LIMBO_STICKY_MS apart. At most ONE dirty entry per id can
                    // exist (single-authority mutations), so per-id save gates
                    // stay correct.
                    if (System.currentTimeMillis() - region.lastTouchMs < LIMBO_STICKY_MS) {
                        logForensics { "SWEEP-STICKY-SKIP id=$key i=${iid(region)} dirty stays parked" }
                        continue
                    }
                    logForensics { "SWEEP-SCHEDULE-SAVE id=$key i=${iid(region)} dirty ungated — retrying persistence" }
                    scheduleSave(region)
                    region.touch()
                }
                if (bucket.isEmpty()) mapIt.remove()
            }
        }
    }

    /**
     * Acquires a reader reference to a region, loading it from disk if needed.
     *
     * This is THE bulletproof read primitive: while the reference is held, the
     * region is guaranteed valid (never recycled), even if concurrent loads
     * evict it from the cache — eviction only demotes; destruction waits for
     * [releaseRegion].
     *
     * NOTE: residency is deliberately NOT required. A region larger than the
     * whole cache budget (huge region, low-memory device) is evicted the
     * instant it is inserted — but the retained reference keeps it fully valid
     * for the caller. Read paths process regions one at a time precisely so
     * this degenerate case still yields correct results.
     *
     * ALWAYS pair with [releaseRegion] (prefer try/finally).
     *
     * Never returns null: the underlying [getRegion] retries until a live
     * instance is produced (the old nullable declaration made every caller
     * carry a dead null-check).
     */
    suspend fun acquireRegion(id: RegionId): RegionData {
        // 1. Resident fast path (READ lock): cache, then overflow.
        var handoff: RegionData? = null
        var promote = false
        stateLock.read {
            regionCache.get(id)?.let { c ->
                if (!c.isRecycled && c.retain()) {
                    logVerbose { "RET-FAST id=$id i=${iid(c)}" }
                    handoff = c
                }
            }
            if (handoff == null) {
                overflowRegions[id]?.let { o ->
                    if (!o.isRecycled && o.retain()) {
                        logVerbose { "RET-OVF id=$id i=${iid(o)}" }
                        handoff = o
                        promote = true
                    }
                }
            }
        }
        val resident = handoff
        if (resident != null) {
            logVerbose { "LINEAGE-SET id=$id old=${iid(liveLineage[id])} new=${iid(resident)} via=getRegion-resident" }
            liveLineage[id] = resident
            if (promote) {
                // One write-locked promotion per eviction cycle: after this,
                // reads serve from cache until the region is evicted again.
                // (Borrow-without-promote was measured as a borrow-storm:
                // every read of a parked id repeated limbo scans.)
                stateLock.write {
                    overflowRegions.remove(id)
                    recomputeOverflowBytes()
                    regionCache.put(id, resident).forEach { demote(it) }
                }
            }
            resident.touch()
            return resident
        }

        // 2. Rescue from limbo: the freshest parked instance may be a mutated
        //    copy whose save is mid-flight — newer than disk BY CONSTRUCTION.
        //    Multiset-aware ([freshestInLimbo]) so a stale sibling can never
        //    outrank the live lineage (proven slot-steal fork). PEEK semantics:
        //    the copy stays listed while checked out; removal happens below
        //    ONLY when it becomes truly resident.
        val rescued = if (limbo.containsKey(id)) freshestInLimbo(id) else null
        if (rescued != null && !rescued.isRecycled && rescued.retain()) {
            logForensics { "ACQ-BORROW id=$id i=${iid(rescued)} items=${rescued.items.size} dirty=${rescued.isDirty}" }
            var useRescue = false
            stateLock.write {
                val existing = regionCache.get(id) ?: overflowRegions[id]
                if (existing == null || existing.isRecycled) {
                    rescued.retain() // residency grant (self-eviction cannot eat the reader ref)
                    putResidentAndParkIfSelfEvicted(id, rescued)
                    updateMetadataCache()
                    useRescue = true
                    if (regionCache.get(id) === rescued || overflowRegions[id] === rescued) {
                        removeFromLimbo(id, rescued, "rescue-promote")
                    }
                }
                // else: resident instance is authoritative; drop the stale
                // rescue below (releaseRegion re-parks it for the sweeper).
            }
            if (useRescue) {
                rescued.touch()
                logVerbose { "LINEAGE-SET id=$id old=${iid(liveLineage[id])} new=${iid(rescued)} via=acquire-rescue" }
                liveLineage[id] = rescued
                auditRegion(forensics, rescued, "RESCUE")
                return rescued
            }
            releaseRegion(rescued)
        }

        // 3. Nothing alive anywhere: deduped disk load installs the authority.
        return getRegion(id)
    }

    /**
     * Drops a reader reference acquired by [acquireRegion]. A zero-reference
     * evicted region simply remains in limbo until the next sweep.
     */
    fun releaseRegion(region: RegionData) {
        val last = region.release()
        logVerbose {
            "REL-REGION id=${region.id} i=${Integer.toHexString(System.identityHashCode(region))} c=${region.debugRefCount()} last=$last"
        }
        // A rescued-from-limbo region left the limbo map when acquired; if its
        // last reference just dropped while evicted, re-park it so the sweeper
        // can dispose it (otherwise it would leak un-recycled forever).
        if (last && region.isEvicted && region.isDirty &&
            (limbo[region.id]?.none { it === region } == true)
        ) {
            logForensics {
                "REL-REPARK id=${region.id} i=${iid(region)} — last ref on evicted DIRTY region, re-parking"
            }
            parkInLimbo(region.id, region)
        }
        scheduleSweep()
        // NOTE: limbo maintenance NEVER runs inline on the releasing thread —
        // it is O(limbo) under a global monitor, and readers paying that cost
        // capped parallel scaling at ~2x (measured). Instead the maintenance
        // is coalesced onto a background coroutine via [scheduleSweep]; the
        // parkInLimbo size threshold and scheduleSave completions still force
        // synchronous sweeps where a thread is already parked on IO anyway.
    }

    private fun updateMetadataCache() {
        cachedActiveIds = regionIndex.keys.toSet()
        val r = RectF()
        if (regionIndex.isNotEmpty()) {
            val it = regionIndex.values.iterator()
            if (it.hasNext()) r.set(it.next())
            while (it.hasNext()) r.union(it.next())
        }
        cachedContentBounds = r
    }

    private fun handleEviction(
        key: RegionId,
        region: RegionData,
    ): Boolean {
        if (region.isDirty || pinnedIds.contains(key)) {
            logForensics {
                "HANDLE-EVICT id=$key items=${region.items.size} dirty=${region.isDirty} " +
                    "pinned=${pinnedIds.contains(key)}"
            }
        }
        stateLock.write {
            if (pinnedIds.contains(key)) {
                val size = region.getSizeCached()

                // Derive occupancy from the MAP, not the counter: counter drift
                // (historically caused by unguarded removals) made this check
                // permanently fail, so pinned regions could never re-home and
                // were eventually swept from limbo after their save landed —
                // vanishing from memory. Map-truth self-heals any drift.
                fun overflowBytesNow() = overflowRegions.values.sumOf { it.getSizeCached() }
                while (overflowBytesNow() + size > maxOverflowBytes && overflowRegions.isNotEmpty()) {
                    val oldestKey = overflowRegions.keys.first()
                    val oldestRegion = overflowRegions.remove(oldestKey)
                    if (oldestRegion != null) {
                        // Demote, don't destroy: readers may still hold references.
                        // Same protocol as the LRU entryRemoved callback.
                        oldestRegion.markEvicted()
                        parkInLimbo(oldestKey, oldestRegion)
                        oldestRegion.releaseOwnership()
                        logForensics {
                            "REL-SLOT-OVF id=$oldestKey i=${
                                Integer.toHexString(
                                    System.identityHashCode(oldestRegion),
                                )
                            } c=${oldestRegion.debugRefCount()}"
                        }
                    }
                    recomputeOverflowBytes()
                }
                if (overflowBytesNow() + size <= maxOverflowBytes) {
                    // OVERWRITE DISCIPLINE: an entry may already exist under
                    // this key (a prior cycle's instance). Replacing it without
                    // consuming its grant leaked exactly one reference per
                    // overwrite — the phantom slot that later surfaced as
                    // sporadic OVER-RELEASEs (proven: REL-SLOT-OVF on c=0).
                    val displaced = overflowRegions.put(key, region)
                    if (displaced != null && displaced !== region) {
                        displaced.markEvicted()
                        logForensics {
                            "REL-SLOT-OVF-DISPLACED id=$key " +
                                "i=${Integer.toHexString(System.identityHashCode(displaced))} " +
                                "c=${displaced.debugRefCount()}"
                        }
                        if (displaced.releaseOwnership()) parkInLimbo(key, displaced)
                    }
                    recomputeOverflowBytes()
                    logForensics {
                        "OVERFLOW-ADD id=$key i=${iid(region)} ok=true size=$size cur=$currentOverflowBytes max=$maxOverflowBytes"
                    }
                } else {
                    logForensics {
                        "OVERFLOW-ADD id=$key i=${iid(
                            region,
                        )} ok=FALSE size=$size cur=$currentOverflowBytes max=$maxOverflowBytes — pinned re-home FAILED, falling to limbo"
                    }
                    return false
                }
                return true
            }
        }
        return false
    }

    /**
     * Persists one snapshot of [region] with stale-write protection.
     *
     * @param stillCurrent re-checked before EVERY write attempt; a `false`
     *   return aborts without touching disk (used to keep scheduled saves
     *   from recreating content that [clearAndWipeStorage] just removed).
     */
    private fun saveRegionInternal(
        region: RegionData,
        initialSnapshot: MutableList<CanvasItem>,
        initialMod: Long,
        stillCurrent: () -> Boolean = { true },
    ) {
        var snapshot = initialSnapshot
        // Baseline is the modCount AT SNAPSHOT TIME (not save start): the
        // guard must prove "disk bytes reflect all mutations up to now",
        // which fails if the snapshot predates a counted mutation.
        var modBaseline = initialMod
        var attempts = 0
        while (true) {
            try {
                if (!stillCurrent()) return
                // modCount guard: if content changes while we serialize/write,
                // the bytes we just wrote are already stale — do NOT clear
                // dirty, or the change is silently lost forever (the region
                // recycles as "clean" while disk holds older content).
                logForensics {
                    "SAVE-PRE id=${region.id} i=${iid(region)} items=${snapshot.size} dirty=${region.isDirty} " +
                        "mod=${region.modCount} gen=${region.generation} attempt=$attempts"
                }
                val wrote = storage.saveRegion(region.copy(items = snapshot))
                bumpDiskGen(region.id)
                if (!wrote) return
                if (region.modCount == modBaseline) {
                    region.isDirty = false
                    return
                }
                logForensics {
                    "SAVE-STALE id=${region.id} i=${iid(region)} baseline=$modBaseline modNow=${region.modCount}"
                }
                // Retry immediately with the newer content; after a bounded
                // number of attempts under continuous mutation, leave dirty —
                // the next park/saveAll will converge. Never re-snapshot a
                // recycled instance: its items were destructively cleared,
                // and saving that husk would regress disk to empty.
                if (region.isRecycled || ++attempts >= 3) return
                modBaseline = region.modCount
                snapshot = region.items.toMutableList()
            } catch (e: Exception) {
                Logger.e("RegionManager", "Exception saving region ${region.id}", e)
                return
            }
        }
    }

    private fun rebuildIndex() {
        val regionIds = storage.listStoredRegions()
        if (regionIds.isEmpty()) return
        var loadedCount = 0
        regionIds.forEach { id ->
            try {
                val region = storage.loadRegion(id)
                if (region != null) {
                    region.rebuildQuadtree(regionSize)
                    if (!region.contentBounds.isEmpty) {
                        regionIndex[id] = RectF(region.contentBounds)
                        loadedCount++
                    }
                }
            } catch (e: Exception) {
                Logger.e("RegionManager", "Failed to load region $id during index rebuild", e)
            }
        }
        if (loadedCount > 0) {
            storage.saveIndex(regionIndex)
            updateMetadataCache()
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? = storage.importImage(uri, context)

    /**
     * Returns the region with a HANDOFF reference: the caller owns exactly one
     * reference and must [releaseRegion] it (prefer try/finally).
     *
     * Why handoff-at-install: a region larger than the whole cache budget is
     * evicted the instant it is inserted, and the sweeper can recycle a clean
     * zero-ref instance within microseconds. Retaining AFTER `getRegion`
     * returns therefore races with recycling and can fail repeatedly (silent
     * data loss in mutators). Instead, the loader retains BEFORE installing
     * (see [loadRegionFromDisk]) and existing residents are retained under the
     * lock — the returned reference is valid by construction, no retries.
     */
    suspend fun getRegion(id: RegionId): RegionData {
        while (true) {
            // Fast path: resident in cache or overflow. Retention happens under
            // the READ lock (demotion runs under WRITE — mutually exclusive).
            // NOTE: never acquire the write lock while holding the read lock
            // (RW locks cannot upgrade → self-deadlock); promotion to cache is
            // done in a SEPARATE write-lock section below.
            var handoff: RegionData? = null
            var promote = false
            stateLock.read {
                regionCache.get(id)?.let { cached ->
                    if (!cached.isRecycled && cached.retain()) {
                        logForensics {
                            "RET-FAST id=$id i=${Integer.toHexString(System.identityHashCode(cached))} c=${cached.debugRefCount()}"
                        }
                        handoff = cached
                    }
                }
                if (handoff == null) {
                    overflowRegions[id]?.let { ov ->
                        if (!ov.isRecycled && ov.retain()) {
                            logForensics {
                                "RET-OVF id=$id i=${Integer.toHexString(System.identityHashCode(ov))} c=${ov.debugRefCount()}"
                            }
                            handoff = ov
                            promote = true
                        }
                    }
                }
            }
            val resident = handoff
            if (resident != null) {
                logVerbose { "LINEAGE-SET id=$id old=${iid(liveLineage[id])} new=${iid(resident)} via=getRegion-resident" }
                liveLineage[id] = resident
                if (promote) {
                    logForensics {
                        "PROMOTE-BEGIN id=$id i=${iid(
                            resident,
                        )} idxHas=${regionIndex.containsKey(id)} ovfIsSame=${overflowRegions[id] === resident}"
                    }
                    stateLock.write {
                        // Re-validate under the write lock: clear() may have
                        // wiped the index meanwhile. Even then the instance
                        // MUST NOT leave residency: removing it from overflow
                        // WITHOUT re-homing orphaned it into NO map, and the
                        // next loader installed stale disk bytes over its live
                        // content (proven fork: chaos 4_1 — erase lost from
                        // memory, divergence permanent because the stale twin
                        // was clean and never re-saved). Keep it in overflow;
                        // a later install/flush still finds and saves it.
                        if (regionIndex.containsKey(id)) {
                            // UNIFORM MODEL — slot TRANSFER overflow→cache:
                            // the instance's single slot simply changes maps.
                            overflowRegions.remove(id)
                            recomputeOverflowBytes()
                            regionCache
                                .put(id, resident)
                                .forEach { demote(it) }
                        } else {
                            logForensics {
                                "PROMOTE-KEEP-NOINDEX id=$id items=${resident.items.size}"
                            }
                        }
                    }
                }
                return resident
            }
            // Slow path: load from disk (deduplicated via loadingJobs).
            // NOTE: loadRegionFromDisk's install-time retain IS the caller
            // handoff — do NOT retain again here (that would leak a reference
            // per load: regions would never reach zero-refs, never be parked
            // or saved on eviction, and reloads would come back empty).
            val deferred =
                loadingJobs.computeIfAbsent(id) {
                    scope.async(Dispatchers.IO) { loadRegionFromDisk(id) }
                }
            // UNIFORM MODEL: the loader granted only the residency slot —
            // EVERY awaiter of the shared load job takes its own handoff here
            // (a single embedded handoff was consumed by whichever caller
            // released first, stranding 0-ref residents).
            val loaded = deferred.await()
            if (!loaded.isRecycled && loaded.retain()) {
                loaded.touch()
                logVerbose { "LINEAGE-SET id=$id old=${iid(liveLineage[id])} new=${iid(loaded)} via=getRegion-loaded" }
                liveLineage[id] = loaded
                logForensics {
                    "RET-HANDOFF id=$id i=${Integer.toHexString(System.identityHashCode(loaded))} c=${loaded.debugRefCount()}"
                }
                return loaded
            }
            if (loadFailCount.merge(id, 1L, Long::plus)!! % 10L == 0L && forensics.enabled) {
                println(
                    "!!!! HANDOFF-FAIL id=$id recycled=${loaded.isRecycled} " +
                        "refs=${loaded.debugRefCount()}",
                )
            }
            // Dead/recycled result: drop the job entry so the retry below
            // starts a FRESH load instead of awaiting the same dead result
            // forever (infinite-loop hazard under eviction storms).
            loadingJobs.remove(id, deferred)
            continue // recycled mid-flight: retry the whole cycle
        }
    }

    /**
     * Seqlock-guarded disk read (see [diskWriteGen]): re-reads until no disk
     * write overlapped the read window. Without this, an entire save could
     * fit between the file READ and the pendingSaveIds CHECK, handing the
     * caller provably-stale bytes that then became the resident authority.
     */
    private suspend fun stableLoadFromDisk(id: RegionId): RegionData? {
        repeat(200) { attempt ->
            val before = diskGenOf(id)
            val region = storage.loadRegion(id)
            val raced = before != diskGenOf(id)
            if (!raced) return region
            logForensics {
                "STABLE-READ-RETRY id=$id attempt=$attempt — write raced read (gen $before -> ${diskGenOf(id)})"
            }
            delay(1)
        }
        logForensics { "!!!! STABLE-READ-GIVEUP id=$id — accepting bytes after 200 unstable reads" }
        return storage.loadRegion(id)
    }

    private suspend fun loadRegionFromDisk(id: RegionId): RegionData {
        try {
            val n = loadCount.merge(id, 1L, Long::plus)!!
            logForensics { "LOAD-CHURN id=$id count=$n" }
            var region = stableLoadFromDisk(id)
            logForensics {
                "LOAD-RAW id=$id items=${region?.items?.size} type=${region?.items?.javaClass?.simpleName}"
            }
            if (pendingSaveIds.contains(id)) {
                // Bytes on disk may be STALE relative to an in-flight eviction
                // save — the file usually EXISTS from an earlier generation, so
                // "file missing" is the wrong trigger. Waiting for the save
                // gate is cheap and always correct: the landing write is at
                // least as new as anything we could read now. (Reading stale
                // bytes here installed an older twin as resident while the
                // newer save landed afterwards — memory regressed below disk
                // and the next save-from-memory made the loss permanent.)
                // If the gate is STILL held at the deadline we proceed with
                // possibly-stale bytes — same behaviour as before this fix.
                val waitStart = System.currentTimeMillis()
                val deadline = waitStart + PENDING_SAVE_WAIT_MS
                while (pendingSaveIds.contains(id) && System.currentTimeMillis() < deadline) {
                    delay(5)
                }
                val waited = System.currentTimeMillis() - waitStart
                if (waited > 100) {
                    if (forensics.enabled) {
                        println(
                            "!!!! GATE-WAIT id=$id waited=${waited}ms held=${pendingSaveIds.contains(id)}",
                        )
                    }
                }
                region = stableLoadFromDisk(id)
                logForensics { "LOAD-RETRY-AFTER-SAVE id=$id items=${region?.items?.size}" }
            }
            var missedOnDisk = false
            if (region == null) {
                logForensics { "LOAD-MISS id=$id -> empty region" }
                region = RegionData(id, CopyOnWriteArrayList())
                missedOnDisk = true
            } else {
                // Convert + rebuild AFTER all load attempts (including the
                // pending-save retry above — its result must be rebuilt too;
                // skipping that left loaded regions with quadtree=null, making
                // every item invisible to queries while still persisted to
                // disk — "ghost" items resurrected on next reload).
                if (region.items !is CopyOnWriteArrayList) {
                    region = region.copy(items = CopyOnWriteArrayList(region.items))
                }
                region.rebuildQuadtree(regionSize)
                // ABA token: content was (re)populated from disk under this id.
                region.bumpGeneration()
            }
            stateLock.write {
                val existingProbe = regionCache.get(id)
                logForensics {
                    "INSTALL-CHECK id=$id existing=${existingProbe?.items?.size} " +
                        "existingGen=${existingProbe?.generation} loaderItems=${region?.items?.size} " +
                        "ovf=${overflowRegions[id]?.items?.size} lineage=${iid(liveLineage[id])} " +
                        "limbo=${iid(freshestInLimbo(id))} loader=${iid(region)}"
                }
                val existing = existingProbe ?: overflowRegions[id]
                var useExisting = false
                if (existing != null) {
                    if (existing.retain()) {
                        // Someone installed an instance while we were loading.
                        // Discard our disk bytes; the caller takes its own
                        // reference (this liveness-check ref is released).
                        existing.release()
                        logForensics {
                            "REL-PROBE id=$id c=${existing.debugRefCount()}"
                        }
                        useExisting = true
                        logForensics { "INSTALL-HANDOFF id=$id items=${existing.items.size}" }
                    } else {
                        // Recycled anomaly: purge and install our fresh copy.
                        logForensics {
                            "!!!! INSTALL-PURGE id=$id items=${existing.items.size} " +
                                "dirty=${existing.isDirty} recycled=${existing.debugIsRecycled()} " +
                                "count=${existing.debugRefCount()}"
                        }
                        regionCache.remove(id)
                        overflowRegions.remove(id)
                        recomputeOverflowBytes()
                    }
                }
                if (useExisting) return existing!!

                // LINEAGE ADOPTION (fork prevention): the residency maps can
                // transiently hold NO instance while a LIVE one exists outside
                // them (rescue checkouts, orphan windows of the overflow↔cache
                // edges). Such an instance is newer than these disk bytes BY
                // CONSTRUCTION — it mutated after the last save. Adopt it as
                // the authority instead of installing a stale twin over it
                // (proven fork: chaos 4_1 — the stale twin was clean and never
                // re-saved, making memory/disk divergence permanent).
                val lineage = liveLineage[id]
                if (lineage == null || lineage === region || lineage.isRecycled) {
                    logForensics {
                        "ADOPT-SKIP id=$id reason=" +
                            (
                                if (lineage == null) {
                                    "no-lineage"
                                } else if (lineage === region) {
                                    "lineage==loader"
                                } else {
                                    "recycled"
                                }
                            ) +
                            " lineage=${iid(lineage)} loader=${iid(region)}"
                    }
                }
                if (lineage != null && lineage !== region && !lineage.isRecycled) {
                    putResidentAndParkIfSelfEvicted(id, lineage)
                    updateMetadataCache()
                    // Single-residency-location invariant (see acquireRegion).
                    if (regionCache.get(id) === lineage || overflowRegions[id] === lineage) {
                        limbo.remove(id)
                    }
                    if (!lineage.contentBounds.isEmpty) {
                        updateRegionIndex(id, RectF(lineage.contentBounds))
                    }
                    liveLineage[id] = lineage
                    logForensics {
                        "LOAD-ADOPT-LINEAGE id=$id i=${iid(lineage)} items=${lineage.items.size} " +
                            "dirty=${lineage.isDirty} diskHad=${region?.items?.size}"
                    }
                    auditRegion(forensics, lineage, "LOAD-ADOPT-LINEAGE")
                    return lineage
                }

                // Only NOW — when we are definitively installing an empty
                // region because NO live instance exists anywhere — may the
                // index entry be dropped. Removing it earlier (before the
                // existing/limbo checks) let a racing LOAD-MISS strip the
                // index of a live dirty region, making all its content
                // invisible to rect queries (caught by forensics).
                if (missedOnDisk) {
                    removeRegionIndex(id)
                    logForensics { "LOAD-MISS id=$id index removed" }
                }

                // LIMBO-LINEAGE RULE: a parked copy is the LIVE instance (it was
                // mutated after these disk bytes were written, and its save may
                // be mid-flight). Installing freshly-read disk bytes alongside
                // it forks the lineage: the stale copy becomes resident, queries
                // miss recent mutations, and a later save regresses the disk.
                // Prefer the parked copy and discard our disk bytes.
                val parked = freshestInLimbo(id)
                if (parked != null && !parked.isRecycled && parked.retain()) {
                    logForensics {
                        "RET-LIMBO id=$id i=${Integer.toHexString(System.identityHashCode(parked))} c=${parked.debugRefCount()}"
                    }
                    // This retain IS the residency slot (parked copies are
                    // slotless); caller-side handoff happens in getRegion.
                    putResidentAndParkIfSelfEvicted(id, parked)
                    updateMetadataCache()
                    // Single-residency-location invariant (see acquireRegion).
                    if (regionCache.get(id) === parked || overflowRegions[id] === parked) {
                        removeFromLimbo(id, parked, "limbo-prefer")
                    }
                    logForensics { "LOAD-PREFER-LIMBO id=$id items=${parked.items.size}" }
                    auditRegion(forensics, parked, "LOAD-PREFER-LIMBO")
                    return parked
                }

                // CRITICAL FIX: Ensure the global spatial index matches the actual loaded content.
                // If the stored index is stale (smaller than actual bounds), strokes extending
                // into neighbors won't be found by getRegionIdsInRect(), causing clipping/disappearance
                // when querying items across regions at different zoom levels.
                if (!region!!.contentBounds.isEmpty) {
                    val indexBounds = regionIndex[id]
                    if (indexBounds == null || indexBounds != region.contentBounds) {
                        Logger.i(
                            "RegionManager",
                            "Self-healing index for region $id: $indexBounds -> ${region.contentBounds}",
                        )
                        updateRegionIndex(id, region.contentBounds)
                    }
                }

                // UNIFORM OWNERSHIP MODEL: refCount = reader refs + exactly one
                // slot ref for residency (cache XOR overflow). The slot is
                // granted BY THE CONSTRUCTOR/DESERIALIZER (initial value 1) —
                // a fresh or freshly-loaded instance always carries exactly one
                // ref, which this install hands to the caller. entryRemoved
                // consumes it on demotion; a combined double-grant here
                // double-counted residency and leaked +1 per load (proven via
                // REFTRACE).
                // Slot came from the constructor/deserializer; NO handoff
                // grant here — N coroutines may await this same load job, and
                // EACH takes its own reference caller-side (proven: the single
                // embedded handoff let the 2nd..Nth awaiter's release consume
                // the residency slot → 0-ref resident → negative counts).
                putResidentAndParkIfSelfEvicted(id, region!!)
                liveLineage[id] = region!!
                logForensics {
                    "LOAD-INSTALL id=$id i=${iid(region)} items=${region.items.size} hadExisting=$useExisting"
                }
                auditRegion(forensics, region, "LOAD-INSTALL")

                // DISPLACED-LINEAGE RESTORE: our put may have just displaced a
                // live DIRTY copy (its demotion parks it into limbo — a park
                // that did not exist when we checked above). An empty disk-miss
                // twin must not outrank it: restore the displaced copy as
                // resident and let our bytes be parked instead.
                val displaced = freshestInLimbo(id)
                if (displaced != null && displaced !== region && !displaced.isRecycled &&
                    (missedOnDisk || displaced.isDirty) && displaced.retain()
                ) {
                    displaced.retain() // residency slot (parked copies are slotless)
                    putResidentAndParkIfSelfEvicted(id, displaced) // parks OUR copy via entryRemoved
                    updateMetadataCache()
                    removeFromLimbo(id, displaced, "restore-displaced")
                    // Our copy's slot was consumed by its own demotion; the
                    // caller takes their handoff on `displaced`.
                    logForensics {
                        "LOAD-RESTORE-DISPLACED id=$id items=${displaced.items.size}"
                    }
                    auditRegion(forensics, displaced, "LOAD-RESTORE-DISPLACED")
                    return displaced
                }
            }
            return region!!
        } finally {
            // Conditional remove by identity: clear() may have cancelled THIS
            // job while a newer load for the same id already occupies the slot
            // — evicting that newer entry would spawn duplicate concurrent
            // loads. Within scope.async { }, the context Job IS the Deferred.
            val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            @Suppress("UNCHECKED_CAST")
            if (self is Deferred<*>) {
                loadingJobs.remove(id, self as Deferred<RegionData>)
            } else {
                loadingJobs.remove(id)
            }
        }
    }

    suspend fun getRegionThumbnail(
        id: RegionId,
        context: android.content.Context,
    ): Bitmap? {
        thumbnailCache.get(id)?.let { return it }

        val fromDisk = storage.loadThumbnail(id)
        if (fromDisk != null) {
            thumbnailCache.put(id, fromDisk)
            return fromDisk
        }

        val rBounds = id.getBounds(regionSize)
        val overlappingIds = getRegionIdsInRect(rBounds)
        // Retain every contributing region so eviction can't recycle them while
        // the snapshot is being taken (the old cache-walk silently skipped
        // regions evicted by concurrent loads).
        val contributors = ArrayList<Pair<RegionId, RegionData>>(overlappingIds.size)
        try {
            for (oid in overlappingIds) {
                contributors.add(oid to acquireRegion(oid))
            }
            val itemsSnapshot = ArrayList<CanvasItem>()
            stateLock.read {
                contributors.forEach { (_, region) ->
                    region.items.forEach { item ->
                        if (RectF.intersects(item.bounds, rBounds)) itemsSnapshot.add(item)
                    }
                }
            }
            val newBitmap = generateThumbnailFromItems(id, itemsSnapshot, context) ?: return null
            storage.saveThumbnail(id, newBitmap)
            thumbnailCache.put(id, newBitmap)
            return newBitmap
        } finally {
            contributors.forEach { (_, region) -> releaseRegion(region) }
        }
    }

    private fun generateThumbnailFromItems(
        id: RegionId,
        items: List<CanvasItem>,
        context: android.content.Context,
    ): Bitmap? {
        val targetSize = CanvasConfig.THUMBNAIL_RESOLUTION
        val scale = targetSize / regionSize
        val size = kotlin.math.ceil(regionSize * scale).toInt()
        if (size <= 0) return null
        try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(-id.x * regionSize, -id.y * regionSize)
            items.forEach { item ->
                StrokeRenderer.drawItem(canvas, item, false, paint, context, scale, true)
            }
            canvas.restore()
            return bitmap
        } catch (e: Exception) {
            return null
        }
    }

    private fun invalidateThumbnail(id: RegionId) {
        thumbnailCache.remove(id)
        storage.deleteThumbnail(id)
    }

    fun loadRegionsAsync(ids: List<RegionId>) {
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEach { id ->
                // getRegion hands off a reference; prefetch holds it only for
                // the callback, then returns the region to the cache's care.
                val r = getRegion(id)
                try {
                    onRegionLoaded?.invoke(r)
                } finally {
                    releaseRegion(r)
                }
            }
        }
    }

    /**
     * Non-suspending variant of [acquireRegion] for synchronous UI-thread
     * paths: succeeds ONLY if the region is already resident (no disk IO).
     * Returns null when not loaded — callers must tolerate missing content.
     */
    fun tryAcquireRegion(id: RegionId): RegionData? {
        stateLock.read {
            val region = regionCache.get(id) ?: overflowRegions[id] ?: return@read null
            if (region.isRecycled) return@read null
            if (region.retain()) return region
            return@read null
        }
        return null
    }

    /**
     * Thread-safe hit test against the spatial index.
     *
     * Concurrency: quadtrees are mutated under [stateLock] write access
     * ([addItem], [addItemsInternal]), and regions can be evicted and recycled
     * by the LRU at any time, so candidate collection must happen under the
     * read lock against a verified-resident reference.
     *
     * Memory-correctness: regions are lazily deserialized and the cache may
     * hold only a subset of the canvas. Like [queryItems], regions are tested
     * ONE AT A TIME — [getRegion] loads/LRU-refreshes the region, candidates
     * are pulled from that live reference under the read lock, then we move to
     * the next region. Priming all regions up front would let later loads
     * evict+recycle earlier ones (silent misses on canvases larger than the
     * region memory budget).
     *
     * The sort + distance scan afterwards operates on the extracted candidate
     * list, which is an immutable snapshot.
     *
     * @return top-most item whose [CanvasItem.distanceToPoint] is within [tolerance], or null.
     */
    suspend fun hitTest(
        x: Float,
        y: Float,
        tolerance: Float = 10f,
    ): CanvasItem? {
        val searchRect = RectF(x - tolerance, y - tolerance, x + tolerance, y + tolerance)
        val candidates = ArrayList<CanvasItem>()

        for (id in getRegionIdsInRect(searchRect)) {
            val region = acquireRegion(id)
            try {
                stateLock.read {
                    region.quadtree?.retrieve(candidates, searchRect)
                }
            } finally {
                releaseRegion(region)
            }
        }

        candidates.sortByDescending { it.order }
        for (item in candidates) {
            if (item.distanceToPoint(x, y) < tolerance) return item
        }
        return null
    }

    private fun invalidateOverlappingThumbnails(rect: RectF) {
        val minX = floor(rect.left / regionSize).toInt()
        val maxX = floor(rect.right / regionSize).toInt()
        val minY = floor(rect.top / regionSize).toInt()
        val maxY = floor(rect.bottom / regionSize).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                invalidateThumbnail(RegionId(x, y))
            }
        }
    }

    suspend fun findItem(
        id: Long,
        bounds: RectF,
    ): CanvasItem? {
        // Robust Lookup Strategy:
        // 1. Check center region
        // 2. Check overlapping regions (with safety margin)

        val centerId =
            getRegionIdForItem(
                object : CanvasItem {
                    override val bounds = bounds
                    override val order = id
                    override val zIndex = 0f

                    override fun distanceToPoint(
                        x: Float,
                        y: Float,
                    ) = 0f
                },
            )

        val regionIds = HashSet<RegionId>()
        regionIds.add(centerId)

        val searchBounds = RectF(bounds)
        searchBounds.inset(-2f, -2f)
        regionIds.addAll(getRegionIdsInRect(searchBounds))

        for (rId in regionIds) {
            val region = acquireRegion(rId)
            var found: CanvasItem? = null
            try {
                stateLock.read {
                    found = region.items.find { it.order == id }
                }
            } finally {
                releaseRegion(region)
            }
            if (found != null) return found
        }
        return null
    }

    /**
     * Recovery for a COMMITTED mutation whose tail bookkeeping threw.
     *
     * The `items` swap is the commit point — it already happened, and reporting
     * failure would make callers either retry-and-reapply the SAME items
     * (proven duplicate class: 135 items / 134 unique orders) or escape
     * mid-mutation leaving ghosts (in items, absent from the quadtree/index,
     * dirty flag unset). Instead, derived structures are rebuilt from items —
     * the source of truth — so committed state stays fully consistent and
     * visible. MUST run inside [stateLock] write.
     */
    private fun recoverAfterCommittedMutation(
        id: RegionId,
        region: RegionData,
        op: String,
        e: Exception,
    ) {
        Logger.e(
            "RegionManager",
            "$op tail failed for $id (mutation committed) — rebuilding derived state",
            e,
        )
        try {
            region.rebuildQuadtree(regionSize)
            if (!region.contentBounds.isEmpty) {
                updateRegionIndex(id, RectF(region.contentBounds))
            }
            region.isDirty = true
            region.invalidateSize()
        } catch (recoveryError: Exception) {
            Logger.e("RegionManager", "$op derived-state rebuild also failed for $id", recoveryError)
        }
    }

    suspend fun addItem(item: CanvasItem) {
        val id = getRegionIdForItem(item)
        // Retry loop: if our acquired instance went stale between acquisition
        // and the write lock (evicted + replaced by a fresher load), the
        // mutation must be re-applied to the NEWER instance.
        var attempts = 0
        while (true) {
            // Unbounded retry: dropping a committed stroke is data loss. Each
            // retry re-acquires the CURRENT resident (LRU-fresh), so repeated
            // staleness is unlikely; yield periodically to avoid starving others.
            if (attempts++ % 32 == 31) delay(1)
            // acquire (not bare getRegion): guarantees the instance stays valid
            // even if concurrent loads evict it before we take the write lock.
            val region = acquireRegion(id)
            var applied = false
            try {
                stateLock.write {
                    applied = mutateDetachedFromCache(id, region) {
                        // The mutation is COMMITTED here. Every tail operation
                        // below is bookkeeping: if one throws, returning null
                        // would retry and re-add the SAME item (proven dup:
                        // hot-region chaos 135 items / 134 unique orders).
                        // Swallow tail failures instead — items is the source
                        // of truth; derived structures are rebuilt from it.
                        region.items = region.items + item
                        try {
                            if (region.quadtree == null) {
                                region.rebuildQuadtree(regionSize)
                            } else {
                                region.quadtree = region.quadtree?.insert(item)
                            }
                            if (region.contentBounds.isEmpty) {
                                region.contentBounds.set(item.bounds)
                            } else {
                                region.contentBounds.union(item.bounds)
                            }
                            updateRegionIndex(id, region.contentBounds)
                            region.isDirty = true
                            invalidateOverlappingThumbnails(item.bounds)
                            region.invalidateSize()
                            updateMetadataCache()
                            if (forensics.enabled) checkInvariants()
                        } catch (e: Exception) {
                            recoverAfterCommittedMutation(id, region, "addItem", e)
                        }
                        auditRegion(forensics, region, "ADD")
                        true
                    } != null
                }
            } finally {
                releaseRegion(region)
            }
            if (!applied) {
                logForensics { "ADD-STALE id=$id — retrying" }
                continue
            }
            return
        }
    }

    suspend fun removeItems(items: List<CanvasItem>) {
        val itemsByRegion = HashMap<RegionId, HashSet<Long>>()

        items.forEach { item ->
            // 1. Center-based ID (Primary target)
            val centerId = getRegionIdForItem(item)
            itemsByRegion.getOrPut(centerId) { HashSet() }.add(item.order)

            // 2. Spatial overlapping IDs (Secondary - handles boundary shifts)
            val searchBounds = RectF(item.bounds)
            searchBounds.inset(-2f, -2f) // 2px safety margin
            val overlaps = getRegionIdsInRect(searchBounds)
            overlaps.forEach { rId ->
                itemsByRegion.getOrPut(rId) { HashSet() }.add(item.order)
            }
        }

        // Process region-by-region; each region is retained for the duration of
        // its mutation so eviction cannot recycle it mid-write.
        itemsByRegion.forEach { (id, idsToRemove) ->
            var attempts = 0
            while (true) {
                if (attempts++ % 32 == 31) delay(1)
                val region = acquireRegion(id)
                var applied = false
                try {
                    stateLock.write {
                        applied = mutateDetachedFromCache(id, region) {
                            // Use ID-based removal to handle cases where structural
                            // properties might have changed.
                            val toRemove = region.items.filter { it.order in idsToRemove }

                            if (toRemove.isNotEmpty()) {
                                // COMMIT POINT — tail failures are recovered,
                                // never propagated (a retry would be a no-op:
                                // ids no longer present; an escape would leave
                                // ghosts).
                                region.items = region.items.filter { it.order !in idsToRemove }
                                try {
                                    toRemove.forEach { item ->
                                        var removedCount = 0
                                        while (region.quadtree?.remove(item) == true) {
                                            removedCount++
                                        }
                                        if (removedCount > 1) {
                                            Logger.w(
                                                "RegionManager",
                                                "Removed item ${item.order} from Quadtree $removedCount times",
                                            )
                                        }
                                    }
                                    region.contentBounds.setEmpty()
                                    region.items.forEach {
                                        if (region.contentBounds.isEmpty) {
                                            region.contentBounds.set(it.bounds)
                                        } else {
                                            region.contentBounds.union(it.bounds)
                                        }
                                    }

                                    if (region.items.isEmpty()) {
                                        removeRegionIndex(id)
                                    } else {
                                        updateRegionIndex(id, region.contentBounds)
                                    }

                                    region.isDirty = true

                                    val removedBounds = RectF(toRemove[0].bounds)
                                    for (i in 1 until toRemove.size) removedBounds.union(toRemove[i].bounds)
                                    invalidateOverlappingThumbnails(removedBounds)

                                    region.invalidateSize()
                                } catch (e: Exception) {
                                    recoverAfterCommittedMutation(id, region, "removeItems", e)
                                }
                            }
                            auditRegion(forensics, region, "REMOVE-ITEMS")
                            true
                        } != null
                    }
                } finally {
                    releaseRegion(region)
                }
                if (!applied) {
                    logForensics { "REMOVE-STALE id=$id — retrying" }
                    continue
                }
                break
            }
        }

        stateLock.write { updateMetadataCache() }
    }

    suspend fun stashSelectedItems(
        rect: RectF,
        ids: Set<Long>,
        outputFile: java.io.File,
    ): Int {
        val regionIds = getRegionIdsInRect(rect)
        var stashedCount = 0
        DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile, true))).use { dos ->
            for (rId in regionIds) {
                val region = acquireRegion(rId)
                try {
                    val toRemove = ArrayList<CanvasItem>()
                    region.items.forEach { item ->
                        if (ids.contains(item.order)) toRemove.add(item)
                    }
                    if (toRemove.isNotEmpty()) {
                        toRemove.forEach { item ->
                            try {
                                val bytes: ByteArray
                                val type: Int
                                when (item) {
                                    is Stroke -> {
                                        type = 0
                                        val data = CanvasSerializer.toStrokeData(item)
                                        bytes = ProtoBuf.encodeToByteArray(data)
                                    }

                                    is CanvasImage -> {
                                        type = 1
                                        val data = CanvasSerializer.toCanvasImageData(item)
                                        bytes = ProtoBuf.encodeToByteArray(data)
                                    }

                                    is com.alexdremov.notate.model.TextItem -> {
                                        type = 2
                                        val data = CanvasSerializer.toTextItemData(item)
                                        bytes = ProtoBuf.encodeToByteArray(data)
                                    }

                                    is LinkItem -> {
                                        type = 3
                                        val data = CanvasSerializer.toLinkItemData(item)
                                        bytes = ProtoBuf.encodeToByteArray(data)
                                    }

                                    else -> {
                                        return@forEach
                                    }
                                }
                                dos.writeInt(type)
                                dos.writeInt(bytes.size)
                                dos.write(bytes)
                                stashedCount++
                            } catch (e: Exception) {
                                Logger.e("RegionManager", "Failed to stash item", e)
                            }
                        }
                        stateLock.write {
                            mutateDetachedFromCache(rId, region) {
                                // COMMIT POINT — tail failures are recovered,
                                // never propagated (the items are already
                                // serialized to the stash file at this point;
                                // an escape would leave ghosts).
                                region.items = region.items.filter { it !in toRemove }
                                try {
                                    toRemove.forEach { region.quadtree?.remove(it) }
                                    region.contentBounds.setEmpty()
                                    region.items.forEach {
                                        if (region.contentBounds.isEmpty) {
                                            region.contentBounds.set(it.bounds)
                                        } else {
                                            region.contentBounds.union(it.bounds)
                                        }
                                    }
                                    if (region.items.isEmpty()) {
                                        removeRegionIndex(rId)
                                    } else {
                                        updateRegionIndex(rId, region.contentBounds)
                                    }
                                    region.isDirty = true
                                    invalidateThumbnail(rId)
                                    region.invalidateSize()
                                    updateMetadataCache()
                                } catch (e: Exception) {
                                    recoverAfterCommittedMutation(rId, region, "stashSelectedItems", e)
                                }
                                auditRegion(forensics, region, "STASH")
                            }
                        }
                    }
                } finally {
                    releaseRegion(region)
                }
            }
        }
        return stashedCount
    }

    suspend fun unstashItems(
        inputFile: java.io.File,
        transform: android.graphics.Matrix,
        onItemUnstashed: ((CanvasItem) -> Unit)? = null,
    ): Pair<Set<Long>, RectF> {
        if (!inputFile.exists()) return Pair(emptySet(), RectF())
        val addedIds = HashSet<Long>()
        val unionBounds = RectF()
        var first = true
        val buffer = ArrayList<CanvasItem>(1000)

        val startTime = System.currentTimeMillis()
        DataInputStream(BufferedInputStream(FileInputStream(inputFile))).use { dis ->
            try {
                while (dis.available() > 0) {
                    val type = dis.readInt()
                    val length = dis.readInt()
                    val bytes = ByteArray(length)
                    dis.readFully(bytes)
                    var item: CanvasItem? = null
                    if (type == 0) {
                        val data = ProtoBuf.decodeFromByteArray<StrokeData>(bytes)
                        item = CanvasSerializer.fromStrokeData(data)
                    } else if (type == 1) {
                        val data = ProtoBuf.decodeFromByteArray<CanvasImageData>(bytes)
                        val logical = RectF(data.x, data.y, data.x + data.width, data.y + data.height)
                        val aabb =
                            com.alexdremov.notate.util.StrokeGeometry
                                .computeRotatedBounds(logical, data.rotation)
                        item =
                            CanvasImage(
                                uri = data.uri,
                                logicalBounds = logical,
                                bounds = aabb,
                                zIndex = data.zIndex,
                                order = data.order,
                                rotation = data.rotation,
                                opacity = data.opacity,
                            )
                    } else if (type == 2) {
                        // Type 2 is TextItem
                        val data = ProtoBuf.decodeFromByteArray<com.alexdremov.notate.data.TextItemData>(bytes)
                        val logical = RectF(data.x, data.y, data.x + data.width, data.y + data.height)
                        val aabb =
                            com.alexdremov.notate.util.StrokeGeometry
                                .computeRotatedBounds(logical, data.rotation)
                        item =
                            com.alexdremov.notate.model.TextItem(
                                text = data.text,
                                fontSize = data.fontSize,
                                color = data.color,
                                logicalBounds = logical,
                                bounds = aabb,
                                alignment =
                                    when (data.alignment) {
                                        1 -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                                        2 -> android.text.Layout.Alignment.ALIGN_CENTER
                                        else -> android.text.Layout.Alignment.ALIGN_NORMAL
                                    },
                                backgroundColor = data.backgroundColor,
                                zIndex = data.zIndex,
                                order = data.order,
                                rotation = data.rotation,
                                opacity = data.opacity,
                            )
                    } else if (type == 3) {
                        val data = ProtoBuf.decodeFromByteArray<LinkItemData>(bytes)
                        val logical = RectF(data.x, data.y, data.x + data.width, data.y + data.height)
                        val aabb =
                            com.alexdremov.notate.util.StrokeGeometry
                                .computeRotatedBounds(logical, data.rotation)
                        item =
                            LinkItem(
                                label = data.label,
                                target = data.target,
                                type = data.type,
                                fontSize = data.fontSize,
                                color = data.color,
                                logicalBounds = logical,
                                bounds = aabb,
                                zIndex = data.zIndex,
                                order = data.order,
                                rotation = data.rotation,
                            )
                    }

                    if (item != null) {
                        val transformed = transformItem(item, transform)
                        buffer.add(transformed)
                        addedIds.add(transformed.order)
                        onItemUnstashed?.invoke(transformed)

                        if (first) {
                            unionBounds.set(transformed.bounds)
                            first = false
                        } else {
                            unionBounds.union(transformed.bounds)
                        }

                        if (buffer.size >= 1000) {
                            addItemsInternal(buffer)
                            buffer.clear()
                        }
                    }
                }
                if (buffer.isNotEmpty()) {
                    addItemsInternal(buffer)
                    buffer.clear()
                }
            } catch (e: java.io.EOFException) {
            } catch (e: Exception) {
                Logger.e("RegionManager", "Failed to unstash items", e)
            }
        }
        val duration = System.currentTimeMillis() - startTime
        if (duration > 100) {
            Logger.w("RegionManager", "Unstash took ${duration}ms for ${addedIds.size} items")
        }
        stateLock.write { checkInvariants() }
        return Pair(addedIds, unionBounds)
    }

    private suspend fun addItemsInternal(items: List<CanvasItem>) {
        if (items.isEmpty()) return

        // Retry loop: if an acquired instance goes stale before the write lock
        // (evicted + replaced by a fresher load), the mutation is re-applied to
        // the newer instance. Bounded to avoid livelock under pathological churn.
        var pending = items
        var attempts = 0
        while (pending.isNotEmpty()) {
            // Unbounded: dropping a batch commit is data loss; each retry
            // re-acquires the current resident, so convergence is expected
            // within a few rounds. Yield periodically.
            if (attempts++ % 32 == 31) delay(1)
            val byRegion = pending.groupBy { getRegionIdForItem(it) }

            // 1. Retain every affected region for the duration of the batch so
            //    eviction cannot recycle any of them between load and mutation.
            val acquired = ArrayList<Pair<RegionId, RegionData>>(byRegion.size)
            val staleIds = HashSet<RegionId>()
            try {
                byRegion.keys.forEach { id ->
                    acquired.add(id to acquireRegion(id))
                }

                // 2. Apply changes with Write Lock
                stateLock.write {
                    for ((id, regionItems) in byRegion) {
                        val region =
                            acquired.firstOrNull { it.first == id }?.second
                                ?: regionCache.get(id)
                                ?: overflowRegions[id]
                                ?: RegionData(id)
                        val applied =
                            mutateDetachedFromCache(id, region) {
                                // COMMIT POINT — a throw after this line must
                                // never escape: the caller would re-apply the
                                // same batch (duplicates) or observe ghosts.
                                region.items = region.items + regionItems
                                try {
                                    if (region.quadtree == null) {
                                        region.rebuildQuadtree(regionSize)
                                    } else {
                                        for (item in regionItems) {
                                            region.quadtree = region.quadtree?.insert(item)
                                        }
                                    }

                                    for (item in regionItems) {
                                        if (region.contentBounds.isEmpty) {
                                            region.contentBounds.set(item.bounds)
                                        } else {
                                            region.contentBounds.union(item.bounds)
                                        }
                                    }

                                    updateRegionIndex(id, region.contentBounds)
                                    region.isDirty = true

                                    val batchBounds = RectF(regionItems[0].bounds)
                                    for (i in 1 until regionItems.size) batchBounds.union(regionItems[i].bounds)
                                    invalidateOverlappingThumbnails(batchBounds)

                                    region.invalidateSize()
                                } catch (e: Exception) {
                                    recoverAfterCommittedMutation(id, region, "addItemsInternal", e)
                                }
                                auditRegion(forensics, region, "ADD-INTERNAL id=$id n=${regionItems.size}")
                                true
                            }
                        if (applied == null) {
                            logForensics { "ADD-INTERNAL-STALE id=$id — re-acquiring" }
                            staleIds.add(id)
                        }
                    }
                    updateMetadataCache()
                }
            } finally {
                acquired.forEach { (_, region) -> releaseRegion(region) }
            }
            pending = pending.filter { getRegionIdForItem(it) in staleIds }
        }
    }

    private fun transformItem(
        item: CanvasItem,
        transform: android.graphics.Matrix,
    ): CanvasItem =
        when (item) {
            is Stroke -> {
                val newPath = android.graphics.Path(item.path)
                newPath.transform(transform)
                val newPoints =
                    item.points.map { p ->
                        val pts = floatArrayOf(p.x, p.y)
                        transform.mapPoints(pts)
                        com.onyx.android.sdk.data.note
                            .TouchPoint(pts[0], pts[1], p.pressure, p.size, p.timestamp)
                    }
                val newBounds = RectF(item.bounds)
                transform.mapRect(newBounds)
                val values = FloatArray(9)
                transform.getValues(values)
                val scale =
                    kotlin.math.sqrt(
                        values[android.graphics.Matrix.MSCALE_X] * values[android.graphics.Matrix.MSCALE_X] +
                            values[android.graphics.Matrix.MSKEW_Y] * values[android.graphics.Matrix.MSKEW_Y],
                    )
                item.copy(path = newPath, points = newPoints, bounds = newBounds, width = item.width * scale)
            }

            is CanvasImage -> {
                val (newLogical, newRotation, newAabb) =
                    com.alexdremov.notate.util.StrokeGeometry.transformItemLogicalBounds(
                        item.logicalBounds,
                        item.rotation,
                        transform,
                    )
                item.copy(logicalBounds = newLogical, bounds = newAabb, rotation = newRotation)
            }

            is com.alexdremov.notate.model.TextItem -> {
                val (newLogical, newRotation, newAabb) =
                    com.alexdremov.notate.util.StrokeGeometry.transformItemLogicalBounds(
                        item.logicalBounds,
                        item.rotation,
                        transform,
                    )

                // For text, we might need to re-measure height if width changed, keeping font size constant.
                // Re-measure height based on new logical width.
                // Note: RegionManager doesn't have Context readily available for full layout measurement,
                // so we use the scaleFactor approximation here.
                val scaleFactor = newLogical.width() / item.logicalBounds.width()
                val approxHeight = item.logicalBounds.height() * scaleFactor
                newLogical.bottom = newLogical.top + approxHeight

                val finalAabb =
                    com.alexdremov.notate.util.StrokeGeometry
                        .computeRotatedBounds(newLogical, newRotation)

                item.copy(logicalBounds = newLogical, bounds = finalAabb, rotation = newRotation)
            }

            is LinkItem -> {
                val (newLogical, newRotation, newAabb) =
                    com.alexdremov.notate.util.StrokeGeometry.transformItemLogicalBounds(
                        item.logicalBounds,
                        item.rotation,
                        transform,
                    )
                item.copy(logicalBounds = newLogical, bounds = newAabb, rotation = newRotation)
            }

            else -> {
                item
            }
        }

    fun getRegionIdsInRect(rect: RectF): List<RegionId> {
        val found = HashSet<RegionId>()
        stateLock.read {
            val foundProxies = ArrayList<CanvasItem>()
            skeletonQuadtree.retrieve(foundProxies, rect)
            foundProxies.mapTo(found) { (it as RegionProxy).id }
            // AUTHORITATIVE FALLBACK: regionIndex is the source of truth for
            // which regions exist and where. The skeleton quadtree is a derived
            // acceleration structure and can drift (partial rebuilds, races in
            // historical paths); scanning the (small) index map guarantees we
            // never silently skip a region that intersects the query.
            for ((id, bounds) in regionIndex) {
                if (RectF.intersects(bounds, rect)) found.add(id)
            }
        }
        return found.toList()
    }

    suspend fun getRegionsInRect(rect: RectF): List<RegionData> {
        val ids = getRegionIdsInRect(rect)
        val result = ArrayList<RegionData>(ids.size)
        try {
            for (id in ids) {
                result.add(acquireRegion(id))
            }
        } catch (t: Throwable) {
            // Don't leak references if acquisition fails midway.
            result.forEach(::releaseRegion)
            throw t
        }
        return result
    }

    /**
     * Releases references returned by [getRegionsInRect]. Call in finally.
     */
    fun releaseRegions(regions: List<RegionData>) {
        regions.forEach(::releaseRegion)
    }

    /**
     * Collects all items intersecting [rect] across every intersecting region.
     *
     * Concurrency AND memory-correctness: this is the hot read path for tile
     * generation and must work when the query's working set EXCEEDS the region
     * memory budget — loading region k can evict (and asynchronously RECYCLE:
     * items cleared, quadtree nulled) region 1. Therefore regions are processed
     * ONE AT A TIME: [getRegion] makes the region LRU-fresh and returns its
     * live reference; candidates are extracted from that exact reference under
     * the read lock before the next region is loaded. Eviction of already-
     * processed regions is harmless because their candidates were copied out.
     *
     * The verify step inside the read lock closes the small window between
     * [getRegion] returning and the lock being acquired, where another
     * coroutine's load could evict+recycle our region: if it is no longer
     * resident we simply retry (the reload brings it back).
     */
    suspend fun queryItems(rect: RectF): ArrayList<CanvasItem> {
        val result = ArrayList<CanvasItem>()
        val regionIds = getRegionIdsInRect(rect)
        for (id in regionIds) {
            // acquireRegion guarantees validity even under concurrent eviction
            // (refcounted demotion) — no residency re-verification needed.
            val region = acquireRegion(id)
            try {
                stateLock.read {
                    region.quadtree?.retrieve(result, rect)
                }
            } finally {
                releaseRegion(region)
            }
        }
        return result
    }

    suspend fun visitItemsInRect(
        rect: RectF,
        visitor: (CanvasItem) -> Unit,
    ) {
        val ids = getRegionIdsInRect(rect)
        for (id in ids) {
            val region = acquireRegion(id)
            try {
                // Read lock: quadtree.visit is read-only, but the lock excludes
                // concurrent structural mutation of the visited subtree.
                stateLock.read {
                    region.quadtree?.visit(rect, visitor)
                }
            } finally {
                releaseRegion(region)
            }
        }
    }

    suspend fun removeItemsByIds(
        rect: RectF,
        ids: Set<Long>,
    ) {
        val regionIds = getRegionIdsInRect(rect)
        for (rId in regionIds) {
            var attempts = 0
            while (true) {
                if (attempts++ % 32 == 31) delay(1)
                val region = acquireRegion(rId)
                var applied = false
                try {
                    val toRemove = region.items.filter { it.order in ids }
                    if (toRemove.isNotEmpty()) {
                        stateLock.write {
                            applied = mutateDetachedFromCache(rId, region) {
                                // COMMIT POINT — tail failures recovered, never
                                // propagated (ids already gone → retry is a
                                // no-op; an escape would leave ghosts).
                                region.items =
                                    region.items.filter { it.order !in ids }
                                try {
                                    toRemove.forEach { region.quadtree?.remove(it) }
                                    region.contentBounds.setEmpty()
                                    region.items.forEach {
                                        if (region.contentBounds.isEmpty) {
                                            region.contentBounds.set(it.bounds)
                                        } else {
                                            region.contentBounds.union(it.bounds)
                                        }
                                    }
                                    if (region.items.isEmpty()) {
                                        removeRegionIndex(rId)
                                    } else {
                                        updateRegionIndex(rId, region.contentBounds)
                                    }
                                    region.isDirty = true
                                    invalidateThumbnail(rId)
                                    region.invalidateSize()
                                    updateMetadataCache()
                                } catch (e: Exception) {
                                    recoverAfterCommittedMutation(rId, region, "removeItemsByIds", e)
                                }
                                auditRegion(forensics, region, "REMOVE-BY-IDS")
                                true
                            } != null
                        }
                    }
                } finally {
                    releaseRegion(region)
                }
                if (!applied) {
                    logForensics { "REMOVE-BY-IDS-STALE id=$rId — retrying" }
                    continue
                }
                break
            }
        }
    }

    fun getContentBounds(): RectF = RectF(cachedContentBounds)

    fun getActiveRegionIds(): Set<RegionId> = cachedActiveIds

    /**
     * Highest item order across ALL active regions (loading them as needed).
     * Session-init cost: one full pass at open time; callers use it to resume
     * order assignment without collisions (a `nextOrder` that restarts at 0
     * makes new strokes share identity with existing ones, silently corrupting
     * undo/redo and erase-by-id).
     */
    suspend fun maxItemOrder(): Long {
        var max = -1L
        for (id in getActiveRegionIds()) {
            val r = acquireRegion(id)
            try {
                stateLock.read {
                    r.items.forEach { if (it.order > max) max = it.order }
                }
            } finally {
                releaseRegion(r)
            }
        }
        return max
    }

    fun forensicsSetEnabled(v: Boolean) {
        forensics.enabled = v
    }

    fun forensicsReset() = forensics.reset()

    /** Enables churn-level (per-operation) forensics logging. */
    fun forensicsSetVerbose(v: Boolean) {
        forensics.verbose = v
    }

    /** TEMP DEBUG: limbo snapshot straight from the source. */
    fun debugLimboSnapshot(): Map<String, Int> =
        limbo.entries.associate { "${it.key.x}_${it.key.y}" to it.value.size }

    /** TEMP DEBUG: identity of the live limbo map. */
    fun debugLimboIdentity(): Int = System.identityHashCode(limbo)

    /** TEMP DEBUG: residency snapshot straight from the source. */
    fun debugResidency(): Triple<Set<String>, Set<String>, Int> {
        val cacheKeys = stateLock.read { regionCache.keySetSnapshot().map { "${it.x}_${it.y}" }.toSet() }
        val ovfKeys = stateLock.read { overflowRegions.keys.map { "${it.x}_${it.y}" }.toSet() }
        return Triple(cacheKeys, ovfKeys, limbo.size)
    }

    fun dumpForensics(id: String): String = forensics.dumpFor(id)

    fun setPinnedRegions(ids: Set<RegionId>) {
        stateLock.write {
            val unpinned = pinnedIds - ids
            pinnedIds = ids
            unpinned.forEach { id ->
                overflowRegions.remove(id)?.let {
                    regionCache.put(id, it).forEach { e -> demote(e) }
                }
            }
            recomputeOverflowBytes()
        }
    }

    fun clear() {
        if (forensics.enabled) {
            val caller = Throwable().stackTrace.getOrNull(1)
            forensics.log("!!!! CLEAR-ENTERED by ${caller ?: "?"}")
        }
        // Any save queued BEFORE this point carries pre-clear content; when its
        // coroutine eventually starts it will see the epoch mismatch and abort
        // instead of writing cleared data back to disk (see [clearEpoch]).
        clearEpoch.incrementAndGet()
        stateLock.write {
            // DISCARD demotion: identical bookkeeping to [demote] but WITHOUT
            // limbo parking — parking schedules saves for dirty copies, which
            // would write PRE-CLEAR bytes back after the wipe (and pinned
            // re-homing via handleEviction can park OTHER regions while making
            // room). Non-destructive eviction keeps held reader references
            // valid without any finalization, so nothing needs parking here.
            regionCache.evictAll().forEach { evictee ->
                evictee.markEvicted()
                evictee.releaseOwnership()
            }

            // Overflow regions are owned by this map — drop that ownership too.
            overflowRegions.values.forEach { it.markEvicted() }
            overflowRegions.clear()
            currentOverflowBytes = 0
            pinnedIds = emptySet()

            thumbnailCache.evictAll()
            regionIndex.clear()
            skeletonQuadtree.clear()
            regionProxies.clear()

            // A closing/discarded session must not keep parked regions (their
            // content is either already flushed or intentionally discarded).
            limbo.clear()

            // MEMORY HYGIENE: auxiliary registries must not outlive the
            // cleared session, or closed documents leak their last lineage
            // copies forever (found by MemoryHygieneTest weak-ref probes).
            liveLineage.clear()

            // In-flight loads would otherwise publish stale regions into the
            // freshly cleared session.
            val staleLoads = loadingJobs.values.toList()
            loadingJobs.clear()
            pendingSaveIds.clear()
            resaveNeeded.clear()
            rescueCount.clear()
            loadCount.clear()

            updateMetadataCache()
            staleLoads.forEach { it.cancel() }
        }
    }

    /**
     * User-initiated "erase everything": [clear] PLUS permanent removal of
     * persisted strokes, thumbnails and the spatial index — so a later
     * reopen's [rebuildIndex] finds nothing to resurrect.
     *
     * Scope discipline: deliberately NOT part of plain [clear]. Flows like
     * setLoadedState clear memory right before repopulating a document; they
     * must keep the store intact.
     *
     * Straggler safety: a scheduled save whose coroutine was already executing
     * when [clear] bumped the epoch can finish its rename after our first
     * delete pass. saveRegionInternal re-checks liveness before every write,
     * shrinking that window to inside a single writeAtomic; the second pass
     * below re-lists and removes anything such a straggler still recreated.
     *
     * Crash-window note: until the next ZIP commit, the .notate container may
     * still hold pre-clear bytes — a crash before that flush rolls the canvas
     * back to its last saved state (same semantics as any unflushed edit).
     * Image assets under images/ are intentionally left in place.
     */
    suspend fun clearAndWipeStorage() {
        // Capture BEFORE clear() empties the index.
        val idsToWipe = stateLock.read { cachedActiveIds.toSet() } + storage.listStoredRegions()
        clear()
        deletePersistedContent(idsToWipe)
        // Give an in-flight straggler write time to finish its rename, then
        // sweep whatever it may have recreated (re-list catches all).
        delay(50)
        deletePersistedContent(storage.listStoredRegions().toSet())
        storage.deleteIndex()
    }

    private fun deletePersistedContent(ids: Set<RegionId>) {
        ids.forEach { id ->
            storage.deleteRegion(id)
            storage.deleteThumbnail(id)
        }
    }

    /**
     * Flushes all dirty regions to storage.
     *
     * Locking protocol (why this is NOT simply `stateLock.write { ... }`):
     * The old implementation held the WRITE lock while performing disk IO,
     * blocking every reader — including UI-thread paths like minimap rendering
     * (`getRegionIdsInRect`) — for the duration of a flash write.
     *
     * Instead:
     *  1. Under a READ lock, serialize each dirty region to bytes. Serialization
     *     is pure CPU over the item list; mutations are excluded by the read
     *     lock itself, so the snapshot is consistent.
     *  2. Outside any lock, write the bytes (slow IO).
     *  3. Re-acquire the region's [RegionData.modCount]: if it changed while we
     *     were writing, the region was mutated after our snapshot — leave it
     *     dirty so the next flush persists the newer content.
     *
     * Note: eviction-driven [scheduleSave] still writes under no lock but
     * operates on regions already removed from the cache, so they are no longer
     * mutated through normal paths.
     */
    fun saveAll() {
        // Process dirty regions ONE AT A TIME (bounded memory: only one
        // serialized snapshot exists at a time — critical for canvases larger
        // than the region budget, the RegionManager's primary use case).
        //
        // Per-region protocol:
        //  1. READ lock: serialize the region (CPU-only; the read lock excludes
        //     writers, so the snapshot is consistent) and capture its modCount.
        //  2. No lock: write bytes / delete (slow IO must not block readers).
        //  3. WRITE lock: clear the dirty flag ONLY if modCount is unchanged —
        //     otherwise the region was mutated while we wrote; it stays dirty
        //     and the next flush persists the newer content.
        val dirtyIds =
            stateLock.read {
                // NOTE: limbo MUST be included. A dirty region can sit in limbo
                // when its park-time save was gate-skipped or its modCount guard
                // kept it dirty — saveAll is then the only remaining writer.
                // Omitting it orphans the region: deletions/additions never reach
                // disk (caught by RenderPipelineChaosTest).
                (regionCache.keySetSnapshot() + overflowRegions.keys + limbo.keys)
                    .filter {
                        val r = regionCache.get(it) ?: overflowRegions[it] ?: freshestInLimbo(it)
                        // Recycled husks have cleared items but may retain a
                        // stale dirty flag; treating them as "dirty & empty"
                        // deleted LIVE region files from disk (caught by
                        // forensics: LOAD-RAW null → LOAD-MISS → data loss).
                        // Their content, if any, is already captured by the
                        // save gate / resave queue.
                        r != null && !r.isRecycled && r.isDirty
                    }.distinct()
            }

        for (id in dirtyIds) {
            // Acquire the per-id save gate BEFORE serializing. This makes the
            // whole capture→IO window mutually exclusive with [scheduleSave]
            // and other saveAll iterations for the same id — without it, a
            // concurrent eviction save could write NEWER bytes that this
            // stale snapshot then overwrites (last-writer-wins with older
            // data = silent content loss; caught by RenderPipelineChaosTest).
            if (!pendingSaveIds.add(id)) continue

            // Re-read each iteration: the region may have been evicted, mutated
            // or removed since the list was captured.
            var bytes: ByteArray? = null
            var isEmpty = false
            var modAtSnapshot = -1L
            var captured = false

            stateLock.read {
                val region =
                    regionCache.get(id) ?: overflowRegions[id] ?: freshestInLimbo(id)
                        ?: return@read
                if (!region.isDirty) return@read
                modAtSnapshot = region.modCount
                if (region.items.isEmpty()) {
                    logForensics {
                        "!!!! SAVEALL-EMPTY-DELETE id=$id dirty=${region.isDirty} " +
                            "recycled=${region.isRecycled} gen=${region.generation} " +
                            "fromCache=${regionCache.get(id) === region} fromLimbo=${limbo[id]?.any { it === region } == true}"
                    }
                    isEmpty = true
                } else {
                    try {
                        bytes = storage.serializeRegion(region)
                    } catch (e: Exception) {
                        Logger.e("RegionManager", "Failed to serialize region $id during saveAll", e)
                        return@read
                    }
                }
                captured = true
            }
            if (!captured) {
                pendingSaveIds.remove(id)
                continue
            }

            // IO outside any lock; the gate stays held so concurrent reloads
            // wait instead of concluding "region missing".
            val ok =
                try {
                    if (isEmpty) {
                        storage.deleteRegion(id)
                        true
                    } else {
                        storage.saveRegionBytes(id, bytes!!)
                    }
                } catch (e: Exception) {
                    Logger.e("RegionManager", "Failed to persist region $id during saveAll", e)
                    false
                } finally {
                    bumpDiskGen(id)
                    pendingSaveIds.remove(id)
                }

            if (ok) {
                val current =
                    regionCache.get(id) ?: overflowRegions[id] ?: freshestInLimbo(id)
                if (current != null) {
                    var reschedule = false
                    stateLock.write {
                        if (current.modCount == modAtSnapshot) {
                            current.isDirty = false
                        } else {
                            // Content changed while our snapshot was in flight:
                            // the written bytes are stale. Re-queue immediately
                            // instead of hoping a future flush notices.
                            reschedule = true
                        }
                    }
                    if (reschedule) scheduleSave(current)
                }
            }
        }

        // Persist the index as well. Index updates happen under the write lock;
        // snapshot under read, write outside. The version check prevents an
        // OLDER snapshot from clobbering a NEWER one already written by a
        // concurrent flush (out-of-order last-writer-wins).
        val captured: Pair<HashMap<RegionId, RectF>, Long> =
            stateLock.read { HashMap(regionIndex) to indexVersion.get() }
        val indexUnchanged = indexVersion.get() == captured.second
        if (indexUnchanged) {
            try {
                storage.saveIndex(captured.first)
            } catch (e: Exception) {
                Logger.e("RegionManager", "Failed to save index during saveAll", e)
            }
        }
    }

    private fun updateRegionIndex(
        id: RegionId,
        bounds: RectF,
    ) {
        val newBounds = RectF(bounds)
        regionIndex[id] = newBounds
        indexVersion.incrementAndGet()
        val oldProxy = regionProxies[id]
        if (oldProxy != null) {
            if (oldProxy.bounds == newBounds) return
            skeletonQuadtree.remove(oldProxy)
        }
        val newProxy = RegionProxy(id, newBounds)
        regionProxies[id] = newProxy
        skeletonQuadtree = skeletonQuadtree.insert(newProxy)
    }

    private fun removeRegionIndex(id: RegionId) {
        regionIndex.remove(id)
        indexVersion.incrementAndGet()
        val proxy = regionProxies.remove(id)
        if (proxy != null) skeletonQuadtree.remove(proxy)
    }

    private fun getRegionIdForItem(item: CanvasItem): RegionId {
        val x = floor(item.bounds.centerX() / regionSize).toInt()
        val y = floor(item.bounds.centerY() / regionSize).toInt()
        return RegionId(x, y)
    }

    private fun rebuildSkeletonQuadtree() {
        skeletonQuadtree = Quadtree(0, RectF(-regionSize, -regionSize, regionSize, regionSize))
        regionProxies.clear()
        regionIndex.forEach { (id, bounds) ->
            val proxy = RegionProxy(id, bounds)
            regionProxies[id] = proxy
            skeletonQuadtree = skeletonQuadtree.insert(proxy)
        }
    }

    fun validateSpatialIndex() {
        scope.launch {
            stateLock.write {
                rebuildSkeletonQuadtree()
                updateMetadataCache()
            }
        }
    }

    private fun checkInvariants() {
        var corrupted = false
        regionIndex.forEach { (id, bounds) ->
            if (!regionProxies.containsKey(id)) {
                Logger.e("RegionManager", "Invariant violation: Region $id in index but not in proxies")
                corrupted = true
            } else if (regionProxies[id]?.bounds != bounds) {
                Logger.e("RegionManager", "Invariant violation: Region $id bounds mismatch in proxy")
                corrupted = true
            }
        }

        if (regionProxies.size != regionIndex.size) {
            Logger.e(
                "RegionManager",
                "Invariant violation: Proxy count ${regionProxies.size} != Index count ${regionIndex.size}",
            )
            corrupted = true
        }

        if (corrupted) {
            Logger.w("RegionManager", "Spatial index corruption detected. Rebuilding...")
            rebuildSkeletonQuadtree()
        }
    }
}

/** Shared default ring for legacy static callers (see [RegionForensicsRing]). */
object RegionForensics : RegionForensicsRing()
