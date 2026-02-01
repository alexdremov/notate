package com.alexdremov.notate.util

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrokeRendererTest {
    @Test
    fun `drawStroke with BALLPOINT and DST_OUT uses SimplePathStrategy (single drawPath call)`() {
        // Arrange
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = Paint()
        val path = mockk<Path>(relaxed = true)
        val bounds = RectF(0f, 0f, 100f, 100f)

        // Create a stroke with enough points that BallpointRenderer WOULD normally segment it
        val points =
            listOf(
                TouchPoint(10f, 10f, 0.5f, 1.0f, 0, 0, 1000),
                TouchPoint(20f, 20f, 0.8f, 1.0f, 0, 0, 1010),
                TouchPoint(30f, 30f, 0.2f, 1.0f, 0, 0, 1020),
            )

        val stroke =
            Stroke(
                path = path,
                points = points,
                color = android.graphics.Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        // Act
        StrokeRenderer.drawStroke(
            canvas = canvas,
            paint = paint,
            stroke = stroke,
            xfermode = PorterDuff.Mode.DST_OUT,
        )

        // Assert
        // SimplePathStrategy calls canvas.drawPath(stroke.path, paint) EXACTLY once.
        verify(exactly = 1) { canvas.drawPath(stroke.path, any()) }
    }

    @Test
    fun `drawStroke with BALLPOINT and NORMAL mode uses BallpointStrategy`() {
        // Arrange
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = Paint()
        val path = mockk<Path>(relaxed = true)
        val bounds = RectF(0f, 0f, 100f, 100f)

        val points =
            listOf(
                TouchPoint(10f, 10f, 0.5f, 1.0f, 0, 0, 1000),
                TouchPoint(50f, 50f, 0.9f, 1.0f, 0, 0, 1010),
                TouchPoint(90f, 90f, 0.1f, 1.0f, 0, 0, 1020),
            )

        val stroke =
            Stroke(
                path = path,
                points = points,
                color = android.graphics.Color.BLACK,
                width = 5.0f,
                style = StrokeType.BALLPOINT,
                bounds = bounds,
            )

        // Act
        StrokeRenderer.drawStroke(
            canvas = canvas,
            paint = paint,
            stroke = stroke,
            xfermode = null, // Normal mode
        )

        // Assert
        // Should NOT use SimplePathStrategy (stroke.path), but rather segments
        verify(exactly = 0) { canvas.drawPath(stroke.path, any()) }
        verify(atLeast = 1) { canvas.drawPath(any(), any()) }
    }

    @Test
    fun `drawStroke with HIGHLIGHTER and DST_OUT uses SimplePathStrategy`() {
        // Arrange
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = Paint()
        val path = mockk<Path>(relaxed = true)
        val bounds = RectF(0f, 0f, 100f, 100f)
        val points = listOf(TouchPoint(10f, 10f, 0.5f, 1.0f, 0))

        val stroke =
            Stroke(
                path = path,
                points = points,
                color = android.graphics.Color.YELLOW,
                width = 20.0f,
                style = StrokeType.HIGHLIGHTER,
                bounds = bounds,
            )

        // Act
        StrokeRenderer.drawStroke(
            canvas = canvas,
            paint = paint,
            stroke = stroke,
            xfermode = PorterDuff.Mode.DST_OUT,
        )

        // Assert
        verify(exactly = 1) { canvas.drawPath(stroke.path, any()) }
        verify(exactly = 0) { canvas.saveLayer(any(), any()) }
    }
}
