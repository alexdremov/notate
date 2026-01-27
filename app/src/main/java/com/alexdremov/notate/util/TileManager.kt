package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/**
 * Coordinates the Level-of-Detail (LOD) tiled rendering system for the infinite canvas.
 *
 * ## Responsibilities
 * 1. **LOD Calculation**: Determines zoom level from scale factor
 * 2. **Tile Visibility**: Calculates which tiles are visible in the current viewport
 * 3. **Background Generation**: Queues tile bitmap generation on worker threads
 * 4. **Cache Management**: Manages LRU cache with memory-aware eviction
 * 5. **Fallback Rendering**: Shows lower-resolution parent tiles while generating
 *
 * ## LOD System
 * Each LOD level represents a 2x scale factor:
 * - Level 0: 1: 1 scale (512px tile = 512 world units)
 * - Level 1: 1:2 scale (512px tile = 1024 world units)
 * - Level -1: 2: 1 scale (512px tile = 256 world units)
 *
 * ## Thread Model
 * - Rendering calls happen on UI thread
 * - Tile generation happens on [dispatcher] (default: Dispatchers.Default)
 * - Cache operations are synchronized
 *
 * @param canvasModel The data model to query for strokes
 * @param renderer The renderer for drawing strokes to tile bitmaps
 * @param tileSize Pixel size of each tile (default: 512)
 * @param scope Coroutine scope for background tile generation
 * @param dispatcher Dispatcher for background generation (default: Dispatchers.Default)
 */
@OptIn(FlowPreview::class)
class TileManager(
    private val context: android.content.Context,
    private val canvasModel: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
    private val tileSize: Int = CanvasConfig.TILE_SIZE,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var onTileReady: (() -> Unit)? = null
    var isInteracting: Boolean = false

    private val tileCache = TileCache(tileSize)

    // State Tracking
    private val generatingKeys = Collections.synchronizedSet(HashSet<TileCache.TileKey>())
    private val generationJobs = ConcurrentHashMap<TileCache.TileKey, Job>()
    private val generationSemaphore = Semaphore(32) // Limit concurrent heavy rendering

    private val renderVersion = AtomicInteger(0)
    private var lastRenderLevel = -1
    private var lastVisibleRect: RectF? = null
    private var lastPrefetchRect: RectF? = null
    private var lastScale: Float = 1.0f
    private var lastVisibleCount = 0

    // Lifecycle
    private val initJobs = mutableListOf<Job>()

    // Update Throttling
    private val updateChannel = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val regionLoadedChannel = Channel<RectF>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Debugging
    private val errorMessages = LruCache<TileCache.TileKey, String>(CanvasConfig.ERROR_CACHE_SIZE)

    private val debugPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = CanvasConfig.DEBUG_STROKE_WIDTH_BASE
        }

    private val debugTextPaint =
        Paint().apply {
            color = Color.RED
            textSize = CanvasConfig.DEBUG_TEXT_SIZE_BASE
            isAntiAlias = true
        }

    private val eraserPaint =
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

    init {
        // Listen for Model Updates
        initJobs +=
            scope.launch {
                canvasModel.events.collect { event ->
                    when (event) {
                        is InfiniteCanvasModel.ModelEvent.ItemsRemoved -> {
                            val bounds = RectF()
                            event.items.forEach { bounds.union(it.bounds) }
                            refreshTiles(bounds)
                        }

                        is InfiniteCanvasModel.ModelEvent.ItemsAdded -> {
                            // Handle operations like Paste, Undo, Redo
                            val bounds = RectF()
                            event.items.forEach { bounds.union(it.bounds) }
                            refreshTiles(bounds)
                        }

                        is InfiniteCanvasModel.ModelEvent.ContentCleared -> {
                            clear()
                            notifyTileReady()
                        }

                        is InfiniteCanvasModel.ModelEvent.RegionLoaded -> {
                            regionLoadedChannel.trySend(event.bounds)
                        }

                        else -> {}
                    }
                }
            }

        // Debounce RegionLoaded events to prevent version thrashing
        initJobs +=
            scope.launch {
                regionLoadedChannel
                    .receiveAsFlow()
                    .debounce(100L)
                    .collect { bounds ->
                        // Collect all pending bounds into one refresh
                        val unionBounds = RectF(bounds)
                        while (true) {
                            val next = regionLoadedChannel.tryReceive().getOrNull() ?: break
                            unionBounds.union(next)
                        }
                        refreshTiles(unionBounds)
                    }
            }

        // Throttle UI updates: debounce based on TILE_MANAGER_TARGET_FPS (caps update rate)
        initJobs +=
            scope.launch {
                updateChannel
                    .receiveAsFlow()
                    .debounce(1000L / CanvasConfig.TILE_MANAGER_TARGET_FPS)
                    .collectLatest {
                        withContext(Dispatchers.Main) {
                            onTileReady?.invoke()
                        }
                    }
            }

        PerformanceProfiler.registerMemoryStats(
            "TileManager",
            object : PerformanceProfiler.MemoryStatsProvider {
                override fun getStats(): Map<String, String> {
                    val stats = tileCache.getStats().toMutableMap()
                    stats["Generating"] = synchronized(generatingKeys) { generatingKeys.size }.toString()
                    stats["Active Jobs"] = generationJobs.size.toString()
                    stats["Semaphore Permits"] = generationSemaphore.availablePermits.toString()

                    val jobs = generationJobs.keys.toList()
                    if (jobs.isNotEmpty()) {
                        val levels = jobs.map { it.level }.distinct().sorted()
                        stats["Job Levels"] = levels.joinToString()

                        // Analyze dominant level
                        val dominantLevel =
                            jobs
                                .groupBy { it.level }
                                .maxByOrNull { it.value.size }
                                ?.key ?: levels.first()

                        val levelJobs = jobs.filter { it.level == dominantLevel }
                        val minCol = levelJobs.minOf { it.col }
                        val maxCol = levelJobs.maxOf { it.col }
                        val minRow = levelJobs.minOf { it.row }
                        val maxRow = levelJobs.maxOf { it.row }
                        stats["L$dominantLevel Bounds"] = "[$minCol,$minRow] to [$maxCol,$maxRow] (${levelJobs.size} tiles)"
                    }

                    return stats
                }
            },
        )
    }

    private var lastLogRect: RectF? = null

    /**
     * Main entry point for drawing tiled content.
     */
    fun render(
        canvas: Canvas,
        visibleRect: RectF,
        scale: Float,
    ) {
        com.alexdremov.notate.util.PerformanceProfiler.trace("TileManager.render") {
            this.lastVisibleRect = RectF(visibleRect)
            this.lastScale = scale

            val level = calculateLOD(scale)

            val worldTileSize = calculateWorldTileSize(level)

            // Calculate visible range
            val startCol = floor(visibleRect.left / worldTileSize).toInt()
            val endCol = floor(visibleRect.right / worldTileSize).toInt()
            val startRow = floor(visibleRect.top / worldTileSize).toInt()
            val endRow = floor(visibleRect.bottom / worldTileSize).toInt()

            if (lastLogRect == null || !lastLogRect!!.equals(visibleRect) || lastRenderLevel != level) {
                Logger.d(
                    "TileManager",
                    "Render: Rect=$visibleRect Scale=$scale Level=$level Cols=$startCol..$endCol Rows=$startRow..$endRow",
                )
                lastLogRect = RectF(visibleRect)
            }

            // Level Switch Strategy: Hard Reset
            if (level != lastRenderLevel) {
                Logger.i("TileManager", "LOD Switch: L$lastRenderLevel -> L$level. Cancelling all jobs.")
                cancelStaleJobs(emptySet())
                lastRenderLevel = level
                renderVersion.incrementAndGet()
            }
            val currentVersion = renderVersion.get()

            // Cache Management
            val visibleCount = (endCol - startCol + 1) * (endRow - startRow + 1)

            if (visibleCount > lastVisibleCount) {
                tileCache.checkBudgetAndResizeIfNeeded(visibleCount)
                lastVisibleCount = visibleCount
            }

            // 0. Prefetch Regions (Speculative Fetching)
            prefetchRegions(visibleRect, worldTileSize)

            val validKeys = HashSet<TileCache.TileKey>()

            // 1. Draw Visible Tiles
            for (col in startCol..endCol) {
                for (row in startRow..endRow) {
                    val key = TileCache.TileKey(col, row, level)
                    validKeys.add(key)
                    drawOrQueueTile(canvas, col, row, level, worldTileSize, true, currentVersion, scale)
                }
            }

            // 2. Pre-cache Neighbors if Idle
            if (!isInteracting) {
                val buffer = CanvasConfig.NEIGHBOR_COUNT
                for (col in (startCol - buffer)..(endCol + buffer)) {
                    for (row in (startRow - buffer)..(endRow + buffer)) {
                        if (col in startCol..endCol && row in startRow..endRow) continue
                        val key = TileCache.TileKey(col, row, level)
                        validKeys.add(key)
                    }
                }
            }

            cancelStaleJobs(validKeys)

            validKeys.forEach { key ->
                val isVisible = (startCol <= key.col) && (key.col <= endCol) && (startRow <= key.row) && (key.row <= endRow)
                drawOrQueueTile(canvas, key.col, key.row, key.level, worldTileSize, isVisible, currentVersion, scale)
            }

            if (CanvasConfig.DEBUG_SHOW_REGIONS) {
                drawRegionDebugOverlay(canvas, scale)
            }
        }
    }

    private fun cancelStaleJobs(validKeys: Set<TileCache.TileKey>) {
        generationJobs.forEach { (key, job) ->
            if (!validKeys.contains(key)) {
                job.cancel()
                tileCache.remove(key)
            }
        }
    }

    private fun drawOrQueueTile(
        canvas: Canvas,
        col: Int,
        row: Int,
        level: Int,
        worldSize: Float,
        isVisible: Boolean,
        version: Int,
        scale: Float,
    ) {
        val key = TileCache.TileKey(col, row, level)
        val bitmap = tileCache.get(key)
        val dstRect = getTileWorldRect(col, row, worldSize)

        if (bitmap != null) {
            if (bitmap != tileCache.errorBitmap) {
                canvas.drawBitmap(bitmap, null, dstRect, null)
            }
            if (CanvasConfig.DEBUG_SHOW_TILES) drawDebugOverlay(canvas, dstRect, key, bitmap, scale)
        } else {
            queueTileGeneration(col, row, level, worldSize, isVisible, version)
            if (isVisible) {
                if (!drawFallbackParent(canvas, col, row, level, worldSize)) {
                    drawFallbackChildren(canvas, col, row, level, worldSize)
                }
            }
        }
    }

    private fun drawFallbackParent(
        canvas: Canvas,
        col: Int,
        row: Int,
        level: Int,
        worldSize: Float,
    ): Boolean {
        // Search up the LOD pyramid for a lower-res cached parent
        for (offset in 1..5) {
            val pLevel = level + offset
            if (pLevel > CanvasConfig.MAX_ZOOM_LEVEL) break

            val pCol = col shr offset
            val pRow = row shr offset
            val pKey = TileCache.TileKey(pCol, pRow, pLevel)

            val pBitmap = tileCache.get(pKey)
            if (pBitmap != null && pBitmap != tileCache.errorBitmap) {
                val pWorldSize = worldSize * (1 shl offset).toFloat()
                val pDstRect = getTileWorldRect(pCol, pRow, pWorldSize)

                // We must clip the parent to strictly the target tile area
                // otherwise we draw over neighbors
                canvas.save()
                val targetRect = getTileWorldRect(col, row, worldSize)
                canvas.clipRect(targetRect)
                canvas.drawBitmap(pBitmap, null, pDstRect, null)
                canvas.restore()
                return true
            }
        }
        return false
    }

    private fun drawFallbackChildren(
        canvas: Canvas,
        col: Int,
        row: Int,
        level: Int,
        worldSize: Float,
    ) {
        // Search down the LOD pyramid (higher resolution children)
        // We limit depth to avoid excessive iteration
        val maxDepth = 2

        // Recursive helper
        fun drawRecursive(
            c: Int,
            r: Int,
            l: Int,
            depth: Int,
        ) {
            if (depth > maxDepth || l < CanvasConfig.MIN_ZOOM_LEVEL) return

            val key = TileCache.TileKey(c, r, l)
            val bitmap = tileCache.get(key)

            if (bitmap != null && bitmap != tileCache.errorBitmap) {
                val size = calculateWorldTileSize(l)
                val rect = getTileWorldRect(c, r, size)
                canvas.drawBitmap(bitmap, null, rect, null)
                return
            }

            // Not found, try children
            val nextL = l - 1
            val nextC = c shl 1
            val nextR = r shl 1

            // 4 Children
            drawRecursive(nextC, nextR, nextL, depth + 1)
            drawRecursive(nextC + 1, nextR, nextL, depth + 1)
            drawRecursive(nextC, nextR + 1, nextL, depth + 1)
            drawRecursive(nextC + 1, nextR + 1, nextL, depth + 1)
        }

        // Start recursion from immediate children
        val startL = level - 1
        val startC = col shl 1
        val startR = row shl 1

        drawRecursive(startC, startR, startL, 1)
        drawRecursive(startC + 1, startR, startL, 1)
        drawRecursive(startC, startR + 1, startL, 1)
        drawRecursive(startC + 1, startR + 1, startL, 1)
    }

    private fun queueTileGeneration(
        col: Int,
        row: Int,
        level: Int,
        worldSize: Float,
        isHighPriority: Boolean,
        version: Int,
        forceRefresh: Boolean = false,
    ) {
        val key = TileCache.TileKey(col, row, level)
        val startTime = System.currentTimeMillis()

        // Use synchronized block for atomic check-and-add
        synchronized(generatingKeys) {
            if (!forceRefresh && (generatingKeys.contains(key) || tileCache.get(key) != null)) return

            // Throttle low-priority background work if cache is pressured
            if (!forceRefresh && !isHighPriority &&
                tileCache.isFull(generatingKeys.size, CanvasConfig.NEIGHBOR_PRECACHE_THRESHOLD_PERCENT)
            ) {
                return
            }

            generatingKeys.add(key)
        }

        // Cancel existing job for this key if it's still running
        generationJobs.remove(key)?.cancel()

        val job =
            scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    Logger.v("TileManager", "Job Start: $key")
                    // Task Cancellation Checks
                    if (!isActive || version != renderVersion.get() || (!isHighPriority && isInteracting)) {
                        return@launch
                    }

                    // Stale Check: Visibility (Double Check)
                    // If it was supposed to be visible (High Priority) but is now off-screen, drop it.
                    val currentVisible = lastVisibleRect
                    if (currentVisible != null && isHighPriority && !forceRefresh) {
                        val tileRect = getTileWorldRect(col, row, worldSize)
                        if (!RectF.intersects(tileRect, currentVisible)) {
                            return@launch
                        }
                    }

                    // Limit concurrent heavy rendering to prevent OOM
                    val bitmap =
                        generationSemaphore.withPermit {
                            if (!isActive || version != renderVersion.get()) return@withPermit null
                            generateTileBitmap(col, row, worldSize, level)
                        }

                    if (bitmap == null || !isActive) return@launch

                    // Final check before committing to cache - ensure we are still on the same version
                    if (version == renderVersion.get()) {
                        if (forceRefresh || tileCache.get(key) == null) {
                            tileCache.put(key, bitmap)
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        errorMessages.put(key, "${t.javaClass.simpleName}: ${t.message}")
                        tileCache.put(key, tileCache.errorBitmap)
                    }
                }
            }

        // CRITICAL FIX: Use invokeOnCompletion to ensure cleanup happens even if the job
        // is cancelled before it starts (which skips the body and any try-finally blocks).
        job.invokeOnCompletion {
            val wasRemoved = generationJobs.remove(key, job)
            if (wasRemoved) {
                synchronized(generatingKeys) { generatingKeys.remove(key) }
            }
            notifyTileReady()
        }

        generationJobs[key] = job
        job.start()
    }

    private suspend fun generateTileBitmap(
        col: Int,
        row: Int,
        worldSize: Float,
        level: Int, // Added level for logging
    ): Bitmap? =
        com.alexdremov.notate.util.PerformanceProfiler.trace("TileManager.generateTileBitmap") {
            Logger.v("TileManager", "Generating: $col,$row L$level")

            val worldRect = getTileWorldRect(col, row, worldSize)
            val scale = tileSize.toFloat() / worldSize

            val rm = canvasModel.getRegionManager()

            // 1. Identify necessary regions
            val regionIds = rm?.getRegionIdsInRect(worldRect) ?: emptyList()

            // 2. Prime the Cache (Async & Cancellable)
            // We ensure all regions are loaded before asking the model to query items.
            // This prevents 'queryItems' (synchronous) from triggering 'runBlocking' inside RegionManager,
            // which avoids thread starvation and deadlocks.
            regionIds.forEach { id ->
                if (!coroutineContext.isActive) return@trace null
                rm?.getRegion(id)
            }

            // 3. Fetch Data (Lightweight, IO-bound, No Bitmap Allocation)
            // Query items (Synchronous, but fast now as cache is primed)
            val items = canvasModel.queryItems(worldRect)

            // 4. Check Cancellation
            if (!coroutineContext.isActive) return@trace null

            // 5. Allocate Bitmap (Late Allocation)
            val bitmap = tileCache.obtainBitmap()
            bitmap.eraseColor(Color.TRANSPARENT)
            val tileCanvas = Canvas(bitmap)

            // 6. Render
            renderItems(tileCanvas, items, worldRect, scale)

            bitmap
        }

    private suspend fun renderItems(
        canvas: Canvas,
        items: List<com.alexdremov.notate.model.CanvasItem>,
        worldRect: RectF,
        scale: Float,
    ) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-worldRect.left, -worldRect.top)

        Logger.v("TileManager", "  Found ${items.size} items")

        // We can sort in place or copy. Query returns ArrayList so it's mutable?
        // queryItems returns ArrayList, let's assume mutable or copy.
        val sortedItems = items.sortedWith(compareBy<com.alexdremov.notate.model.CanvasItem> { it.zIndex }.thenBy { it.order })

        for (item in sortedItems) {
            yield() // Check cancellation
            renderer.drawItemToCanvas(canvas, item, scale = scale)
        }
        canvas.restore()
    }

    fun updateTilesWithItem(item: com.alexdremov.notate.model.CanvasItem) {
        if (item is Stroke && item.style == com.alexdremov.notate.model.StrokeType.HIGHLIGHTER) {
            refreshTiles(item.bounds)
            return
        }

        val bounds = item.bounds
        val snapshot = tileCache.snapshot()
        val version = renderVersion.get()
        val visibleRect = lastVisibleRect
        val currentLevel = if (visibleRect != null) calculateLOD(lastScale) else -1

        // Use a set for efficient intersection checks during update
        val handledKeys = HashSet<TileCache.TileKey>()

        // 1. Update/Clean Cached Tiles
        for ((key, bitmap) in snapshot) {
            if (bitmap == null || bitmap.isRecycled || bitmap == tileCache.errorBitmap) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                val isVisible = visibleRect != null && key.level == currentLevel && RectF.intersects(visibleRect, tileRect)

                // Update ANY intersected cached bitmap, regardless of visibility (level),
                // to preserve fallback tiles (parents/children) for smooth zooming.
                val tileCanvas = Canvas(bitmap)
                val scale = tileSize.toFloat() / worldSize
                tileCanvas.save()
                tileCanvas.scale(scale, scale)
                tileCanvas.translate(-tileRect.left, -tileRect.top)
                renderer.drawItemToCanvas(tileCanvas, item, scale = scale)
                tileCanvas.restore()

                // Re-queue to ensure final consistency if background tasks were active.
                // If we don't restart, the background task (started before item addition)
                // might overwrite our manual update with a bitmap missing the new item.
                val isBeingGenerated = synchronized(generatingKeys) { generatingKeys.contains(key) }
                if (isBeingGenerated) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }
                handledKeys.add(key)
            }
        }

        // 2. Handle Generating Tiles that weren't in snapshot but intersect the new item
        val currentGenerating = synchronized(generatingKeys) { HashSet(generatingKeys) }
        for (key in currentGenerating) {
            if (handledKeys.contains(key)) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                // Re-queue generation if it's potentially visible
                val isVisible = visibleRect == null || (key.level == currentLevel && RectF.intersects(visibleRect, tileRect))
                if (isVisible) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }
            }
        }

        notifyTileReady()
    }

    fun updateTilesWithItems(items: List<com.alexdremov.notate.model.CanvasItem>) {
        if (items.isEmpty()) return

        // Separate highlighters (require full refresh due to blending) vs standard items
        val (highlighters, standardItems) =
            items.partition {
                it is Stroke && it.style == com.alexdremov.notate.model.StrokeType.HIGHLIGHTER
            }

        // 1. Handle Highlighters (Force Refresh)
        if (highlighters.isNotEmpty()) {
            val unionBounds = RectF(highlighters[0].bounds)
            for (i in 1 until highlighters.size) unionBounds.union(highlighters[i].bounds)
            refreshTiles(unionBounds)
        }

        if (standardItems.isEmpty()) return

        // 2. Handle Standard Items (Batch Draw)
        val unionBounds = RectF(standardItems[0].bounds)
        for (i in 1 until standardItems.size) unionBounds.union(standardItems[i].bounds)

        val snapshot = tileCache.snapshot()
        val version = renderVersion.get()
        val visibleRect = lastVisibleRect
        val currentLevel = if (visibleRect != null) calculateLOD(lastScale) else -1

        val handledKeys = HashSet<TileCache.TileKey>()

        // Update Cached Tiles
        for ((key, bitmap) in snapshot) {
            if (bitmap == null || bitmap.isRecycled || bitmap == tileCache.errorBitmap) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            // Fast Check: Does tile intersect the collective bounds?
            if (RectF.intersects(unionBounds, tileRect)) {
                val isVisible = visibleRect != null && key.level == currentLevel && RectF.intersects(visibleRect, tileRect)

                // Update ANY intersected cached bitmap, regardless of visibility (level),
                // to preserve fallback tiles (parents/children) for smooth zooming.
                val tileCanvas = Canvas(bitmap)
                val scale = tileSize.toFloat() / worldSize
                tileCanvas.save()
                tileCanvas.scale(scale, scale)
                tileCanvas.translate(-tileRect.left, -tileRect.top)

                // Batch Draw Intersecting Items
                // Optimization: Filter items intersecting this specific tile
                for (item in standardItems) {
                    if (RectF.intersects(item.bounds, tileRect)) {
                        renderer.drawItemToCanvas(tileCanvas, item, scale = scale)
                    }
                }
                tileCanvas.restore()

                // Re-queue logic
                val isBeingGenerated = synchronized(generatingKeys) { generatingKeys.contains(key) }
                if (isBeingGenerated) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }

                handledKeys.add(key)
            }
        }

        // Handle Generating Tiles
        val currentGenerating = synchronized(generatingKeys) { HashSet(generatingKeys) }
        for (key in currentGenerating) {
            if (handledKeys.contains(key)) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(unionBounds, tileRect)) {
                val isVisible = visibleRect == null || (key.level == currentLevel && RectF.intersects(visibleRect, tileRect))
                if (isVisible) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }
            }
        }

        notifyTileReady()
    }

    fun updateTilesWithErasure(stroke: Stroke) {
        val bounds = stroke.bounds
        val snapshot = tileCache.snapshot()
        val version = renderVersion.get()
        val visibleRect = lastVisibleRect
        val currentLevel = if (visibleRect != null) calculateLOD(lastScale) else -1

        eraserPaint.strokeWidth = stroke.width

        // Use a set for efficient intersection checks during update
        val handledKeys = HashSet<TileCache.TileKey>()

        // 1. Update/Clean Cached Tiles
        for ((key, bitmap) in snapshot) {
            if (bitmap == null || bitmap.isRecycled || bitmap == tileCache.errorBitmap) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                // Update ALL intersected cached tiles in-place to prevent flash/inconsistency.
                // We rely on LRU to evict them if they are truly off-screen/stale.
                val tileCanvas = Canvas(bitmap)
                val scale = tileSize.toFloat() / worldSize
                tileCanvas.save()
                tileCanvas.scale(scale, scale)
                tileCanvas.translate(-tileRect.left, -tileRect.top)

                // Draw Eraser Path instantly on UI thread
                tileCanvas.drawPath(stroke.path, eraserPaint)
                tileCanvas.restore()

                // If currently generating, queue refresh to ensure consistency
                val isBeingGenerated = synchronized(generatingKeys) { generatingKeys.contains(key) }
                if (isBeingGenerated) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }

                handledKeys.add(key)
            }
        }

        // 2. Handle Generating Tiles that weren't in snapshot
        val currentGenerating = synchronized(generatingKeys) { HashSet(generatingKeys) }
        for (key in currentGenerating) {
            if (handledKeys.contains(key)) continue

            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                val isVisible = visibleRect == null || (key.level == currentLevel && RectF.intersects(visibleRect, tileRect))
                if (isVisible) {
                    queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
                }
            }
        }

        notifyTileReady()
    }

    fun invalidateTiles(bounds: RectF) {
        // Delegate to refreshTiles to ensure double-buffering (no white flashes).
        // This keeps the stale content visible until the new content is ready (async generation).
        refreshTiles(bounds)
    }

    fun refreshTiles(bounds: RectF) {
        val version = renderVersion.incrementAndGet()
        val snapshot = tileCache.snapshot()
        val currentGenerating = synchronized(generatingKeys) { HashSet(generatingKeys) }

        val visibleRect = lastVisibleRect
        val currentLevel = if (visibleRect != null) calculateLOD(lastScale) else -1

        // 1. Refresh Cached Tiles
        for (key in snapshot.keys) {
            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                // Queue regeneration for ALL intersected cached tiles.
                // We do NOT remove them. This keeps stale content visible until new content arrives (Double Buffering).
                // Priority: High if visible, Low if not.
                val isVisible = visibleRect != null && key.level == currentLevel && RectF.intersects(visibleRect, tileRect)
                queueTileGeneration(key.col, key.row, key.level, worldSize, isVisible, version, forceRefresh = true)

                currentGenerating.remove(key) // Handled
            }
        }

        // 2. Refresh Generating Tiles
        for (key in currentGenerating) {
            val worldSize = calculateWorldTileSize(key.level)
            val tileRect = getTileWorldRect(key.col, key.row, worldSize)

            if (RectF.intersects(bounds, tileRect)) {
                // Always re-queue generating tiles if they intersect the dirty bounds.
                // If we skip them here, they will be effectively cancelled (due to version mismatch)
                // and simply stop loading, leaving gaps or missing updates.
                queueTileGeneration(key.col, key.row, key.level, worldSize, true, version, forceRefresh = true)
            }
        }
    }

    fun forceRefreshVisibleTiles(
        visibleRect: RectF,
        scale: Float,
    ) {
        val level = calculateLOD(scale)
        val worldSize = calculateWorldTileSize(level)
        val version = renderVersion.incrementAndGet()

        val startCol = floor(visibleRect.left / worldSize).toInt()
        val endCol = floor(visibleRect.right / worldSize).toInt()
        val startRow = floor(visibleRect.top / worldSize).toInt()
        val endRow = floor(visibleRect.bottom / worldSize).toInt()

        for (col in startCol..endCol) {
            for (row in startRow..endRow) {
                // Async regeneration to prevent UI freeze while ensuring visibility
                queueTileGeneration(col, row, level, worldSize, true, version, forceRefresh = true)
            }
        }
    }

    fun clear() {
        synchronized(generatingKeys) {
            tileCache.clear()
            generatingKeys.clear()
            lastVisibleCount = 0
        }
    }

    fun destroy() {
        initJobs.forEach { it.cancel() }
        initJobs.clear()
        generationJobs.values.forEach { it.cancel() }
        generationJobs.clear()
        updateChannel.close()
        clear()
    }

    // --- Private Helpers ---

    private fun calculateLOD(scale: Float): Int {
        val rawLOD = log2(1.0f / scale) + CanvasConfig.LOD_BIAS
        val level = floor(rawLOD).toInt().coerceIn(CanvasConfig.MIN_ZOOM_LEVEL, CanvasConfig.MAX_ZOOM_LEVEL)
        return level
    }

    private fun calculateWorldTileSize(level: Int): Float = tileSize * 2.0.pow(level.toDouble()).toFloat()

    private fun getTileWorldRect(
        col: Int,
        row: Int,
        worldSize: Float,
    ): RectF {
        val left = col * worldSize
        val top = row * worldSize
        return RectF(left, top, left + worldSize, top + worldSize)
    }

    private fun prefetchRegions(
        visibleRect: RectF,
        worldTileSize: Float,
    ) {
        val rm = canvasModel.getRegionManager() ?: return

        // Throttle: Don't prefetch if moved less than 1/4 of a tile
        val threshold = worldTileSize / 4f
        val last = lastPrefetchRect
        if (last != null &&
            kotlin.math.abs(last.centerX() - visibleRect.centerX()) < threshold &&
            kotlin.math.abs(last.centerY() - visibleRect.centerY()) < threshold &&
            kotlin.math.abs(last.width() - visibleRect.width()) < threshold
        ) {
            return
        }

        lastPrefetchRect = RectF(visibleRect)

        // Expand rect to cover neighbors (plus a bit more for momentum)
        val expansion = worldTileSize * (CanvasConfig.NEIGHBOR_COUNT + 1)
        val fetchRect = RectF(visibleRect)
        fetchRect.inset(-expansion, -expansion)

        // Get IDs on IO thread to avoid blocking render thread with lock contention
        scope.launch(Dispatchers.IO) {
            val ids = rm.getRegionIdsInRect(fetchRect)

            // Pin these regions to prevent eviction while visible/near-visible
            rm.setPinnedRegions(ids.toSet())

            rm.loadRegionsAsync(ids)
        }
    }

    private fun notifyTileReady() {
        updateChannel.trySend(Unit)
    }

    private fun drawDebugOverlay(
        canvas: Canvas,
        rect: RectF,
        key: TileCache.TileKey,
        bitmap: Bitmap?,
        scale: Float,
    ) {
        debugPaint.color = if (bitmap == tileCache.errorBitmap) Color.MAGENTA else Color.GREEN
        debugPaint.alpha = 50
        debugPaint.style = Paint.Style.FILL
        canvas.drawRect(rect, debugPaint)

        debugPaint.alpha = 255
        debugPaint.style = Paint.Style.STROKE
        debugPaint.color = Color.BLACK
        debugPaint.strokeWidth = 2f / scale
        canvas.drawRect(rect, debugPaint)

        debugTextPaint.textSize = 20f / scale
        val label = "L${key.level} [${key.col},${key.row}]"
        canvas.drawText(label, rect.left + 5 / scale, rect.top + 25 / scale, debugTextPaint)
    }

    private fun drawRegionDebugOverlay(
        canvas: Canvas,
        scale: Float,
    ) {
        val rm = canvasModel.getRegionManager() ?: return
        val activeIds = rm.getActiveRegionIds()

        debugPaint.color = Color.BLUE
        debugPaint.style = Paint.Style.STROKE
        debugPaint.strokeWidth = 4f / scale
        debugPaint.alpha = 255

        debugTextPaint.color = Color.BLUE
        debugTextPaint.textSize = 30f / scale

        activeIds.forEach { id ->
            val bounds = id.getBounds(rm.regionSize)
            canvas.drawRect(bounds, debugPaint)
            canvas.drawText("R(${id.x},${id.y})", bounds.left + 10 / scale, bounds.top + 40 / scale, debugTextPaint)
        }
    }
}
