package com.alexdremov.notate.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.CanvasImage
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
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
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
            val bounds = RectF()
            if (contentBounds.isEmpty) {
                bounds.set(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
            } else {
                bounds.set(contentBounds)
            }
            bounds.inset(-padding, -padding)

            val width = bounds.width()
            val height = bounds.height()

            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)

            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false)

            // Coordinate System: We use standard PDF coordinates (+Y is up).
            // Manual mapping is performed in render functions to ensure correct orientation of complex items.

            callback?.onProgress(10, "Rendering Background...")
            renderBackgroundVectorToStream(contentStream, model.backgroundStyle, bounds, height, 0f, 0f)

            callback?.onProgress(20, "Rendering Items...")

            val regionManager = model.getRegionManager()
            if (regionManager != null) {
                val regions = regionManager.getRegionsInRect(bounds)
                val totalRegions = regions.size
                var processedRegions = 0
                val processedItems = HashSet<Long>()

                // Cache for Transparency States
                val alphaCache = HashMap<Int, PDExtendedGraphicsState>()

                for (region in regions) {
                    val items = ArrayList<CanvasItem>()
                    if (region.quadtree != null) {
                        region.quadtree?.retrieve(items, bounds)
                    } else {
                        // Fallback if quadtree is not initialized for some reason
                        items.addAll(region.items.filter { RectF.intersects(it.bounds, bounds) })
                    }
                    items.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

                    for (item in items) {
                        if (!processedItems.add(item.order)) continue

                        when (item) {
                            is Stroke -> renderStrokeToPdf(contentStream, item, alphaCache, bounds, height)
                            is TextItem -> renderTextToPdf(document, contentStream, item, context, bounds, height)
                            is CanvasImage -> renderImageToPdf(document, contentStream, item, context, bounds, height)
                        }
                    }

                    processedRegions++
                    if (processedRegions % 5 == 0 || processedRegions == totalRegions) {
                        val progress = 20 + ((processedRegions.toFloat() / totalRegions) * 70).toInt()
                        callback?.onProgress(progress, "Exporting Region $processedRegions/$totalRegions")
                        currentCoroutineContext().ensureActive()
                    }
                }
            }

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

    private fun renderBackgroundVectorToStream(
        stream: PDPageContentStream,
        style: BackgroundStyle,
        bounds: RectF,
        pageHeight: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        when (style) {
            is BackgroundStyle.Dots -> {
                renderDotsToStream(stream, style, bounds, pageHeight, offsetX, offsetY)
            }

            is BackgroundStyle.Lines -> {
                renderLinesToStream(stream, style, bounds, pageHeight, offsetX, offsetY)
            }

            is BackgroundStyle.Grid -> {
                renderGridToStream(stream, style, bounds, pageHeight, offsetX, offsetY)
            }

            else -> {}
        }
    }

    private fun renderDotsToStream(
        stream: PDPageContentStream,
        style: BackgroundStyle.Dots,
        bounds: RectF,
        pageHeight: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val spacing = style.spacing
        if (spacing <= 0.1f) return

        val color = style.color
        stream.setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)

        val startX = floor((bounds.left - offsetX) / spacing) * spacing + offsetX
        val startY = floor((bounds.top - offsetY) / spacing) * spacing + offsetY

        var x = startX
        while (x < bounds.right + spacing) {
            var y = startY
            while (y < bounds.bottom + spacing) {
                if (x >= bounds.left - 0.1f && x <= bounds.right + 0.1f &&
                    y >= bounds.top - 0.1f && y <= bounds.bottom + 0.1f
                ) {
                    val pdfX = x - bounds.left
                    val pdfY = pageHeight - (y - bounds.top)

                    val r = style.radius
                    // Use 4 bezier curves to approximate a circle
                    val k = 0.552284749831f * r
                    stream.moveTo(pdfX + r, pdfY)
                    stream.curveTo(pdfX + r, pdfY + k, pdfX + k, pdfY + r, pdfX, pdfY + r)
                    stream.curveTo(pdfX - k, pdfY + r, pdfX - r, pdfY + k, pdfX - r, pdfY)
                    stream.curveTo(pdfX - r, pdfY - k, pdfX - k, pdfY - r, pdfX, pdfY - r)
                    stream.curveTo(pdfX + k, pdfY - r, pdfX + r, pdfY - k, pdfX + r, pdfY)
                    stream.fill()
                }
                y += spacing
            }
            x += spacing
        }
    }

    private fun renderLinesToStream(
        stream: PDPageContentStream,
        style: BackgroundStyle.Lines,
        bounds: RectF,
        pageHeight: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val spacing = style.spacing
        if (spacing <= 0.1f) return

        val color = style.color
        stream.setStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        stream.setLineWidth(style.thickness)

        val startY = floor((bounds.top - offsetY) / spacing) * spacing + offsetY

        var y = startY
        while (y <= bounds.bottom + 0.1f) {
            if (y >= bounds.top - 0.1f) {
                val pdfY = pageHeight - (y - bounds.top)
                stream.moveTo(0f, pdfY)
                stream.lineTo(bounds.width(), pdfY)
                stream.stroke()
            }
            y += spacing
        }
    }

    private fun renderGridToStream(
        stream: PDPageContentStream,
        style: BackgroundStyle.Grid,
        bounds: RectF,
        pageHeight: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val spacing = style.spacing
        if (spacing <= 0.1f) return

        val color = style.color
        stream.setStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        stream.setLineWidth(style.thickness)

        // Vertical lines
        val startX = floor((bounds.left - offsetX) / spacing) * spacing + offsetX
        var x = startX
        while (x <= bounds.right + 0.1f) {
            if (x >= bounds.left - 0.1f) {
                val pdfX = x - bounds.left
                stream.moveTo(pdfX, 0f)
                stream.lineTo(pdfX, pageHeight)
                stream.stroke()
            }
            x += spacing
        }

        // Horizontal lines
        val startY = floor((bounds.top - offsetY) / spacing) * spacing + offsetY
        var y = startY
        while (y <= bounds.bottom + 0.1f) {
            if (y >= bounds.top - 0.1f) {
                val pdfY = pageHeight - (y - bounds.top)
                stream.moveTo(0f, pdfY)
                stream.lineTo(bounds.width(), pdfY)
                stream.stroke()
            }
            y += spacing
        }
    }

    private fun renderTextToPdf(
        document: PDDocument,
        stream: PDPageContentStream,
        item: TextItem,
        context: android.content.Context,
        bounds: RectF,
        pageHeight: Float,
    ) {
        val w = ceil(item.bounds.width()).toInt().coerceAtLeast(1)
        val h = ceil(item.bounds.height()).toInt().coerceAtLeast(1)

        val pdfDoc = PdfDocument()
        try {
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

            var tempDoc: PDDocument? = null
            try {
                tempDoc = PDDocument.load(os.toByteArray())
                val layerUtility = LayerUtility(document)
                val form = layerUtility.importPageAsForm(tempDoc, tempDoc.getPage(0))

                val left = item.bounds.left - bounds.left
                val bottom = pageHeight - (item.bounds.bottom - bounds.top)

                stream.saveGraphicsState()

                // Move to center of the item
                stream.transform(Matrix.getTranslateInstance(left + item.bounds.width() / 2f, bottom + item.bounds.height() / 2f))
                // Rotate (PDF CCW vs Android CW)
                stream.transform(Matrix.getRotateInstance(-Math.toRadians(item.rotation.toDouble()), 0f, 0f))
                // Move back to origin (bottom-left of block)
                stream.transform(Matrix.getTranslateInstance(-item.bounds.width() / 2f, -item.bounds.height() / 2f))

                // Render visual layer: No manual flip needed as form from PdfDocument is already oriented correctly
                stream.drawForm(form)

                // Render searchable layer: origin at top-left of block for baseline calculations
                stream.transform(Matrix.getTranslateInstance(0f, item.bounds.height()))
                addSearchableText(stream, item, context)

                stream.restoreGraphicsState()
            } catch (e: Exception) {
                Logger.e("PdfExporter", "Failed to render text block to PDF", e)
            } finally {
                tempDoc?.close()
            }
        } finally {
            pdfDoc.close()
        }
    }

    private fun addSearchableText(
        stream: PDPageContentStream,
        item: TextItem,
        context: android.content.Context,
    ) {
        try {
            val layout = TextRenderer.getStaticLayout(context, item)

            stream.beginText()
            stream.setRenderingMode(RenderingMode.NEITHER)

            val font = PDType1Font.HELVETICA
            stream.setFont(font, item.fontSize)

            for (i in 0 until layout.lineCount) {
                val lineText = layout.text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString()
                if (lineText.isBlank()) continue

                val lineLeft = layout.getLineLeft(i)
                // In a coordinate system where origin is at top of block and Y is up:
                val lineBaseline = -layout.getLineBaseline(i).toFloat()

                stream.newLineAtOffset(lineLeft, lineBaseline)
                stream.showText(lineText)
                stream.newLineAtOffset(-lineLeft, -lineBaseline)
            }

            stream.endText()
        } catch (e: Exception) {
            Logger.w("PdfExporter", "Failed to add searchable text layer: ${e.message}")
        }
    }

    private fun renderImageToPdf(
        document: PDDocument,
        stream: PDPageContentStream,
        item: CanvasImage,
        context: android.content.Context,
        bounds: RectF,
        pageHeight: Float,
    ) {
        Logger.d("PdfExporter", "Rendering image: ${item.uri} at ${item.bounds}")
        try {
            val uriStr = item.uri
            val uri = Uri.parse(uriStr)

            val bitmap =
                try {
                    if (uri.scheme == "content") {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } else {
                        val file = java.io.File(uriStr)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(file.absolutePath)
                        } else {
                            BitmapFactory.decodeFile(uri.path)
                        }
                    }
                } catch (e: Exception) {
                    null
                }

            if (bitmap != null) {
                Logger.d("PdfExporter", "Successfully decoded bitmap for ${item.uri} (${bitmap.width}x${bitmap.height})")
                val image = LosslessFactory.createFromImage(document, bitmap)

                val left = item.bounds.left - bounds.left
                val bottom = pageHeight - (item.bounds.bottom - bounds.top)

                stream.saveGraphicsState()

                stream.transform(Matrix.getTranslateInstance(left + item.bounds.width() / 2f, bottom + item.bounds.height() / 2f))
                stream.transform(Matrix.getRotateInstance(-Math.toRadians(item.rotation.toDouble()), 0f, 0f))
                stream.transform(Matrix.getTranslateInstance(-item.bounds.width() / 2f, -item.bounds.height() / 2f))

                if (item.opacity < 1.0f) {
                    val gstate = PDExtendedGraphicsState()
                    gstate.nonStrokingAlphaConstant = item.opacity
                    stream.setGraphicsStateParameters(gstate)
                }

                stream.drawImage(image, 0f, 0f, item.bounds.width(), item.bounds.height())
                stream.restoreGraphicsState()

                bitmap.recycle()
            }
        } catch (e: Exception) {
            Logger.e("PdfExporter", "Failed to render image to PDF: ${item.uri}", e)
        }
    }

    private fun getSafeMaxPressure(stroke: Stroke): Float {
        val maxObserved = stroke.points.maxOfOrNull { it.pressure } ?: 0f

        return when {
            maxObserved > 0f && maxObserved <= 1.0f -> {
                1.0f
            }

            else -> {
                val hwMax = EpdController.getMaxTouchPressure()
                if (hwMax <= 0f) 4096f else hwMax
            }
        }
    }

    private fun renderStrokeToPdf(
        stream: PDPageContentStream,
        stroke: Stroke,
        alphaCache: HashMap<Int, PDExtendedGraphicsState>,
        bounds: RectF,
        pageHeight: Float,
    ) {
        val isFilled =
            when (stroke.style) {
                com.alexdremov.notate.model.StrokeType.FOUNTAIN -> true
                com.alexdremov.notate.model.StrokeType.CHARCOAL -> true
                com.alexdremov.notate.model.StrokeType.BRUSH -> false
                else -> false
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

        val error = 0.5f
        val coords = path.approximate(error)

        val color = stroke.color
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val a = (Color.alpha(color) * stroke.style.alphaMultiplier).toInt().coerceIn(0, 255)

        stream.saveGraphicsState()

        if (a < 255) {
            val alphaKey = a
            var extGState = alphaCache[alphaKey]
            if (extGState == null) {
                extGState = PDExtendedGraphicsState()
                extGState.nonStrokingAlphaConstant = a / 255f
                extGState.strokingAlphaConstant = a / 255f
                alphaCache[alphaKey] = extGState
            }
            stream.setGraphicsStateParameters(extGState)
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
            stream.moveTo(coords[1] - bounds.left, pageHeight - (coords[2] - bounds.top))
            for (i in 3 until coords.size step 3) {
                stream.lineTo(coords[i + 1] - bounds.left, pageHeight - (coords[i + 2] - bounds.top))
            }
            if (isFilled) {
                stream.fill()
            } else {
                stream.stroke()
            }
        }

        stream.restoreGraphicsState()
    }

    private suspend fun renderBackgroundTiledToStream(
        doc: PDDocument,
        stream: PDPageContentStream,
        style: BackgroundStyle,
        bounds: RectF,
        pageHeight: Float,
    ) {
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
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.translate(-tileRect.left, -tileRect.top)
                    BackgroundDrawer.draw(canvas, style, tileRect, forceVector = false)

                    val image = LosslessFactory.createFromImage(doc, bitmap)

                    val pdfX = tileRect.left - bounds.left
                    val pdfY = pageHeight - (tileRect.bottom - bounds.top)

                    stream.drawImage(image, pdfX, pdfY, tileRect.width(), tileRect.height())
                } catch (e: Exception) {
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
        bitmapScale: Float,
    ) = withContext(Dispatchers.IO) {
        val document = PDDocument(MemoryUsageSetting.setupTempFileOnly())

        try {
            val contentBounds = model.getContentBounds()
            val padding = 50f
            val bounds = RectF()
            if (contentBounds.isEmpty) {
                bounds.set(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
            } else {
                bounds.set(contentBounds)
            }
            bounds.inset(-padding, -padding)

            val width = bounds.width()
            val height = bounds.height()

            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)

            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false)

            callback?.onProgress(10, "Rendering Canvas...")
            renderTilesToPdfBox(document, contentStream, model, bounds, context, callback, bitmapScale)

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
        val bounds = RectF()
        if (contentBounds.isEmpty) {
            bounds.set(0f, 0f, CanvasConfig.PAGE_A4_WIDTH, CanvasConfig.PAGE_A4_HEIGHT)
        } else {
            bounds.set(contentBounds)
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

    private suspend fun renderVectorItemsFromRegions(
        canvas: Canvas,
        model: InfiniteCanvasModel,
        bounds: RectF,
        paint: Paint,
        context: android.content.Context,
    ) {
        val regionManager = model.getRegionManager() ?: return
        val regions = regionManager.getRegionsInRect(bounds)

        for (region in regions) {
            val regionItems = ArrayList<CanvasItem>()
            region.quadtree?.retrieve(regionItems, bounds)
            regionItems.sortWith(compareBy<CanvasItem> { it.zIndex }.thenBy { it.order })

            for (item in regionItems) {
                if (item is Stroke) {
                    paint.color = item.color
                    paint.strokeWidth = item.width
                    StrokeRenderer.drawStroke(canvas, paint, item, forceVector = true)
                } else {
                    StrokeRenderer.drawItem(canvas, item, false, paint, context)
                }
            }
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
        bitmapScale: Float,
    ) = withContext(Dispatchers.Default) {
        val tileSize = 2048
        val cols = ceil(bounds.width() / tileSize).toInt()
        val rows = ceil(bounds.height() / tileSize).toInt()
        val totalTiles = cols * rows
        val completedTiles = AtomicInteger(0)

        val mutex = Mutex()
        val semaphore = kotlinx.coroutines.sync.Semaphore(2)

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
                        val w = (tileRect.width() * bitmapScale).toInt().coerceAtLeast(1)
                        val h = (tileRect.height() * bitmapScale).toInt().coerceAtLeast(1)

                        var bitmap: Bitmap? = null
                        try {
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.scale(bitmapScale, bitmapScale)
                            canvas.drawColor(Color.WHITE)
                            canvas.translate(-tileRect.left, -tileRect.top)

                            // Use 0f offsets for infinite canvas to match UI
                            BackgroundDrawer.draw(canvas, bgStyle, tileRect, 1.0f, 0f, 0f, forceVector = false)

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

                            // Manual R/B channel swap because PDFBox-Android's LosslessFactory
                            // often swaps Red and Blue channels for ARGB_8888 bitmaps.
                            val pixels = IntArray(w * h)
                            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                            for (i in pixels.indices) {
                                val p = pixels[i]
                                val a = (p shr 24) and 0xff
                                val r = (p shr 16) and 0xff
                                val g = (p shr 8) and 0xff
                                val b = p and 0xff
                                pixels[i] = (a shl 24) or (b shl 16) or (g shl 8) or r
                            }
                            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

                            val image = LosslessFactory.createFromImage(doc, bitmap)

                            mutex.withLock {
                                val pdfX = tileRect.left - bounds.left
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
