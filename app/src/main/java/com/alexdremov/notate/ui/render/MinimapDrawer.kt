package com.alexdremov.notate.ui.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.min

class MinimapDrawer(
    private val view: android.view.View,
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
    private val onRefresh: () -> Unit,
) {
    private val minimapHandler = Handler(Looper.getMainLooper())
    private var isMinimapVisible = false
    private var contentThumbnail: Bitmap? = null
    private val contentRect = RectF()
    private var minimapDirty = true

    private val hideMinimapRunnable =
        Runnable {
            isMinimapVisible = false
            onRefresh()
        }

    private val minimapPaint =
        Paint().apply {
            color = CanvasConfig.MINIMAP_BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CanvasConfig.MINIMAP_STROKE_WIDTH
        }
    private val viewportPaint =
        Paint().apply {
            color = CanvasConfig.MINIMAP_VIEWPORT_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CanvasConfig.MINIMAP_STROKE_WIDTH * 1.5f
        }
    private val textPaint =
        Paint().apply {
            color = Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

    fun setDirty() {
        minimapDirty = true
    }

    fun show() {
        isMinimapVisible = true
        minimapHandler.removeCallbacks(hideMinimapRunnable)
        minimapHandler.postDelayed(hideMinimapRunnable, CanvasConfig.MINIMAP_HIDE_DELAY_MS)
    }

    private var isRegenerating = false

    fun draw(
        canvas: Canvas,
        matrix: Matrix,
        inverseMatrix: Matrix,
        currentScale: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        if (!isMinimapVisible) return

        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val padding = CanvasConfig.MINIMAP_PADDING

        // 1. Calculate World Viewport
        val viewportRect = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        matrix.invert(inverseMatrix) // Assumes caller updates matrix/inverse
        inverseMatrix.mapRect(viewportRect)

        // 2. Determine Context Rect based on Canvas Type
        val contextRect = RectF()
        if (model.canvasType == CanvasType.FIXED_PAGES) {
            val pageFullHeight = model.pageHeight + CanvasConfig.PAGE_SPACING
            val firstPageIdx =
                kotlin.math
                    .floor(viewportRect.top / pageFullHeight)
                    .toInt()
                    .coerceAtLeast(0)
            val lastPageIdx =
                kotlin.math
                    .floor(viewportRect.bottom / pageFullHeight)
                    .toInt()
                    .coerceAtLeast(firstPageIdx)

            val top = firstPageIdx * pageFullHeight
            val bottom = (lastPageIdx + 1) * pageFullHeight

            contextRect.set(0f, top, model.pageWidth, bottom)
            contextRect.union(viewportRect)
        } else {
            val currentContentBounds = model.getContentBounds()
            contextRect.set(viewportRect)
            if (!currentContentBounds.isEmpty) {
                contextRect.union(currentContentBounds)
            }
        }

        // 3. Determine Scale to fit Context into MINIMAP_WIDTH
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)

        val scaleX = CanvasConfig.MINIMAP_WIDTH / contextW
        val scaleY = CanvasConfig.MINIMAP_WIDTH / contextH
        val mapScale = min(scaleX, scaleY)

        val targetW = contextW * mapScale
        val targetH = contextH * mapScale

        val mapLeft = width - targetW - padding
        val mapTop = padding

        // Minimap Background
        canvas.drawRect(
            mapLeft,
            mapTop,
            mapLeft + targetW,
            mapTop + targetH,
            Paint().apply {
                color = Color.argb(200, 255, 255, 255)
                style = Paint.Style.FILL
            },
        )
        canvas.drawRect(mapLeft, mapTop, mapLeft + targetW, mapTop + targetH, minimapPaint)

        // 4. Update Content Thumbnail if Dirty or Context Changed
        val contextChanged = !contentRect.contains(contextRect) && model.canvasType == CanvasType.FIXED_PAGES
        val shouldRegenerate = (minimapDirty || contentThumbnail == null || contextChanged) && !isRegenerating

        if (shouldRegenerate) {
            regenerateThumbnail(contextRect)
        }

        // 5. Draw Content Thumbnail
        val destLeft = mapLeft + (contentRect.left - contextRect.left) * mapScale
        val destTop = mapTop + (contentRect.top - contextRect.top) * mapScale
        val destWidth = contentRect.width() * mapScale
        val destHeight = contentRect.height() * mapScale

        contentThumbnail?.let {
            canvas.drawBitmap(it, null, RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight), null)
        }

        // 6. Draw Viewport (Red Stroke)
        val minimapMatrix = Matrix()
        minimapMatrix.postTranslate(-contextRect.left, -contextRect.top)
        minimapMatrix.postScale(mapScale, mapScale)
        minimapMatrix.postTranslate(mapLeft, mapTop)

        val mappedViewport = RectF(viewportRect)
        minimapMatrix.mapRect(mappedViewport)
        canvas.drawRect(mappedViewport, viewportPaint)

        // Draw Scale Text
        val scaleText = "${(currentScale * 100).toInt()}%"
        canvas.drawText(scaleText, width - padding, mapTop + targetH + 40f, textPaint)
    }

    private fun regenerateThumbnail(contextRect: RectF) {
        isRegenerating = true
        val contextW = contextRect.width().coerceAtLeast(1f)
        val contextH = contextRect.height().coerceAtLeast(1f)

        // Capture context for background task
        val capturedContextRect = RectF(contextRect)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val maxDim = 512f
                val thumbScale = min(maxDim / contextW, maxDim / contextH)
                val thumbW = (contextW * thumbScale).toInt().coerceAtLeast(1)
                val thumbH = (contextH * thumbScale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                val thumbCanvas = Canvas(bitmap)
                val thumbMatrix = Matrix()
                thumbMatrix.postTranslate(-capturedContextRect.left, -capturedContextRect.top)
                thumbMatrix.postScale(thumbScale, thumbScale)

                renderer.renderDirectVectors(thumbCanvas, thumbMatrix, capturedContextRect, RenderQuality.SIMPLE)

                minimapHandler.post {
                    contentThumbnail?.recycle()
                    contentThumbnail = bitmap
                    contentRect.set(capturedContextRect)
                    minimapDirty = false
                    isRegenerating = false
                    onRefresh()
                }
            } catch (e: Exception) {
                com.alexdremov.notate.util.Logger
                    .e("MinimapDrawer", "Failed to regenerate thumbnail", e)
                isRegenerating = false
            }
        }
    }
}
