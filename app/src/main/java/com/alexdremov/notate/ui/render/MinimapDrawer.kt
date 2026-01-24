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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Volatile
    private var isRegenerating = false

    fun draw(
        canvas: Canvas,
        matrix: Matrix,
        inverseMatrix: Matrix,
        currentScale: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        if (!isMinimapVisible) return

        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val padding = CanvasConfig.MINIMAP_PADDING

        // 1. Calculate World Viewport
        val viewportRect = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        matrix.invert(inverseMatrix)
        inverseMatrix.mapRect(viewportRect)

        // 2. Determine Context Rect
        val contextRect = RectF()
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
            val currentContentBounds = model.getContentBounds() // Sync-safe copy
            contextRect.set(viewportRect)
            if (!currentContentBounds.isEmpty) contextRect.union(currentContentBounds)
        }

        // 3. Determine Scale
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)
        val mapScale = min((width - 2 * padding) / contextW, (height - 2 * padding) / contextH)
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

        // 4. Update Content Thumbnail
        val contextChanged = !contentRect.contains(contextRect) && model.canvasType == CanvasType.FIXED_PAGES
        val shouldRegenerate = (minimapDirty || contentThumbnail == null || contextChanged) && !isRegenerating

        if (shouldRegenerate) regenerateThumbnail(contextRect)

        // 5. Draw Content Thumbnail
        val destLeft = mapLeft + (contentRect.left - contextRect.left) * mapScale
        val destTop = mapTop + (contentRect.top - contextRect.top) * mapScale
        val destWidth = contentRect.width() * mapScale
        val destHeight = contentRect.height() * mapScale

        contentThumbnail?.let {
            canvas.save()
            canvas.clipRect(mapLeft, mapTop, mapLeft + targetW, mapTop + targetH)
            canvas.drawBitmap(it, null, RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight), null)
            canvas.restore()
        }

        canvas.drawRect(mapLeft, mapTop, mapLeft + targetW, mapTop + targetH, minimapPaint)

        // 6. Draw Viewport
        val minimapMatrix = Matrix()
        minimapMatrix.postTranslate(-contextRect.left, -contextRect.top)
        minimapMatrix.postScale(mapScale, mapScale)
        minimapMatrix.postTranslate(mapLeft, mapTop)
        val mappedViewport = RectF(viewportRect)
        minimapMatrix.mapRect(mappedViewport)
        canvas.drawRect(mappedViewport, viewportPaint)

        val scaleText = "${(currentScale * 100).toInt()}%"
        canvas.drawText(scaleText, width - padding, mapTop + targetH + 40f, textPaint)
    }

    private val dirtyRegionIds = mutableSetOf<com.alexdremov.notate.data.region.RegionId>()
    private var currentThumbnailBitmap: Bitmap? = null
    private var currentThumbnailCanvas: Canvas? = null
    private var regionsProcessed = 0
    private var totalRegionsToProcess = 0
    private var regenerationContextRect: RectF = RectF()

    private val drawerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun detach() {
        drawerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        minimapHandler.removeCallbacksAndMessages(null)
    }

    private fun regenerateThumbnail(contextRect: RectF) {
        isRegenerating = true
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)
        val capturedContextRect = RectF(contextRect)

        drawerScope.launch {
            try {
                val maxDim = 512f
                val thumbScale = min(maxDim / contextW, maxDim / contextH)
                val thumbW = (contextW * thumbScale).toInt().coerceAtLeast(1)
                val thumbH = (contextH * thumbScale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                val thumbCanvas = Canvas(bitmap)
                thumbCanvas.drawColor(android.graphics.Color.WHITE)

                synchronized(this@MinimapDrawer) {
                    currentThumbnailBitmap = bitmap
                    currentThumbnailCanvas = thumbCanvas
                    regionsProcessed = 0
                    regenerationContextRect.set(capturedContextRect)
                }

                val regionManager = model.getRegionManager()
                if (regionManager != null) {
                    val regionIds = regionManager.getRegionIdsInRect(capturedContextRect)
                    synchronized(this@MinimapDrawer) {
                        dirtyRegionIds.clear()
                        dirtyRegionIds.addAll(regionIds)
                        totalRegionsToProcess = dirtyRegionIds.size
                    }
                }

                if (totalRegionsToProcess > 0) {
                    renderNextDirtyRegion(capturedContextRect, thumbScale)
                } else {
                    finalizeRegeneration()
                }
            } catch (e: Exception) {
                cleanupRegeneration()
            }
        }
    }

    private fun renderNextDirtyRegion(
        contextRect: RectF,
        thumbScale: Float,
    ) {
        drawerScope.launch {
            var regionId: com.alexdremov.notate.data.region.RegionId? = null
            synchronized(this@MinimapDrawer) {
                if (dirtyRegionIds.isNotEmpty()) {
                    regionId = dirtyRegionIds.first()
                }
            }

            if (regionId != null) {
                renderSingleRegionToThumbnail(regionId!!, contextRect, thumbScale)
                synchronized(this@MinimapDrawer) {
                    dirtyRegionIds.remove(regionId)
                    regionsProcessed++
                }
                onRefresh()
                renderNextDirtyRegion(contextRect, thumbScale)
            } else {
                finalizeRegeneration()
            }
        }
    }

    private suspend fun renderSingleRegionToThumbnail(
        regionId: com.alexdremov.notate.data.region.RegionId,
        contextRect: RectF,
        thumbScale: Float,
    ) {
        val regionManager = model.getRegionManager() ?: return
        val regionThumb = regionManager.getRegionThumbnail(regionId, view.context) ?: return

        withContext(Dispatchers.Default) {
            val canvas = currentThumbnailCanvas ?: return@withContext
            synchronized(canvas) {
                canvas.save()
                canvas.scale(thumbScale, thumbScale)
                canvas.translate(-contextRect.left, -contextRect.top)
                val regionSize = regionManager.regionSize
                val dstRect =
                    RectF(
                        regionId.x * regionSize,
                        regionId.y * regionSize,
                        (regionId.x + 1) * regionSize,
                        (regionId.y + 1) * regionSize,
                    )
                canvas.drawBitmap(regionThumb, null, dstRect, null)
                canvas.restore()
            }
        }
    }

    private fun finalizeRegeneration() {
        synchronized(this@MinimapDrawer) {
            currentThumbnailBitmap?.let { bitmap ->
                contentThumbnail?.recycle()
                contentThumbnail = bitmap
                contentRect.set(regenerationContextRect)
            }
            currentThumbnailBitmap = null
            currentThumbnailCanvas = null
            minimapDirty = false
            isRegenerating = false
        }
        onRefresh()
    }

    private fun cleanupRegeneration() {
        synchronized(this@MinimapDrawer) {
            currentThumbnailBitmap?.recycle()
            currentThumbnailBitmap = null
            currentThumbnailCanvas = null
            dirtyRegionIds.clear()
            isRegenerating = false
        }
    }

    fun markRegionDirty(regionId: com.alexdremov.notate.data.region.RegionId) {
        synchronized(this@MinimapDrawer) {
            dirtyRegionIds.add(regionId)
            minimapDirty = true
        }
    }

    fun markAllRegionsDirty() {
        synchronized(this@MinimapDrawer) {
            dirtyRegionIds.clear()
            minimapDirty = true
        }
    }
}
