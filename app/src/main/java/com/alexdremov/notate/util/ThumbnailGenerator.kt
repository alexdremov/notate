package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.StrokeType
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ThumbnailGenerator {
    private const val THUMB_WIDTH = 300
    private const val THUMB_HEIGHT = 300
    private const val PADDING = 10f

    fun generateBase64(
        data: CanvasData,
        context: android.content.Context,
    ): String? =
        try {
            val bitmap = generateBitmap(data, context)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val byteArray = outputStream.toByteArray()
            bitmap.recycle()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    private fun generateBitmap(
        data: CanvasData,
        context: android.content.Context,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE) // Background

        // 1. Calculate World Bounds
        val bounds =
            when (data.canvasType) {
                CanvasType.FIXED_PAGES -> {
                    val w = if (data.pageWidth > 0) data.pageWidth else 2480f
                    val h = if (data.pageHeight > 0) data.pageHeight else 3508f
                    RectF(0f, 0f, w, h)
                }

                CanvasType.INFINITE -> {
                    calculateContentBounds(data)
                }
            }

        // 2. Setup Transform
        val scaleX = (THUMB_WIDTH - PADDING * 2) / bounds.width()
        val scaleY = (THUMB_HEIGHT - PADDING * 2) / bounds.height()
        val scale = min(scaleX, scaleY).takeIf { it.isFinite() && it > 0 } ?: 1f

        val dx = (THUMB_WIDTH - bounds.width() * scale) / 2f - bounds.left * scale
        val dy = (THUMB_HEIGHT - bounds.height() * scale) / 2f - bounds.top * scale

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        // 3. Draw Strokes
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        val strokesToDraw =
            if (data.strokes.size > 2000) {
                data.strokes.filterIndexed { index, _ -> index % 2 == 0 }
            } else {
                data.strokes
            }

        strokesToDraw.forEach { stroke ->
            drawStroke(canvas, stroke, paint)
        }

        // 4. Draw Images
        data.images.forEach { imgData ->
            val image =
                CanvasImage(
                    uri = imgData.uri,
                    bounds = RectF(imgData.x, imgData.y, imgData.x + imgData.width, imgData.y + imgData.height),
                    zIndex = imgData.zIndex,
                    order = imgData.order,
                    rotation = imgData.rotation,
                    opacity = imgData.opacity,
                )
            StrokeRenderer.drawItem(canvas, image, false, paint, context)
        }

        canvas.restore()
        return bitmap
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: StrokeData,
        paint: Paint,
    ) {
        paint.color = stroke.color
        paint.strokeWidth = stroke.width

        if (stroke.style == StrokeType.HIGHLIGHTER) {
            paint.alpha = 80
            paint.strokeWidth = stroke.width * 1.5f
        } else {
            paint.alpha = 255
        }

        val path = Path()

        if (stroke.pointsPacked != null && stroke.pointsPacked.size >= 2) {
            val arr = stroke.pointsPacked
            val stride = StrokeData.PACKED_POINT_STRIDE
            path.moveTo(arr[0], arr[1])
            var i = stride
            while (i < arr.size) {
                path.lineTo(arr[i], arr[i + 1])
                i += stride
            }
        } else if (stroke.points.isNotEmpty()) {
            val list = stroke.points
            path.moveTo(list[0].x, list[0].y)
            for (i in 1 until list.size) {
                path.lineTo(list[i].x, list[i].y)
            }
        }

        canvas.drawPath(path, paint)
    }

    private fun calculateContentBounds(data: CanvasData): RectF {
        if (data.strokes.isEmpty() && data.images.isEmpty()) return RectF(0f, 0f, 1000f, 1000f)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var hasContent = false

        data.strokes.forEach { stroke ->
            if (stroke.pointsPacked != null) {
                val arr = stroke.pointsPacked
                var i = 0
                while (i < arr.size) {
                    val x = arr[i]
                    val y = arr[i + 1]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    hasContent = true
                    i += 4 * StrokeData.PACKED_POINT_STRIDE // Skip some points
                }
            } else {
                stroke.points.forEach { p ->
                    if (p.x < minX) minX = p.x
                    if (p.x > maxX) maxX = p.x
                    if (p.y < minY) minY = p.y
                    if (p.y > maxY) maxY = p.y
                    hasContent = true
                }
            }
        }

        data.images.forEach { img ->
            if (img.x < minX) minX = img.x
            if (img.x + img.width > maxX) maxX = img.x + img.width
            if (img.y < minY) minY = img.y
            if (img.y + img.height > maxY) maxY = img.y + img.height
            hasContent = true
        }

        return if (hasContent) {
            RectF(minX, minY, maxX, maxY)
        } else {
            RectF(0f, 0f, 1000f, 1000f)
        }
    }
}
