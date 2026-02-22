package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import com.alexdremov.notate.R
import com.alexdremov.notate.data.LinkType
import com.alexdremov.notate.model.LinkItem

object LinkRenderer {
    private const val PADDING_X = 20f
    private const val PADDING_Y = 10f
    private const val ICON_SIZE = 24f
    private const val ICON_PADDING = 10f
    private const val CORNER_RADIUS = 12f

    fun measureSize(
        context: Context,
        label: String,
        fontSize: Float,
    ): Pair<Float, Float> {
        val paint = Paint()
        paint.textSize = fontSize
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val textWidth = paint.measureText(label)
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        val totalWidth = PADDING_X * 2 + ICON_SIZE + ICON_PADDING + textWidth
        val totalHeight = kotlin.math.max(textHeight, ICON_SIZE) + PADDING_Y * 2

        return Pair(totalWidth, totalHeight)
    }

    fun draw(
        canvas: Canvas,
        item: LinkItem,
        context: Context?,
        paint: Paint,
        scale: Float = 1.0f,
    ) {
        val originalStyle = paint.style
        val originalColor = paint.color
        val originalStrokeWidth = paint.strokeWidth

        // Apply rotation around center
        canvas.save()
        val centerX = item.bounds.centerX()
        val centerY = item.bounds.centerY()
        canvas.rotate(item.rotation, centerX, centerY)

        // Draw Background (White capsule)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        // Shadow effect could be added here if needed
        canvas.drawRoundRect(item.logicalBounds, CORNER_RADIUS, CORNER_RADIUS, paint)

        // Draw Border (Non-Scaling Stroke)
        paint.style = Paint.Style.STROKE
        paint.color = item.color
        val effectiveScale = if (scale > 0) scale else 1.0f
        paint.strokeWidth = 2f / effectiveScale
        canvas.drawRoundRect(item.logicalBounds, CORNER_RADIUS, CORNER_RADIUS, paint)

        // Draw Icon and Text
        val contentLeft = item.logicalBounds.left + PADDING_X
        val contentCenterY = item.logicalBounds.centerY()

        // Icon
        drawIcon(canvas, item.type, contentLeft, contentCenterY, ICON_SIZE, paint, context, item.color)

        // Text
        val textLeft = contentLeft + ICON_SIZE + ICON_PADDING
        paint.style = Paint.Style.FILL
        paint.color = item.color // Text same color as border/icon
        paint.textSize = item.fontSize
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        // Align text vertically
        val fontMetrics = paint.fontMetrics
        val textY = contentCenterY - (fontMetrics.descent + fontMetrics.ascent) / 2
        canvas.drawText(item.label, textLeft, textY, paint)

        // Restore paint and canvas
        paint.style = originalStyle
        paint.color = originalColor
        paint.strokeWidth = originalStrokeWidth
        canvas.restore()
    }

    private fun drawIcon(
        canvas: Canvas,
        type: LinkType,
        x: Float,
        y: Float,
        size: Float,
        paint: Paint,
        context: Context?,
        color: Int,
    ) {
        if (context == null) return

        val drawableId =
            when (type) {
                LinkType.INTERNAL_NOTE -> R.drawable.ic_link_file
                LinkType.EXTERNAL_URL -> R.drawable.ic_link_globe
            }

        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return

        drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)

        val left = x.toInt()
        val top = (y - size / 2).toInt()
        val right = (x + size).toInt()
        val bottom = (y + size / 2).toInt()

        drawable.setBounds(left, top, right, bottom)
        drawable.draw(canvas)
    }
}
