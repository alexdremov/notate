package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.TileManager
import kotlin.math.floor

enum class RenderQuality {
    HIGH, // Pressure-sensitive, NeoFountainPen
    SIMPLE, // Scaled down, simple stroke (e.g. for Minimap)
}

interface CanvasLayout {
    fun render(
        canvas: Canvas,
        matrix: Matrix,
        visibleRect: RectF?,
        quality: RenderQuality,
        zoomLevel: Float,
        model: InfiniteCanvasModel,
        tileManager: TileManager,
        renderer: CanvasRenderer,
    )
}

class InfiniteLayout : CanvasLayout {
    override fun render(
        canvas: Canvas,
        matrix: Matrix,
        visibleRect: RectF?,
        quality: RenderQuality,
        zoomLevel: Float,
        model: InfiniteCanvasModel,
        tileManager: TileManager,
        renderer: CanvasRenderer,
    ) {
        canvas.save()
        canvas.concat(matrix)

        // Draw Infinite Background Pattern
        // If visibleRect is null (export whole canvas), we calculate content bounds
        val drawRect = visibleRect ?: model.getContentBounds()

        // We only draw background if we have a valid rect.
        // For infinite export, usually we want a background behind content.
        if (!drawRect.isEmpty) {
            BackgroundDrawer.draw(canvas, model.backgroundStyle, drawRect)
        }

        val useDirectVectors = visibleRect == null
        if (useDirectVectors) {
            renderer.renderDirectVectors(canvas, matrix, visibleRect, quality)
        } else {
            tileManager.render(canvas, visibleRect!!, zoomLevel)
        }

        canvas.restore()
    }
}

class FixedPageLayout(
    private val pageWidth: Float,
    private val pageHeight: Float,
) : CanvasLayout {
    private val bgPaint =
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(10f, 0f, 5f, Color.LTGRAY)
        }
    private val borderPaint =
        Paint().apply {
            color = Color.GRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

    override fun render(
        canvas: Canvas,
        matrix: Matrix,
        visibleRect: RectF?,
        quality: RenderQuality,
        zoomLevel: Float,
        model: InfiniteCanvasModel,
        tileManager: TileManager,
        renderer: CanvasRenderer,
    ) {
        canvas.save()
        canvas.concat(matrix)

        if (visibleRect != null) {
            val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
            val firstPageIdx = floor(visibleRect.top / pageFullHeight).toInt().coerceAtLeast(0)
            val lastPageIdx = floor(visibleRect.bottom / pageFullHeight).toInt()

            for (i in firstPageIdx..lastPageIdx) {
                val top = i * pageFullHeight
                val pageRect = RectF(0f, top, pageWidth, top + pageHeight)

                // 1. Draw Page Background (White Base)
                canvas.drawRect(pageRect, bgPaint)

                // 2. Render content CLIPPED to this page
                canvas.save()
                canvas.clipRect(pageRect)

                // Draw Pattern inside the page (now clipped)
                // Apply padding and alignment logic
                val style = model.backgroundStyle
                val patternArea =
                    RectF(
                        pageRect.left + style.paddingLeft,
                        pageRect.top + style.paddingTop,
                        pageRect.right - style.paddingRight,
                        pageRect.bottom - style.paddingBottom,
                    )

                // We intersect the pattern area with visible rect to avoid drawing pattern outside viewport
                val bgIntersection = RectF(patternArea)
                if (bgIntersection.intersect(visibleRect)) {
                    // Calculate offsets
                    var offsetX = patternArea.left
                    val offsetY = patternArea.top // Pattern starts after top padding

                    if (style.isCentered) {
                        val spacing =
                            when (style) {
                                is BackgroundStyle.Dots -> style.spacing
                                is BackgroundStyle.Lines -> style.spacing
                                is BackgroundStyle.Grid -> style.spacing
                                else -> 0f
                            }
                        if (spacing > 0) {
                            // Center the pattern horizontally within the patternArea
                            val availableWidth = patternArea.width()
                            val remainder = availableWidth % spacing
                            offsetX += remainder / 2f
                        }
                    }

                    // Pass offsets to align pattern to page origin (or padded origin)
                    BackgroundDrawer.draw(canvas, style, bgIntersection, offsetX, offsetY)
                }

                val intersection = RectF(pageRect)
                if (intersection.intersect(visibleRect)) {
                    tileManager.render(canvas, intersection, zoomLevel)
                }

                canvas.restore()

                // 4. Draw Page Border
                canvas.drawRect(pageRect, borderPaint)
            }
        } else {
            // Fallback for full export / minimap if rect is null (unbounded)
            renderPagesBackgroundForExport(canvas, model, model.getContentBounds())
            renderer.renderDirectVectors(canvas, matrix, visibleRect, quality)
        }

        canvas.restore()
    }

    private fun renderPagesBackgroundForExport(
        canvas: Canvas,
        model: InfiniteCanvasModel,
        contentBounds: RectF,
    ) {
        if (contentBounds.isEmpty) return
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        val firstPageIdx = floor(contentBounds.top / pageFullHeight).toInt().coerceAtLeast(0)
        val lastPageIdx = floor(contentBounds.bottom / pageFullHeight).toInt()

        for (i in firstPageIdx..lastPageIdx) {
            val top = i * pageFullHeight
            val pageRect = RectF(0f, top, pageWidth, top + pageHeight)
            canvas.drawRect(pageRect, bgPaint)

            // Export should also respect page origin
            canvas.save()
            canvas.clipRect(pageRect)
            BackgroundDrawer.draw(canvas, model.backgroundStyle, pageRect, pageRect.left, pageRect.top)
            canvas.restore()

            canvas.drawRect(pageRect, borderPaint)
        }
    }
}
