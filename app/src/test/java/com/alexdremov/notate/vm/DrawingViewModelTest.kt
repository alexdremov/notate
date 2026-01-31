package com.alexdremov.notate.vm

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasRepository
import com.alexdremov.notate.data.CanvasSession
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DrawingViewModelTest {
    private lateinit var viewModel: DrawingViewModel
    private lateinit var application: Application
    private lateinit var repository: CanvasRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)

        // PreferencesManager is an object, use mockkObject
        mockkObject(PreferencesManager)
        every { PreferencesManager.getToolbarItems(any()) } returns listOf(
            ToolbarItem.Pen(PenTool("p1", "Pen", ToolType.PEN))
        )
        every { PreferencesManager.isCollapsibleToolbarEnabled(any()) } returns false
        every { PreferencesManager.getToolbarCollapseTimeout(any()) } returns 3000L
        every { PreferencesManager.saveToolbarItems(any(), any()) } just Runs

        viewModel = DrawingViewModel(application, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadCanvasSession should update currentSession flow on success`() =
        runTest {
            val path = "test/path.notate"
            val session = mockk<CanvasSession>(relaxed = true)
            val metadata = CanvasData(version = 3)

            every { session.metadata } returns metadata
            every { session.isClosed() } returns false
            coEvery { repository.openCanvasSession(path) } returns session

            viewModel.loadCanvasSession(path)
            advanceUntilIdle()

            assert(viewModel.currentSession.value == session)
        }

    @Test
    fun `selectTool updates activeTool flow`() = runTest {
        val penTool = PenTool("p1", "Pen", ToolType.PEN)
        viewModel.selectTool("p1")
        assert(viewModel.activeTool.value.id == "p1")
        assert(viewModel.activeToolId.value == "p1")
    }

    @Test
    fun `updateTool modifies existing tool in toolbarItems`() = runTest {
        val updatedTool = PenTool("p1", "Updated Pen", ToolType.PEN, color = Color.RED)
        viewModel.updateTool(updatedTool)
        
        val item = viewModel.toolbarItems.value.find { it.id == "p1" } as ToolbarItem.Pen
        assert(item.penTool.name == "Updated Pen")
        assert(item.penTool.color == Color.RED)
    }

    @Test
    fun `removeToolbarItem updates flow and persists`() = runTest {
        val item = viewModel.toolbarItems.value[0]
        viewModel.removeToolbarItem(item)
        
        assert(!viewModel.toolbarItems.value.contains(item))
        verify { PreferencesManager.saveToolbarItems(any(), any()) }
    }

    @Test
    fun `addToolbarItem avoids duplicates for non-pen items`() = runTest {
        val action = ToolbarItem.Action(com.alexdremov.notate.model.ActionType.UNDO)
        viewModel.addToolbarItem(action)
        val initialSize = viewModel.toolbarItems.value.size
        
        viewModel.addToolbarItem(action)
        assert(viewModel.toolbarItems.value.size == initialSize)
    }

    @Test
    fun `setEditMode should update isEditMode flow and drawing state`() =
        runTest {
            viewModel.setEditMode(true)
            assert(viewModel.isEditMode.value)
            assert(!viewModel.isDrawingEnabled.value)

            viewModel.setEditMode(false)
            assert(!viewModel.isEditMode.value)
            assert(viewModel.isDrawingEnabled.value)
        }
}
