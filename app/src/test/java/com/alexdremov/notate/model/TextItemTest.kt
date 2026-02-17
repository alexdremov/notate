package com.alexdremov.notate.model

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextItemTest {
    @Test
    fun `test text item properties`() {
        val bounds = RectF(10f, 20f, 110f, 40f)
        val item =
            TextItem(
                text = "Hello World",
                fontSize = 16f,
                color = 0xFF000000.toInt(),
                bounds = bounds,
                order = 123L,
                zIndex = 5f,
                rotation = 45f,
                opacity = 0.8f,
            )

        assertThat(item.text).isEqualTo("Hello World")
        assertThat(item.fontSize).isEqualTo(16f)
        assertThat(item.bounds).isEqualTo(bounds)
        assertThat(item.order).isEqualTo(123L)
        assertThat(item.zIndex).isEqualTo(5f)
        assertThat(item.rotation).isEqualTo(45f)
        assertThat(item.opacity).isEqualTo(0.8f)
    }

    @Test
    fun `test copy with defaults`() {
        val item = TextItem(text = "Initial", bounds = RectF(0f, 0f, 100f, 50f), fontSize = 16f, color = 0)
        val copied = item.copy(text = "Updated")

        assertThat(copied.text).isEqualTo("Updated")
        assertThat(copied.bounds).isEqualTo(item.bounds)
        assertThat(copied.fontSize).isEqualTo(item.fontSize)
    }
}
