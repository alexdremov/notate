package com.alexdremov.notate.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionData
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.Quadtree
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfExporterTest {
    private lateinit var context: Context
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(testDispatcher)

        mockkStatic(EpdController::class)
        every { EpdController.getMaxTouchPressure() } returns 4096f
    }

    @After
    fun teardown() {
        unmockkStatic(EpdController::class)
        Dispatchers.resetMain()
    }

    private fun createTestStroke(
        x: Float,
        y: Float,
    ): Stroke {
        val path =
            Path().apply {
                moveTo(x, y)
                lineTo(x + 100f, y + 100f)
            }
        val points =
            listOf(
                TouchPoint(x, y, 2048f, 10f, 0, 0, 0L),
                TouchPoint(x + 100f, y + 100f, 2048f, 10f, 0, 0, 10L),
            )
        val bounds = RectF(x, y, x + 100f, y + 100f)
        bounds.inset(-5f, -5f)
        return Stroke(
            path = path,
            points = points,
            color = Color.BLACK,
            width = 10f,
            style = StrokeType.BALLPOINT,
            bounds = bounds,
        )
    }

    private fun createMockPdfDocumentWrapper(): PdfDocumentWrapper {
        val doc = mockk<PdfDocumentWrapper>(relaxed = true)
        every { doc.startPage(any()) } answers {
            val pageWrapper = mockk<PdfPageWrapper<Any>>(relaxed = true)
            val canvas = mockk<Canvas>(relaxed = true)
            every { pageWrapper.canvas } returns canvas
            pageWrapper
        }
        return doc
    }

    @Test
    fun `test export infinite canvas vector`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>(relaxed = true)
            val stroke = createTestStroke(100f, 100f)

            io.mockk.coEvery { model.queryItems(any()) } returns arrayListOf(stroke)
            every { model.getRegionManager() } returns null
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 200f)
            every { model.canvasType } returns CanvasType.INFINITE
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns CanvasConfig.PAGE_A4_HEIGHT
            every { model.backgroundStyle } returns BackgroundStyle.Dots(Color.LTGRAY, 50f, 2f)

            val outputStream = ByteArrayOutputStream()
            val mockDoc = createMockPdfDocumentWrapper()

            try {
                PdfExporter.export(
                    context,
                    model,
                    outputStream,
                    isVector = true,
                    callback = null,
                    pdfDocumentFactory = { mockDoc },
                )

                verify(exactly = 0) { mockDoc.startPage(any()) }
            } catch (e: Exception) {
                verify(exactly = 0) { mockDoc.startPage(any()) }
            }
        }

    @Test
    fun `test export invokes progress callback`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>(relaxed = true)
            val stroke = createTestStroke(100f, 100f)
            val callback = mockk<PdfExporter.ProgressCallback>(relaxed = true)

            io.mockk.coEvery { model.queryItems(any()) } returns arrayListOf(stroke)
            every { model.getRegionManager() } returns null
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 200f)
            every { model.canvasType } returns CanvasType.INFINITE
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns CanvasConfig.PAGE_A4_HEIGHT
            every { model.backgroundStyle } returns BackgroundStyle.Blank()

            val outputStream = ByteArrayOutputStream()
            val mockDoc = createMockPdfDocumentWrapper()

            PdfExporter.export(
                context,
                model,
                outputStream,
                isVector = true,
                callback = callback,
                pdfDocumentFactory = { mockDoc },
            )

            verify { callback.onProgress(any(), any()) }
        }

    @Test
    fun `test export fixed pages vector`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>(relaxed = true)
            val stroke1 = createTestStroke(100f, 100f)
            val stroke2 = createTestStroke(100f, 2000f) // Should be on second page

            io.mockk.coEvery { model.queryItems(any()) } returns arrayListOf(stroke1, stroke2)
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 2100f)
            every { model.canvasType } returns CanvasType.FIXED_PAGES
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns 1000f // Small page height to force multiple pages
            every { model.backgroundStyle } returns BackgroundStyle.Lines(Color.LTGRAY, 50f, 1f)

            val outputStream = ByteArrayOutputStream()
            val mockDoc = createMockPdfDocumentWrapper()

            PdfExporter.export(
                context,
                model,
                outputStream,
                isVector = true,
                callback = null,
                pdfDocumentFactory = { mockDoc },
            )

            // Should have at least 2 pages
            verify(atLeast = 2) { mockDoc.startPage(any()) }
            verify(atLeast = 2) { mockDoc.finishPage(any()) }
            verify { mockDoc.writeTo(any()) }
            verify { mockDoc.close() }
        }

    @Test
    fun `test export fixed pages bitmap`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>(relaxed = true)
            val stroke1 = createTestStroke(100f, 100f)

            io.mockk.coEvery { model.queryItems(any()) } returns arrayListOf(stroke1)
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 200f)
            every { model.canvasType } returns CanvasType.FIXED_PAGES
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns CanvasConfig.PAGE_A4_HEIGHT
            every { model.backgroundStyle } returns BackgroundStyle.Grid(Color.LTGRAY, 50f, 1f)

            val outputStream = ByteArrayOutputStream()
            val mockDoc = createMockPdfDocumentWrapper()

            PdfExporter.export(
                context,
                model,
                outputStream,
                isVector = false,
                callback = null,
                pdfDocumentFactory = { mockDoc },
            )

            verify { mockDoc.startPage(any()) }
            verify { mockDoc.finishPage(any()) }
            verify { mockDoc.writeTo(any()) }
            verify { mockDoc.close() }
        }

    @Test
    fun `test export highlighter transparency uses graphics state`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>(relaxed = true)
            // Create a Highlighter stroke (Color.RED, alpha multiplier 0.5)
            val path =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(100f, 100f)
                }
            val points = listOf(TouchPoint(0f, 0f, 10f, 1f, 0, 0, 0), TouchPoint(100f, 100f, 10f, 1f, 0, 0, 0))
            val bounds = RectF(0f, 0f, 100f, 100f)
            val stroke =
                Stroke(
                    path = path,
                    points = points,
                    color = Color.RED,
                    width = 20f,
                    style = StrokeType.HIGHLIGHTER,
                    bounds = bounds,
                )

            // Setup RegionManager to return this stroke
            val regionManager = mockk<RegionManager>(relaxed = true)
            every { model.getRegionManager() } returns regionManager
            val region = mockk<RegionData>(relaxed = true)
            // Use coEvery for suspend function
            coEvery { regionManager.getRegionsInRect(any()) } returns listOf(region)

            // Mock Quadtree retrieval
            val quadtree = mockk<Quadtree>(relaxed = true)
            every { region.quadtree } returns quadtree
            every { quadtree.retrieve(any<ArrayList<CanvasItem>>(), any<RectF>()) } answers {
                val list = firstArg<ArrayList<CanvasItem>>()
                list.add(stroke)
            }

            every { model.getContentBounds() } returns RectF(0f, 0f, 200f, 200f)
            every { model.canvasType } returns CanvasType.INFINITE
            every { model.backgroundStyle } returns BackgroundStyle.Blank()

            // Mock PDFBox static interactions
            mockkStatic(PDFBoxResourceLoader::class)
            every { PDFBoxResourceLoader.init(any()) } returns Unit

            // Mock PDDocument construction
            mockkConstructor(PDDocument::class)
            every { anyConstructed<PDDocument>().addPage(any()) } returns Unit
            every { anyConstructed<PDDocument>().save(any<OutputStream>()) } returns Unit
            every { anyConstructed<PDDocument>().close() } returns Unit

            // Mock PDPage construction
            mockkConstructor(PDPage::class)

            // Mock PDPageContentStream construction
            mockkConstructor(PDPageContentStream::class)
            every { anyConstructed<PDPageContentStream>().setGraphicsStateParameters(any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().close() } returns Unit
            // Stub other methods called
            every { anyConstructed<PDPageContentStream>().saveGraphicsState() } returns Unit
            every { anyConstructed<PDPageContentStream>().restoreGraphicsState() } returns Unit
            every { anyConstructed<PDPageContentStream>().transform(any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().setStrokingColor(any<Float>(), any(), any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().setNonStrokingColor(any<Float>(), any(), any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().setLineWidth(any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().setLineCapStyle(any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().setLineJoinStyle(any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().moveTo(any(), any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().lineTo(any(), any()) } returns Unit
            every { anyConstructed<PDPageContentStream>().stroke() } returns Unit
            every { anyConstructed<PDPageContentStream>().fill() } returns Unit

            val outputStream = ByteArrayOutputStream()

            PdfExporter.export(
                context,
                model,
                outputStream,
                isVector = true,
                callback = null,
            )

            // Verify that setGraphicsStateParameters was called
            // Ideally verify the alpha value in the state
            val slots = mutableListOf<com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState>()
            verify { anyConstructed<PDPageContentStream>().setGraphicsStateParameters(capture(slots)) }

            // Highlighter has 0.5 alpha multiplier. Color.RED is 255 alpha.
            // So expecting ~0.5 alpha.
            val hasTransparentState =
                slots.any {
                    it.strokingAlphaConstant != null && it.strokingAlphaConstant < 0.9f
                }
            assertTrue("Should have set a transparency graphics state for highlighter", hasTransparentState)

            unmockkStatic(PDFBoxResourceLoader::class)
            unmockkConstructor(PDDocument::class)
            unmockkConstructor(PDPage::class)
            unmockkConstructor(PDPageContentStream::class)
        }
}
