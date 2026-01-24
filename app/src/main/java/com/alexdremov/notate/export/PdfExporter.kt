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
import com.alexdremov.notate.util.CharcoalPenRenderer
import com.alexdremov.notate.util.FountainPenRenderer
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeRenderer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
        val isFixedPages = model.canvasType == CanvasType.FIXED_PAGES

        // Use PDFBox for Infinite Canvas (both Bitmap and Vector) to support streaming.
        // Use PdfDocument (Android) for Fixed Pages as they are naturally paginated and smaller.
        if (!isFixedPages) {
            PDFBoxResourceLoader.init(context)
            if (isVector) {
                exportInfiniteCanvasVectorStreaming(context, model, outputStream, callback)
            } else {
                exportBitmapStreaming(context, model, outputStream, callback)
            }
        } else {
            // Use standard PdfDocument for Fixed Pages
            exportWithPdfDocument(context, model, outputStream, isVector, callback, pdfDocumentFactory)
        }
    }

    private suspend fun exportWithPdfDocument(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        isVector: Boolean,
        callback: ProgressCallback?,
        pdfDocumentFactory: () -> PdfDocumentWrapper,
    ) {
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
                // Fallback for infinite canvas if PDFBox fails (should not happen with new logic)
                exportInfiniteCanvasVector(pdfDocument, model, bounds, bgStyle, callback, context)
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

    private suspend fun exportInfiniteCanvasVectorStreaming(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        callback: ProgressCallback?,
    ) = withContext(Dispatchers.Default) {
        val document = PDDocument(MemoryUsageSetting.setupTempFileOnly())

        try {
            val contentBounds = model.getContentBounds()
            val padding = 50f
            val bounds =
                if (contentBounds.isEmpty) {
                    RectF(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
                } else {
                    RectF(contentBounds)
                }
            bounds.inset(-padding, -padding)

            val width = bounds.width()
            val height = bounds.height()

            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)

            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false)

            // Coordinate System: PDF is Bottom-Left. Android is Top-Left.
            // Transform: Flip Y and translate
            // Matrix(1, 0, 0, -1, 0, height)
            // But we also need to translate by -bounds.left, -bounds.top to map canvas content to 0,0

            // Final Matrix:
            // Scale(1, -1) * Translate(0, -height) (to flip) -> No, standard PDF flip is (1 0 0 -1 0 h)
            // Then apply View Translation (-bounds.left, -bounds.top)

            // Combined:
            // x' = x - bounds.left
            // y' = height - (y - bounds.top) = height + bounds.top - y

            // Let's use PDFBox transformation
            contentStream.saveGraphicsState()
            // 1. Flip Y: (1 0 0 -1 0 height)
            contentStream.transform(
                com.tom_roush.pdfbox.util
                    .Matrix(1f, 0f, 0f, -1f, 0f, height),
            )
            // 2. Translate to align content bounds: (-bounds.left, -bounds.top)
            contentStream.transform(
                com.tom_roush.pdfbox.util
                    .Matrix(1f, 0f, 0f, 1f, -bounds.left, -bounds.top),
            )

            callback?.onProgress(10, "Rendering Background...")

            // Render Background (Approximation or Tiled Image?)
            // For true vector, we should draw background pattern using vector commands.
            // But BackgroundDrawer uses Canvas.
            // Fallback: Render background as a single large bitmap (might be blurry) or Tiled?
            // Or just skip background for Vector Export (many vector apps do this)?
            // Let's try to render background as tiled bitmap for now to ensure it looks correct.
            // Vector background is complex to port.
            renderBackgroundTiledToStream(document, contentStream, model.backgroundStyle, bounds)

            callback?.onProgress(20, "Rendering Strokes...")

            val regionManager = model.getRegionManager()
            if (regionManager != null) {
                val regions = regionManager.getRegionsInRect(bounds)
                val totalRegions = regions.size
                var processedRegions = 0

                // Cache for Transparency States to avoid redundant objects
                val alphaCache = HashMap<Int, com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState>()

                for (region in regions) {
                    val items = ArrayList<CanvasItem>()
                    region.quadtree?.retrieve(items, bounds)
                    items.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

                    for (item in items) {
                        if (item is Stroke) {
                            renderStrokeToPdf(contentStream, item, alphaCache)
                        } else if (item is com.alexdremov.notate.model.CanvasImage) {
                            // Render Image
                            // TODO: Implement Image rendering in Vector Stream
                        }
                    }

                    processedRegions++
                    if (processedRegions % 5 == 0) {
                        val progress = 20 + ((processedRegions.toFloat() / totalRegions) * 70).toInt()
                        callback?.onProgress(progress, "Exporting Region $processedRegions/$totalRegions")
                        currentCoroutineContext().ensureActive()
                    }
                }
            }

            contentStream.restoreGraphicsState()
            contentStream.close()

            callback?.onProgress(90, "Writing to file...")
            document.save(outputStream)
            callback?.onProgress(100, "Done")
        } catch (e: Exception) {
            Logger.e("PdfExporter", "Vector Streaming Export failed", e)
            throw e
        } finally {
            document.close()
        }
    }

    private fun getSafeMaxPressure(stroke: Stroke): Float {
        var maxObserved = 0f
        for (p in stroke.points) {
            if (p.pressure > maxObserved) maxObserved = p.pressure
        }
        if (maxObserved > 0f && maxObserved <= 1.0f) {
            return 1.0f
        }
        val hwMax = EpdController.getMaxTouchPressure()
        return if (hwMax <= 0f) 4096f else hwMax
    }

    private fun renderStrokeToPdf(
        stream: PDPageContentStream,
        stroke: Stroke,
        alphaCache: HashMap<Int, com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState>,
    ) {
        // Robust check: If stroke width is encoded in the path (outline), we fill.
        // If stroke width is a property, we stroke.
        // How to distinguish?
        // StrokeType tells us.
        val isFilled =
            when (stroke.style) {
                com.alexdremov.notate.model.StrokeType.FOUNTAIN -> true

                com.alexdremov.notate.model.StrokeType.CHARCOAL -> true

                // Brush treated as stroke (centerline) for vector fallback, as we don't have native outline.
                com.alexdremov.notate.model.StrokeType.BRUSH -> false

                else -> false // Ballpoint, Fineliner, Highlighter, Pencil, Dash
            }

        val path =
            if (isFilled) {
                val maxPressure = getSafeMaxPressure(stroke)
                when (stroke.style) {
                    com.alexdremov.notate.model.StrokeType.FOUNTAIN -> {
                        FountainPenRenderer.getPath(stroke, maxPressure)
                    }

                    com.alexdremov.notate.model.StrokeType.CHARCOAL -> {
                        CharcoalPenRenderer.getPath(stroke, maxPressure) ?: stroke.path
                    }

                    else -> {
                        stroke.path
                    }
                }
            } else {
                stroke.path
            }

        if (path.isEmpty) return

        // Approximate Path
        val error = 0.5f // Pixel error
        val coords = path.approximate(error)

        // Setup Paint
        val color = stroke.color
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val a = (Color.alpha(color) * stroke.style.alphaMultiplier).toInt().coerceIn(0, 255)

        // Handle Transparency
        if (a < 255) {
            val alphaKey = a
            var extGState = alphaCache[alphaKey]
            if (extGState == null) {
                extGState =
                    com.tom_roush.pdfbox.pdmodel.graphics.state
                        .PDExtendedGraphicsState()
                extGState.nonStrokingAlphaConstant = a / 255f
                extGState.strokingAlphaConstant = a / 255f
                alphaCache[alphaKey] = extGState
            }
            stream.setGraphicsStateParameters(extGState)
        } else {
            // Reset to opaque? Or assume default.
            // Ideally we should cache opaque state too.
            // For simplicity, we just set transparency if needed.
            // Note: State persists until changed or restored.
            // So we must reset if previous was transparent.
            // Let's rely on save/restore for each stroke? No, too much overhead.
            // Global state tracking is better.
        }

        stream.setStrokingColor(r, g, b)

        if (isFilled) {
            stream.setNonStrokingColor(r, g, b)
        } else {
            stream.setLineWidth(stroke.width)
            stream.setLineCapStyle(1) // Round
            stream.setLineJoinStyle(1) // Round
        }

        // Write Path
        if (coords.isNotEmpty()) {
            // coords: [fraction, x, y, ...]
            // Move to first
            stream.moveTo(coords[1], coords[2])

            for (i in 3 until coords.size step 3) {
                stream.lineTo(coords[i + 1], coords[i + 2])
            }

            if (isFilled) {
                stream.fill()
            } else {
                stream.stroke()
            }
        }

        // Reset Transparency if needed (hacky, assuming next might be opaque)
        if (a < 255) {
            // Reset to opaque for next
            // Ideally optimize this
            val opaque =
                alphaCache[255] ?: com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().also {
                    it.nonStrokingAlphaConstant = 1f
                    it.strokingAlphaConstant = 1f
                    alphaCache[255] = it
                }
            stream.setGraphicsStateParameters(opaque)
        }
    }

    private suspend fun renderBackgroundTiledToStream(
        doc: PDDocument,
        stream: PDPageContentStream,
        style: BackgroundStyle,
        bounds: RectF,
    ) {
        // Rasterize background to tiles and draw them.
        // Similar to renderTilesToPdfBox but simpler (no content)
        // This is a good enough fallback for vector background.

        val tileSize = 1024
        val cols = ceil(bounds.width() / tileSize).toInt()
        val rows = ceil(bounds.height() / tileSize).toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = bounds.left + c * tileSize
                val top = bounds.top + r * tileSize
                val right = min(left + tileSize, bounds.right)
                val bottom = min(top + tileSize, bounds.bottom)
                val tileRect = RectF(left, top, right, bottom)

                val w = tileRect.width().toInt().coerceAtLeast(1)
                val h = tileRect.height().toInt().coerceAtLeast(1)

                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.translate(-tileRect.left, -tileRect.top)
                    BackgroundDrawer.draw(canvas, style, tileRect, forceVector = false)

                    val image = JPEGFactory.createFromImage(doc, bitmap, 0.8f)
                    stream.drawImage(image, tileRect.left, tileRect.top, tileRect.width(), tileRect.height())
                } catch (e: Exception) {
                    // Ignore background error
                } finally {
                    bitmap?.recycle()
                }
            }
        }
    }

    private suspend fun exportBitmapStreaming(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        callback: ProgressCallback?,
    ) = withContext(Dispatchers.IO) {
        // Use temp file buffering to avoid OOM on large exports
        val document = PDDocument(MemoryUsageSetting.setupTempFileOnly())

        try {
            val contentBounds = model.getContentBounds()
            val padding = 50f
            val bounds =
                if (contentBounds.isEmpty) {
                    RectF(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
                } else {
                    RectF(contentBounds)
                }
            bounds.inset(-padding, -padding)

            val width = bounds.width()
            val height = bounds.height()

            // Create a single page with the full dimensions
            // PDRectangle uses 1/72 inch units. Android Canvas uses pixels (usually 1:1 in PDF).
            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)

            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false)

            callback?.onProgress(10, "Rendering Canvas...")

            // We need to render background first
            // Since we can't draw Canvas to contentStream easily, we render background into tiles as well?
            // Actually, for simplicity in Bitmap mode, we include background in the tiles.

            // Render Tiles and stream them into the PDF
            renderTilesToPdfBox(document, contentStream, model, bounds, context, callback)

            contentStream.close()

            callback?.onProgress(90, "Writing to file...")
            document.save(outputStream)
            callback?.onProgress(100, "Done")
        } catch (e: Exception) {
            Logger.e("PdfExporter", "Bitmap Export failed", e)
            throw e
        } finally {
            document.close()
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

    private suspend fun exportInfiniteCanvasVector(
        doc: PdfDocumentWrapper,
        model: InfiniteCanvasModel,
        contentBounds: RectF,
        bgStyle: BackgroundStyle,
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

        BackgroundDrawer.draw(canvas, bgStyle, bounds, forceVector = true)

        val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

        // Use region-aware rendering for vector export to avoid OOM
        renderVectorItemsFromRegions(canvas, model, bounds, paint, context)

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
    private suspend fun renderVectorItemsFromRegions(
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

    private suspend fun renderTilesToPdfBox(
        doc: PDDocument,
        contentStream: PDPageContentStream,
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

        // We use a Mutex to synchronize writes to contentStream (it's not thread safe)
        val mutex = Mutex()
        // Limit concurrency to avoid OOM (2 tiles = ~16MB-32MB depending on config)
        val semaphore = kotlinx.coroutines.sync.Semaphore(2)

        // Background Style
        val bgStyle = model.backgroundStyle

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
                    semaphore.withPermit {
                        val w = tileRect.width().toInt().coerceAtLeast(1)
                        val h = tileRect.height().toInt().coerceAtLeast(1)

                        var bitmap: Bitmap? = null
                        try {
                            // Use RGB_565 to save memory (50% reduction).
                            // Tiles are opaque (filled with white), so alpha is not needed for the final PDF image.
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            canvas.translate(-tileRect.left, -tileRect.top)

                            // Draw Background
                            BackgroundDrawer.draw(canvas, bgStyle, tileRect, forceVector = false)

                            // Draw Content
                            val tileItems = model.queryItems(tileRect)
                            tileItems.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

                            val paint =
                                Paint().apply {
                                    isAntiAlias = true
                                    isDither = true
                                    strokeJoin = Paint.Join.ROUND
                                    strokeCap = Paint.Cap.ROUND
                                }

                            for (item in tileItems) {
                                StrokeRenderer.drawItem(canvas, item, false, paint, context)
                            }

                            // Create PDImageXObject
                            // We compress to JPEG for efficiency
                            val image = JPEGFactory.createFromImage(doc, bitmap, 0.9f)

                            mutex.withLock {
                                // PDF coords are bottom-left.
                                // bounds.height is the total height.
                                // tileRect.top is from top (y-down).
                                // We need to map to PDF y (y-up).

                                val pdfX = tileRect.left - bounds.left
                                // pdfY is calculated from the BOTTOM of the page
                                val pdfY = bounds.height() - (tileRect.bottom - bounds.top)

                                contentStream.drawImage(image, pdfX, pdfY, tileRect.width(), tileRect.height())
                            }
                        } catch (e: Exception) {
                            Logger.e("PdfExporter", "Error rendering tile", e)
                        } finally {
                            bitmap?.recycle()
                        }

                        val finished = completedTiles.incrementAndGet()
                        val progress = 10 + ((finished.toFloat() / totalTiles) * 80).toInt()
                        callback?.onProgress(progress, "Rendering Tile $finished/$totalTiles")
                    }
                }
            }.forEach { it.await() }
    }
}
