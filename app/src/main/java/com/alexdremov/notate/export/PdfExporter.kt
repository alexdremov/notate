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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Wrapper interface for PdfDocument.Page (final class)
interface PdfPageWrapper {
    val canvas: Canvas
    val wrappedPage: Any // Keep reference to original if needed for finishPage
}

class AndroidPdfPageWrapper(
    private val page: PdfDocument.Page,
) : PdfPageWrapper {
    override val canvas: Canvas get() = page.canvas
    override val wrappedPage: Any get() = page
}

// Wrapper interface to allow mocking PdfDocument
interface PdfDocumentWrapper {
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfPageWrapper

    fun finishPage(page: PdfPageWrapper)

    fun writeTo(out: OutputStream)

    fun close()
}

class AndroidPdfDocumentWrapper : PdfDocumentWrapper {
    private val document = PdfDocument()

    override fun startPage(pageInfo: PdfDocument.PageInfo): PdfPageWrapper = AndroidPdfPageWrapper(document.startPage(pageInfo))

    override fun finishPage(page: PdfPageWrapper) {
        document.finishPage(page.wrappedPage as PdfDocument.Page)
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
        pdfDocumentFactory: () -> PdfDocumentWrapper = { AndroidPdfDocumentWrapper() },
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = pdfDocumentFactory()

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
        doc: PdfDocumentWrapper,
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
        doc: PdfDocumentWrapper,
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
            // Parallelized Tiled Rendering
            // We do not scale down. PDF pages can be large.
            renderTiledBitmap(canvas, items, bounds, context)
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

    private suspend fun renderTiledBitmap(
        canvas: Canvas,
        items: List<CanvasItem>,
        bounds: RectF,
        context: android.content.Context,
    ) = withContext(Dispatchers.Default) {
        val tileSize = 2048
        val cols = ceil(bounds.width() / tileSize).toInt()
        val rows = ceil(bounds.height() / tileSize).toInt()

        // Semaphore to limit concurrent tile rendering.
        // Estimate worst-case tile memory as ARGB_8888 2048x2048 (~16MB), then
        // derive a conservative concurrency limit from available heap to account
        // for boundary tiles, Paint objects, temporary buffers, etc.
        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        val bytesPerPixel = 4L // ARGB_8888
        val maxTileBytes = tileSize.toLong() * tileSize.toLong() * bytesPerPixel
        // Be conservative: only allow a fraction of the heap to be used by tiles.
        val safetyDivider = 6L
        val maxTilesByMemory = (maxMemoryBytes / safetyDivider / maxTileBytes)
            .coerceIn(1L, 3L)
            .toInt()
        val semaphore = Semaphore(maxTilesByMemory)
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
                // Use async to parallelize the "Rendering" part
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        try {
                            val tileItems = items.filter { RectF.intersects(it.bounds, tileRect) }
                            if (tileItems.isNotEmpty()) {
                                // Create a specific paint for this thread
                                val threadPaint =
                                    Paint().apply {
                                        isAntiAlias = true
                                        isDither = true
                                        strokeJoin = Paint.Join.ROUND
                                        strokeCap = Paint.Cap.ROUND
                                    }

                                var bitmap: Bitmap? = null
                                try {
                                    // Render to Bitmap (Heavy CPU)
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

                                    currentCoroutineContext().ensureActive()

                                    // Draw to PDF (Fast, Serialized)
                                    mutex.withLock {
                                        canvas.drawBitmap(bitmap, tileRect.left, tileRect.top, null)
                                    }
                                } finally {
                                    bitmap?.recycle()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            e.printStackTrace()
                        }
                    }
                }
            }.forEach { it.await() }
    }
}
