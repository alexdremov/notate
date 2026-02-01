package com.alexdremov.notate.ui.controller

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.region.RegionData
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.util.ClipboardManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CanvasControllerTest {
    private lateinit var controller: CanvasControllerImpl
    private lateinit var model: InfiniteCanvasModel
    private lateinit var renderer: CanvasRenderer
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        every { context.cacheDir } returns File("/tmp")

        model = mockk(relaxed = true)
        renderer = mockk(relaxed = true)

        mockkObject(ClipboardManager)
        every { ClipboardManager.copy(any()) } just Runs
        every { ClipboardManager.hasContent() } returns false

        controller = CanvasControllerImpl(context, model, renderer)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRealStroke(id: Long = 0L): Stroke =
        Stroke(
            path = Path(),
            points = emptyList(),
            color = 0,
            width = 1f,
            style = StrokeType.BALLPOINT,
            bounds = RectF(0f, 0f, 10f, 10f),
            strokeOrder = id,
            zIndex = 0f,
        )

    @Test
    fun `copySelection should copy items when they are found in model`() =
        runTest {
            val item1 = createRealStroke(1L)
            val selectionManager = controller.getSelectionManager()
            selectionManager.select(item1)

            coEvery { model.getItem(1L, any()) } returns item1

            controller.copySelection()
            testDispatcher.scheduler.advanceUntilIdle()

            val slot = slot<Collection<CanvasItem>>()
            verify { ClipboardManager.copy(capture(slot)) }
            assert(slot.captured.size == 1)
            assert(slot.captured.first().order == 1L)
        }

    @Test
    fun `copySelection should trigger Paranoid Path when spatial lookup fails`() =
        runTest {
            val item1 = createRealStroke(1L)
            val selectionManager = controller.getSelectionManager()
            selectionManager.select(item1)

            coEvery { model.getItem(1L, any()) } returns null
            coEvery { model.queryItems(any()) } returns ArrayList()

            val regionManager = mockk<RegionManager>(relaxed = true)
            val region = mockk<RegionData>(relaxed = true)
            val regionId = RegionId(0, 0)

            val regionItems = CopyOnWriteArrayList<CanvasItem>()
            regionItems.add(item1)

            every { region.items } returns regionItems
            every { regionManager.getActiveRegionIds() } returns setOf(regionId)
            coEvery { regionManager.getRegion(regionId) } returns region
            every { model.getRegionManager() } returns regionManager

            controller.copySelection()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { model.getRegionManager() }
            coVerify { regionManager.getRegion(regionId) }

            val slot = slot<Collection<CanvasItem>>()
            verify { ClipboardManager.copy(capture(slot)) }
            assert(slot.captured.size == 1)
            assert(slot.captured.first().order == 1L)
        }

    @Test
    fun `paste should add items from clipboard to model`() =
        runTest {
            val pastedStroke = createRealStroke(0L)

            every { ClipboardManager.hasContent() } returns true
            every { ClipboardManager.getItems() } returns listOf(pastedStroke)

            coEvery { model.addItem(any()) } returns pastedStroke

            controller.paste(50f, 50f)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { model.addItem(any()) }
            coVerify { renderer.updateTilesWithItems(any()) }
            assert(controller.getSelectionManager().getSelectedIds().isNotEmpty())
        }
}
