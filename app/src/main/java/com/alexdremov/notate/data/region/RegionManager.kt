package com.alexdremov.notate.data.region

import android.graphics.RectF
import android.util.LruCache
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.Quadtree
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

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
                    // Returns size in KB
                    return (value.sizeBytes() / 1024).toInt().coerceAtLeast(1)
                }

                override fun entryRemoved(
                    evicted: Boolean,
                    key: RegionId,
                    oldValue: RegionData,
                    newValue: RegionData?,
                ) {
                    if (evicted) {
                        Logger.d("RegionManager", "Evicting region $key (Size: ${oldValue.sizeBytes() / 1024}KB)")
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
            storage.saveIndex(regionIndex)
            Logger.i("RegionManager", "Index rebuilt with $loadedCount regions and saved.")
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? = storage.importImage(uri, context)

    fun getRegion(id: RegionId): RegionData {
        // 1. Fast Cache Check (Thread-safe via LruCache)
        var region = regionCache.get(id)
        if (region != null) {
            return region
        }

        // 2. Load from Disk (No Global Lock to avoid blocking readers)
        // We accept potential double-loading race condition here as harmless/rare
        region = storage.loadRegion(id)

        if (region == null) {
            // Handle missing region logic
            // Need lock only if we suspect index inconsistency
            var inIndex = false
            lock.read { inIndex = regionIndex.containsKey(id) }

            if (inIndex) {
                lock.write {
                    if (regionIndex.containsKey(id)) {
                        Logger.e("RegionManager", "Region $id indexed but missing on disk! Data loss possible.")
                        removeRegionIndex(id)
                    }
                }
            }
            region = RegionData(id)
        } else {
            region.rebuildQuadtree(regionSize)
        }

        regionCache.put(id, region)

        if (region.sizeBytes() > memoryLimitBytes) {
            Logger.w("RegionManager", "Region $id size (${region.sizeBytes() / 1024 / 1024}MB) exceeds cache limit! Thrashing likely.")
        }

        return region
    }

    fun getRegionReadOnly(id: RegionId): RegionData? {
        // 1. Fast Cache Check
        var region = regionCache.get(id)
        if (region != null) return region

        // 2. Load from Disk (No Global Lock)
        // Check index existence first to avoid useless IO (Index is in memory, fast check)
        // Accessing regionIndex needs Read Lock? It's ConcurrentHashMap? No, MutableMap.
        // But reads are usually safe if writes are atomic.
        // Safest is to use lock.read for index check.

        var exists = false
        lock.read {
            exists = regionIndex.containsKey(id)
        }

        if (exists) {
            region = storage.loadRegion(id)
            if (region != null) {
                region.rebuildQuadtree(regionSize)
                regionCache.put(id, region)
            }
        }

        return region
    }

    fun addItem(item: CanvasItem) {
        val id = getRegionIdForItem(item)
        lock.write {
            var region = regionCache.get(id)
            if (region == null) {
                region = getRegion(id)
            }

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
        }
    }

    fun removeItems(items: List<CanvasItem>) {
        val byRegion = items.groupBy { getRegionIdForItem(it) }

        lock.write {
            byRegion.forEach { (id, regionItems) ->
                val region = getRegion(id)
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
            }
        }
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
            storage.saveIndex(regionIndex)
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
