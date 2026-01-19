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
}
