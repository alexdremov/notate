package com.alexdremov.notate.data.region

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.PerformanceProfiler
import com.alexdremov.notate.util.Quadtree
import com.alexdremov.notate.util.StrokeRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * Manages the spatial partitioning, caching, and persistence of canvas regions.
 *
 * **Concurrency Architecture:**
 * This class uses a [ReentrantReadWriteLock] to protect its internal state (Cache, Index, Quadtree).
 * - **Read Lock**: Used for querying spatial data and creating safe snapshots of item lists.
 * - **Write Lock**: Used for modifying region data (add/remove items), updating the index, and mutating the LruCache.
 *
 * **Performance Optimization (The "Outside-Lock" Strategy):**
 * To prevent ANRs (Application Not Responding), heavy operations such as Disk I/O and
 * Bitmap Rendering are performed **outside** the locks.
 * - [getRegion]: Loads from disk without lock, then merges into cache under write lock.
 * - [getRegionThumbnail]: Snapshots data under read lock, renders bitmap without lock, then caches under write lock.
 *
 * **LruCache Consistency:**
 * [RegionData] items are mutable (size changes when strokes are added). Standard LruCache breaks if
 * item size changes in-place. We use a "Remove-Modify-Put" pattern with a [resizingId] guard
 * to ensure the cache's internal size accounting remains accurate without triggering premature disk saves.
 */
class RegionManager(
    private val storage: RegionStorage,
    val regionSize: Float,
    private val memoryLimitBytes: Long =
        (
            Runtime.getRuntime().maxMemory() *
                com.alexdremov.notate.config.CanvasConfig.REGIONS_CACHE_MEMORY_PERCENT
        ).toLong(),
) {
    // Cache size in KB
    private val regionCache: LruCache<RegionId, RegionData>

    // Thumbnail Cache (Separate from Region Data) - 20MB limit
    private val thumbnailCache =
        object : LruCache<RegionId, Bitmap>(20 * 1024) {
            override fun sizeOf(
                key: RegionId,
                value: Bitmap,
            ): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

    // Global Index of active regions and their bounds
    private val regionIndex: MutableMap<RegionId, RectF>

    // Skeleton Index for fast spatial queries
    private var skeletonQuadtree = Quadtree(0, RectF(-regionSize, -regionSize, regionSize, regionSize))
    private val regionProxies = HashMap<RegionId, RegionProxy>()

    // Pinned Regions (Overflow protection for visible items)
    private var pinnedIds: Set<RegionId> = emptySet()
    // Using LinkedHashMap for access/insertion order (Insertion is default, which works for FIFO eviction of stale items)
    private val overflowRegions = java.util.LinkedHashMap<RegionId, RegionData>()
    // Hard limit for overflow: 50% of the main cache size.
    // This prevents "Pinned" regions from consuming infinite memory if the user zooms out too much.
    private val maxOverflowBytes = memoryLimitBytes / 2
    private var currentOverflowBytes = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onRegionLoaded: ((RegionData) -> Unit)? = null

    // GUARD: Tracks the Region ID currently being resized (Remove -> Put).
    // Prevents 'entryRemoved' from interpreting this as an eviction and triggering a save.
    @Volatile
    private var resizingId: RegionId? = null

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
        // Load index on startup
        regionIndex = storage.loadIndex().toMutableMap()

        if (regionIndex.isEmpty()) {
            Logger.w("RegionManager", "Index empty on init. Attempting to rebuild from region files...")
            rebuildIndex()
        }

        Logger.d("RegionManager", "Initialized with ${regionIndex.size} regions from index")

        // Build Skeleton Quadtree
        rebuildSkeletonQuadtree()

        val maxKb = (memoryLimitBytes / 1024).toInt()
        regionCache =
            object : LruCache<RegionId, RegionData>(maxKb) {
                override fun sizeOf(
                    key: RegionId,
                    value: RegionData,
                ): Int {
                    // Returns size in KB. Uses cached size for consistency.
                    // IMPORTANT: value.getSizeCached() returns the size at the time of 'put'.
                    return (value.getSizeCached() / 1024).toInt().coerceAtLeast(1)
                }

                override fun entryRemoved(
                    evicted: Boolean,
                    key: RegionId,
                    oldValue: RegionData,
                    newValue: RegionData?,
                ) {
                    // CRITICAL: If we are just resizing (remove -> put), skip the save logic.
                    if (key == resizingId) return

                    if (evicted) {
                        // Check if pinned
                        if (pinnedIds.contains(key)) {
                            val size = oldValue.getSizeCached()
                            
                            // Check if adding this would exceed overflow limits
                            if (currentOverflowBytes + size > maxOverflowBytes) {
                                // Must make space or drop.
                                // Strategy: Drop oldest from overflow.
                                
                                val it = overflowRegions.iterator()
                                while (it.hasNext() && currentOverflowBytes + size > maxOverflowBytes) {
                                    val (oldKey, oldRegion) = it.next()
                                    // Don't drop the one we are trying to add (unless it's the only one and too big)
                                    // Actually we haven't added 'key' yet.
                                    
                                    // Save if dirty before dropping
                                    if (oldRegion.isDirty) {
                                        try {
                                            storage.saveRegion(oldRegion)
                                            oldRegion.isDirty = false
                                        } catch (e: Exception) {
                                            Logger.e("RegionManager", "Failed to save overflow region $oldKey during forced eviction", e)
                                        }
                                    }
                                    
                                    currentOverflowBytes -= oldRegion.getSizeCached()
                                    it.remove()
                                    Logger.w("RegionManager", "Forced eviction of pinned region $oldKey from overflow (Limit exceeded)")
                                }
                            }
                            
                            // Double check if we have space now (or if the item itself is too huge)
                            if (currentOverflowBytes + size <= maxOverflowBytes) {
                                overflowRegions[key] = oldValue
                                currentOverflowBytes += size
                                Logger.d("RegionManager", "Moved pinned region $key to overflow (Size: ${size / 1024}KB)")
                                return
                            } else {
                                Logger.e("RegionManager", "Dropping pinned region $key. Too large for overflow ($size > $maxOverflowBytes)")
                                // Fallthrough to standard save/evict logic below
                            }
                        } else {
                            Logger.d("RegionManager", "Evicting region $key (Size: ${oldValue.getSizeCached() / 1024}KB)")
                        }
                    }
                    
                    if (oldValue.isDirty) {
                        try {
                            if (!storage.saveRegion(oldValue)) {
                                Logger.e("RegionManager", "CRITICAL: Failed to save evicted region $key. DATA LOSS LIKELY.")
                            } else {
                                oldValue.isDirty = false
                            }
                        } catch (e: Exception) {
                            Logger.e("RegionManager", "Failed to save evicted region $key", e)
                        }
                    }
                }
            }

        PerformanceProfiler.registerMemoryStats(
            "RegionManager",
            object : PerformanceProfiler.MemoryStatsProvider {
                override fun getStats(): Map<String, String> {
                    val rSize = regionCache.size() / 1024
                    val rMax = regionCache.maxSize() / 1024
                    val tSize = thumbnailCache.size() / 1024
                    val tMax = thumbnailCache.maxSize() / 1024
                    val oSize = currentOverflowBytes / (1024 * 1024)

                    var pSizeBytes = 0L
                    lock.read {
                        pinnedIds.forEach { id ->
                            val region = regionCache.get(id) ?: overflowRegions[id]
                            pSizeBytes += region?.getSizeCached() ?: 0L
                        }
                    }

                    return mapOf(
                        "Region Cache (MB)" to "$rSize / $rMax",
                        "Thumb Cache (MB)" to "$tSize / $tMax",
                        "Overflow (MB)" to "$oSize (Count: ${overflowRegions.size})",
                        "Pinned" to "${pinnedIds.size} (${pSizeBytes / (1024 * 1024)}MB)",
                        "Index" to "${regionIndex.size}",
                    )
                }
            },
        )
    }

    private val lock = ReentrantReadWriteLock()

    private fun rebuildIndex() {
        val regionIds = storage.listStoredRegions()
        if (regionIds.isEmpty()) {
            Logger.i("RegionManager", "No region files found. Clean session.")
            return
        }

        Logger.i("RegionManager", "Found ${regionIds.size} region files. Rebuilding index...")
        var loadedCount = 0

        regionIds.forEach { id ->
            try {
                // We load the region fully to get accurate bounds
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
            // Save the repaired index
            if (storage.saveIndex(regionIndex)) {
                Logger.i("RegionManager", "Index rebuilt with $loadedCount regions and saved.")
            } else {
                Logger.e("RegionManager", "Index rebuilt with $loadedCount regions but failed to save!")
            }
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? = storage.importImage(uri, context)

    /**
     * Retrieves a region, loading it from disk if necessary.
     * Uses an optimistic lock-free check followed by an atomic load-and-put strategy
     * to ensure IO happens outside the lock where possible.
     */
    fun getRegion(id: RegionId): RegionData {
        // 1. Fast Cache Check (Thread-safe via LruCache)
        var region = regionCache.get(id)
        if (region != null) {
            return region
        }

        // 2. Load from Disk (IO, No Lock)
        // We accept that two threads might load the same region concurrently.
        // The race is resolved in step 3 by prioritizing the cache.
        val loadedFromDisk = storage.loadRegion(id)

        // 3. Update Cache under Write Lock
        lock.write {
            // Re-check cache under lock (another thread may have won the race)
            region = regionCache.get(id)
            if (region != null) {
                return region!!
            }
            
            // Check Overflow
            region = overflowRegions[id]
            if (region != null) {
                // Found in overflow (pinned).
                // DO NOT promote back to Cache to avoid thrashing (Overflow -> Cache -> Evict -> Overflow).
                // Just refresh its position in the overflow map (LRU behavior for overflow).
                overflowRegions.remove(id)
                overflowRegions[id] = region!!
                return region!!
            }

            if (loadedFromDisk == null) {
                // Handle missing region - check index consistency
                if (regionIndex.containsKey(id)) {
                    Logger.e("RegionManager", "Region $id indexed but missing on disk! Data loss possible.")
                    removeRegionIndex(id)
                }
                region = RegionData(id)
            } else {
                region = loadedFromDisk
                region!!.rebuildQuadtree(regionSize)
            }

            regionCache.put(id, region!!)

            if (region!!.getSizeCached() > memoryLimitBytes) {
                Logger.w(
                    "RegionManager",
                    "Region $id size (${region!!.getSizeCached() / 1024 / 1024}MB) exceeds cache limit! Thrashing likely.",
                )
            }
        }

        return region!!
    }

    /**
     * Retrieves or generates a thumbnail for the region.
     * Optimized to perform heavy rendering OUTSIDE the lock to prevent UI stalls.
     */
    fun getRegionThumbnail(
        id: RegionId,
        context: android.content.Context,
    ): Bitmap? {
        // 1. Fast Cache Check
        var bitmap = thumbnailCache.get(id)
        if (bitmap != null) return bitmap

        // 2. Disk Check (IO, no lock)
        bitmap = storage.loadThumbnail(id)
        if (bitmap != null) {
            thumbnailCache.put(id, bitmap)
            return bitmap
        }

        // 3. Generation Needed
        // Ensure the primary region is loaded
        getRegion(id)

        // 3a. Smart Stitching: Identify all regions that overlap this tile.
        // This includes the region itself and any neighbors with strokes extending into this tile.
        val regionBounds =
            RectF(
                id.x * regionSize,
                id.y * regionSize,
                (id.x + 1) * regionSize,
                (id.y + 1) * regionSize,
            )
        val overlappingIds = getRegionIdsInRect(regionBounds)

        // 3b. Ensure all overlapping regions are loaded (IO outside lock)
        overlappingIds.forEach { getRegion(it) }

        // 4. Snapshot items under Read Lock
        // We collect items from ALL overlapping regions that actually intersect our tile.
        val itemsSnapshot = ArrayList<CanvasItem>()
        lock.read {
            overlappingIds.forEach { overlapId ->
                val r = regionCache.get(overlapId)
                r?.items?.forEach { item ->
                    if (RectF.intersects(item.bounds, regionBounds)) {
                        itemsSnapshot.add(item)
                    }
                }
            }
        }

        // 5. Generate Bitmap (Heavy CPU, No Lock)
        val newBitmap = generateThumbnailFromItems(id, itemsSnapshot, context) ?: return null

        // 6. Save to disk (IO, No Lock)
        storage.saveThumbnail(id, newBitmap)

        // 7. Update memory cache
        thumbnailCache.put(id, newBitmap)

        return newBitmap
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
            // Map world region bounds to 0..size
            // World: [x*size, y*size] to [x*size+size, y*size+size]
            // Local: [0, 0] to [128, 128]
            canvas.scale(scale, scale)
            canvas.translate(-id.x * regionSize, -id.y * regionSize)

            items.forEach { item ->
                StrokeRenderer.drawItem(canvas, item, false, paint, context, scale, true)
            }
            canvas.restore()

            return bitmap
        } catch (e: Exception) {
            Logger.e("RegionManager", "Failed to generate thumbnail for $id", e)
            return null
        }
    }

    private fun invalidateThumbnail(
        id: RegionId,
        regionHint: RegionData? = null,
    ) {
        thumbnailCache.remove(id)
        storage.deleteThumbnail(id)
    }

    fun getAvailableRegionsAndMissingIds(rect: RectF): Pair<List<RegionData>, List<RegionId>> {
        val foundProxies = ArrayList<CanvasItem>()
        val ids = ArrayList<RegionId>()

        lock.read {
            skeletonQuadtree.retrieve(foundProxies, rect)
        }
        ids.addAll(foundProxies.map { (it as RegionProxy).id }.distinct())

        val available = ArrayList<RegionData>()
        val missing = ArrayList<RegionId>()

        ids.forEach { id ->
            val region = regionCache.get(id)
            if (region != null) {
                available.add(region)
            } else {
                missing.add(id)
            }
        }
        return Pair(available, missing)
    }

    fun loadRegionsAsync(ids: List<RegionId>) {
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEach { id ->
                // Check if already loaded by another thread or cache hit
                if (regionCache.get(id) == null) {
                    val region = getRegion(id) // This handles load + cache put
                    onRegionLoaded?.invoke(region)
                }
            }
        }
    }

    fun getRegionReadOnly(id: RegionId): RegionData? {
        // 1. Fast Cache Check
        var region = regionCache.get(id)
        if (region != null) return region

        // 2. Check index existence first (No IO yet)
        val exists = lock.read { regionIndex.containsKey(id) }
        if (!exists) return null

        // 3. Load using standard optimization
        return getRegion(id)
    }

    fun addItem(item: CanvasItem) {
        val id = getRegionIdForItem(item)
        lock.write {
            var region = regionCache.get(id)
            if (region == null) {
                // Load and cache if not present
                region = getRegion(id)
            }

            // Remove from cache before modifying size to avoid LruCache inconsistency
            resizingId = id
            regionCache.remove(id)
            resizingId = null

            region.items.add(item)
            if (region.quadtree == null) region.rebuildQuadtree(regionSize)
            region.quadtree = region.quadtree?.insert(item)

            val wasEmpty = region.contentBounds.isEmpty
            if (wasEmpty) {
                region.contentBounds.set(item.bounds)
            } else {
                region.contentBounds.union(item.bounds)
            }

            updateRegionIndex(id, region.contentBounds)

            region.isDirty = true
            invalidateThumbnail(id, region)

            // Put back into cache with updated size
            region.invalidateSize()
            regionCache.put(id, region)
        }
    }

    fun removeItems(items: List<CanvasItem>) {
        val byRegion = items.groupBy { getRegionIdForItem(it) }

        lock.write {
            byRegion.forEach { (id, regionItems) ->
                // Ensure region is loaded
                var region = regionCache.get(id)
                if (region == null) {
                    region = getRegion(id)
                }

                // Remove from cache before modifying size
                resizingId = id
                regionCache.remove(id)
                resizingId = null

                region.items.removeAll(regionItems)
                regionItems.forEach { region.quadtree?.remove(it) }

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
                invalidateThumbnail(id, region)

                // Put back into cache with updated size
                region.invalidateSize()
                regionCache.put(id, region)
            }
        }
    }

    fun getRegionIdsInRect(rect: RectF): List<RegionId> {
        val foundProxies = ArrayList<CanvasItem>()
        lock.read {
            skeletonQuadtree.retrieve(foundProxies, rect)
        }
        return foundProxies.map { (it as RegionProxy).id }.distinct()
    }

    fun getRegionsInRect(rect: RectF): List<RegionData> {
        val foundProxies = ArrayList<CanvasItem>()
        val ids = ArrayList<RegionId>()

        lock.read {
            skeletonQuadtree.retrieve(foundProxies, rect)
        }

        ids.addAll(foundProxies.map { (it as RegionProxy).id }.distinct())

        if (ids.isNotEmpty()) {
            Logger.d("RegionManager", "Query $rect found ${ids.size} regions: $ids")
        }

        return ids.map { getRegion(it) }
    }

    fun getContentBounds(): RectF {
        val r = RectF()
        lock.read {
            if (regionIndex.isNotEmpty()) {
                val it = regionIndex.values.iterator()
                if (it.hasNext()) r.set(it.next())
                while (it.hasNext()) {
                    r.union(it.next())
                }
            }
        }
        return r
    }

    fun getActiveRegionIds(): Set<RegionId> {
        lock.read {
            return regionIndex.keys.toSet()
        }
    }

    fun setPinnedRegions(ids: Set<RegionId>) {
        lock.write {
            pinnedIds = ids
            
            // Re-integrate unpinned overflow items back to LRU
            if (overflowRegions.isNotEmpty()) {
                val iterator = overflowRegions.iterator()
                while (iterator.hasNext()) {
                    val (id, region) = iterator.next()
                    if (!pinnedIds.contains(id)) {
                        iterator.remove()
                        currentOverflowBytes -= region.getSizeCached()
                        // Put back to LRU. This might trigger eviction of other items,
                        // effectively prioritizing the just-unpinned item over oldest LRU items.
                        regionCache.put(id, region)
                    }
                }
            }
        }
    }

    fun clear() {
        lock.write {
            regionCache.evictAll()
            overflowRegions.clear()
            currentOverflowBytes = 0
            thumbnailCache.evictAll()
            regionIndex.clear()
            skeletonQuadtree.clear()
            regionProxies.clear()
        }
    }

    fun saveAll() {
        lock.write {
            val map = regionCache.snapshot()
            
            // Also save overflow regions
            overflowRegions.forEach { (_, region) -> 
                if (region.isDirty) {
                     if (storage.saveRegion(region)) {
                         region.isDirty = false
                     }
                }
            }
            
            map.forEach { (_, region) ->
                if (region.isDirty) {
                    if (region.items.isEmpty()) {
                        storage.deleteRegion(region.id)
                        region.isDirty = false
                    } else {
                        if (storage.saveRegion(region)) {
                            region.isDirty = false
                        } else {
                            Logger.e("RegionManager", "Failed to save dirty region ${region.id} during flush")
                        }
                    }
                }
            }
            if (!storage.saveIndex(regionIndex)) {
                Logger.e("RegionManager", "Failed to save index during flush!")
            }
        }
    }

    private fun updateRegionIndex(
        id: RegionId,
        bounds: RectF,
    ) {
        val newBounds = RectF(bounds)
        regionIndex[id] = newBounds
        Logger.d("RegionManager", "Updating index for $id: $newBounds")

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
        val proxy = regionProxies.remove(id)
        if (proxy != null) {
            skeletonQuadtree.remove(proxy)
        }
    }

    private fun getRegionIdForItem(item: CanvasItem): RegionId {
        val cx = item.bounds.centerX()
        val cy = item.bounds.centerY()
        val size = regionSize
        val x = floor(cx / size).toInt()
        val y = floor(cy / size).toInt()
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
        Logger.i(
            "RegionManager",
            "Skeleton Quadtree rebuilt from ${regionIndex.size} regions. Root bounds: ${skeletonQuadtree.getBounds()}",
        )
    }

    /**
     * Checks if the spatial index (Quadtree) is consistent with the flat index.
     * If corruption is detected (e.g. tree bounds don't cover content), it rebuilds the tree.
     */
    fun validateSpatialIndex() {
        lock.write {
            if (regionIndex.isEmpty()) return

            val treeBounds = skeletonQuadtree.getBounds()
            // Check if any region is outside the tree bounds (which implies Quadtree failed to grow or was reset)
            val contentBounds = getContentBoundsInternal() // Use internal version to avoid deadlock if we were using read lock (but we are in write lock now)

            if (!treeBounds.contains(contentBounds)) {
                Logger.w(
                    "RegionManager",
                    "Spatial Index Corruption Detected! Tree bounds $treeBounds do not cover content $contentBounds. Rebuilding...",
                )
                rebuildSkeletonQuadtree()
                return
            }

            // Heuristic: If we have many regions but tree is small/empty?
            // This catches the "No regions to process" case where tree might be disjoint.
            if (regionIndex.size > 0 && regionProxies.size != regionIndex.size) {
                Logger.w(
                    "RegionManager",
                    "Spatial Index Sync Mismatch! Proxies: ${regionProxies.size}, Index: ${regionIndex.size}. Rebuilding...",
                )
                rebuildSkeletonQuadtree()
            }
        }
    }

    // Internal version of getContentBounds that assumes lock is already held
    private fun getContentBoundsInternal(): RectF {
        val r = RectF()
        if (regionIndex.isNotEmpty()) {
            val it = regionIndex.values.iterator()
            if (it.hasNext()) r.set(it.next())
            while (it.hasNext()) {
                r.union(it.next())
            }
        }
        return r
    }
}
