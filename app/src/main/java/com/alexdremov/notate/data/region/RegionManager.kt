package com.alexdremov.notate.data.region

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.util.Logger
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

    // Global Index of active regions and their bounds
    private val regionIndex: MutableMap<RegionId, RectF>

    // Skeleton Index for fast spatial queries
    private var skeletonQuadtree = Quadtree(0, RectF(-regionSize, -regionSize, regionSize, regionSize))
    private val regionProxies = HashMap<RegionId, RegionProxy>()

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
        regionIndex.forEach { (id, bounds) ->
            val proxy = RegionProxy(id, bounds)
            regionProxies[id] = proxy
            skeletonQuadtree = skeletonQuadtree.insert(proxy)
        }
        Logger.d("RegionManager", "Skeleton Quadtree built. Root bounds: ${skeletonQuadtree.getBounds()}")

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
                        Logger.d("RegionManager", "Evicting region $key (Size: ${oldValue.getSizeCached() / 1024}KB)")
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
        var region = regionCache.get(id)
        if (region?.cachedThumbnail != null) return region!!.cachedThumbnail

        // 2. Disk Check (IO, no lock)
        var bitmap = storage.loadThumbnail(id)
        if (bitmap != null) {
            // Hit! Update memory cache if loaded
            lock.write {
                region = regionCache.get(id)
                if (region != null) {
                    // Atomic Resize Dance
                    resizingId = id
                    regionCache.remove(id)
                    resizingId = null

                    region!!.cachedThumbnail = bitmap
                    region!!.invalidateSize()

                    regionCache.put(id, region!!)
                }
            }
            return bitmap
        }

        // 3. Generation Needed
        // Load region (handles its own locking optimization)
        val loadedRegion = getRegion(id)

        // 4. Snapshot items under Read Lock
        // This avoids holding the lock during the heavy draw call below.
        val itemsSnapshot = lock.read { ArrayList(loadedRegion.items) }

        // 5. Generate Bitmap (Heavy CPU, No Lock)
        val newBitmap = generateThumbnailFromItems(id, itemsSnapshot, context) ?: return null

        // 6. Save to disk (IO, No Lock)
        storage.saveThumbnail(id, newBitmap)

        // 7. Update memory cache (Fast, Write Lock)
        lock.write {
            region = regionCache.get(id)
            if (region != null) {
                resizingId = id
                regionCache.remove(id)
                resizingId = null

                region!!.cachedThumbnail = newBitmap
                region!!.invalidateSize()

                regionCache.put(id, region!!)
            }
        }

        return newBitmap
    }

    private fun generateThumbnailFromItems(
        id: RegionId,
        items: List<CanvasItem>,
        context: android.content.Context,
    ): Bitmap? {
        val targetSize = 128f
        val scale = targetSize / regionSize
        val size = (regionSize * scale).toInt()
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
        // Called while holding lock.write
        val region = regionHint ?: regionCache.get(id)
        if (region != null) {
            val inCache = regionCache.get(id) != null
            if (inCache) {
                resizingId = id
                regionCache.remove(id)
                resizingId = null
            }

            region.cachedThumbnail = null
            region.invalidateSize()

            if (inCache) {
                regionCache.put(id, region)
            }
        }
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

    fun clear() {
        lock.write {
            regionCache.evictAll()
            regionIndex.clear()
            skeletonQuadtree.clear()
            regionProxies.clear()
        }
    }

    fun saveAll() {
        lock.write {
            val map = regionCache.snapshot()
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
}
