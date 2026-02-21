package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.util.StrokeGeometry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TextItemTest {
    @Test
    fun `test text item properties`() {
        val logical = RectF(10f, 20f, 110f, 40f)
        val rotation = 45f
        val aabb = StrokeGeometry.computeRotatedBounds(logical, rotation)
        val item =
            TextItem(
                text = "Hello World",
                fontSize = 16f,
                color = 0xFF000000.toInt(),
                logicalBounds = logical,
                bounds = aabb,
                order = 123L,
                zIndex = 5f,
                rotation = rotation,
                opacity = 0.8f,
            )

        assertThat(item.text).isEqualTo("Hello World")
        assertThat(item.fontSize).isEqualTo(16f)
        assertThat(item.logicalBounds).isEqualTo(logical)
        assertThat(item.bounds).isEqualTo(aabb)
        assertThat(item.order).isEqualTo(123L)
        assertThat(item.zIndex).isEqualTo(5f)
        assertThat(item.rotation).isEqualTo(45f)
        assertThat(item.opacity).isEqualTo(0.8f)
    }

    @Test
    fun `test copy with defaults`() {
        val logical = RectF(0f, 0f, 100f, 50f)
        val item = TextItem(text = "Initial", logicalBounds = logical, bounds = logical, fontSize = 16f, color = 0)
        val copied = item.copy(text = "Updated")

        assertThat(copied.text).isEqualTo("Updated")
        assertThat(copied.bounds).isEqualTo(item.bounds)
        assertThat(copied.fontSize).isEqualTo(item.fontSize)
    }
}
