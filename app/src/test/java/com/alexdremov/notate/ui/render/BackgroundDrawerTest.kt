package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.RectF
import com.alexdremov.notate.model.BackgroundStyle
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BackgroundDrawerTest {
    @Test
    fun `test no overflow with large bounds`() {
        val canvas = mockk<Canvas>(relaxed = true)
        // Huge rect that would cause overflow with Int
        val hugeRect = RectF(0f, 0f, 100000f, 100000f)
        val style = BackgroundStyle.Dots(spacing = 2f, radius = 1f, color = 0) // Small spacing -> many dots

        // Should return early safely instead of crashing or throwing OOM
        BackgroundDrawer.draw(canvas, style, hugeRect, forceVector = true)

        // Verify we didn't crash.
    }

    @Test
    fun `test grid drawing bounds`() {
        val canvas = mockk<Canvas>(relaxed = true)
        val rect = RectF(0f, 0f, 100f, 100f)
        val style = BackgroundStyle.Grid(spacing = 10f, thickness = 1f, color = 0)

        BackgroundDrawer.draw(canvas, style, rect, forceVector = true)

        // Verify drawLines was called
        verify(atLeast = 1) { canvas.drawLines(any(), any(), any(), any()) }
    }

    @Test
    fun `test buffer resizing`() {
        val canvas = mockk<Canvas>(relaxed = true)
        // Default buffer is 20000 lines (80k floats).
        // Let's force a larger draw.
        // 25000 lines requires 100k floats.
        // Spacing 10 -> needs 250000 height.
        val rect = RectF(0f, 0f, 100f, 250000f)
        val style = BackgroundStyle.Lines(spacing = 10f, thickness = 1f, color = 0)

        // This will request ~25000 lines. MAX_PRIMITIVES is 20000.
        // BackgroundDrawer limits to MAX_PRIMITIVES if !forceVector.
        // So we must use forceVector = true to test resizing logic inside drawLinesVector.

        BackgroundDrawer.draw(canvas, style, rect, forceVector = true)

        verify { canvas.drawLines(any(), any(), any(), any()) }
    }

    @Test
    fun `test multiple calls reuse buffer`() {
        val canvas = mockk<Canvas>(relaxed = true)
        val rect = RectF(0f, 0f, 100f, 100f)
        val style = BackgroundStyle.Grid(spacing = 10f, thickness = 1f, color = 0)

        // Call 1
        BackgroundDrawer.draw(canvas, style, rect, forceVector = true)
        // Call 2 (Should reuse buffer)
        BackgroundDrawer.draw(canvas, style, rect, forceVector = true)

        verify(exactly = 2) { canvas.drawLines(any(), any(), any(), any()) }
    }

    @Test
    fun `test small pattern`() {
        val canvas = mockk<Canvas>(relaxed = true)
        // Small rect, few lines
        val rect = RectF(0f, 0f, 100f, 100f) // Increased size
        val style = BackgroundStyle.Lines(spacing = 20f, thickness = 1f, color = 0) // Increased spacing > 10f

        BackgroundDrawer.draw(canvas, style, rect, forceVector = true)

        // Should draw lines.
        verify { canvas.drawLines(any(), 0, any(), any()) }
    }
}
