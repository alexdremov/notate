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

    /**
     * Invoked when an action becomes permanently unreachable: it either fell off
     * the bottom of a full stack or was dropped by [clear]. Used by the model to
     * release external resources referenced by the action (e.g. stash files of
     * [HistoryAction.RemoveStashed]), which would otherwise leak on disk.
     *
     * Must be cheap and non-suspending; called while the model mutex is held.
     */
    var onActionDiscarded: ((HistoryAction) -> Unit)? = null

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
        // Notify before wiping so resource-bearing actions can release files.
        val discarded = ArrayList<HistoryAction>(undoStack.size + redoStack.size + currentBatch.size)
        discarded.addAll(undoStack)
        discarded.addAll(redoStack)
        discarded.addAll(currentBatch)
        discarded.forEach { onActionDiscarded?.invoke(it) }

        undoStack.clear()
        redoStack.clear()
        currentBatch.clear()
        isBatching = false
    }

    private fun limitStackSize(stack: ArrayDeque<HistoryAction>) {
        while (stack.size > MAX_HISTORY_SIZE) {
            val oldest = stack.removeLast() // Remove oldest (last in deque, since we use push/addFirst)
            // The oldest undo entry is unreachable forever. The oldest redo entry
            // is also unreachable: redoStack is cleared on every new action, and
            // entries only leave it via redoActionOnly (from the top).
            onActionDiscarded?.invoke(oldest)
        }
    }
}
