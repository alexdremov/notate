package com.alexdremov.notate.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionData
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.testutil.SnapshotVerifier
import com.alexdremov.notate.util.Quadtree
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfExporterSnapshotTest {
    private lateinit var context: Context
    private lateinit var model: InfiniteCanvasModel
    private lateinit var contentResolver: ContentResolver

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        every { context.cacheDir } returns File(tmpDir)
        every { context.resources.displayMetrics } returns
            android.util.DisplayMetrics().apply {
                density = 1f
                xdpi = 160f
            }

        model = mockk(relaxed = true)

        mockkStatic(EpdController::class)
        every { EpdController.getMaxTouchPressure() } returns 4096f

        mockkStatic(NeoBrushPenWrapper::class)
        every { NeoBrushPenWrapper.drawStroke(any(), any(), any(), any(), any(), any()) } answers {
            // No-op or simulate
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(EpdController::class)
        unmockkStatic(NeoBrushPenWrapper::class)
    }

    private fun setupModel(
        canvasType: CanvasType = CanvasType.INFINITE,
        backgroundStyle: BackgroundStyle = BackgroundStyle.Dots(spacing = 40f, color = Color.LTGRAY),
        items: List<CanvasItem> = emptyList(),
        bounds: RectF = RectF(0f, 0f, 595f, 842f),
    ) {
        every { model.canvasType } returns canvasType
        every { model.backgroundStyle } returns backgroundStyle
        every { model.getContentBounds() } returns bounds
        every { model.pageWidth } returns bounds.width()
        every { model.pageHeight } returns bounds.height()

        val regionManager = mockk<com.alexdremov.notate.data.region.RegionManager>(relaxed = true)
        every { model.getRegionManager() } returns regionManager

        val region = RegionData(RegionId(0, 0))
        region.items.addAll(items)
        region.rebuildQuadtree(1000f) // Larger than bounds

        coEvery { regionManager.getRegionsInRect(any()) } returns listOf(region)

        coEvery { model.queryItems(any()) } answers {
            val queryRect = arg<RectF>(0)
            val result = ArrayList<CanvasItem>()
            result.addAll(
                items.filter {
                    RectF.intersects(it.bounds, queryRect) ||
                        (it is Stroke && StrokeGeometry.strokeIntersectsRect(it, queryRect))
                },
            )
            result
        }
    }

    private fun createStroke(
        points: List<TouchPoint>,
        color: Int = Color.BLACK,
        width: Float = 5f,
        type: StrokeType = StrokeType.BALLPOINT,
    ): Stroke {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.inset(-width, -width)

        return Stroke(
            path = path,
            points = points,
            color = color,
            width = width,
            style = type,
            bounds = bounds,
        )
    }

    private fun runExportAndVerify(
        name: String,
        isVector: Boolean,
    ) = runBlocking {
        val outputStream = ByteArrayOutputStream()

        PdfExporter.export(
            context = context,
            model = model,
            outputStream = outputStream,
            isVector = isVector,
            callback = null,
        )

        val bytes = outputStream.toByteArray()
        val document = PDDocument.load(ByteArrayInputStream(bytes))
        val renderer = PDFRenderer(document)
        val bitmap = renderer.renderImageWithDPI(0, 72f, com.tom_roush.pdfbox.rendering.ImageType.ARGB)

        SnapshotVerifier.verify(bitmap, name)
        document.close()
    }

    @Test
    fun `export Vector Infinite Dots`() {
        val stroke =
            createStroke(
                listOf(TouchPoint(100f, 100f, 0.5f, 1f, 0, 0, 0), TouchPoint(200f, 200f, 0.5f, 1f, 0, 0, 0)),
            )
        setupModel(
            canvasType = CanvasType.INFINITE,
            backgroundStyle = BackgroundStyle.Dots(spacing = 50f, color = Color.BLACK, radius = 2f),
            items = listOf(stroke),
        )
        runExportAndVerify("export_vector_infinite_dots", true)
    }

    @Test
    fun `export Vector Infinite Grid`() {
        val stroke =
            createStroke(
                listOf(TouchPoint(50f, 50f, 0.5f, 1f, 0, 0, 0), TouchPoint(150f, 50f, 0.5f, 1f, 0, 0, 0)),
            )
        setupModel(
            canvasType = CanvasType.INFINITE,
            backgroundStyle = BackgroundStyle.Grid(spacing = 50f, color = Color.BLUE, thickness = 1f),
            items = listOf(stroke),
        )
        runExportAndVerify("export_vector_infinite_grid", true)
    }

    @Test
    fun `export Bitmap Infinite Lines`() {
        val stroke =
            createStroke(
                listOf(TouchPoint(50f, 100f, 0.5f, 1f, 0, 0, 0), TouchPoint(50f, 200f, 0.5f, 1f, 0, 0, 0)),
            )
        setupModel(
            canvasType = CanvasType.INFINITE,
            backgroundStyle = BackgroundStyle.Lines(spacing = 40f, color = Color.RED, thickness = 1f),
            items = listOf(stroke),
        )
        runExportAndVerify("export_bitmap_infinite_lines", false)
    }

    @Test
    fun `export Vector Fixed Dots interaction`() =
        runBlocking {
            val mockDoc = mockk<PdfDocumentWrapper>(relaxed = true)
            val mockPage = mockk<PdfPageWrapper<*>>(relaxed = true)
            val mockCanvas = mockk<android.graphics.Canvas>(relaxed = true)
            every { mockPage.canvas } returns mockCanvas
            every { mockDoc.startPage(any()) } returns mockPage

            setupModel(
                canvasType = CanvasType.FIXED_PAGES,
                backgroundStyle = BackgroundStyle.Dots(spacing = 30f, color = Color.DKGRAY, radius = 1.5f),
                items = emptyList(),
            )

            PdfExporter.export(
                context = context,
                model = model,
                outputStream = ByteArrayOutputStream(),
                isVector = true,
                callback = null,
                pdfDocumentFactory = { mockDoc },
            )

            io.mockk.verifyOrder {
                mockDoc.startPage(any())
                mockDoc.finishPage(mockPage)
                mockDoc.writeTo(any())
                mockDoc.close()
            }
        }

    /*
     * Skipped due to Robolectric native PdfDocument issues with finishPage().
     * Logic is covered by `export Vector Fixed Dots interaction`.
     */
    /*
    @Test
    fun `export Vector Fixed Dots`() {
        val stroke = createStroke(
            listOf(TouchPoint(100f, 100f, 0.5f, 1f, 0, 0, 0), TouchPoint(300f, 100f, 0.5f, 1f, 0, 0, 0))
        )
        setupModel(
            canvasType = CanvasType.FIXED_PAGES,
            backgroundStyle = BackgroundStyle.Dots(spacing = 30f, color = Color.DKGRAY, radius = 1.5f),
            items = listOf(stroke)
        )
        runExportAndVerify("export_vector_fixed_dots", true)
    }
     */

    @Test
    fun `export Vector Image Rotated`() {
        // 1. Non-rotated image
        val logical1 = RectF(50f, 50f, 150f, 150f)
        val imageItem1 =
            CanvasImage(
                uri = "content://dummy/image1.png",
                logicalBounds = logical1,
                bounds = RectF(logical1),
                rotation = 0f,
                zIndex = 0f,
                order = 1,
            )

        // 2. Rotated image (45 degrees)
        val logical2 = RectF(200f, 50f, 300f, 150f)
        val rotation2 = 45f
        val aabb2 =
            com.alexdremov.notate.util.StrokeGeometry
                .computeRotatedBounds(logical2, rotation2)
        val imageItem2 =
            CanvasImage(
                uri = "content://dummy/image2.png",
                logicalBounds = logical2,
                bounds = aabb2,
                rotation = rotation2,
                zIndex = 0f,
                order = 2,
            )

        val dummyImage = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val orange = Color.argb(255, 255, 128, 0)
        dummyImage.eraseColor(orange)
        val stream = ByteArrayOutputStream()
        dummyImage.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val imageBytes = stream.toByteArray()

        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(imageBytes)
        }

        setupModel(
            canvasType = CanvasType.INFINITE,
            backgroundStyle = BackgroundStyle.Blank(Color.WHITE),
            items = listOf(imageItem1, imageItem2),
            bounds = RectF(0f, 0f, 500f, 300f),
        )
        runExportAndVerify("export_vector_image_rotated", true)
    }
}
