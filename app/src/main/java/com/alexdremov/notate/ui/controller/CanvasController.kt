package com.alexdremov.notate.ui.controller

import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.Stroke

/**
 * Clean Architecture Interface for Canvas Operations.
 * Boundary between Input (Pen/Touch) and Core Logic.
 *
 * Refactored to be fully suspendable for non-blocking concurrency.
 */
interface CanvasController {
    suspend fun startBatchSession()

    suspend fun endBatchSession()

    /**
     * Commits a completed stroke to the model.
     */
    suspend fun commitStroke(stroke: Stroke)

    /**
     * Commits multiple strokes to the model efficiently.
     */
    suspend fun addStrokes(strokes: Sequence<Stroke>)

    /**
     * Previews an erasure operation without committing it to history immediately.
     */
    suspend fun previewEraser(
        stroke: Stroke,
        type: EraserType,
    )

    /**
     * Commits a completed erasure action.
     */
    suspend fun commitEraser(
        stroke: Stroke,
        type: EraserType,
    )

    fun setViewportController(controller: ViewportController)

    // --- Page Navigation ---
    suspend fun getCurrentPageIndex(): Int

    suspend fun getTotalPages(): Int

    suspend fun jumpToPage(index: Int)

    suspend fun nextPage()

    suspend fun prevPage()

    // --- Selection & Clipboard ---
    suspend fun getItemAt(
        x: Float,
        y: Float,
    ): com.alexdremov.notate.model.CanvasItem?

    fun getItemAtSync(
        x: Float,
        y: Float,
    ): com.alexdremov.notate.model.CanvasItem?

    suspend fun getItemsInRect(rect: android.graphics.RectF): List<com.alexdremov.notate.model.CanvasItem>

    suspend fun getItemsInPath(path: android.graphics.Path): List<com.alexdremov.notate.model.CanvasItem>

    suspend fun selectItem(item: com.alexdremov.notate.model.CanvasItem)

    suspend fun selectItems(items: List<com.alexdremov.notate.model.CanvasItem>)

    suspend fun clearSelection()

    suspend fun deleteSelection()

    suspend fun copySelection()

    suspend fun paste(
        x: Float,
        y: Float,
    )

    suspend fun pasteImage(
        uri: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    )

    suspend fun startMoveSelection()

    suspend fun moveSelection(
        dx: Float,
        dy: Float,
    )

    suspend fun transformSelection(matrix: android.graphics.Matrix)

    suspend fun commitMoveSelection(shouldReselect: Boolean = true)

    fun getSelectionManager(): SelectionManager

    fun setOnContentChangedListener(listener: () -> Unit)

    fun setProgressCallback(callback: (isVisible: Boolean, message: String?, progress: Int) -> Unit)
}

interface ViewportController {
    fun scrollTo(
        x: Float,
        y: Float,
    )

    fun getViewportOffset(): Pair<Float, Float>
}
