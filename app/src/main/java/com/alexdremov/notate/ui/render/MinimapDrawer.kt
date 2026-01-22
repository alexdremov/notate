package com.alexdremov.notate.ui.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeRenderer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class MinimapDrawer(
    private val view: android.view.View,
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
    private val onRefresh: () -> Unit,
) {
    private val minimapHandler = Handler(Looper.getMainLooper())
    private var isMinimapVisible = false
    private var contentThumbnail: Bitmap? = null
    private val contentRect = RectF()
    private var minimapDirty = true

    private val hideMinimapRunnable =
        Runnable {
            isMinimapVisible = false
            onRefresh()
        }

    private val minimapPaint =
        Paint().apply {
            color = CanvasConfig.MINIMAP_BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CanvasConfig.MINIMAP_STROKE_WIDTH
        }
    private val viewportPaint =
        Paint().apply {
            color = CanvasConfig.MINIMAP_VIEWPORT_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CanvasConfig.MINIMAP_STROKE_WIDTH * 1.5f
        }
    private val textPaint =
        Paint().apply {
            color = Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

    fun setDirty() {
        minimapDirty = true
    }

    fun show() {
        isMinimapVisible = true
        minimapHandler.removeCallbacks(hideMinimapRunnable)
        minimapHandler.postDelayed(hideMinimapRunnable, CanvasConfig.MINIMAP_HIDE_DELAY_MS)
    }

    private var isRegenerating = false

    fun draw(
        canvas: Canvas,
        matrix: Matrix,
        inverseMatrix: Matrix,
        currentScale: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        Logger.d("MinimapDrawer", "=== DRAW called ===")
        Logger.d("MinimapDrawer", "isMinimapVisible: $isMinimapVisible")
        Logger.d("MinimapDrawer", "isRegenerating: $isRegenerating")
        Logger.d("MinimapDrawer", "minimapDirty: $minimapDirty")
        Logger.d("MinimapDrawer", "contentThumbnail: ${contentThumbnail != null}")
        Logger.d("MinimapDrawer", "contentRect: $contentRect")

        if (!isMinimapVisible) {
            Logger.d("MinimapDrawer", "Skipping draw - minimap not visible")
            return
        }

        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val padding = CanvasConfig.MINIMAP_PADDING

        // 1. Calculate World Viewport
        val viewportRect = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        matrix.invert(inverseMatrix) // Assumes caller updates matrix/inverse
        inverseMatrix.mapRect(viewportRect)

        Logger.d("MinimapDrawer", "World viewport: $viewportRect")

        // 2. Determine Context Rect based on Canvas Type
        val contextRect = RectF()
        Logger.d("MinimapDrawer", "Canvas type: ${model.canvasType}")
        if (model.canvasType == CanvasType.FIXED_PAGES) {
            val pageFullHeight = model.pageHeight + CanvasConfig.PAGE_SPACING
            val firstPageIdx =
                kotlin.math
                    .floor(viewportRect.top / pageFullHeight)
                    .toInt()
                    .coerceAtLeast(0)
            val lastPageIdx =
                kotlin.math
                    .floor(viewportRect.bottom / pageFullHeight)
                    .toInt()
                    .coerceAtLeast(firstPageIdx)

            val top = firstPageIdx * pageFullHeight
            val bottom = (lastPageIdx + 1) * pageFullHeight

            contextRect.set(0f, top, model.pageWidth, bottom)
            contextRect.union(viewportRect)
        } else {
            val currentContentBounds = model.getContentBounds()
            contextRect.set(viewportRect)
            if (!currentContentBounds.isEmpty) {
                contextRect.union(currentContentBounds)
            }
        }

        // 3. Determine Scale to fit Context into MINIMAP_WIDTH
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)

        val scaleX = CanvasConfig.MINIMAP_WIDTH / contextW
        val scaleY = CanvasConfig.MINIMAP_WIDTH / contextH
        val mapScale = min(scaleX, scaleY)

        val targetW = contextW * mapScale
        val targetH = contextH * mapScale

        val mapLeft = width - targetW - padding
        val mapTop = padding

        // Minimap Background
        canvas.drawRect(
            mapLeft,
            mapTop,
            mapLeft + targetW,
            mapTop + targetH,
            Paint().apply {
                color = Color.argb(200, 255, 255, 255)
                style = Paint.Style.FILL
            },
        )
        canvas.drawRect(mapLeft, mapTop, mapLeft + targetW, mapTop + targetH, minimapPaint)

        // 4. Update Content Thumbnail if Dirty or Context Changed
        val contextChanged = !contentRect.contains(contextRect) && model.canvasType == CanvasType.FIXED_PAGES
        val shouldRegenerate = (minimapDirty || contentThumbnail == null || contextChanged) && !isRegenerating

        if (shouldRegenerate) {
            regenerateThumbnail(contextRect)
        }

        // 5. Draw Content Thumbnail
        Logger.d("MinimapDrawer", "Drawing content thumbnail...")
        Logger.d("MinimapDrawer", "contentRect: $contentRect")
        Logger.d("MinimapDrawer", "contextRect: $contextRect")
        Logger.d("MinimapDrawer", "mapScale: $mapScale, mapLeft: $mapLeft, mapTop: $mapTop")

        val destLeft = mapLeft + (contentRect.left - contextRect.left) * mapScale
        val destTop = mapTop + (contentRect.top - contextRect.top) * mapScale
        val destWidth = contentRect.width() * mapScale
        val destHeight = contentRect.height() * mapScale

        Logger.d("MinimapDrawer", "About to draw thumbnail...")
        Logger.d("MinimapDrawer", "contentThumbnail: ${contentThumbnail != null}")
        Logger.d("MinimapDrawer", "destLeft: $destLeft, destTop: $destTop")
        Logger.d("MinimapDrawer", "destWidth: $destWidth, destHeight: $destHeight")

        contentThumbnail?.let {
            Logger.d("MinimapDrawer", "Drawing thumbnail: ${it.width}x${it.height}")

            // DEBUG: Draw a red border around the thumbnail area
            val debugPaint =
                Paint().apply {
                    color = android.graphics.Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
            canvas.drawRect(RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight), debugPaint)

            // Draw the actual thumbnail
            canvas.drawBitmap(it, null, RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight), null)

            Logger.d("MinimapDrawer", "Thumbnail drawn successfully with debug border")
        }

        // 6. Draw Viewport (Red Stroke)
        val minimapMatrix = Matrix()
        minimapMatrix.postTranslate(-contextRect.left, -contextRect.top)
        minimapMatrix.postScale(mapScale, mapScale)
        minimapMatrix.postTranslate(mapLeft, mapTop)

        val mappedViewport = RectF(viewportRect)
        minimapMatrix.mapRect(mappedViewport)
        canvas.drawRect(mappedViewport, viewportPaint)

        // Draw Scale Text
        val scaleText = "${(currentScale * 100).toInt()}%"
        canvas.drawText(scaleText, width - padding, mapTop + targetH + 40f, textPaint)
    }

    // Track which regions need to be rendered
    private val dirtyRegionIds = mutableSetOf<com.alexdremov.notate.data.region.RegionId>()
    private var currentThumbnailBitmap: Bitmap? = null
    private var currentThumbnailCanvas: Canvas? = null
    private var regionsProcessed = 0
    private var totalRegionsToProcess = 0

    // CRITICAL: Store the context rect used during regeneration
    private var regenerationContextRect: RectF = RectF()

    // Memory and performance bounds
    private val maxConcurrentRegionLoads = 2 // Max regions to load simultaneously
    private val currentLoadingRegions = mutableSetOf<com.alexdremov.notate.data.region.RegionId>()
    private val regionLoadTimeoutMs = 5000L // 5 second timeout per region

    private fun regenerateThumbnail(contextRect: RectF) {
        Logger.d("MinimapDrawer", "=== Starting thumbnail regeneration ===")
        Logger.d("MinimapDrawer", "Context rect: $contextRect")

        isRegenerating = true
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)

        Logger.d("MinimapDrawer", "Context dimensions: ${contextW}x$contextH")

        // Capture context for background task
        val capturedContextRect = RectF(contextRect)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Logger.d("MinimapDrawer", "Background thread started for thumbnail generation")

                // Initialize thumbnail bitmap and canvas
                val maxDim = 512f
                val thumbScale = min(maxDim / contextW, maxDim / contextH)
                val thumbW = (contextW * thumbScale).toInt().coerceAtLeast(1)
                val thumbH = (contextH * thumbScale).toInt().coerceAtLeast(1)

                Logger.d("MinimapDrawer", "Creating bitmap: ${thumbW}x$thumbH, scale: $thumbScale")

                val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                val thumbCanvas = Canvas(bitmap)

                // Fill with white background first (this is what we're seeing!)
                thumbCanvas.drawColor(android.graphics.Color.WHITE)
                Logger.d("MinimapDrawer", "Filled canvas with white background")

                // Initialize for progressive rendering
                synchronized(this@MinimapDrawer) {
                    currentThumbnailBitmap = bitmap
                    currentThumbnailCanvas = thumbCanvas
                    regionsProcessed = 0

                    // CRITICAL FIX: Store the context rect for later use in finalizeRegeneration
                    regenerationContextRect.set(capturedContextRect)
                    Logger.d("MinimapDrawer", "Stored regeneration context: $regenerationContextRect")

                    // Mark all regions in context as dirty
                    val regionManager = model.getRegionManager()
                    Logger.d("MinimapDrawer", "Getting region manager...")
                    if (regionManager != null) {
                        Logger.d("MinimapDrawer", "Region manager obtained, getting regions for context: $capturedContextRect")
                        val regions = regionManager.getRegionsInRect(capturedContextRect)
                        Logger.d("MinimapDrawer", "Found ${regions.size} regions in context")

                        if (regions.isEmpty()) {
                            Logger.w("MinimapDrawer", "WARNING: No regions found in context! This could cause white thumbnail.")
                        }

                        // Log details about the regions
                        regions.forEach { region ->
                            Logger.d("MinimapDrawer", "Region ${region.id}: ${region.items.size} items, bounds: ${region.contentBounds}")
                        }

                        dirtyRegionIds.clear()
                        dirtyRegionIds.addAll(regions.map { it.id })
                        totalRegionsToProcess = regions.size

                        Logger.d("MinimapDrawer", "Marked ${dirtyRegionIds.size} regions as dirty for processing")
                    } else {
                        Logger.e("MinimapDrawer", "ERROR: Region manager is null! Cannot process any regions.")
                        totalRegionsToProcess = 0
                    }
                }

                // Start progressive region rendering
                if (totalRegionsToProcess > 0) {
                    Logger.d("MinimapDrawer", "Starting progressive rendering of $totalRegionsToProcess regions")
                    renderNextDirtyRegion(capturedContextRect, thumbScale)
                } else {
                    Logger.w("MinimapDrawer", "No regions to process, will finalize with white thumbnail")
                    finalizeRegeneration()
                }
            } catch (e: Exception) {
                com.alexdremov.notate.util.Logger
                    .e("MinimapDrawer", "Failed to initialize thumbnail regeneration", e)
                cleanupRegeneration()
            }
        }
    }

    /**
     * Render regions progressively, one at a time, to avoid memory spikes and blocking
     */
    private fun renderNextDirtyRegion(
        contextRect: RectF,
        thumbScale: Float,
    ) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var regionId: com.alexdremov.notate.data.region.RegionId? = null
            try {
                // Get next dirty region in a thread-safe manner
                synchronized(this@MinimapDrawer) {
                    if (dirtyRegionIds.isNotEmpty() && currentLoadingRegions.size < maxConcurrentRegionLoads) {
                        regionId = dirtyRegionIds.first()
                        currentLoadingRegions.add(regionId)
                    }
                }

                if (regionId != null) {
                    val regionManager = model.getRegionManager()
                    if (regionManager != null) {
                        // Load only this specific region with timeout
                        val region =
                            withTimeoutOrNull(regionLoadTimeoutMs) {
                                regionManager.getRegionReadOnly(regionId)
                            }

                        if (region != null) {
                            // Render this single region
                            renderSingleRegionToThumbnail(region, contextRect, thumbScale)

                            // Mark as clean and update progress
                            synchronized(this@MinimapDrawer) {
                                dirtyRegionIds.remove(regionId)
                                currentLoadingRegions.remove(regionId)
                                regionsProcessed++
                            }

                            // Post incremental update to UI
                            postThumbnailUpdate()

                            // Continue with next region
                            renderNextDirtyRegion(contextRect, thumbScale)
                        } else {
                            // Region load failed or timed out
                            synchronized(this@MinimapDrawer) {
                                currentLoadingRegions.remove(regionId)
                            }

                            com.alexdremov.notate.util.Logger.w(
                                "MinimapDrawer",
                                "Region $regionId load failed or timed out",
                            )

                            // Continue with next region
                            renderNextDirtyRegion(contextRect, thumbScale)
                        }
                    }
                } else {
                    // All regions processed or max concurrent loads reached
                    finalizeRegeneration()
                }
            } catch (e: Exception) {
                com.alexdremov.notate.util.Logger
                    .e("MinimapDrawer", "Failed to render region ${regionId ?: "unknown"}", e)

                synchronized(this@MinimapDrawer) {
                    regionId?.let { id ->
                        dirtyRegionIds.remove(id)
                        currentLoadingRegions.remove(id)
                    }
                }

                // Continue with next region even if this one failed
                renderNextDirtyRegion(contextRect, thumbScale)
            }
        }
    }

    /**
     * Render a single region to the thumbnail
     */
    private fun renderSingleRegionToThumbnail(
        region: com.alexdremov.notate.data.region.RegionData,
        contextRect: RectF,
        thumbScale: Float,
    ) {
        val bitmap = currentThumbnailBitmap
        val canvas = currentThumbnailCanvas

        if (bitmap == null || canvas == null) {
            Logger.e("MinimapDrawer", "ERROR: Bitmap or canvas is null, cannot render region ${region.id}")
            return
        }

        Logger.d("MinimapDrawer", "Region ${region.id} has ${region.items.size} items, quadtree: ${region.quadtree != null}")
        // Save current canvas state
        canvas.save()

        // Apply transformation: translate and scale to map world coordinates to thumbnail coordinates
        // 1. Translate so contextRect.left/top becomes (0,0) in thumbnail space
        // 2. Scale by thumbScale to fit context into thumbnail
        // Note: Canvas transformations are pre-concatenated. scale() then translate() results in M * S * T.
        // Applied to point P: S * T * P. T is applied first.
        canvas.scale(thumbScale, thumbScale)
        canvas.translate(-contextRect.left, -contextRect.top)

        // Set up painting for this region only
        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeMiter = 4.0f
            }

        // Process only items from this single region
        val regionItems = ArrayList<com.alexdremov.notate.model.CanvasItem>()
        region.quadtree?.retrieve(regionItems, contextRect)

        Logger.d("MinimapDrawer", "Retrieved ${regionItems.size} items from quadtree for region ${region.id}")

        for (item in regionItems) {
            if (item is com.alexdremov.notate.model.Stroke) {
                // Logger.d("MinimapDrawer", "Rendering stroke: points=${item.points.size}, color=${item.color}, width=${item.width}")
                // Logger.d("MinimapDrawer", "Stroke bounds: ${item.bounds}")

                // CRITICAL FIX: Recreate path from raw points
                // Don't use the pre-transformed item.path
                val path = Path()

                if (item.points.isNotEmpty()) {
                    val first = item.points[0]
                    path.moveTo(first.x, first.y)
                    for (i in 1 until item.points.size) {
                        val point = item.points[i]
                        path.lineTo(point.x, point.y)
                    }
                }

                paint.color = item.color

                // Ensure visibility on minimap (large canvases)
                val minPixels = 1.0f
                val minWidth = minPixels / thumbScale
                var targetWidth = kotlin.math.max(item.width * 0.5f, minWidth)

                paint.strokeWidth = targetWidth

                // Draw the path - it will be automatically transformed by the canvas matrix
                canvas.drawPath(path, paint)
            }
        }

        // Restore canvas state
        canvas.restore()

        // Clear immediately to free memory
        regionItems.clear()
    }

    /**
     * Post incremental update to UI thread
     */
    private fun postThumbnailUpdate() {
        minimapHandler.post {
            onRefresh()
        }
    }

    /**
     * Finalize regeneration when all regions are processed
     */
    private fun finalizeRegeneration() {
        minimapHandler.post {
            currentThumbnailBitmap?.let { bitmap ->
                Logger.d("MinimapDrawer", "Finalizing regeneration with bitmap: ${bitmap.width}x${bitmap.height}")

                contentThumbnail?.recycle()
                contentThumbnail = bitmap

                // CRITICAL FIX: Restore the context rect used during regeneration
                // This ensures the thumbnail is positioned correctly in the minimap
                contentRect.set(regenerationContextRect)
                Logger.d("MinimapDrawer", "Restored contentRect: $contentRect")
            }

            currentThumbnailBitmap = null
            currentThumbnailCanvas = null
            minimapDirty = false
            isRegenerating = false
            onRefresh()

            com.alexdremov.notate.util.Logger.d(
                "MinimapDrawer",
                "Thumbnail regeneration complete: $regionsProcessed regions processed",
            )
        }
    }

    /**
     * Clean up if regeneration fails
     */
    private fun cleanupRegeneration() {
        synchronized(this@MinimapDrawer) {
            currentThumbnailBitmap?.recycle()
            currentThumbnailBitmap = null
            currentThumbnailCanvas = null
            dirtyRegionIds.clear()
            isRegenerating = false
        }
    }

    /**
     * Mark a specific region as dirty for incremental updates
     */
    fun markRegionDirty(regionId: com.alexdremov.notate.data.region.RegionId) {
        synchronized(this@MinimapDrawer) {
            dirtyRegionIds.add(regionId)
            minimapDirty = true
        }
    }

    /**
     * Mark all regions as dirty (full refresh needed)
     */
    fun markAllRegionsDirty() {
        synchronized(this@MinimapDrawer) {
            dirtyRegionIds.clear()
            minimapDirty = true
        }
    }
}
