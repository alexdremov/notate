package com.alexdremov.notate.controller

import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.Stroke

/**
 * Clean Architecture Interface for Canvas Operations.
 * Acts as the boundary between Input (Pen/Touch) and Core Logic (Model/Renderer).
 *
 * This interface defines the contract for high-level canvas interactions, ensuring that
 * input handlers (like PenInputHandler) do not need to know about the internal workings
 * of the InfiniteCanvasModel or TileManager.
 */
interface CanvasController {
    /**
     * Starts a batch session in the history manager.
     * Subsequent actions (like continuous eraser moves) will be grouped into a single undo step.
     */
    fun startBatchSession()

    /**
     * Ends the current batch session.
     * Finalizes the grouped actions in the history stack.
     */
    fun endBatchSession()

    /**
     * Commits a completed stroke to the model.
     * - Adds the stroke to the persistence layer.
     * - Triggers a visual update on the renderer.
     * - Updates the undo/redo history.
     *
     * @param stroke The stroke to commit.
     */
    fun commitStroke(stroke: Stroke)

    /**
     * Previews an erasure operation without committing it to history immediately (for realtime feedback).
     * - Standard Eraser: Updates visual tiles in-place (pixel manipulation).
     * - Object Eraser: Calculates potential changes but might defer full history commitment until batch end.
     *
     * @param stroke The eraser path.
     * @param type The type of eraser (Standard, Object, Lasso).
     */
    fun previewEraser(
        stroke: Stroke,
        type: EraserType,
    )

    /**
     * Commits a completed erasure action.
     * - Finalizes the changes in the model.
     * - Triggers a robust refresh of the affected area to ensure consistency.
     *
     * @param stroke The eraser path.
     * @param type The type of eraser.
     */
    fun commitEraser(
        stroke: Stroke,
        type: EraserType,
    )

    fun setStrokeWidth(width: Float)

    fun setViewportController(controller: ViewportController)

    // --- Page Navigation ---
    fun getCurrentPageIndex(): Int

    fun getTotalPages(): Int

    fun jumpToPage(index: Int)

    fun nextPage()

    fun prevPage()

    // --- Selection & Clipboard ---
    fun getStrokeAt(
        x: Float,
        y: Float,
    ): Stroke?

    fun getStrokesInRect(rect: android.graphics.RectF): List<Stroke>

    // Simplified Lasso: check if stroke center or bounds are substantially inside path
    fun getStrokesInPath(path: android.graphics.Path): List<Stroke>

    fun selectStroke(stroke: Stroke)

    fun selectStrokes(strokes: List<Stroke>)

    fun clearSelection()

    fun deleteSelection()

    fun copySelection()

    fun paste(
        x: Float,
        y: Float,
    )

    fun startMoveSelection()

    fun moveSelection(
        dx: Float,
        dy: Float,
    )

    fun transformSelection(matrix: android.graphics.Matrix)

    fun commitMoveSelection()

    fun getSelectionManager(): SelectionManager

    fun setOnContentChangedListener(listener: () -> Unit)
}

interface ViewportController {
    fun scrollTo(
        x: Float,
        y: Float,
    )

    fun getViewportOffset(): Pair<Float, Float>
}
