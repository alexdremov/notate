package com.alexdremov.notate.util

import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.TextItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasItemBoundsTest {
    @Test
    fun `test computeRotatedBounds identity`() {
        val logical = RectF(100f, 100f, 300f, 200f)
        val aabb = StrokeGeometry.computeRotatedBounds(logical, 0f)
        assertEquals(logical.left, aabb.left, 0.01f)
        assertEquals(logical.top, aabb.top, 0.01f)
        assertEquals(logical.right, aabb.right, 0.01f)
        assertEquals(logical.bottom, aabb.bottom, 0.01f)
    }

    @Test
    fun `test computeRotatedBounds 90 degrees`() {
        val logical = RectF(100f, 100f, 300f, 200f) // width 200, height 100
        val aabb = StrokeGeometry.computeRotatedBounds(logical, 90f)

        // Center is (200, 150)
        // After 90 deg rotation, width and height swap.
        // New width 100, new height 200
        // New bounds: left=200-50=150, top=150-100=50, right=200+50=250, bottom=150+100=250
        assertEquals(150f, aabb.left, 0.01f)
        assertEquals(50f, aabb.top, 0.01f)
        assertEquals(250f, aabb.right, 0.01f)
        assertEquals(250f, aabb.bottom, 0.01f)
    }

    @Test
    fun `test transformItemLogicalBounds translation`() {
        val logical = RectF(0f, 0f, 100f, 100f)
        val matrix = Matrix()
        matrix.postTranslate(50f, 50f)

        val (newLogical, newRotation, newAabb) = StrokeGeometry.transformItemLogicalBounds(logical, 0f, matrix)

        assertEquals(50f, newLogical.left, 0.01f)
        assertEquals(50f, newLogical.top, 0.01f)
        assertEquals(150f, newLogical.right, 0.01f)
        assertEquals(150f, newLogical.bottom, 0.01f)
        assertEquals(0f, newRotation, 0.01f)
        assertEquals(newLogical, newAabb)
    }

    @Test
    fun `test transformItemLogicalBounds scale`() {
        val logical = RectF(0f, 0f, 100f, 100f)
        val matrix = Matrix()
        matrix.postScale(2f, 3f)

        val (newLogical, newRotation, newAabb) = StrokeGeometry.transformItemLogicalBounds(logical, 0f, matrix)

        // Scale is applied relative to (0,0) by default in Matrix.postScale
        assertEquals(0f, newLogical.left, 0.01f)
        assertEquals(0f, newLogical.top, 0.01f)
        assertEquals(200f, newLogical.right, 0.01f)
        assertEquals(300f, newLogical.bottom, 0.01f)
        assertEquals(0f, newRotation, 0.01f)
    }

    @Test
    fun `test transformItemLogicalBounds rotation direction`() {
        val logical = RectF(0f, 0f, 100f, 100f)
        val matrix = Matrix()
        matrix.postRotate(45f) // Clockwise

        val (newLogical, newRotation, newAabb) = StrokeGeometry.transformItemLogicalBounds(logical, 0f, matrix)

        assertEquals(45f, newRotation, 0.01f)
        // Logical bounds should NOT grow just because of rotation
        assertEquals(100f, newLogical.width(), 0.01f)
        assertEquals(100f, newLogical.height(), 0.01f)

        // AABB should be larger than logical
        assertTrue(newAabb.width() > 100f)
        assertTrue(newAabb.height() > 100f)
    }

    @Test
    fun `test CanvasImage consistency`() {
        val logical = RectF(0f, 0f, 100f, 100f)
        val rotation = 45f
        val aabb = StrokeGeometry.computeRotatedBounds(logical, rotation)

        val image =
            CanvasImage(
                uri = "test",
                logicalBounds = logical,
                bounds = aabb,
                rotation = rotation,
            )

        // Hit test at center
        assertEquals(0f, image.distanceToPoint(50f, 50f), 0.01f)

        // Hit test outside
        assertTrue(image.distanceToPoint(200f, 200f) > 0f)
    }

    @Test
    fun `test TextItem distanceToPoint rotated`() {
        val logical = RectF(0f, 0f, 100f, 20f)
        val rotation = 90f // Vertical
        val aabb = StrokeGeometry.computeRotatedBounds(logical, rotation)
        // Center is (50, 10). Rotated 90 deg: width 20, height 100.
        // new AABB: left=50-10=40, top=10-50=-40, right=50+10=60, bottom=10+50=60

        val text =
            TextItem(
                text = "test",
                fontSize = 12f,
                color = 0,
                logicalBounds = logical,
                bounds = aabb,
                rotation = rotation,
            )

        // (50, 50) is inside the rotated vertical box
        assertEquals(0f, text.distanceToPoint(50f, 50f), 0.01f)

        // (70, 50) is outside
        assertTrue(text.distanceToPoint(70f, 50f) > 0f)
    }
}
