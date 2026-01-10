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
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.BackgroundDrawer
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
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        isVector: Boolean,
        callback: ProgressCallback?,
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        try {
            // Snapshot data from model
            val strokes = ArrayList<Stroke>()
            var bounds = RectF()
            var type = CanvasType.INFINITE
            var pWidth = 0f
            var pHeight = 0f
            var bgStyle: BackgroundStyle = BackgroundStyle.Blank()

            model.performRead {
                strokes.addAll(it)
                bounds = model.getContentBounds()
                type = model.canvasType
                pWidth = model.pageWidth
                pHeight = model.pageHeight
                bgStyle = model.backgroundStyle
            }

            // Important: Sort strokes by Z-Index (Highlighters first)
            strokes.sortWith(compareBy<Stroke> { it.zIndex }.thenBy { it.strokeOrder })

            currentCoroutineContext().ensureActive()

            if (type == CanvasType.FIXED_PAGES) {
                exportFixedPages(pdfDocument, strokes, bounds, pWidth, pHeight, bgStyle, isVector, callback)
            } else {
                exportInfiniteCanvas(pdfDocument, strokes, bounds, bgStyle, isVector, callback)
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
        strokes: List<Stroke>,
        contentBounds: RectF,
        pageWidth: Float,
        pageHeight: Float,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
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
            // Pattern expects to be drawn in world coordinates or we translate context?
            // BackgroundDrawer draws in current canvas coords but uses rect for pattern alignment.
            // We want the pattern to look continuous or per-page?
            // FixedPageLayout renders per-page with offsets.
            // Let's translate the canvas so (0,0) is the top-left of the page in world space?
            // No, the PDF page is (0..W, 0..H).
            // The world content for this page is at (0, topOffset).
            // So we translate canvas by -topOffset.
            canvas.translate(0f, -topOffset)

            // Draw Pattern
            val pageWorldRect = RectF(0f, topOffset, pageWidth, topOffset + pageHeight)
            BackgroundDrawer.draw(canvas, bgStyle, pageWorldRect, 1.0f, 0f, topOffset) // Pass offset to align pattern

            // Draw Strokes
            // Optimization: Filter strokes that intersect this page
            // Since we have the list, we iterate. (Quadtree would be faster but we copied list).
            val visibleStrokes = strokes.filter { RectF.intersects(it.bounds, pageWorldRect) }

            if (isVector) {
                renderVectorStrokes(canvas, visibleStrokes, paint)
            } else {
                renderBitmapStrokes(canvas, visibleStrokes, pageWorldRect, paint)
            }

            canvas.restore()
            doc.finishPage(page)
        }
    }

    private suspend fun exportInfiniteCanvas(
        doc: PdfDocument,
        strokes: List<Stroke>,
        contentBounds: RectF,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
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
        BackgroundDrawer.draw(canvas, bgStyle, bounds, 1.0f)

        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        if (isVector) {
            renderVectorStrokes(canvas, strokes, paint)
        } else {
            // For infinite canvas, bitmap might be huge.
            // We should check size.
            // If > 4000x4000, maybe scale down? Or render in tiles?
            // Drawing a huge bitmap to PDF canvas works if memory allows.
            // Let's try direct bitmap render. If bounds are massive, we might crash.
            // Limit to 4096 dim.
            if (width > 4096 || height > 4096) {
                val scale = 4096f / max(width, height)
                canvas.save()
                canvas.scale(scale, scale)
                renderBitmapStrokes(canvas, strokes, bounds, paint) // Render to bitmap of scaled size?
                // renderBitmapStrokes creates a bitmap of `bounds` size.
                // We need to tell it to create a smaller bitmap.
                // Let's just default to Vector for huge canvases or use a Tiled approach?
                // Tiled approach for PDF:
                // Draw multiple bitmaps onto the PDF canvas.
                renderTiledBitmap(canvas, strokes, bounds, paint)
                canvas.restore()
            } else {
                renderBitmapStrokes(canvas, strokes, bounds, paint)
            }
        }

        doc.finishPage(page)
    }

    private fun renderVectorStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        paint: Paint,
    ) {
        for (stroke in strokes) {
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            // Vector render using StrokeRenderer
            // We use the existing logic which handles Paths nicely
            StrokeRenderer.drawStroke(canvas, paint, stroke)
        }
    }

    private fun renderBitmapStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        bounds: RectF,
        paint: Paint,
    ) {
        // Create a bitmap for the content
        // This ensures pixel-perfect rendering of brushes like Charcoal/Marker
        val w = bounds.width().toInt().coerceAtLeast(1)
        val h = bounds.height().toInt().coerceAtLeast(1)

        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = Canvas(bitmap)

            // We need to translate bmpCanvas so that `bounds.left` is at 0
            bmpCanvas.translate(-bounds.left, -bounds.top)

            for (stroke in strokes) {
                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                StrokeRenderer.drawStroke(bmpCanvas, paint, stroke)
            }

            // Draw bitmap to PDF canvas
            // The PDF canvas is already translated to world coordinates if called from Infinite/Fixed
            // BUT wait.
            // In `exportFixedPages`, canvas is translated by `-topOffset` (World -> Page).
            // `bounds` is in World coords.
            // If we draw the bitmap at `bounds.left, bounds.top`, it should align.
            // Because `bmpCanvas` was translated by `-bounds.left`, `drawStroke` (World Coords) drew to (0,0) of Bitmap.
            // So Bitmap (0,0) corresponds to World (bounds.left, bounds.top).

            canvas.drawBitmap(bitmap, bounds.left, bounds.top, null)

            bitmap.recycle()
        } catch (e: OutOfMemoryError) {
            // Fallback to vector if OOM
            System.gc()
            renderVectorStrokes(canvas, strokes, paint)
        }
    }

    private fun renderTiledBitmap(
        canvas: Canvas,
        strokes: List<Stroke>,
        bounds: RectF,
        paint: Paint,
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

                val tileStrokes = strokes.filter { RectF.intersects(it.bounds, tileRect) }
                if (tileStrokes.isNotEmpty()) {
                    renderBitmapStrokes(canvas, tileStrokes, tileRect, paint)
                }
            }
        }
    }
}
