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
    @Test
    fun `test history size limit`() {
        val historyManager = HistoryManager()

        // Apply 110 actions
        for (i in 0 until 110) {
            historyManager.addToStack(HistoryAction.Add(emptyList()))
        }

        // Undo 100 times should work
        for (i in 0 until 100) {
            assertNotNull("Undo $i should not be null", historyManager.undoActionOnly())
        }

        // The 101st undo should be null because limit is 100
        assertNull("101st undo should be null", historyManager.undoActionOnly())
    }

    @Test
    fun `test redo size limit`() {
        val historyManager = HistoryManager()

        // Add 1 action
        historyManager.addToStack(HistoryAction.Add(emptyList()))

        // Undo it
        historyManager.undoActionOnly()

        // Redo stack should have 1 item
        assertNotNull(historyManager.redoActionOnly())

        // Now fill undo stack to 100
        historyManager.clear()
        for (i in 0 until 110) {
            historyManager.addToStack(HistoryAction.Add(emptyList()))
        }

        // undo stack has 100
        // undo 110 times (only 100 possible)
        for (i in 0 until 100) {
            historyManager.undoActionOnly()
        }

        // redo stack should have 100
        // redo 100 times should work
        for (i in 0 until 100) {
            assertNotNull("Redo $i should not be null", historyManager.redoActionOnly())
        }
        assertNull(historyManager.redoActionOnly())
    }
}
