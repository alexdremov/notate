package com.alexdremov.notate.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

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
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Default } returns testDispatcher

        mockkStatic(EpdController::class)
        every { EpdController.getMaxTouchPressure() } returns 4096f
    }

    @After
    fun teardown() {
        unmockkStatic(EpdController::class)
        unmockkStatic(Dispatchers::class)
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
            val page = mockk<PdfPageWrapper>(relaxed = true)
            val canvas = mockk<Canvas>(relaxed = true)
            every { page.canvas } returns canvas
            page
        }
        return doc
    }

    @Test
    fun `test export infinite canvas vector`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>()
            val stroke = createTestStroke(100f, 100f)

            every { model.performRead(any()) } answers {
                val block = arg<(List<com.alexdremov.notate.model.CanvasItem>) -> Unit>(0)
                block(listOf(stroke))
            }
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 200f)
            every { model.canvasType } returns CanvasType.INFINITE
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns CanvasConfig.PAGE_A4_HEIGHT
            every { model.backgroundStyle } returns BackgroundStyle.Dots(Color.LTGRAY, 50f, 2f)

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

            verify { mockDoc.startPage(any()) }
            verify { mockDoc.finishPage(any()) }
            verify { mockDoc.writeTo(any()) }
            verify { mockDoc.close() }
        }

    @Test
    fun `test export infinite canvas bitmap`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>()
            val stroke = createTestStroke(100f, 100f)

            every { model.performRead(any()) } answers {
                val block = arg<(List<com.alexdremov.notate.model.CanvasItem>) -> Unit>(0)
                block(listOf(stroke))
            }
            every { model.getContentBounds() } returns RectF(100f, 100f, 200f, 200f)
            every { model.canvasType } returns CanvasType.INFINITE
            every { model.pageWidth } returns CanvasConfig.PAGE_A4_WIDTH
            every { model.pageHeight } returns CanvasConfig.PAGE_A4_HEIGHT
            every { model.backgroundStyle } returns BackgroundStyle.Dots(Color.LTGRAY, 50f, 2f)

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
    fun `test export fixed pages vector`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>()
            val stroke1 = createTestStroke(100f, 100f)
            val stroke2 = createTestStroke(100f, 2000f) // Should be on second or third page

            every { model.performRead(any()) } answers {
                val block = arg<(List<com.alexdremov.notate.model.CanvasItem>) -> Unit>(0)
                block(listOf(stroke1, stroke2))
            }
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

            // Should have at least 2 pages (0-1000, 1100-2100) -> 2100 is on page index 1 (2nd page) or index 2 (3rd page)
            // 2100 / (1000 + 100) = 2100 / 1100 = 1.9 -> floor is 1. lastPageIdx = 1. Total pages = 2.
            verify(atLeast = 2) { mockDoc.startPage(any()) }
            verify(atLeast = 2) { mockDoc.finishPage(any()) }
            verify { mockDoc.writeTo(any()) }
            verify { mockDoc.close() }
        }

    @Test
    fun `test export fixed pages bitmap`() =
        runTest(testDispatcher) {
            val model = mockk<InfiniteCanvasModel>()
            val stroke1 = createTestStroke(100f, 100f)

            every { model.performRead(any()) } answers {
                val block = arg<(List<com.alexdremov.notate.model.CanvasItem>) -> Unit>(0)
                block(listOf(stroke1))
            }
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
}
