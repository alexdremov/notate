package com.alexdremov.notate.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasRepository
import com.alexdremov.notate.data.CanvasSession
import com.alexdremov.notate.data.PreferencesManager
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
        every { PreferencesManager.getToolbarItems(any()) } returns emptyList()
        every { PreferencesManager.isCollapsibleToolbarEnabled(any()) } returns false
        every { PreferencesManager.getToolbarCollapseTimeout(any()) } returns 3000L

        viewModel = DrawingViewModel(application, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadCanvasSession should update currentSession flow on success`() = runTest {
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
    fun `saveCanvasSession should call repository save`() = runTest {
        val path = "test/path.notate"
        val session = mockk<CanvasSession>(relaxed = true)
        val metadata = CanvasData(version = 3)
        
        coEvery { repository.openCanvasSession(path) } returns session
        viewModel.loadCanvasSession(path)
        advanceUntilIdle()

        val saveResult = CanvasRepository.SaveResult(path, 123L, 456L)
        coEvery { repository.saveCanvasSession(path, session, commitToZip = true) } returns saveResult
        
        viewModel.saveCanvasSession(path, metadata, commit = true)
        advanceUntilIdle()

        coVerify { repository.saveCanvasSession(path, session, commitToZip = true) }
        verify { session.updateMetadata(metadata) }
        verify { session.updateOrigin(123L, 456L) }
    }

    @Test
    fun `closeSession should clear currentSession and call saveAndClose`() = runTest {
        val path = "test/path.notate"
        val session = mockk<CanvasSession>(relaxed = true)
        
        coEvery { repository.openCanvasSession(path) } returns session
        viewModel.loadCanvasSession(path)
        advanceUntilIdle()

        coEvery { repository.saveAndCloseSession(any(), any()) } returns Unit
        
        viewModel.closeSession(path, null)
        advanceUntilIdle()

        assert(viewModel.currentSession.value == null)
        coVerify { repository.saveAndCloseSession(any(), any()) }
    }

    @Test
    fun `setEditMode should update isEditMode flow and drawing state`() = runTest {
        viewModel.setEditMode(true)
        assert(viewModel.isEditMode.value)
        assert(!viewModel.isDrawingEnabled.value)

        viewModel.setEditMode(false)
        assert(!viewModel.isEditMode.value)
        assert(viewModel.isDrawingEnabled.value)
    }

    @Test
    fun `addToolbarItem should update toolbarItems flow`() = runTest {
        val item = com.alexdremov.notate.model.ToolbarItem.Pen(
            com.alexdremov.notate.model.PenTool(id = "test", name = "Test", type = com.alexdremov.notate.model.ToolType.PEN)
        )
        
        every { PreferencesManager.saveToolbarItems(any(), any()) } just Runs
        
        viewModel.addToolbarItem(item)
        
        assert(viewModel.toolbarItems.value.contains(item))
        verify { PreferencesManager.saveToolbarItems(any(), any()) }
    }
}