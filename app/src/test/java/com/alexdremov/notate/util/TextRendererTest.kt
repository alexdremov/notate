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
                order = 1L, // Assign order for caching
            )

        val canvas = Canvas()

        // First draw - should create cache
        TextRenderer.draw(canvas, item, context)
        val entry1 = TextRenderer.layoutCache.get(item.order)
        assertThat(entry1).isNotNull()
        val layout1 = entry1.layout

        // Second draw with same properties - should reuse cache
        TextRenderer.draw(canvas, item, context)
        val entry2 = TextRenderer.layoutCache.get(item.order)
        assertThat(entry2.layout).isSameInstanceAs(layout1)

        // Draw with different text - should invalidate cache
        val updatedItem = item.copy(text = "New Text")
        TextRenderer.draw(canvas, updatedItem, context)
        val entry3 = TextRenderer.layoutCache.get(updatedItem.order)
        assertThat(entry3.layout).isNotSameInstanceAs(layout1)
    }
}
