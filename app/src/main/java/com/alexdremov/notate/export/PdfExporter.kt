package com.alexdremov.notate.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

// Wrapper interface for PdfDocument.Page (final class)
interface PdfPageWrapper<T> {
    val canvas: Canvas
    val wrappedPage: T
}

class AndroidPdfPageWrapper(
    private val page: PdfDocument.Page,
) : PdfPageWrapper<PdfDocument.Page> {
    override val canvas: Canvas get() = page.canvas
    override val wrappedPage: PdfDocument.Page get() = page
}

// Wrapper interface to allow mocking PdfDocument
interface PdfDocumentWrapper {
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfPageWrapper<*>

    fun finishPage(page: PdfPageWrapper<*>)

    fun writeTo(out: OutputStream)

    fun close()
}

class AndroidPdfDocumentWrapper : PdfDocumentWrapper {
    private val document = PdfDocument()

    override fun startPage(pageInfo: PdfDocument.PageInfo): PdfPageWrapper<PdfDocument.Page> =
        AndroidPdfPageWrapper(document.startPage(pageInfo))

    override fun finishPage(page: PdfPageWrapper<*>) {
        if (page is AndroidPdfPageWrapper) {
            document.finishPage(page.wrappedPage)
        }
    }

    override fun writeTo(out: OutputStream) {
        document.writeTo(out)
    }

    override fun close() {
        document.close()
    }
}

object PdfExporter {
    interface ProgressCallback {
        fun onProgress(
            progress: Int,
            message: String,
        )
    }

    suspend fun export(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        isVector: Boolean,
        callback: ProgressCallback?,
        pdfDocumentFactory: () -> PdfDocumentWrapper = { AndroidPdfDocumentWrapper() },
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = pdfDocumentFactory()

        try {
            val bounds = model.getContentBounds()
            val type = model.canvasType
            val pWidth = model.pageWidth
            val pHeight = model.pageHeight
            val bgStyle = model.backgroundStyle

            currentCoroutineContext().ensureActive()

            if (type == CanvasType.FIXED_PAGES) {
                exportFixedPages(pdfDocument, model, bounds, pWidth, pHeight, bgStyle, isVector, callback, context)
            } else {
                exportInfiniteCanvas(pdfDocument, model, bounds, bgStyle, isVector, callback, context)
            }

            currentCoroutineContext().ensureActive()
            callback?.onProgress(90, "Writing to file...")

            pdfDocument.writeTo(outputStream)
            callback?.onProgress(100, "Done")
        } catch (e: Exception) {
            Logger.e("PdfExporter", "Export failed", e)
            throw e
        } finally {
            pdfDocument.close()
        }
    }

    private suspend fun exportFixedPages(
        doc: PdfDocumentWrapper,
        model: InfiniteCanvasModel,
        contentBounds: RectF,
        pageWidth: Float,
        pageHeight: Float,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
        context: android.content.Context,
    ) {
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
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

            canvas.drawColor(Color.WHITE)
            val topOffset = i * pageFullHeight

            canvas.save()
            canvas.translate(0f, -topOffset)

            val pageWorldRect = RectF(0f, topOffset, pageWidth, topOffset + pageHeight)
            val patternArea = PatternLayoutHelper.calculatePatternArea(pageWorldRect, bgStyle)
            val (offsetX, offsetY) = PatternLayoutHelper.calculateOffsets(patternArea, bgStyle)

            BackgroundDrawer.draw(canvas, bgStyle, patternArea, 0f, offsetX, offsetY, forceVector = isVector)

            // Dynamic Query per page to avoid OOM
            val visibleItems = model.queryItems(pageWorldRect)
            visibleItems.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

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
        doc: PdfDocumentWrapper,
        model: InfiniteCanvasModel,
        contentBounds: RectF,
        bgStyle: BackgroundStyle,
        isVector: Boolean,
        callback: ProgressCallback?,
        context: android.content.Context,
    ) {
        val padding = 50f
        val bounds =
            if (contentBounds.isEmpty) {
                RectF(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
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
        canvas.translate(-bounds.left, -bounds.top)

        BackgroundDrawer.draw(canvas, bgStyle, bounds, forceVector = isVector)

        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        if (isVector) {
            // Use region-aware rendering for vector export to avoid OOM
            renderVectorItemsFromRegions(canvas, model, bounds, paint, context)
        } else {
            renderTiledBitmap(canvas, model, bounds, context, callback)
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

    /**
     * Region-aware vector rendering for infinite canvas export to avoid OOM.
     */
    private fun renderVectorItemsFromRegions(
        canvas: Canvas,
        model: InfiniteCanvasModel,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
    ) {
        val regionManager = model.getRegionManager() ?: return

        // Get regions that intersect with our export bounds
        val regions = regionManager.getRegionsInRect(bounds)

        // Process each region individually to avoid memory spikes
        for (region in regions) {
            val regionItems = ArrayList<CanvasItem>()
            region.quadtree?.retrieve(regionItems, bounds)

            // Sort items by zIndex and order within this region
            regionItems.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

            // Render items from this region
            for (item in regionItems) {
                if (item is Stroke) {
                    paint.color = item.color
                    paint.strokeWidth = item.width
                    StrokeRenderer.drawStroke(canvas, paint, item, forceVector = true)
                } else {
                    StrokeRenderer.drawItem(canvas, item, false, paint, context)
                }
            }

            // Clear the temporary list to free memory
            regionItems.clear()
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
            renderVectorItems(canvas, items, paint, context)
        }
    }

    private suspend fun renderTiledBitmap(
        canvas: Canvas,
        model: InfiniteCanvasModel,
        bounds: RectF,
        context: android.content.Context,
        callback: ProgressCallback?,
    ) = withContext(Dispatchers.Default) {
        val tileSize = 2048
        val cols = ceil(bounds.width() / tileSize).toInt()
        val rows = ceil(bounds.height() / tileSize).toInt()
        val totalTiles = cols * rows
        val completedTiles = AtomicInteger(0)

        val mutex = Mutex()

        val tiles = ArrayList<RectF>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = bounds.left + c * tileSize
                val top = bounds.top + r * tileSize
                val right = min(left + tileSize, bounds.right)
                val bottom = min(top + tileSize, bounds.bottom)
                tiles.add(RectF(left, top, right, bottom))
            }
        }

        tiles
            .map { tileRect ->
                async(Dispatchers.Default) {
                    // Query model dynamically for this tile
                    val tileItems = model.queryItems(tileRect)
                    if (tileItems.isNotEmpty()) {
                        tileItems.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

                        val threadPaint =
                            Paint().apply {
                                isAntiAlias = true
                                isDither = true
                                strokeJoin = Paint.Join.ROUND
                                strokeCap = Paint.Cap.ROUND
                            }

                        var bitmap: Bitmap? = null
                        try {
                            val w = tileRect.width().toInt().coerceAtLeast(1)
                            val h = tileRect.height().toInt().coerceAtLeast(1)
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val bmpCanvas = Canvas(bitmap)
                            bmpCanvas.translate(-tileRect.left, -tileRect.top)

                            for (item in tileItems) {
                                if (item is Stroke) {
                                    threadPaint.color = item.color
                                    threadPaint.strokeWidth = item.width
                                }
                                StrokeRenderer.drawItem(bmpCanvas, item, false, threadPaint, context)
                            }

                            mutex.withLock {
                                canvas.drawBitmap(bitmap, tileRect.left, tileRect.top, null)
                            }
                        } finally {
                            bitmap?.recycle()
                        }
                    }
                    val finished = completedTiles.incrementAndGet()
                    val progress = 10 + ((finished.toFloat() / totalTiles) * 80).toInt()
                    callback?.onProgress(progress, "Rendering Tile $finished/$totalTiles")
                }
            }.forEach { it.await() }
    }
}
