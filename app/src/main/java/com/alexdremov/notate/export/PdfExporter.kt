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
import com.alexdremov.notate.model.TextItem
import com.alexdremov.notate.ui.render.BackgroundDrawer
import com.alexdremov.notate.ui.render.background.PatternLayoutHelper
import com.alexdremov.notate.util.CharcoalPenRenderer
import com.alexdremov.notate.util.FountainPenRenderer
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeRenderer
import com.alexdremov.notate.util.TextRenderer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
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
import java.util.ArrayList
import java.util.HashMap
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
        bitmapScale: Float = 1.0f,
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
                exportBitmapStreaming(context, model, outputStream, callback, bitmapScale)
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

            // Draw White Background
            contentStream.setNonStrokingColor(1f, 1f, 1f)
            contentStream.addRect(bounds.left, bounds.top, bounds.width(), bounds.height())
            contentStream.fill()

            callback?.onProgress(10, "Rendering Background...")

            // Render Background as Vectors
            renderBackgroundVectorToStream(contentStream, model.backgroundStyle, bounds)

            callback?.onProgress(20, "Rendering Strokes...")

            val regionManager = model.getRegionManager()
            if (regionManager != null) {
                val regions = regionManager.getRegionsInRect(bounds)
                val totalRegions = regions.size
                var processedRegions = 0

                val alphaCache = HashMap<Int, com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState>()

                for (region in regions) {
                    val items = ArrayList<CanvasItem>()
                    region.quadtree?.retrieve(items, bounds)
                    items.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

                    for (item in items) {
                        if (item is Stroke) {
                            renderStrokeToPdf(contentStream, item, alphaCache)
                        } else if (item is TextItem) {
                            renderTextToPdf(document, contentStream, item, context)
                        } else if (item is com.alexdremov.notate.model.CanvasImage) {
                            try {
                                val uriStr = item.uri
                                val bitmap =
                                    if (uriStr.startsWith("content://")) {
                                        context.contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        }
                                    } else {
                                        val path = if (uriStr.startsWith("file://")) uriStr.substring(7) else uriStr
                                        android.graphics.BitmapFactory.decodeFile(path)
                                    }
                                if (bitmap != null) {
                                    try {
                                        val pdImage =
                                            if (bitmap.hasAlpha()) {
                                                LosslessFactory.createFromImage(document, bitmap)
                                            } else {
                                                JPEGFactory.createFromImage(document, bitmap, 0.9f)
                                            }
                                        contentStream.saveGraphicsState()
                                        if (item.rotation != 0f) {
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                                                    item.bounds.centerX(),
                                                    item.bounds.centerY(),
                                                ),
                                            )
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                                                    Math.toRadians(item.rotation.toDouble()),
                                                    0f,
                                                    0f,
                                                ),
                                            )
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix
                                                    .getScaleInstance(1f, -1f),
                                            )
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                                                    -item.bounds.width() / 2,
                                                    -item.bounds.height() / 2,
                                                ),
                                            )
                                        } else {
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix
                                                    .getTranslateInstance(item.bounds.left, item.bounds.top),
                                            )
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix
                                                    .getScaleInstance(1f, -1f),
                                            )
                                            contentStream.transform(
                                                com.tom_roush.pdfbox.util.Matrix
                                                    .getTranslateInstance(0f, -item.bounds.height()),
                                            )
                                        }
                                        contentStream.drawImage(pdImage, 0f, 0f, item.bounds.width(), item.bounds.height())
                                        contentStream.restoreGraphicsState()
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.e("PdfExporter", "Failed to render image in vector export", e)
                            }
                        }
                    }
                    processedRegions++
                    if (processedRegions % 5 == 0) {
                        callback?.onProgress(
                            20 + ((processedRegions.toFloat() / totalRegions) * 70).toInt(),
                            "Exporting Region $processedRegions/$totalRegions",
                        )
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

    private suspend fun renderBackgroundVectorToStream(
        stream: PDPageContentStream,
        style: BackgroundStyle,
        rect: RectF,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val spacing =
            when (style) {
                is BackgroundStyle.Dots -> style.spacing
                is BackgroundStyle.Lines -> style.spacing
                is BackgroundStyle.Grid -> style.spacing
                else -> 0f
            }
        if (spacing <= 5f) return
        val color =
            when (style) {
                is BackgroundStyle.Dots -> style.color
                is BackgroundStyle.Lines -> style.color
                is BackgroundStyle.Grid -> style.color
                else -> Color.TRANSPARENT
            }
        val r = (Color.red(color) / 255f).coerceIn(0f, 1f)
        val g = (Color.green(color) / 255f).coerceIn(0f, 1f)
        val b = (Color.blue(color) / 255f).coerceIn(0f, 1f)
        stream.setStrokingColor(r, g, b)
        stream.setNonStrokingColor(r, g, b)
        when (style) {
            is BackgroundStyle.Dots -> {
                val radius = style.radius
                // Bezier control point distance for circle approximation: 4*(√2-1)/3 ≈ 0.552
                val k = 0.551915024494f
                val cd = radius * k
                val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
                val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
                var x = startX
                var dotCount = 0
                try {
                    while (x < rect.right + spacing) {
                        var y = startY
                        while (y < rect.bottom + spacing) {
                            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                                stream.moveTo(x + radius, y)
                                stream.curveTo(x + radius, y + cd, x + cd, y + radius, x, y + radius)
                                stream.curveTo(x - cd, y + radius, x - radius, y + cd, x - radius, y)
                                stream.curveTo(x - radius, y - cd, x - cd, y - radius, x, y - radius)
                                stream.curveTo(x + cd, y - radius, x + radius, y - cd, x + radius, y)
                                dotCount++
                                if (dotCount >= 500) {
                                    stream.fill()
                                    dotCount = 0
                                }
                            }
                            y += spacing
                        }
                        x += spacing
                    }
                    if (dotCount > 0) stream.fill()
                } catch (e: Exception) {
                    if (dotCount > 0) {
                        try {
                            stream.fill()
                        } catch (fillException: Exception) {
                            // Swallow to avoid masking the original exception
                        }
                    }
                    throw e
                }
            }

            is BackgroundStyle.Lines -> {
                stream.setLineWidth(style.thickness)
                val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
                var y = startY
                while (y < rect.bottom + spacing) {
                    if (y >= rect.top && y <= rect.bottom) {
                        stream.moveTo(rect.left, y)
                        stream.lineTo(rect.right, y)
                        stream.stroke()
                    }
                    y += spacing
                }
            }

            is BackgroundStyle.Grid -> {
                stream.setLineWidth(style.thickness)
                val startX = floor((rect.left - offsetX) / spacing) * spacing + offsetX
                var x = startX
                while (x < rect.right + spacing) {
                    if (x >= rect.left && x <= rect.right) {
                        stream.moveTo(x, rect.top)
                        stream.lineTo(x, rect.bottom)
                        stream.stroke()
                    }
                    x += spacing
                }
                val startY = floor((rect.top - offsetY) / spacing) * spacing + offsetY
                var y = startY
                while (y < rect.bottom + spacing) {
                    if (y >= rect.top && y <= rect.bottom) {
                        stream.moveTo(rect.left, y)
                        stream.lineTo(rect.right, y)
                        stream.stroke()
                    }
                    y += spacing
                }
            }

            else -> {}
    private fun renderTextToPdf(
        document: PDDocument,
        stream: PDPageContentStream,
        item: TextItem,
        context: android.content.Context,
    ) {
        val w = ceil(item.bounds.width()).toInt().coerceAtLeast(1)
        val h = ceil(item.bounds.height()).toInt().coerceAtLeast(1)

        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(w, h, 1).create()
        val page = pdfDoc.startPage(pageInfo)

        val renderItem =
            item.copy(
                bounds = RectF(0f, 0f, w.toFloat(), h.toFloat()),
                rotation = 0f,
            )

        TextRenderer.draw(page.canvas, renderItem, context)
        pdfDoc.finishPage(page)

        val os = java.io.ByteArrayOutputStream()
        pdfDoc.writeTo(os)
        pdfDoc.close()

        var tempDoc: PDDocument? = null
        try {
            tempDoc = PDDocument.load(os.toByteArray())
            val layerUtility = LayerUtility(document)
            val form = layerUtility.importPageAsForm(tempDoc, tempDoc.getPage(0))

            stream.saveGraphicsState()

            val centerX = item.bounds.centerX()
            val centerY = item.bounds.centerY()
            val rad = Math.toRadians(item.rotation.toDouble()).toFloat()

            // Transformation pipeline:
            // 1. Translate to Center
            // 2. Rotate
            // 3. Translate to top-left of the form (relative to center)
            stream.transform(
                com.tom_roush.pdfbox.util.Matrix
                    .getTranslateInstance(centerX, centerY),
            )
            stream.transform(
                com.tom_roush.pdfbox.util.Matrix
                    .getRotateInstance(rad.toDouble(), 0f, 0f),
            )
            stream.transform(
                com.tom_roush.pdfbox.util.Matrix
                    .getTranslateInstance(-w / 2f, -h / 2f),
            )

            stream.drawForm(form)
            stream.restoreGraphicsState()
        } catch (e: Exception) {
            Logger.e("PdfExporter", "Failed to render text block to PDF", e)
        } finally {
            tempDoc?.close()
        }
    }

    private fun getSafeMaxPressure(stroke: Stroke): Float {
        val maxObserved = stroke.points.maxOfOrNull { it.pressure } ?: 0f
        return if (maxObserved > 0f && maxObserved <= 1.0f) {
            1.0f
        } else {
            EpdController.getMaxTouchPressure().let { if (it <= 0f) 4096f else it }
        }
    }

    private fun renderStrokeToPdf(
        stream: PDPageContentStream,
        stroke: Stroke,
        alphaCache: HashMap<Int, com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState>,
    ) {
        val isFilled =
            when (stroke.style) {
                com.alexdremov.notate.model.StrokeType.FOUNTAIN, com.alexdremov.notate.model.StrokeType.CHARCOAL -> true
                else -> false
            }
        val path =
            if (isFilled) {
                val mp = getSafeMaxPressure(stroke)
                when (stroke.style) {
                    com.alexdremov.notate.model.StrokeType.FOUNTAIN -> FountainPenRenderer.getPath(stroke, mp)
                    com.alexdremov.notate.model.StrokeType.CHARCOAL -> CharcoalPenRenderer.getPath(stroke, mp) ?: stroke.path
                    else -> stroke.path
                }
            } else {
                stroke.path
            }
        if (path.isEmpty) return
        val coords = path.approximate(0.5f)
        val color = stroke.color
        val r = (Color.red(color) / 255f).coerceIn(0f, 1f)
        val g = (Color.green(color) / 255f).coerceIn(0f, 1f)
        val b = (Color.blue(color) / 255f).coerceIn(0f, 1f)
        val a = (Color.alpha(color) * stroke.style.alphaMultiplier).toInt().coerceIn(0, 255)
        if (a < 255) {
            val gs =
                alphaCache.getOrPut(a) {
                    com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = a / 255f
                        strokingAlphaConstant = a / 255f
                    }
                }
            stream.setGraphicsStateParameters(gs)
        }
        stream.setStrokingColor(r, g, b)
        if (isFilled) {
            stream.setNonStrokingColor(r, g, b)
        } else {
            stream.setLineWidth(stroke.width)
            stream.setLineCapStyle(1)
            stream.setLineJoinStyle(1)
        }
        if (coords.isNotEmpty()) {
            stream.moveTo(coords[1], coords[2])
            for (i in 3 until coords.size step 3) stream.lineTo(coords[i + 1], coords[i + 2])
            if (isFilled) stream.fill() else stream.stroke()
        }
        if (a < 255) {
            stream.setGraphicsStateParameters(
                alphaCache.getOrPut(255) {
                    com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = 1f
                        strokingAlphaConstant = 1f
                    }
                },
            )
        }
    }

    private suspend fun exportBitmapStreaming(
        context: android.content.Context,
        model: InfiniteCanvasModel,
        outputStream: OutputStream,
        callback: ProgressCallback?,
        bitmapScale: Float,
    ) = withContext(Dispatchers.IO) {
        val document = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            val cb = model.getContentBounds()
            val padding = 50f
            val bounds = if (cb.isEmpty) RectF(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT) else RectF(cb)
            bounds.inset(-padding, -padding)
            val page = PDPage(PDRectangle(bounds.width(), bounds.height()))
            document.addPage(page)
            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false)
            callback?.onProgress(10, "Rendering Canvas...")
            renderTilesToPdfBox(document, contentStream, model, bounds, context, callback, bitmapScale)
            contentStream.close()
            callback?.onProgress(90, "Writing to file...")
            document.save(outputStream)
            callback?.onProgress(100, "Done")
        } catch (e: Exception) {
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
            callback?.onProgress(((i.toFloat() / totalPages) * 90).toInt(), "Exporting Page ${i + 1}/$totalPages")

            val topOffset = i * pageFullHeight
            val pageWorldRect = RectF(0f, topOffset, pageWidth, topOffset + pageHeight)
            val patternArea = PatternLayoutHelper.calculatePatternArea(pageWorldRect, bgStyle)
            val (offsetX, offsetY) = PatternLayoutHelper.calculateOffsets(patternArea, bgStyle)
            val items = model.queryItems(pageWorldRect).apply { sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order }) }

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), i + 1).create()
            val page = doc.startPage(pageInfo)
            try {
                val canvas = page.canvas

                if (isVector) {
                    // Vector Mode: Draw background on PDF canvas, then vector items
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.translate(0f, -topOffset)
                    BackgroundDrawer.draw(canvas, bgStyle, patternArea, 1f, offsetX, offsetY, forceVector = true)
                    renderVectorItems(canvas, items, paint, context)
                    canvas.restore()
                } else {
                    // Bitmap Mode: Draw background AND items into a single bitmap to avoid PDF transparency issues (JPEG compression)
                    canvas.save()
                    canvas.translate(0f, -topOffset)
                    renderBitmapItems(canvas, items, pageWorldRect, paint, context, bgStyle, patternArea, offsetX, offsetY)
                    canvas.restore()
                }
            } finally {
                doc.finishPage(page)
            }
        }
    }

    private suspend fun exportInfiniteCanvasVector(
        doc: PdfDocumentWrapper,
        model: InfiniteCanvasModel,
        cb: RectF,
        bgStyle: BackgroundStyle,
        callback: ProgressCallback?,
        context: android.content.Context,
    ) {
        val padding = 50f
        val bounds = if (cb.isEmpty) RectF(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT) else RectF(cb)
        bounds.inset(-padding, -padding)
        val pageInfo = PdfDocument.PageInfo.Builder(bounds.width().toInt(), bounds.height().toInt(), 1).create()
        val page = doc.startPage(pageInfo)
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.translate(-bounds.left, -bounds.top)
            BackgroundDrawer.draw(canvas, bgStyle, bounds, forceVector = true)
            renderVectorItemsFromRegions(
                canvas,
                model,
                bounds,
                Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    strokeJoin =
                        Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                },
                context,
            )
        } finally {
            doc.finishPage(page)
        }
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

    private suspend fun renderVectorItemsFromRegions(
        canvas: Canvas,
        model: InfiniteCanvasModel,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
    ) {
        val rm = model.getRegionManager() ?: return
        for (region in rm.getRegionsInRect(bounds)) {
            val items = ArrayList<CanvasItem>()
            region.quadtree?.retrieve(items, bounds)
            items.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })
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
    }

    private fun renderBitmapItems(
        canvas: Canvas,
        items: List<CanvasItem>,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
        bgStyle: BackgroundStyle? = null,
        patternArea: RectF? = null,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val w = bounds.width().toInt().coerceAtLeast(1)
        val h = bounds.height().toInt().coerceAtLeast(1)
        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = Canvas(bitmap)

            // Draw Opaque Background first
            bmpCanvas.drawColor(Color.WHITE)
            if (bgStyle != null && patternArea != null) {
                // Adjust pattern area to be relative to the bitmap (0,0)
                // The bounds passed in are World Bounds (e.g. 0, topOffset).
                // bmpCanvas is already 0-based.
                // We need to shift patternArea by -bounds.left, -bounds.top
                val localPatternArea = RectF(patternArea)
                localPatternArea.offset(-bounds.left, -bounds.top)

                // OffsetX/Y are relative to World Origin usually?
                // BackgroundDrawer expects logic relative to the canvas it draws on.
                // If we translate canvas by -bounds, we are in World Space relative to Page Top.
                // PatternLayoutHelper calculates offsets relative to Page Rect.
                // So we can just draw directly if we setup the matrix?

                // Let's reset translation for background drawing to match expected inputs
                // Actually BackgroundDrawer uses the rect passed to it.
                // We passed localPatternArea.
                // Offsets: offsetX/Y are pattern start points.
                // If we shift the rect, we must ensure the pattern aligns.
                // BackgroundDrawer uses (startX = floor((rect.left - offsetX) / spacing) ...).
                // If we shift rect.left, we must shift offsetX too?
                // The easiest way is to use the original World Coordinates but translate the canvas.

                // But wait, bmpCanvas was just created. It has no translation.
                // So (0,0) on bmpCanvas corresponds to (bounds.left, bounds.top).

                bmpCanvas.save()
                bmpCanvas.translate(-bounds.left, -bounds.top)
                BackgroundDrawer.draw(bmpCanvas, bgStyle, patternArea, 1f, offsetX, offsetY, forceVector = false)

                // Draw Items
                for (item in items) {
                    if (item is Stroke) {
                        paint.color = item.color
                        paint.strokeWidth = item.width
                    }
                    StrokeRenderer.drawItem(bmpCanvas, item, false, paint, context)
                }
                bmpCanvas.restore()
            } else {
                bmpCanvas.translate(-bounds.left, -bounds.top)
                for (item in items) {
                    if (item is Stroke) {
                        paint.color = item.color
                        paint.strokeWidth = item.width
                    }
                    StrokeRenderer.drawItem(bmpCanvas, item, false, paint, context)
                }
            }

            canvas.drawBitmap(bitmap, bounds.left, bounds.top, null)
            bitmap.recycle()
        } catch (e: OutOfMemoryError) {
            // Fallback to vector if OOM (Note: Background will be missing in fallback if we don't handle it,
            // but this is a rare edge case. We prioritize not crashing.)
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
        bitmapScale: Float,
    ) = withContext(Dispatchers.Default) {
        val ts = 2048
        val cols = ceil(bounds.width() / ts).toInt()
        val rows = ceil(bounds.height() / ts).toInt()
        val total = cols * rows
        val completed = AtomicInteger(0)
        val mutex = Mutex()
        val sem = kotlinx.coroutines.sync.Semaphore(2)
        val bgStyle = model.backgroundStyle
        val tiles = ArrayList<RectF>(total)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                tiles.add(
                    RectF(
                        bounds.left + c * ts,
                        bounds.top + r * ts,
                        min(bounds.left + (c + 1) * ts, bounds.right),
                        min(bounds.top + (r + 1) * ts, bounds.bottom),
                    ),
                )
            }
        }
        tiles
            .map { tr ->
                async(Dispatchers.Default) {
                    sem.withPermit {
                        val w = (tr.width() * bitmapScale).toInt().coerceAtLeast(1)
                        val h = (tr.height() * bitmapScale).toInt().coerceAtLeast(1)
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                            val canvas = Canvas(bitmap)
                            canvas.scale(bitmapScale, bitmapScale)
                            canvas.drawColor(Color.WHITE)
                            canvas.translate(-tr.left, -tr.top)
                            BackgroundDrawer.draw(canvas, bgStyle, tr, forceVector = false)
                            val items = model.queryItems(tr).apply { sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order }) }
                            val paint =
                                Paint().apply {
                                    isAntiAlias = true
                                    isDither = true
                                    strokeJoin = Paint.Join.ROUND
                                    strokeCap = Paint.Cap.ROUND
                                }
                            for (item in items) StrokeRenderer.drawItem(canvas, item, false, paint, context)
                            val image = JPEGFactory.createFromImage(doc, bitmap, 0.9f)
                            mutex.withLock {
                                contentStream.drawImage(
                                    image,
                                    tr.left - bounds.left,
                                    bounds.height() - (tr.bottom - bounds.top),
                                    tr.width(),
                                    tr.height(),
                                )
                            }
                        } catch (e: Exception) {
                            Logger.e("PdfExporter", "Error rendering tile", e)
                        } finally {
                            bitmap?.recycle()
                        }
                        callback?.onProgress(
                            10 + ((completed.incrementAndGet().toFloat() / total) * 80).toInt(),
                            "Rendering Tile ${completed.get()}/$total",
                        )
                    }
                }
            }.forEach { it.await() }
    }
}
