package com.alexdremov.notate.model

import android.graphics.RectF
import java.util.Stack

class HistoryManager(
    private val strokeExecutor: StrokeExecutor,
) {
    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }

    private val undoStack = Stack<HistoryAction>()
    private val redoStack = Stack<HistoryAction>()

    private var isBatching = false
    private val currentBatch = ArrayList<HistoryAction>()

    interface StrokeExecutor {
        fun execute(action: HistoryAction)

        fun revert(action: HistoryAction)

        fun calculateBounds(action: HistoryAction): RectF
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

    fun applyAction(action: HistoryAction) {
        strokeExecutor.execute(action)
        if (isBatching) {
            currentBatch.add(action)
        } else {
            undoStack.push(action)
            limitStackSize(undoStack)
            redoStack.clear()
        }
    }

    fun undo(): RectF? {
        if (undoStack.isNotEmpty()) {
            val action = undoStack.pop()
            strokeExecutor.revert(action)
            redoStack.push(action)
            limitStackSize(redoStack)
            return strokeExecutor.calculateBounds(action)
        }
        return null
    }

    fun redo(): RectF? {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.pop()
            strokeExecutor.execute(action)
            undoStack.push(action)
            limitStackSize(undoStack)
            return strokeExecutor.calculateBounds(action)
        }
        return null
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
