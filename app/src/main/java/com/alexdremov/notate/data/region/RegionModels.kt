package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Quadtree
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RegionId(
    val x: Int,
    val y: Int,
) {
    override fun toString(): String = "${x}_$y"

    fun getBounds(regionSize: Float): RectF = RectF(x * regionSize, y * regionSize, (x + 1) * regionSize, (y + 1) * regionSize)

    companion object {
        fun fromString(s: String): RegionId? {
            val parts = s.split("_")
            if (parts.size != 2) return null
            return try {
                RegionId(parts[0].toInt(), parts[1].toInt())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

data class RegionData(
    val id: RegionId,
    // COW-PUBLISHED: never mutated in place after publish. Writers build a
    // fresh list under stateLock and swap it in; the volatile read makes
    // reader access lock-free and always consistent (RegionStore design G2).
    @Volatile var items: List<CanvasItem> = ArrayList(),
    @Volatile var isDirty: Boolean = false,
) {
    @Transient
    var quadtree: Quadtree? = null

    @Transient
    val contentBounds = RectF()

    @Transient
    private var lastCalculatedSize: Long = -1L

    /*
     * REFERENCE COUNTING (eviction safety)
     * ------------------------------------
     * Industry-standard pattern for tile/region caches (Chromium CC TileManager,
     * Skia GrResourceCache, Mapbox retained tiles): eviction is a DEMOTION, not
     * destruction. The cache holds exactly ONE ownership reference; readers take
     * additional references via RegionManager.acquireRegion() and release them
     * when done. recycle() (which destructively clears items and the quadtree)
     * runs only when the last reference drops on an evicted region.
     *
     * Declared in the class body (NOT the constructor) deliberately: data-class
     * copy() must give the copy its own fresh counter.
     */
    @Transient
    private val refCount =
        java.util.concurrent.atomic
            .AtomicInteger(1)

    /** Last time this instance was handed to a caller. Drives sticky-limbo:
     *  hot copies are not swept+recycled, preventing reload churn for
     *  oversized (> budget) regions. Class body (NOT ctor) so copy() resets. */
    @Volatile
    var lastTouchMs: Long = System.currentTimeMillis()
        private set

    /** Set when the cache dropped its ownership reference (demoted). */
    @Transient
    @Volatile
    var isEvicted: Boolean = false
        private set

    /** Set once recycle() has run; the object must never be handed out again. */
    @Transient
    @Volatile
    var isRecycled: Boolean = false
        private set

    /*
     * GENERATION TOKEN (ABA detection).
     * Incremented every time this instance is populated from disk. Holders of a
     * long-lived reference can compare generations to detect that the content
     * they are looking at was replaced/reloaded under the same RegionId —
     * the same idea as HTTP ETags or TileManager.renderVersion, but per region.
     */
    @Transient
    @Volatile
    var generation: Long = 0
        private set

    /** Called by RegionManager after (re)populating content from disk. */
    fun bumpGeneration() {
        generation++
    }

    /**
     * Monotonic counter bumped on every content mutation (see [invalidateSize],
     * which all mutation paths in [RegionManager] already call). Used by
     * save-all-style flushes to detect whether a region was modified while its
     * serialized snapshot was being written to disk outside the state lock —
     * in that case the dirty flag must NOT be cleared, so the change is picked
     * up by the next flush.
     */
    @Transient
    @Volatile
    var modCount: Long = 0
        private set

    /**
     * Returns the size in bytes, using a cached value if available.
     * This is critical for LruCache consistency.
     */
    fun getSizeCached(): Long {
        if (lastCalculatedSize == -1L) {
            lastCalculatedSize = sizeBytes()
        }
        return lastCalculatedSize
    }

    /**
     * Invalidates the cached size. Call this before putting the region back into LruCache
     * after modification.
     *
     * Also bumps [modCount]: every RegionManager mutation path calls this, so
     * the counter doubles as the "content changed" signal for lock-free saves.
     */
    fun invalidateSize() {
        lastCalculatedSize = -1L
        modCount++
    }

    fun rebuildQuadtree(regionSize: Float) {
        val rBounds = id.getBounds(regionSize)

        var qt = Quadtree(0, rBounds)
        contentBounds.setEmpty()

        for (item in items) {
            qt = qt.insert(item)
            if (contentBounds.isEmpty) {
                contentBounds.set(item.bounds)
            } else {
                contentBounds.union(item.bounds)
            }
        }
        quadtree = qt
    }

    /**
     * Takes a reader reference. Returns false only if the region has been
     * recycled — the caller must reload it via RegionManager.getRegion.
     *
     * Retaining a ZERO-reference evicted region is explicitly allowed: between
     * cache-ownership drop and async finalization the object is alive but
     * unowned; a reader arriving in that window pins it (the pending finalize's
     * [tryRecycle] will fail and be retried when the reader releases).
     */
    fun retain(): Boolean {
        // VESTIGIAL BY DESIGN (RegionStore Phase 3): destruction no longer
        // exists, so a retain can never legitimately fail. Historical failure
        // chain: any unpaired decrement drove counts negative → retain() began
        // failing → INSTALL-PURGE forked lineages over live content. Counts
        // are now pure diagnostics; correctness comes from the single-authority
        // lock and non-destructive eviction.
        refCount.incrementAndGet()
        return true
    }

    /**
     * Drops the CACHE's ownership reference (called on eviction).
     * Returns true if this was the LAST reference — the caller (RegionManager)
     * must then finalize the region: persist if dirty, then [recycle].
     * If false, a reader still holds references and will finalize via
     * [release] instead.
     */
    fun releaseOwnership(): Boolean = releaseInternal()

    /** TEMP forensics: current raw refcount. */
    fun debugRefCount(): Int = refCount.get()

    /** TEMP forensics: raw CAS-free peek at recycled flag. */
    fun debugIsRecycled(): Boolean = isRecycled

    /**
     * Drops a READER reference acquired via [retain].
     * Returns true if this was the last reference on an EVICTED region — the
     * caller must finalize (persist + recycle). For resident regions this just
     * returns false (the cache still owns it).
     */
    fun release(): Boolean = releaseInternal()

    private fun releaseInternal(): Boolean {
        val before = refCount.get()
        val remaining = refCount.decrementAndGet()
        if (remaining < 0 && com.alexdremov.notate.data.region.RegionForensics.enabled) {
            com.alexdremov.notate.data.region.RegionForensics.log(
                "!!!! OVER-RELEASE count $before -> $remaining via " +
                    Throwable().stackTrace.take(4).joinToString("<-") { it.methodName },
            )
            // Direct print: the forensics RING BUFFER may have evicted this
            // event by dump time; over-release evidence must survive.
            println(
                "!!!! OVER-RELEASE id=$id count $before -> $remaining via " +
                    Throwable().stackTrace.take(12).joinToString("<-") { it.methodName },
            )
            // Dump this region's tagged RET/REL history: the second decrement
            // site will be visible in the events preceding this one.
            println(
                com.alexdremov.notate.data.region.RegionForensics
                    .dumpFor("$id"),
            )
        }
        return remaining <= 0
    }

    fun touch() {
        lastTouchMs = System.currentTimeMillis()
    }

    /**
     * NON-DESTRUCTIVE by design (RegionStore redesign, Phase 1): dropping an
     * region only removes it from residency maps — the instance itself is
     * NEVER cleared. Readers/mutators holding it see frozen-but-valid content
     * (GC reclaims it once references drop). This deletes the entire class of
     * recycled-husk bugs: INSTALL-PURGE, HANDOFF-FAIL, recycle-vs-retain
     * races, negative-refcount spirals. Always returns false so callers'
     * finalize-once logic stays inert.
     */
    fun tryRecycle(): Boolean = false

    /** Called by the cache when it drops ownership (demotion). */
    fun markEvicted() {
        isEvicted = true
    }

    /** True while any reader (or the cache) holds a reference. */
    fun hasReferences(): Boolean = refCount.get() > 0

    /**
     * Destructively releases resources. Prefer [tryRecycle] from RegionManager:
     * it refuses to recycle regions that still have references. This variant
     * forces recycling unconditionally (used only where the manager knows no
     * readers exist).
     */
    fun recycle() {
        if (isRecycled) return
        isRecycled = true
        refCount.set(Int.MIN_VALUE)
        quadtree?.clear()
        quadtree = null
    }

    fun sizeBytes(): Long {
        // More precise size calculation for LRU
        // Object Headers (~16 bytes) + References (4-8 bytes) are accounted for.
        var size = 0L
        for (item in items) {
            size +=
                when (item) {
                    is Stroke -> {
                        // Base Stroke object (~64) + RectF (~32) + references
                        var itemSize = 128L

                        // Native Path Overhead Estimation:
                        // A simple line might be small, but a complex handwriting stroke has many verbs.
                        // We add a safety buffer of 1KB per stroke for Native Path backing.
                        itemSize += 1024L

                        // Points: List<TouchPoint>
                        // TouchPoint is an object.
                        // Per point: Object Header(16) + Fields(x,y,pressure,size,timestamp ~32) + Ref(4) = ~52 bytes
                        itemSize += item.points.size * 52L

                        // NOTE: renderCache deliberately NOT counted. It is
                        // transient DERIVED data (rebuildable from points)
                        // whose weight can exceed the entire cache budget,
                        // forcing constant demote/reload churn.
                        itemSize
                    }

                    is com.alexdremov.notate.model.CanvasImage -> {
                        // Object overhead (~64) + RectF (~32) + URI String (Header+Ref ~48) + char array
                        144L + (item.uri.length * 2L)
                    }

                    else -> {
                        128L
                    }
                }
        }

        size += 128L // Base RegionData overhead
        size += quadtree?.sizeBytes() ?: 0L
        return size
    }
}
