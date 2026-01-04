package com.example.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.example.notate.config.CanvasConfig
import com.example.notate.data.CanvasType
import com.example.notate.model.InfiniteCanvasModel
import com.example.notate.model.Stroke
import com.example.notate.util.StrokeRenderer
import com.example.notate.util.TileManager

/**
 * The high-level rendering coordinator.
 * Owns the TileManager and decides which rendering strategy (Infinite vs Fixed Page) to use.
 * Acts as the bridge between the View (Canvas) and the rendering engine.
 */
class CanvasRenderer(
    private val model: InfiniteCanvasModel,
    private val onTileReady: () -> Unit,
) {
    private val tileManager = TileManager(model, this)
    private var layoutStrategy: CanvasLayout = InfiniteLayout()
    private val renderLock = Any()

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
     * Updates cached tiles with a new stroke.
     * Renders the stroke directly onto the cached bitmaps for instant feedback.
     */
    fun updateTilesWithStroke(stroke: Stroke) {
        tileManager.updateTilesWithStroke(stroke)
    }

    /**
     * Updates cached tiles with an erasure stroke.
     * Renders the erasure path (Clear mode) directly onto the cached bitmaps.
     */
    fun updateTilesWithErasure(stroke: Stroke) {
        tileManager.updateTilesWithErasure(stroke)
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
        renderDirectVectorsInternal(canvas, visibleRect, quality)
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

        // We need thread-safe access to strokes from Model
        model.performRead { allStrokes ->
            for (stroke in allStrokes) {
                if (visibleRect != null && !RectF.intersects(visibleRect, stroke.bounds)) continue

                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                if (quality == RenderQuality.SIMPLE) paint.strokeWidth *= 0.5f

                if (quality != RenderQuality.SIMPLE && stroke.points.isNotEmpty()) {
                    StrokeRenderer.drawStroke(canvas, paint, stroke)
                } else {
                    paint.style = Paint.Style.STROKE
                    canvas.drawPath(stroke.path, paint)
                }
            }
        }
    }

    /**
     * Helper for TileManager to render a single stroke to a tile bitmap.
     * This encapsulates the specific Paint configuration and StrokeRenderer call used for tile generation.
     */
    fun drawStrokeToCanvas(
        canvas: Canvas,
        stroke: Stroke,
        debug: Boolean = false,
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

        StrokeRenderer.drawStroke(canvas, paint, stroke, debug)
    }
}
