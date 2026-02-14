package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.alexdremov.notate.model.TextItem
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import java.lang.ref.WeakReference
import kotlin.math.ceil

object TextRenderer {
    private var markwon: Markwon? = null

    private fun getMarkwon(context: Context): Markwon {
        var instance = markwon
        if (instance == null) {
            val appContext = context.applicationContext
            instance =
                Markwon
                    .builder(appContext)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(appContext))
                    .usePlugin(TaskListPlugin.create(appContext))
                    .build()
            markwon = instance
        }
        return instance
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

    fun draw(
        canvas: Canvas,
        item: TextItem,
        context: Context?,
        paint: Paint? = null,
    ) {
        if (context == null) return

        com.alexdremov.notate.util.PerformanceProfiler.trace("TextRenderer.draw") {
            val markwon = getMarkwon(context)

            // Cache Logic
            var layout = item.renderCache
            val targetWidth = ceil(item.bounds.width()).toInt().coerceAtLeast(1)

            if (layout == null || layout.text.toString() != item.text || layout.width != targetWidth) {
                // Recreate Layout
                val spanned = markwon.toMarkdown(item.text)
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                textPaint.textSize = item.fontSize
                textPaint.color = item.color

                layout =
                    StaticLayout.Builder
                        .obtain(spanned, 0, spanned.length, textPaint, targetWidth)
                        .setAlignment(item.alignment)
                        .setLineSpacing(0f, 1.0f)
                        .setIncludePad(true)
                        .build()

                item.renderCache = layout
            }

            // Draw
            canvas.save()
            // Translate to position
            canvas.translate(item.bounds.left, item.bounds.top)

            // Rotation
            if (item.rotation != 0f) {
                // Rotate around center of the text box
                canvas.rotate(item.rotation, item.bounds.width() / 2f, item.bounds.height() / 2f)
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
                canvas.drawRect(0f, 0f, item.bounds.width(), height, bgPaint)
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
