package com.alexdremov.notate.util

import android.graphics.Path
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33])
class StrokeGeometryTest {

    private fun createStroke(points: List<TouchPoint>, width: Float = 10f): Stroke {
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
            color = -16777216,
            width = width,
            style = StrokeType.PENCIL,
            bounds = bounds
        )
    }
    
    @Test
    fun `test distPointToStroke`() {
        val points = listOf(
            TouchPoint(0f, 0f, 1f, 1f, 0L),
            TouchPoint(100f, 0f, 1f, 1f, 0L)
        )
        val stroke = createStroke(points)
        
        // Point exactly on line
        assertEquals(0f, StrokeGeometry.distPointToStroke(50f, 0f, stroke), 0.1f)
        
        // Point 10 units away
        assertEquals(10f, StrokeGeometry.distPointToStroke(50f, 10f, stroke), 0.1f)
        
        // Point beyond end
        assertEquals(10f, StrokeGeometry.distPointToStroke(110f, 0f, stroke), 0.1f)
    }
    
    @Test
    fun `test intersection basic crossing`() {
        // Horizontal line
        val s1 = createStroke(listOf(
            TouchPoint(0f, 50f, 1f, 1f, 0L),
            TouchPoint(100f, 50f, 1f, 1f, 0L)
        ))
        
        // Vertical line crossing it
        val s2 = createStroke(listOf(
            TouchPoint(50f, 0f, 1f, 1f, 0L),
            TouchPoint(50f, 100f, 1f, 1f, 0L)
        ))
        
        assertTrue(StrokeGeometry.strokeIntersects(s1, s2))
    }
    
    @Test
    fun `test intersection no overlap`() {
         val s1 = createStroke(listOf(
            TouchPoint(0f, 0f, 1f, 1f, 0L),
            TouchPoint(10f, 0f, 1f, 1f, 0L)
        ))
        
        val s2 = createStroke(listOf(
            TouchPoint(0f, 100f, 1f, 1f, 0L),
            TouchPoint(10f, 100f, 1f, 1f, 0L)
        ))
        
        assertFalse(StrokeGeometry.strokeIntersects(s1, s2))
    }
    
    @Test
    fun `test isPointInPolygon`() {
        val polygon = listOf(
            TouchPoint(0f, 0f, 0f, 0f, 0L),
            TouchPoint(100f, 0f, 0f, 0f, 0L),
            TouchPoint(100f, 100f, 0f, 0f, 0L),
            TouchPoint(0f, 100f, 0f, 0f, 0L)
        )
        
        assertTrue(StrokeGeometry.isPointInPolygon(50f, 50f, polygon))
        assertFalse(StrokeGeometry.isPointInPolygon(150f, 50f, polygon))
    }
}
