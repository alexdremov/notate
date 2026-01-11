package com.alexdremov.notate.ui.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.alexdremov.notate.model.BackgroundStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

object BackgroundDrawer {
    private val paint =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    private val fillPaint =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    private val shaderPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private const val MAX_PRIMITIVES = 10000

    // Caching
    private var cachedBitmap: Bitmap? = null
    private var cachedShader: BitmapShader? = null
    private var cachedStyle: BackgroundStyle? = null
    private val shaderMatrix = Matrix()

    fun draw(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        zoomLevel: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.right.isFinite() || !rect.top.isFinite() || !rect.bottom.isFinite()) return

        // Hybrid Rendering Strategy:
        // High Zoom (> 2.0): Use Vector Loops. Items are sparse, loops are fast, quality is perfect.
        // Low Zoom (<= 2.0): Use Bitmap Cache. Items are dense, loops are slow.
        val useCache = zoomLevel <= 2.0f

        when (style) {
            is BackgroundStyle.Blank -> {
                // No pattern to draw
            }

            is BackgroundStyle.Dots -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    drawWithCache(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawDots(canvas, style, rect, offsetX, offsetY)
                }
            }

            is BackgroundStyle.Lines -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    drawWithCache(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawLines(canvas, style, rect, offsetX, offsetY)
                }
            }

            is BackgroundStyle.Grid -> {
                if (style.spacing <= 0.1f) return
                if (useCache) {
                    drawWithCache(canvas, style, rect, offsetX, offsetY, style.spacing)
                } else {
                    drawGrid(canvas, style, rect, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawWithCache(
        canvas: Canvas,
        style: BackgroundStyle,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
        spacing: Float
    ) {
        if (cachedStyle != style) {
            updateCache(style, spacing)
        }

        val shader = cachedShader ?: return

        // The pattern in the bitmap is 1:1 with World Units (assuming we generated it that way).
        // If our bitmap size `S` corresponds to `spacing` world units.
        // We don't need to scale the shader if 1px = 1 unit.
        // But if spacing is non-integer (e.g. 50.5), we generated a bitmap of size ceil(50.5) = 51.
        // So we need to scale the bitmap back to match spacing.
        // Scale = spacing / bitmapSize.

        val bitmapSize = cachedBitmap?.width?.toFloat() ?: spacing
        val scale = spacing / bitmapSize

        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)

        // Align pattern.
        // We want the pattern anchor (0,0 of bitmap) to land at (offsetX, offsetY) in World Space.
        // The shader tiles from (0,0) world space by default.
        // So we translate the shader by (offsetX % spacing, offsetY % spacing).
        // Actually, simple translation by (offsetX, offsetY) works because it tiles.
        shaderMatrix.postTranslate(offsetX, offsetY)

        shader.setLocalMatrix(shaderMatrix)
        shaderPaint.shader = shader

        canvas.drawRect(rect, shaderPaint)
    }

    private fun updateCache(style: BackgroundStyle, spacing: Float) {
        // Recycle old
        // cachedBitmap?.recycle() // Don't recycle immediately if used elsewhere? Safe here as we own it.
        // Actually, safer to let GC handle it or reuse if mutable. For now, create new.

        // Determine Bitmap Size
        // We want 1px ~ 1 World Unit.
        // Size should be at least spacing.
        // Cap at reasonable max to prevent OOM if spacing is huge (though huge spacing means vector is better).
        // Since we only use cache for spacing * zoom < Threshold...
        // If spacing is 5000, and zoom is 0.001 (visible), we might try cache.
        // But 5000px bitmap is big.
        // Let's cap texture size at 256px.
        // If spacing > 256, we scale down the drawing into the 256px bitmap.
        // scale = 256 / spacing.

        val targetSize = ceil(spacing).toInt()
        val useScale = if (targetSize > 256) 256.toFloat() / spacing else 1f
        val bitmapSize = if (targetSize > 256) 256 else targetSize

        if (bitmapSize <= 0) return

        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw Pattern on Bitmap
        // We draw one "cell" of the pattern.
        // The cell covers (0,0) to (spacing, spacing) in World.
        // We scale this to (0,0) to (bitmapSize, bitmapSize).

        val drawScale = bitmapSize.toFloat() / spacing // This should match useScale roughly?
        // Actually: scale = bitmapSize / spacing.
        // If spacing=50, size=50 -> scale=1.
        // If spacing=500, size=256 -> scale=0.512.

        canvas.scale(drawScale, drawScale)

        // Draw primitives
        // To handle wrapping correctly (primitives centered at corners being cut),
        // we must draw the primitive at all relevant corners:
        // (0,0), (spacing, 0), (0, spacing), (spacing, spacing).
        // For lines, we draw at relevant edges.

        when (style) {
            is BackgroundStyle.Dots -> {
                fillPaint.color = style.color
                canvas.drawCircle(0f, 0f, style.radius, fillPaint)
                canvas.drawCircle(spacing, 0f, style.radius, fillPaint)
                canvas.drawCircle(0f, spacing, style.radius, fillPaint)
                canvas.drawCircle(spacing, spacing, style.radius, fillPaint)
            }
            is BackgroundStyle.Lines -> {
                paint.color = style.color
                paint.strokeWidth = style.thickness
                // Horizontal lines at y=0 and y=spacing
                canvas.drawLine(0f, 0f, spacing, 0f, paint)
                canvas.drawLine(0f, spacing, spacing, spacing, paint)
            }
            is BackgroundStyle.Grid -> {
                paint.color = style.color
                paint.strokeWidth = style.thickness
                // Vertical at x=0 and x=spacing
                canvas.drawLine(0f, 0f, 0f, spacing, paint)
                canvas.drawLine(spacing, 0f, spacing, spacing, paint)
                
                // Horizontal at y=0 and y=spacing
                canvas.drawLine(0f, 0f, spacing, 0f, paint)
                canvas.drawLine(0f, spacing, spacing, spacing, paint)
            }
            else -> {}
        }

        cachedBitmap = bitmap
        cachedStyle = style
        cachedShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun drawDots(
        canvas: Canvas,
        style: BackgroundStyle.Dots,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        fillPaint.color = style.color

        // Align to the pattern origin (offsetX, offsetY)
        val spacing = style.spacing
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        var primitivesDrawn = 0

        // We draw slightly outside the visible rect to ensure continuity during pan
        var x = startX
        while (x < rect.right + spacing) {
            var y = startY
            while (y < rect.bottom + spacing) {
                if (y >= rect.top - spacing && y <= rect.bottom + spacing &&
                    x >= rect.left - spacing && x <= rect.right + spacing
                ) {
                    canvas.drawCircle(x, y, style.radius, fillPaint)
                    primitivesDrawn++
                    if (primitivesDrawn > MAX_PRIMITIVES) return
                }
                y += spacing
            }
            x += spacing
            // Safety check against infinite outer loop
            if (primitivesDrawn > MAX_PRIMITIVES) return
        }
    }

    private fun drawLines(
        canvas: Canvas,
        style: BackgroundStyle.Lines,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        paint.color = style.color
        paint.strokeWidth = style.thickness

        val spacing = style.spacing
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY

        var y = startY
        var linesDrawn = 0
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                canvas.drawLine(rect.left, y, rect.right, y, paint)
                linesDrawn++
                if (linesDrawn > MAX_PRIMITIVES) return
            }
            y += spacing
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        style: BackgroundStyle.Grid,
        rect: RectF,
        offsetX: Float,
        offsetY: Float,
    ) {
        paint.color = style.color
        paint.strokeWidth = style.thickness

        var primitivesDrawn = 0
        val spacing = style.spacing

        // Vertical Lines
        val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
        var x = startX
        while (x < rect.right + spacing) {
            if (x >= rect.left - spacing) {
                canvas.drawLine(x, rect.top, x, rect.bottom, paint)
                primitivesDrawn++
                if (primitivesDrawn > MAX_PRIMITIVES) return
            }
            x += spacing
        }

        // Horizontal Lines
        val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
        var y = startY
        while (y < rect.bottom + spacing) {
            if (y >= rect.top - spacing) {
                canvas.drawLine(rect.left, y, rect.right, y, paint)
                primitivesDrawn++
                if (primitivesDrawn > MAX_PRIMITIVES) return
            }
            y += spacing
        }
    }
}
