package com.alexdremov.notate.ui.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Looper
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.model.InfiniteCanvasModel
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MinimapMemoryTest {
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRegionManager: RegionManager
    private lateinit var mockRenderer: CanvasRenderer
    private lateinit var mockView: android.view.View

    @Before
    fun setup() {
        mockModel = mockk(relaxed = true)
        mockRegionManager = mockk(relaxed = true)
        mockRenderer = mockk(relaxed = true)
        mockView = mockk(relaxed = true)

        every { mockModel.getRegionManager() } returns mockRegionManager
        every { mockModel.canvasType } returns CanvasType.INFINITE
        every { mockModel.getContentBounds() } returns RectF(0f, 0f, 1000f, 1000f)
        every { mockRegionManager.regionSize } returns 1000f
        every { mockView.context } returns RuntimeEnvironment.getApplication()
        every { mockView.width } returns 1080
        every { mockView.height } returns 1920
    }

    @Test
    fun testRegionAwareRenderingIsInvoked() {
        // Mock the region manager to return some regions
        val regionId = RegionId(0, 0)
        every { mockRegionManager.getRegionIdsInRect(any()) } returns listOf(regionId)
        coEvery { mockRegionManager.getRegionThumbnail(any(), any()) } returns mockk(relaxed = true)

        // Create minimap drawer and show it
        val minimapDrawer = MinimapDrawer(mockView, mockModel, mockRenderer) {}
        minimapDrawer.show()

        // Trigger draw which should trigger thumbnail regeneration
        val mockCanvas = mockk<Canvas>(relaxed = true)
        minimapDrawer.draw(mockCanvas, Matrix(), Matrix(), 1.0f, 1080, 1920)

        // Advance looper to run the coroutine launched by MinimapDrawer
        shadowOf(Looper.getMainLooper()).idle()

        // Verify that regionManager was queried for regions in the context
        verify(atLeast = 1) { mockRegionManager.getRegionIdsInRect(any()) }
    }
}
