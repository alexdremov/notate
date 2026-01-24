package com.alexdremov.notate.model

import android.graphics.RectF
import java.util.Stack

/**
 * Pure state manager for Undo/Redo stacks.
 * Decoupled from execution logic to allow Suspend/Async execution in the Model.
 */
class HistoryManager(
    // Optional callbacks if needed, but we try to keep it pure
    private val executor: StrokeExecutor? = null,
) {
    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }

    private val undoStack = Stack<HistoryAction>()
    private val redoStack = Stack<HistoryAction>()

    private var isBatching = false
    private val currentBatch = ArrayList<HistoryAction>()

    interface StrokeExecutor {
        fun calculateBounds(action: HistoryAction): RectF

        // Execute/Revert removed to allow suspend handling in Model
        fun execute(action: HistoryAction) {}

        fun revert(action: HistoryAction) {}
    }

    fun startBatchSession() {
        isBatching = true
        currentBatch.clear()
    }

    fun endBatchSession() {
        if (isBatching && currentBatch.isNotEmpty()) {
            val batch = HistoryAction.Batch(ArrayList(currentBatch))
            undoStack.push(batch)
            limitStackSize(undoStack)
            redoStack.clear()
            currentBatch.clear()
        }
        isBatching = false
    }

    /**
     * Records an action that has ALREADY been executed.
     */
    fun addToStack(action: HistoryAction) {
        if (isBatching) {
            currentBatch.add(action)
        } else {
            undoStack.push(action)
            limitStackSize(undoStack)
            redoStack.clear()
        }
    }

    // Legacy support if needed, but prefer addToStack
    fun applyAction(action: HistoryAction) {
        executor?.execute(action)
        addToStack(action)
    }

    /**
     * Pops action from Undo stack and pushes to Redo stack.
     * Returns the action for the caller to Revert.
     */
    fun undoActionOnly(): HistoryAction? {
        if (undoStack.isNotEmpty()) {
            val action = undoStack.pop()
            redoStack.push(action)
            limitStackSize(redoStack)
            return action
        }
        return null
    }

    /**
     * Pops action from Redo stack and pushes to Undo stack.
     * Returns the action for the caller to Execute.
     */
    fun redoActionOnly(): HistoryAction? {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.pop()
            undoStack.push(action)
            limitStackSize(undoStack)
            return action
        }
        return null
    }

    // Legacy methods calling executor if present (for non-suspend paths if any)
    fun undo(): RectF? {
        val action = undoActionOnly() ?: return null
        executor?.revert(action)
        return executor?.calculateBounds(action)
    }

    fun redo(): RectF? {
        val action = redoActionOnly() ?: return null
        executor?.execute(action)
        return executor?.calculateBounds(action)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        currentBatch.clear()
        isBatching = false
    }

    private fun limitStackSize(stack: Stack<HistoryAction>) {
        while (stack.size > MAX_HISTORY_SIZE) {
            stack.removeAt(0)
        }
    }
}
