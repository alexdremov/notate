package com.alexdremov.notate.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.StrokeRenderer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.alexdremov.notate.testutil.SnapshotVerifier
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingSnapshotTest {

    @Before
    fun setup() {
        mockkStatic(EpdController::class)
        every { EpdController.getMaxTouchPressure() } returns 4096f
        
        mockkStatic(NeoBrushPenWrapper::class)
        // Mock native brush drawing to just draw a simple path (simulation)
        // signature: drawStroke(Canvas, Paint, List<TouchPoint>, float, float, boolean)
        every { 
            NeoBrushPenWrapper.drawStroke(any(), any(), any(), any(), any(), any()) 
        } answers {
            val canvas = arg<Canvas>(0)
            val paint = arg<Paint>(1)
            val points = arg<List<TouchPoint>>(2)
            
            // Simulate native drawing by just drawing lines
            if (points.isNotEmpty()) {
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }
    
    @After
    fun teardown() {
        unmockkStatic(EpdController::class)
        unmockkStatic(NeoBrushPenWrapper::class)
    }

    private fun createStroke(points: List<TouchPoint>, type: StrokeType, width: Float = 10f, color: Int = Color.BLACK): Stroke {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.inset(-width, -width)

        return Stroke(
            path = path,
            points = points,
            color = color,
            width = width,
            style = type,
            bounds = bounds
        )
    }

    private fun createSineWavePoints(): List<TouchPoint> {
        val points = ArrayList<TouchPoint>()
        val segments = 100
        val startX = 50f
        val endX = 450f
        val amplitude = 80f
        val centerY = 150f

        for (i in 0..segments) {
            val t = i / segments.toFloat() // 0.0 to 1.0
            val x = startX + (endX - startX) * t
            val y = centerY + amplitude * sin(t * Math.PI * 4).toFloat() 

            // Varying Pressure
            val pressure = (sin(t * Math.PI) * 4096).toFloat().coerceIn(100f, 4096f)
            
            // Varying Tilt (Int)
            val tiltX = (t * 90).toInt() // 0 to 90 degrees
            val tiltY = 0
            
            val size = 10f + t * 20f

            points.add(TouchPoint(x, y, pressure, size, tiltX, tiltY, i * 10L))
        }
        return points
    }

    private fun verifySnapshot(type: StrokeType, name: String, color: Int = Color.BLACK, width: Float = 10f) {
        val widthPx = 500
        val heightPx = 300
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint()

        val points = createSineWavePoints()
        val stroke = createStroke(points, type, width, color)
        
        StrokeRenderer.drawStroke(canvas, paint, stroke)
        
        SnapshotVerifier.verify(bitmap, name)
    }

    @Test
    fun `test snapshot pencil`() {
        verifySnapshot(StrokeType.PENCIL, "snapshot_pencil")
    }

    @Test
    fun `test snapshot fountain`() {
        verifySnapshot(StrokeType.FOUNTAIN, "snapshot_fountain", Color.BLUE, 15f)
    }

    @Test
    fun `test snapshot highlighter`() {
        verifySnapshot(StrokeType.HIGHLIGHTER, "snapshot_highlighter", Color.YELLOW, 40f)
    }

    @Test
    fun `test snapshot brush`() {
        verifySnapshot(StrokeType.BRUSH, "snapshot_brush", Color.RED, 20f)
    }

    @Test
    fun `test snapshot charcoal`() {
        verifySnapshot(StrokeType.CHARCOAL, "snapshot_charcoal", Color.DKGRAY, 30f)
    }

    @Test
    fun `test snapshot dash`() {
        verifySnapshot(StrokeType.DASH, "snapshot_dash", Color.BLACK, 5f)
    }

    @Test
    fun `test complex stroke canonical snapshot`() {
        // 1. Setup Canvas
        val width = 500
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE) // White background for clarity
        val paint = Paint()

        // 2. Generate Complex Path
        val points = createSineWavePoints()

        // 3. Render FOUNTAIN pen (sensitive to pressure)
        val stroke = createStroke(points, StrokeType.FOUNTAIN, width = 5f, color = Color.BLUE)
        StrokeRenderer.drawStroke(canvas, paint, stroke)

        // 4. Render a second overlapping HIGHLIGHTER stroke
        val highlighterPoints = listOf(
            TouchPoint(50f, 150f, 2000f, 20f, 0, 0, 0L),
            TouchPoint(450f, 150f, 2000f, 20f, 0, 0, 0L)
        )
        val hlStroke = createStroke(highlighterPoints, StrokeType.HIGHLIGHTER, width = 30f, color = Color.YELLOW)
        StrokeRenderer.drawStroke(canvas, paint, hlStroke)

        // 5. Verify
        SnapshotVerifier.verify(bitmap, "complex_stroke_fountain_highlighter")
    }
}
