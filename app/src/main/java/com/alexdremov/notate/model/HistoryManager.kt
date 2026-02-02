package com.alexdremov.notate.model

import android.graphics.RectF
import java.util.ArrayDeque

/**
 * Pure state manager for Undo/Redo stacks.
 * Decoupled from execution logic to allow Suspend/Async execution in the Model.
 */
class HistoryManager {
    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }

    private val undoStack = ArrayDeque<HistoryAction>()
    private val redoStack = ArrayDeque<HistoryAction>()

    private var isBatching = false
    private val currentBatch = ArrayList<HistoryAction>()

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

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        currentBatch.clear()
        isBatching = false
    }

    private fun limitStackSize(stack: ArrayDeque<HistoryAction>) {
        while (stack.size > MAX_HISTORY_SIZE) {
            stack.removeLast() // Remove oldest (last in deque, since we use push/addFirst)
        }
    }
}