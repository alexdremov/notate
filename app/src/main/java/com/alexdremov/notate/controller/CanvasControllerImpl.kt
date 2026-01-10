package com.alexdremov.notate.controller

import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer

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
        if (model.addStroke(stroke)) {
            // Visual update must be on UI thread
            runOnUi {
                renderer.updateTilesWithStroke(stroke)
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
        // Viewport offset is usually negative in matrix terms, but let's check ViewportController impl.
        // If we implement scrollTo(x, y) as "set matrix translate to -x, -y", then positive content Y corresponds to positive scroll Y.
        // Let's assume scrollTo takes WORLD coordinates of top-left.
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
        // Center vertically? Or just top? Usually top.
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

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            uiHandler.post(block)
        }
    }
}
