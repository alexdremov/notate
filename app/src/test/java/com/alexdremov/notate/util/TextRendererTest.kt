package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.alexdremov.notate.model.TextItem
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TextRendererTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `test measureHeight returns positive value`() {
        val height =
            TextRenderer.measureHeight(
                context = context,
                text = "This is a test\nwith multiple lines.",
                width = 200f,
                fontSize = 20f,
            )
        assertThat(height).isGreaterThan(0f)
    }

    @Test
    fun `test cache logic in draw`() {
        val item =
            TextItem(
                text = "Cache Test",
                bounds = RectF(0f, 0f, 100f, 50f),
                fontSize = 16f,
                color = Color.BLACK,
            )

        val canvas = Canvas()

        // First draw - should create cache
        TextRenderer.draw(canvas, item, context)
        val cache1 = item.renderCache
        assertThat(cache1).isNotNull()

        // Second draw with same properties - should reuse cache
        TextRenderer.draw(canvas, item, context)
        assertThat(item.renderCache).isSameInstanceAs(cache1)

        // Draw with different text - should invalidate cache
        val updatedItem = item.copy(text = "New Text")
        TextRenderer.draw(canvas, updatedItem, context)
        // Since TextRenderer matches layout.text.toString() vs item.text,
        // we need to make sure draw actually updates the cache if it's different.
        assertThat(updatedItem.renderCache).isNotSameInstanceAs(cache1)
    }
}
