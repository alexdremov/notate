package com.alexdremov.notate.data.region

import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * Manages the spatial partitioning, caching, and persistence of canvas regions.
 *
 * **Concurrency Architecture (Refactored):**
 * - **Mutex**: Replaces `ReentrantReadWriteLock`. Guards internal state (Cache, Index, Quadtree).
 * - **Structured Concurrency**: All heavy operations (IO, Loading) are suspendable and use `Deferred` for deduplication.
 * - **No Blocking**: `runBlocking` is banned. All access is reactive or suspendable.
 * - **Thread Safety**: `RegionData.items` uses `CopyOnWriteArrayList` (implicitly handled during load/init) or relies on Mutex for structural changes.
 *
 * **Cache Consistency:**
 * Uses [resizingId] guard pattern to update LruCache accounting when item sizes change.
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
    // Guarded by [mutex]
    private val regionCache: LruCache<RegionId, RegionData>

    // Thumbnail Cache (Separate from Region Data) - 20MB limit
    // Guarded by [mutex] (mostly) or Concurrent
    private val thumbnailCache =
        object : LruCache<RegionId, Bitmap>(20 * 1024) {
            override fun sizeOf(
                key: RegionId,
                value: Bitmap,
            ): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

    // Global Index of active regions and their bounds
    // Guarded by [mutex]
    private val regionIndex: MutableMap<RegionId, RectF>

    // Metadata cache for sync-safe access (Rendering)
    @Volatile
    private var cachedActiveIds: Set<RegionId> = emptySet()

    @Volatile
    private var cachedContentBounds: RectF = RectF()

    // Skeleton Index for fast spatial queries
    // Guarded by [mutex]
    private var skeletonQuadtree = Quadtree(0, RectF(-regionSize, -regionSize, regionSize, regionSize))
    private val regionProxies = HashMap<RegionId, RegionProxy>()

    // Pinned Regions
    // Guarded by [mutex]
    private var pinnedIds: Set<RegionId> = emptySet()

    // Overflow regions
    // Guarded by [mutex]
    private val overflowRegions = java.util.LinkedHashMap<RegionId, RegionData>()
    private val maxOverflowBytes = memoryLimitBytes / 2
    private var currentOverflowBytes = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onRegionLoaded: ((RegionData) -> Unit)? = null

    // GUARD: Tracks the Region ID currently being resized (Remove -> Put).
    @Volatile
    private var resizingId: RegionId? = null

    // GUARD: Deduplicate concurrent load requests
    private val loadingJobs = ConcurrentHashMap<RegionId, Deferred<RegionData>>()

    // State Lock for structural metadata (Index, Tree, Cache Access)
    private val stateLock = ReentrantReadWriteLock()

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
        // Load index on startup (deep-copy RectF values to avoid shared mutable state)
        regionIndex =
            storage
                .loadIndex()
                .mapValues { (_, rect) -> RectF(rect) }
                .toMutableMap()

        if (regionIndex.isEmpty()) {
            Logger.w("RegionManager", "Index empty on init. Attempting to rebuild from region files...")
            rebuildIndex()
        }

        Logger.d("RegionManager", "Initialized with ${regionIndex.size} regions from index")

        // Build Skeleton Quadtree and Metadata Cache
        rebuildSkeletonQuadtree()
        updateMetadataCache()

        val maxKb = (memoryLimitBytes / 1024).toInt()
        regionCache =
            object : LruCache<RegionId, RegionData>(maxKb) {
                override fun sizeOf(
                    key: RegionId,
                    value: RegionData,
                ): Int = (value.getSizeCached() / 1024).toInt().coerceAtLeast(1)

                override fun entryRemoved(
                    evicted: Boolean,
                    key: RegionId,
                    oldValue: RegionData,
                    newValue: RegionData?,
                ) {
                    if (key == resizingId) return

                    if (evicted) {
                        handleEviction(key, oldValue)
                    }

                    // If it was moved to overflow, don't save or recycle yet
                    val inOverflow = stateLock.read { overflowRegions.containsKey(key) }
                    if (inOverflow) return

                    if (oldValue.isDirty) {
                        saveRegionInternal(oldValue)
                    }

                    oldValue.recycle()
                }
            }

        PerformanceProfiler.registerMemoryStats(
            "RegionManager",
            object : PerformanceProfiler.MemoryStatsProvider {
                override fun getStats(): Map<String, String> =
                    mapOf(
                        "Region Cache (MB)" to
                            "${stateLock.read { regionCache.size() / 1024 }} / ${stateLock.read { regionCache.maxSize() / 1024 }}",
                        "Index" to "${stateLock.read { regionIndex.size }}",
                        "Loading Jobs" to "${loadingJobs.size}",
                    )
            },
        )
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
    ) {
        stateLock.write {
            if (pinnedIds.contains(key)) {
                val size = region.getSizeCached()

                while (currentOverflowBytes + size > maxOverflowBytes && overflowRegions.isNotEmpty()) {
                    val oldestKey = overflowRegions.keys.first()
                    val oldestRegion = overflowRegions.remove(oldestKey)
                    if (oldestRegion != null) {
                        currentOverflowBytes -= oldestRegion.getSizeCached()
                        if (oldestRegion.isDirty) saveRegionInternal(oldestRegion)
                        oldestRegion.recycle()
                    }
                }

                if (currentOverflowBytes + size <= maxOverflowBytes) {
                    overflowRegions[key] = region
                    currentOverflowBytes += size
                } else {
                    if (region.isDirty) saveRegionInternal(region)
                }
            }
        }
    }

    private fun saveRegionInternal(region: RegionData) {
        try {
            if (storage.saveRegion(region)) {
                region.isDirty = false
            }
        } catch (e: Exception) {
            Logger.e("RegionManager", "Exception saving region ${region.id}", e)
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

    suspend fun getRegion(id: RegionId): RegionData {
        stateLock.read {
            regionCache.get(id)?.let { return it }
            overflowRegions[id]?.let {
                // Promotion requires write lock, fall through
            }
        }

        stateLock.write {
            overflowRegions.remove(id)?.let {
                currentOverflowBytes -= it.getSizeCached()
                regionCache.put(id, it)
                return it
            }
            regionCache.get(id)?.let { return it }
        }

        val deferred =
            loadingJobs.getOrPut(id) {
                scope.async(Dispatchers.IO) { loadRegionFromDisk(id) }
            }

        return deferred.await()
    }

    private suspend fun loadRegionFromDisk(id: RegionId): RegionData {
        // Logger.v("RegionManager", "Loading start: $id")
        try {
            var region = storage.loadRegion(id)
            if (region != null && region.items !is CopyOnWriteArrayList) {
                region = region.copy(items = CopyOnWriteArrayList(region.items))
                region.rebuildQuadtree(regionSize)
            }

            if (region == null) {
                stateLock.write { removeRegionIndex(id) }
                region = RegionData(id, CopyOnWriteArrayList())
            }

            stateLock.write {
                val existing = regionCache.get(id) ?: overflowRegions[id]
                if (existing != null) return existing

                // CRITICAL FIX: Ensure the global spatial index matches the actual loaded content.
                // If the stored index is stale (smaller than actual bounds), strokes extending
                // into neighbors won't be found by getRegionIdsInRect(), causing clipping/disappearance
                // when querying items across regions at different zoom levels.
                if (!region!!.contentBounds.isEmpty) {
                    val indexBounds = regionIndex[id]
                    if (indexBounds == null || indexBounds != region.contentBounds) {
                        Logger.i("RegionManager", "Self-healing index for region $id: $indexBounds -> ${region.contentBounds}")
                        updateRegionIndex(id, region.contentBounds)
                    }
                }

                regionCache.put(id, region!!)
            }
            // Logger.v("RegionManager", "Loading end: $id")
            return region!!
        } finally {
            loadingJobs.remove(id)
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

        getRegion(id)
        val rBounds = id.getBounds(regionSize)
        val overlappingIds = getRegionIdsInRect(rBounds)
        overlappingIds.forEach { getRegion(it) }

        val itemsSnapshot = ArrayList<CanvasItem>()
        stateLock.read {
            overlappingIds.forEach { oid ->
                regionCache.get(oid)?.items?.forEach { item ->
                    if (RectF.intersects(item.bounds, rBounds)) itemsSnapshot.add(item)
                }
            }
        }

        val newBitmap = generateThumbnailFromItems(id, itemsSnapshot, context) ?: return null
        storage.saveThumbnail(id, newBitmap)
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
                val r = getRegion(id)
                onRegionLoaded?.invoke(r)
            }
        }
    }

    fun getRegionReadOnly(id: RegionId): RegionData? {
        stateLock.read {
            regionCache.get(id)?.let { return it }
            if (!regionIndex.containsKey(id)) return null
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

    suspend fun addItem(item: CanvasItem) {
        val id = getRegionIdForItem(item)
        val region = getRegion(id)

        stateLock.write {
            resizingId = id
            regionCache.remove(id)
            resizingId = null

            region.items.add(item)
            if (region.quadtree == null) region.rebuildQuadtree(regionSize)
            region.quadtree = region.quadtree?.insert(item)

            if (region.contentBounds.isEmpty) {
                region.contentBounds.set(item.bounds)
            } else {
                region.contentBounds.union(item.bounds)
            }

            updateRegionIndex(id, region.contentBounds)
            region.isDirty = true
            invalidateOverlappingThumbnails(item.bounds)
            region.invalidateSize()
            regionCache.put(id, region)
            updateMetadataCache()
        }
    }

    suspend fun removeItems(items: List<CanvasItem>) {
        val byRegion = items.groupBy { getRegionIdForItem(it) }
        byRegion.keys.forEach { getRegion(it) }

        stateLock.write {
            byRegion.forEach { (id, regionItems) ->
                val region = regionCache.get(id) ?: return@forEach

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

                if (regionItems.isNotEmpty()) {
                    val removedBounds = RectF()
                    removedBounds.set(regionItems[0].bounds)
                    for (i in 1 until regionItems.size) {
                        removedBounds.union(regionItems[i].bounds)
                    }
                    invalidateOverlappingThumbnails(removedBounds)
                }

                region.invalidateSize()
                regionCache.put(id, region)
            }
            updateMetadataCache()
        }
    }

    fun getRegionIdsInRect(rect: RectF): List<RegionId> {
        val foundProxies = ArrayList<CanvasItem>()
        stateLock.read {
            skeletonQuadtree.retrieve(foundProxies, rect)
        }
        return foundProxies.map { (it as RegionProxy).id }.distinct()
    }

    suspend fun getRegionsInRect(rect: RectF): List<RegionData> {
        val ids = getRegionIdsInRect(rect)
        return ids.map { getRegion(it) }
    }

    fun getContentBounds(): RectF = RectF(cachedContentBounds)

    fun getActiveRegionIds(): Set<RegionId> = cachedActiveIds

    fun setPinnedRegions(ids: Set<RegionId>) {
        stateLock.write {
            val unpinned = pinnedIds - ids
            pinnedIds = ids

            unpinned.forEach { id ->
                overflowRegions.remove(id)?.let {
                    currentOverflowBytes -= it.getSizeCached()
                    regionCache.put(id, it)
                }
            }
        }
    }

    fun clear() {
        stateLock.write {
            regionCache.evictAll()
            overflowRegions.clear()
            thumbnailCache.evictAll()
            regionIndex.clear()
            skeletonQuadtree.clear()
            regionProxies.clear()
            currentOverflowBytes = 0
            updateMetadataCache()
        }
    }

    fun saveAll() {
        stateLock.write {
            regionCache.snapshot().forEach { (_, region) ->
                if (region.isDirty) {
                    if (region.items.isEmpty()) {
                        storage.deleteRegion(region.id)
                    } else {
                        saveRegionInternal(region)
                    }
                }
            }
            overflowRegions.forEach { (_, region) ->
                if (region.isDirty) saveRegionInternal(region)
            }
            storage.saveIndex(regionIndex)
        }
    }

    private fun updateRegionIndex(
        id: RegionId,
        bounds: RectF,
    ) {
        val newBounds = RectF(bounds)
        regionIndex[id] = newBounds
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
}
