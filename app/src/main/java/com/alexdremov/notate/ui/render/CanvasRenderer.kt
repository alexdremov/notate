package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.StrokeRenderer
import com.alexdremov.notate.util.TileManager
import kotlinx.coroutines.CoroutineScope

/**
 * The high-level rendering coordinator.
 * Owns the TileManager and decides which rendering strategy (Infinite vs Fixed Page) to use.
 * Acts as the bridge between the View (Canvas) and the rendering engine.
 */
class CanvasRenderer(
    private val model: InfiniteCanvasModel,
    private val context: android.content.Context,
    scope: CoroutineScope,
    private val onTileReady: () -> Unit,
) {
    private val tileManager = TileManager(model, this, scope = scope)
    private var layoutStrategy: CanvasLayout = InfiniteLayout()

    init {
        tileManager.onTileReady = onTileReady
        updateLayoutStrategy()
    }

    /**
     * Updates the rendering strategy based on the current model configuration.
     * Switches between InfiniteLayout and FixedPageLayout.
     */
    fun updateLayoutStrategy() {
        layoutStrategy =
            if (model.canvasType == CanvasType.FIXED_PAGES) {
                FixedPageLayout(model.pageWidth, model.pageHeight)
            } else {
                InfiniteLayout()
            }
    }

    /**
     * Updates the interaction state.
     * When interacting (panning/zooming), the renderer might switch to lower quality or
     * different EPD modes to ensure high frame rates.
     */
    fun setInteracting(isInteracting: Boolean) {
        tileManager.isInteracting = isInteracting
    }

    /**
     * Clears all cached tiles.
     * Forces a full regeneration of the view on the next render pass.
     */
    fun clearTiles() {
        tileManager.clear()
    }

    /**
     * Invalidates tiles intersecting the given bounds.
     * Removes them from the cache, forcing them to be re-rendered from the Model.
     * Used for destructive changes like Undo/Redo.
     */
    fun invalidateTiles(bounds: RectF) {
        tileManager.invalidateTiles(bounds)
    }

    /**
     * Refreshes tiles intersecting the given bounds.
     * Queues regeneration tasks but keeps the old tile visible until the new one is ready.
     * Used for non-destructive updates or object removal.
     */
    fun refreshTiles(bounds: RectF) {
        tileManager.refreshTiles(bounds)
    }

    /**
     * Updates cached tiles with a new item (stroke or image).
     * Renders the item directly onto the cached bitmaps for instant feedback.
     */
    fun updateTilesWithItem(item: com.alexdremov.notate.model.CanvasItem) {
        tileManager.updateTilesWithItem(item)
    }

    /**
     * Updates cached tiles with a new stroke.
     * Backwards compatibility.
     */
    fun updateTilesWithStroke(stroke: Stroke) {
        updateTilesWithItem(stroke)
    }

    /**
     * Updates cached tiles with an erasure stroke.
     * Renders the erasure path (Clear mode) directly onto the cached bitmaps.
     */
    fun updateTilesWithErasure(stroke: Stroke) {
        tileManager.updateTilesWithErasure(stroke)
    }

    /**
     * Triggers a view invalidation to redraw overlays or UI components.
     */
    fun invalidate() {
        onTileReady()
    }

    /**
     * Forces a refresh of all tiles currently visible in the viewport.
     * useful after zooming or significant layout changes.
     */
    fun refreshTiles(
        scale: Float,
        visibleRect: RectF,
    ) {
        tileManager.forceRefreshVisibleTiles(visibleRect, scale)
    }

    /**
     * Performs cleanup of resources and background tasks.
     */
    fun destroy() {
        tileManager.destroy()
    }

    /**
     * Main render entry point.
     * Delegates to the active LayoutStrategy to draw the background, content (tiles), and overlays.
     *
     * @param canvas The Android Canvas to draw onto.
     * @param matrix The current view transformation matrix (zoom/pan).
     * @param visibleRect The current visible area in World Coordinates.
     * @param quality The desired rendering quality.
     * @param zoomLevel The effective zoom level.
     */
    fun render(
        canvas: Canvas,
        matrix: Matrix,
        visibleRect: RectF?,
        quality: RenderQuality,
        zoomLevel: Float,
    ) {
        layoutStrategy.render(canvas, matrix, visibleRect, quality, zoomLevel, model, tileManager, this)
    }

    /**
     * Performs direct vector rendering of strokes with a given transformation.
     * Used for export, printing, or when the tiled engine is bypassed (e.g. minimap).
     * Iterates through all strokes in the model and draws them to the canvas.
     */
    fun renderDirectVectors(
        canvas: Canvas,
        matrix: Matrix,
        visibleRect: RectF?,
        quality: RenderQuality,
    ) {
        canvas.save()
        canvas.setMatrix(matrix)
        val viewScale = matrix.mapRadius(1.0f)
        renderDirectVectorsInternal(canvas, visibleRect, quality, viewScale)
        canvas.restore()
    }

    /**
     * Performs direct vector rendering of strokes.
     * Used for export, printing, or when the tiled engine is bypassed (e.g. minimap).
     * Iterates through all strokes in the model and draws them to the canvas.
     */
    private fun renderDirectVectorsInternal(
        canvas: Canvas,
        visibleRect: RectF?,
        quality: RenderQuality,
        viewScale: Float = 1.0f,
    ) {
        // Query visible items or all items if unbound
        val queryRect = visibleRect ?: model.getContentBounds()
        val items = model.queryItems(queryRect)

        renderItems(canvas, items, visibleRect, quality, viewScale, context)
    }

    companion object {
        fun renderItems(
            canvas: Canvas,
            items: List<com.alexdremov.notate.model.CanvasItem>,
            visibleRect: RectF?,
            quality: RenderQuality,
            viewScale: Float,
            context: android.content.Context?,
        ) {
            val paint =
                Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    strokeMiter = 4.0f
                }

            for (item in items) {
                if (visibleRect != null && !RectF.intersects(visibleRect, item.bounds)) continue

                if (item is Stroke) {
                    paint.color = item.color

                    var targetWidth = item.width
                    if (quality == RenderQuality.SIMPLE) {
                        // Ensure visibility on minimap (large canvases)
                        val minPixels = 1.0f
                        val minWidth = minPixels / viewScale
                        targetWidth = kotlin.math.max(targetWidth * 0.5f, minWidth)
                    }

                    paint.strokeWidth = targetWidth

                    if (quality != RenderQuality.SIMPLE && item.points.isNotEmpty()) {
                        StrokeRenderer.drawItem(canvas, item, false, paint, context)
                    } else {
                        paint.style = Paint.Style.STROKE
                        canvas.drawPath(item.path, paint)
                    }
                } else {
                    StrokeRenderer.drawItem(canvas, item, false, paint, context, viewScale)
                }
            }
        }
    }

    /**
     * Helper for TileManager to render a single item to a tile bitmap.
     */
    fun drawItemToCanvas(
        canvas: Canvas,
        item: com.alexdremov.notate.model.CanvasItem,
        debug: Boolean = false,
        scale: Float = 1.0f,
    ) {
        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeMiter = 4.0f
            }

        StrokeRenderer.drawItem(canvas, item, debug, paint, context, scale)
    }

    /**
     * Helper for TileManager to render a single stroke to a tile bitmap.
     * Backwards compatibility.
     */
    fun drawStrokeToCanvas(
        canvas: Canvas,
        stroke: Stroke,
        debug: Boolean = false,
    ) {
        drawItemToCanvas(canvas, stroke, debug)
    }
}
