package com.alexdremov.notate.ui.render.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.alexdremov.notate.model.BackgroundStyle
import kotlin.math.ceil

/**
 * Manages the BitmapShader cache for background patterns.
 * Handles invalidation and regeneration when style changes.
 */
class BackgroundPatternCache {
    private var cachedBitmap: Bitmap? = null
    private var cachedShader: BitmapShader? = null
    private var cachedStyle: BackgroundStyle? = null
    private val shaderMatrix = Matrix()

    private val shaderPaint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

    fun drawCached(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
        spacing: Float,
    ) {
        if (cachedStyle != style) {
            updateCache(style, spacing)
        }

        val shader = cachedShader ?: return

        val bitmapSize = cachedBitmap?.width?.toFloat() ?: spacing
        val scale = spacing / bitmapSize

        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(offsetX, offsetY)

        shader.setLocalMatrix(shaderMatrix)
        shaderPaint.shader = shader

        canvas.drawRect(rect, shaderPaint)
    }

    private fun updateCache(
        style: BackgroundStyle,
        spacing: Float,
    ) {
        // Cap texture size to avoid OOM
        val targetSize = ceil(spacing).toInt()
        val bitmapSize = if (targetSize > 256) 256 else targetSize

        if (bitmapSize <= 0) return

        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Scale rendering to fit into our capped bitmap size
        val drawScale = bitmapSize.toFloat() / spacing
        canvas.scale(drawScale, drawScale)

        BackgroundPrimitiveRenderer.drawTilePrimitives(canvas, style, spacing)

        cachedBitmap = bitmap
        cachedStyle = style
        cachedShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
