package com.alexdremov.notate.controller

import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import java.util.ArrayList

/**
 * Implementation of CanvasController.
 * Orchestrates the data flow between the Model and the Renderer.
 * Ensures that all Rendering operations are dispatched to the Main Thread (UI Thread)
 * to avoid concurrency issues with Android Views and OpenGL contexts.
 */
class CanvasControllerImpl(
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
) : CanvasController {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var viewportController: com.alexdremov.notate.controller.ViewportController? = null

    // --- Selection ---
    private val selectionManager = SelectionManager()

    override fun setViewportController(controller: com.alexdremov.notate.controller.ViewportController) {
        this.viewportController = controller
    }

    override fun startBatchSession() {
        model.startBatchSession()
    }

    override fun endBatchSession() {
        model.endBatchSession()
    }

    override fun commitStroke(stroke: Stroke) {
        // Model update is thread-safe
        val added = model.addStroke(stroke)
        if (added != null) {
            // Visual update must be on UI thread
            runOnUi {
                renderer.updateTilesWithStroke(added)
            }
        }
    }

    override fun previewEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        // Perform heavy geometric calculations (Model logic)
        val invalidated = model.erase(stroke, type)

        // Dispatch visual updates to UI thread
        runOnUi {
            if (type == EraserType.STANDARD) {
                // In-place update for standard eraser (visual only)
                renderer.updateTilesWithErasure(stroke)
            } else if (invalidated != null) {
                // Queue regeneration for object eraser
                renderer.refreshTiles(invalidated)
            }
        }
    }

    override fun commitEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        // Finalize model state
        val invalidated = model.erase(stroke, type)

        runOnUi {
            if ((type == EraserType.LASSO || type == EraserType.STROKE) && invalidated != null) {
                renderer.invalidateTiles(invalidated) // Force invalidation for Lasso/Stroke finish
            } else if (type == EraserType.STANDARD) {
                renderer.refreshTiles(stroke.bounds) // Clean up any visual artifacts
            }
        }
    }

    override fun setStrokeWidth(width: Float) {
        // Implementation pending or not needed for this task
    }

    override fun getCurrentPageIndex(): Int {
        val offsetY = viewportController?.getViewportOffset()?.second ?: return 0
        val pageFullHeight = model.pageHeight + com.alexdremov.notate.config.CanvasConfig.PAGE_SPACING
        return kotlin.math
            .floor(offsetY / pageFullHeight)
            .toInt()
            .coerceAtLeast(0)
    }

    override fun getTotalPages(): Int {
        val contentPages = model.getTotalPages()
        val current = getCurrentPageIndex() + 1
        return kotlin.math.max(contentPages, current)
    }

    override fun jumpToPage(index: Int) {
        if (index < 0) return
        val bounds = model.getPageBounds(index)
        runOnUi {
            viewportController?.scrollTo(0f, bounds.top)
        }
    }

    override fun nextPage() {
        val current = getCurrentPageIndex()
        jumpToPage(current + 1)
    }

    override fun prevPage() {
        val current = getCurrentPageIndex()
        if (current > 0) {
            jumpToPage(current - 1)
        }
    }

    override fun getSelectionManager(): SelectionManager = selectionManager

    override fun getStrokeAt(x: Float, y: Float): Stroke? {
        return model.hitTest(x, y, 20f)
    }

    override fun getStrokesInRect(rect: android.graphics.RectF): List<Stroke> {
        val strokes = model.queryStrokes(rect)
        // Relaxed containment: Intersects
        return strokes.filter { android.graphics.RectF.intersects(rect, it.bounds) }
    }

    override fun getStrokesInPath(path: android.graphics.Path): List<Stroke> {
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        val strokes = model.queryStrokes(bounds)
        
        val region = android.graphics.Region()
        val clip = android.graphics.Region(-50000, -50000, 50000, 50000)
        region.setPath(path, clip)

        return strokes.filter { stroke ->
            region.contains(stroke.bounds.centerX().toInt(), stroke.bounds.centerY().toInt())
        }
    }

    override fun selectStroke(stroke: Stroke) {
        selectionManager.select(stroke)
        runOnUi { renderer.invalidate() }
    }

    override fun selectStrokes(strokes: List<Stroke>) {
        selectionManager.selectAll(strokes)
        runOnUi { renderer.invalidate() }
    }

    override fun clearSelection() {
        if (selectionManager.hasSelection()) {
            selectionManager.clearSelection()
            runOnUi { renderer.invalidate() }
        }
    }

    override fun deleteSelection() {
        if (selectionManager.hasSelection()) {
            val toRemove = selectionManager.selectedStrokes.toList()
            selectionManager.clearSelection()
            deleteStrokesFromModel(toRemove)
        }
    }
    
    private fun deleteStrokesFromModel(strokes: List<Stroke>) {
        model.deleteStrokes(strokes)
        if (strokes.isNotEmpty()) {
            val bounds = android.graphics.RectF(strokes[0].bounds)
            strokes.forEach { bounds.union(it.bounds) }
            runOnUi { renderer.invalidateTiles(bounds) }
        }
    }

    override fun copySelection() {
        if (selectionManager.hasSelection()) {
            com.alexdremov.notate.util.ClipboardManager.copy(selectionManager.selectedStrokes)
        }
    }

    override fun paste(x: Float, y: Float) {
        if (com.alexdremov.notate.util.ClipboardManager.hasContent()) {
            val strokes = com.alexdremov.notate.util.ClipboardManager.getStrokes()
            if (strokes.isEmpty()) return

            val bounds = android.graphics.RectF()
            bounds.set(strokes[0].bounds)
            for(s in strokes) bounds.union(s.bounds)
            
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            
            val dx = x - centerX
            val dy = y - centerY
            
            val newStrokes = strokes.map { s ->
                val matrix = android.graphics.Matrix()
                matrix.setTranslate(dx, dy)
                val newPath = android.graphics.Path(s.path)
                newPath.transform(matrix)
                
                val newPoints = s.points.map { p ->
                    com.onyx.android.sdk.data.note.TouchPoint(
                        p.x + dx, p.y + dy, p.pressure, p.size, p.timestamp
                    )
                }
                
                val newBounds = android.graphics.RectF(s.bounds)
                matrix.mapRect(newBounds)
                
                s.copy(path = newPath, points = newPoints, bounds = newBounds, strokeOrder = 0)
            }
            
            startBatchSession()
            newStrokes.forEach { commitStroke(it) }
            endBatchSession()
        }
    }

    override fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return
        
        startBatchSession()
        // Visually "Lift" by removing from Model (but keeping in SelectionManager)
        deleteStrokesFromModel(selectionManager.selectedStrokes.toList())
        
        runOnUi { renderer.invalidate() }
    }

    override fun moveSelection(dx: Float, dy: Float) {
        selectionManager.translate(dx, dy)
        runOnUi { renderer.invalidate() }
    }

    override fun commitMoveSelection() {
        if (!selectionManager.hasSelection()) return
        
        val originals = selectionManager.selectedStrokes.toList()
        val transform = selectionManager.transformMatrix
        
        // Note: startBatchSession and deleteStrokesFromModel were called in startMoveSelection
        
        val newSelected = ArrayList<Stroke>()
        
        originals.forEach { s ->
            val newPath = android.graphics.Path(s.path)
            newPath.transform(transform)
            
            val newPoints = s.points.map { p ->
                val pts = floatArrayOf(p.x, p.y)
                transform.mapPoints(pts)
                com.onyx.android.sdk.data.note.TouchPoint(
                    pts[0], pts[1], p.pressure, p.size, p.timestamp
                )
            }
            
            val newBounds = android.graphics.RectF(s.bounds)
            transform.mapRect(newBounds)
            
            val newStroke = s.copy(path = newPath, points = newPoints, bounds = newBounds)
            val added = model.addStroke(newStroke)
            if (added != null) {
                newSelected.add(added)
                runOnUi { renderer.updateTilesWithStroke(added) }
            }
        }
        
        endBatchSession()
        
        selectionManager.clearSelection()
        selectionManager.selectAll(newSelected)
        selectionManager.transformMatrix.reset()
        
        runOnUi { renderer.invalidate() }
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            uiHandler.post(block)
        }
    }
}