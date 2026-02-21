package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.alexdremov.notate.model.TextItem
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlin.math.ceil

object TextRenderer {
    @Volatile
    private var markwon: Markwon? = null

    // Thread-safe external cache for StaticLayouts
    internal data class CacheEntry(
        val item: TextItem,
        val layout: StaticLayout,
    )

    internal val layoutCache = LruCache<Long, CacheEntry>(200)

    private fun getMarkwon(context: Context): Markwon =
        markwon ?: synchronized(this) {
            markwon ?: run {
                val appContext = context.applicationContext
                Markwon
                    .builder(appContext)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(appContext))
                    .usePlugin(TaskListPlugin.create(appContext))
                    .build()
                    .also { markwon = it }
            }
        }

    fun measureHeight(
        context: Context,
        text: String,
        width: Float,
        fontSize: Float,
    ): Float {
        val markwon = getMarkwon(context)
        val spanned = markwon.toMarkdown(text)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = fontSize

        val layout =
            StaticLayout.Builder
                .obtain(spanned, 0, spanned.length, paint, ceil(width).toInt().coerceAtLeast(1))
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(true)
                .build()

        return layout.height.toFloat()
    }

    fun getStaticLayout(
        context: Context,
        item: TextItem,
    ): StaticLayout {
        val markwon = getMarkwon(context)
        val entry = if (item.order != 0L) layoutCache.get(item.order) else null
        if (entry != null && entry.item == item) {
            return entry.layout
        }
        val targetWidth = ceil(item.logicalBounds.width()).toInt().coerceAtLeast(1)
        val spanned = markwon.toMarkdown(item.text)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textSize = item.fontSize
        textPaint.color = item.color

        val newLayout =
            StaticLayout.Builder
                .obtain(spanned, 0, spanned.length, textPaint, targetWidth)
                .setAlignment(item.alignment)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(true)
                .build()

        if (item.order != 0L) {
            layoutCache.put(item.order, CacheEntry(item.copy(), newLayout))
        }
        return newLayout
    }

    fun draw(
        canvas: Canvas,
        item: TextItem,
        context: Context?,
        paint: Paint? = null,
    ) {
        if (context == null) return

        com.alexdremov.notate.util.PerformanceProfiler.trace("TextRenderer.draw") {
            val layout = getStaticLayout(context, item)

            // Draw
            canvas.save()
            // Translate to position
            canvas.translate(item.logicalBounds.left, item.logicalBounds.top)

            // Rotation
            if (item.rotation != 0f) {
                // Rotate around center of the text box
                canvas.rotate(item.rotation, item.logicalBounds.width() / 2f, item.logicalBounds.height() / 2f)
            }

            // Draw Background if set
            if (item.backgroundColor != android.graphics.Color.TRANSPARENT) {
                val bgPaint = Paint()
                bgPaint.color = item.backgroundColor
                bgPaint.style = Paint.Style.FILL
                if (paint?.xfermode != null) {
                    bgPaint.xfermode = paint.xfermode
                }
                // Determine height from layout
                val height = layout.height.toFloat()
                canvas.drawRect(0f, 0f, item.logicalBounds.width(), height, bgPaint)
            }

            // Apply external paint properties (Xfermode) to Layout Paint
            val originalXfermode = layout.paint.xfermode
            if (paint?.xfermode != null) {
                layout.paint.xfermode = paint.xfermode
            }

            // Opacity
            val originalAlpha = layout.paint.alpha
            if (item.opacity < 1.0f) {
                layout.paint.alpha = (item.opacity * 255).toInt()
            }

            layout.draw(canvas)

            // Restore Paint State
            layout.paint.xfermode = originalXfermode
            if (item.opacity < 1.0f) {
                layout.paint.alpha = originalAlpha
            }

            canvas.restore()
        }
    }
}
