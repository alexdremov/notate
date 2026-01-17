package com.alexdremov.notate.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.BackgroundDrawer
import com.alexdremov.notate.ui.render.background.PatternLayoutHelper
import com.alexdremov.notate.util.StrokeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object PdfExporter {
    interface ProgressCallback {
        fun onProgress(
            progress: Int,
            message: String,
        )
    }

    /**
     * Exports the canvas content to a PDF format.
     *
     * @param model The canvas model containing strokes and configuration.
     * @param outputStream The stream to write the PDF data to. The caller is responsible for closing this stream.
     * @param isVector If true, uses vector paths. If false, rasterizes to bitmaps (better for texture brushes).
     * @param callback Optional callback for progress updates.
     */
    suspend fun export(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        isVector: Boolean,
        callback: ProgressCallback?,
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        try {
            // Snapshot data from model
            val items = ArrayList<CanvasItem>()
            var bounds = RectF()
            var type = CanvasType.INFINITE
            var pWidth = 0f
            var pHeight = 0f
            var bgStyle: BackgroundStyle = BackgroundStyle.Blank()

            model.performRead {
                items.addAll(it)
                bounds = model.getContentBounds()
                type = model.canvasType
                pWidth = model.pageWidth
                pHeight = model.pageHeight
                bgStyle = model.backgroundStyle
            }

            // Important: Sort items by Z-Index (Highlighters first)
            items.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

            currentCoroutineContext().ensureActive()

            if (type == CanvasType.FIXED_PAGES) {
                exportFixedPages(pdfDocument, items, bounds, pWidth, pHeight, bgStyle, isVector, callback, context)
            } else {
                exportInfiniteCanvas(pdfDocument, items, bounds, bgStyle, isVector, callback, context)
            }

            currentCoroutineContext().ensureActive()
            callback?.onProgress(90, "Writing to file...")

            pdfDocument.writeTo(outputStream)

            callback?.onProgress(100, "Done")
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            pdfDocument.close()
        }
    }

    private suspend fun exportFixedPages(
        doc: PdfDocument,
        items: List<CanvasItem>,
        contentBounds: RectF,
        pageWidth: Float,
        pageHeight: Float,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
        context: android.content.Context,
    ) {
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        // Determine last page index based on content
        val lastPageIdx = if (contentBounds.isEmpty) 0 else floor(contentBounds.bottom / pageFullHeight).toInt().coerceAtLeast(0)

        val totalPages = lastPageIdx + 1

        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        for (i in 0..lastPageIdx) {
            currentCoroutineContext().ensureActive()
            val progress = ((i.toFloat() / totalPages) * 90).toInt()
            callback?.onProgress(progress, "Exporting Page ${i + 1}/$totalPages")

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            // Draw Background
            canvas.drawColor(Color.WHITE)

            // Translate to page origin
            val topOffset = i * pageFullHeight

            // Draw Pattern
            // We need to clip to avoid drawing outside
            canvas.save()
            canvas.translate(0f, -topOffset)

            // Draw Pattern
            val pageWorldRect = RectF(0f, topOffset, pageWidth, topOffset + pageHeight)
            val patternArea = PatternLayoutHelper.calculatePatternArea(pageWorldRect, bgStyle)
            val (offsetX, offsetY) = PatternLayoutHelper.calculateOffsets(patternArea, bgStyle)

            BackgroundDrawer.draw(canvas, bgStyle, patternArea, 0f, offsetX, offsetY, forceVector = isVector)

            // Draw Items
            // Optimization: Filter items that intersect this page
            val visibleItems = items.filter { RectF.intersects(it.bounds, pageWorldRect) }

            if (isVector) {
                renderVectorItems(canvas, visibleItems, paint, context)
            } else {
                renderBitmapItems(canvas, visibleItems, pageWorldRect, paint, context)
            }

            canvas.restore()
            doc.finishPage(page)
        }
    }

    private suspend fun exportInfiniteCanvas(
        doc: PdfDocument,
        items: List<CanvasItem>,
        contentBounds: RectF,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
        context: android.content.Context,
    ) {
        val padding = 50f
        val bounds =
            if (contentBounds.isEmpty) {
                RectF(
                    0f,
                    0f,
                    CanvasConfig.PAGE_A4_WIDTH,
                    CanvasConfig.PAGE_A4_HEIGHT,
                )
            } else {
                RectF(contentBounds)
            }
        bounds.inset(-padding, -padding)

        val width = bounds.width().toInt()
        val height = bounds.height().toInt()

        callback?.onProgress(10, "Rendering Canvas...")

        val pageInfo = PdfDocument.PageInfo.Builder(width, height, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        canvas.drawColor(Color.WHITE)

        // Translate so bounds.left, bounds.top is at 0,0
        canvas.translate(-bounds.left, -bounds.top)

        // Draw Background
        BackgroundDrawer.draw(canvas, bgStyle, bounds, forceVector = isVector)

        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        if (isVector) {
            renderVectorItems(canvas, items, paint, context)
        } else {
            // For infinite canvas, bitmap might be huge.
            // Limit to 4096 dim.
            if (width > 4096 || height > 4096) {
                val scale = 4096f / max(width, height)
                canvas.save()
                canvas.scale(scale, scale)
                renderTiledBitmap(canvas, items, bounds, paint, context)
                canvas.restore()
            } else {
                renderBitmapItems(canvas, items, bounds, paint, context)
            }
        }

        doc.finishPage(page)
    }

    private fun renderVectorItems(
        canvas: Canvas,
        items: List<CanvasItem>,
        paint: Paint,
        context: android.content.Context,
    ) {
        for (item in items) {
            if (item is Stroke) {
                paint.color = item.color
                paint.strokeWidth = item.width
                StrokeRenderer.drawStroke(canvas, paint, item, forceVector = true)
            } else {
                StrokeRenderer.drawItem(canvas, item, false, paint, context)
            }
        }
    }

    private fun renderBitmapItems(
        canvas: Canvas,
        items: List<CanvasItem>,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
    ) {
        val w = bounds.width().toInt().coerceAtLeast(1)
        val h = bounds.height().toInt().coerceAtLeast(1)

        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = Canvas(bitmap)

            // We need to translate bmpCanvas so that `bounds.left` is at 0
            bmpCanvas.translate(-bounds.left, -bounds.top)

            for (item in items) {
                if (item is Stroke) {
                    paint.color = item.color
                    paint.strokeWidth = item.width
                }
                StrokeRenderer.drawItem(bmpCanvas, item, false, paint, context)
            }

            canvas.drawBitmap(bitmap, bounds.left, bounds.top, null)

            bitmap.recycle()
        } catch (e: OutOfMemoryError) {
            // Fallback to vector if OOM
            System.gc()
            renderVectorItems(canvas, items, paint, context)
        }
    }

    private fun renderTiledBitmap(
        canvas: Canvas,
        items: List<CanvasItem>,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
    ) {
        val tileSize = 2048
        val cols = ceil(bounds.width() / tileSize).toInt()
        val rows = ceil(bounds.height() / tileSize).toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = bounds.left + c * tileSize
                val top = bounds.top + r * tileSize
                val right = min(left + tileSize, bounds.right)
                val bottom = min(top + tileSize, bounds.bottom)
                val tileRect = RectF(left, top, right, bottom)

                val tileItems = items.filter { RectF.intersects(it.bounds, tileRect) }
                if (tileItems.isNotEmpty()) {
                    renderBitmapItems(canvas, tileItems, tileRect, paint, context)
                }
            }
        }
    }
}
