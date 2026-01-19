package com.alexdremov.notate.model

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HistoryManagerTest {
    class MockExecutor : HistoryManager.StrokeExecutor {
        val executedActions = mutableListOf<HistoryAction>()
        val revertedActions = mutableListOf<HistoryAction>()

        override fun execute(action: HistoryAction) {
            executedActions.add(action)
        }

        override fun revert(action: HistoryAction) {
            revertedActions.add(action)
        }

        override fun calculateBounds(action: HistoryAction): RectF = RectF()
    }

    @Test
    fun `test history size limit`() {
        val executor = MockExecutor()
        val historyManager = HistoryManager(executor)

        // Apply 110 actions
        for (i in 0 until 110) {
            historyManager.applyAction(HistoryAction.Add(emptyList()))
        }

        // Undo 100 times should work
        for (i in 0 until 100) {
            assertNotNull("Undo $i should not be null", historyManager.undo())
        }

        // The 101st undo should be null because limit is 100
        assertNull("101st undo should be null", historyManager.undo())
    }

    @Test
    fun `test redo size limit`() {
        val executor = MockExecutor()
        val historyManager = HistoryManager(executor)

        // Add 1 action
        historyManager.applyAction(HistoryAction.Add(emptyList()))

        // Undo it
        historyManager.undo()

        // Redo stack should have 1 item
        assertNotNull(historyManager.redo())

        // Now fill undo stack to 100
        for (i in 0 until 100) {
            historyManager.applyAction(HistoryAction.Add(emptyList()))
        }

        // Redo 100 times to fill undo stack further?
        // Let's test redo stack specifically.
        historyManager.clear()

        for (i in 0 until 110) {
            historyManager.applyAction(HistoryAction.Add(emptyList()))
        }

        // undo stack has 100
        // undo 110 times (only 100 possible)
        for (i in 0 until 100) {
            historyManager.undo()
        }

        // redo stack should have 100
        // redo 100 times should work
        for (i in 0 until 100) {
            assertNotNull("Redo $i should not be null", historyManager.redo())
        }
        assertNull(historyManager.redo())
    }
}
