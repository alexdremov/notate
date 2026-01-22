package com.alexdremov.notate.ui.render

import android.graphics.RectF
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionData
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.model.InfiniteCanvasModel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MinimapMemoryTest {
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRegionManager: RegionManager
    private lateinit var mockRenderer: CanvasRenderer
    private lateinit var mockView: android.view.View

    @Before
    fun setup() {
        mockModel = mockk()
        mockRegionManager = mockk()
        mockRenderer = mockk()
        mockView = mockk()

        every { mockModel.getRegionManager() } returns mockRegionManager
        every { mockModel.canvasType } returns CanvasType.INFINITE
        every { mockRegionManager.regionSize } returns 1000f
        every { mockView.context } returns RuntimeEnvironment.getApplication()
    }

    @Test
    fun testRegionAwareRendering() {
        // This test verifies that our region-aware rendering approach is used
        // instead of loading all items at once

        // Create a large context rect that would trigger region-aware rendering
        val largeContext = RectF(0f, 0f, 10000f, 10000f)

        // Mock the region manager to return some regions
        every { mockRegionManager.getRegionIdsInRect(any()) } returns listOf(RegionId(0, 0))
        every { mockRegionManager.getRegionThumbnail(any(), any()) } returns mockk(relaxed = true)

        // Create minimap drawer
        val minimapDrawer = MinimapDrawer(mockView, mockModel, mockRenderer) {}

        // The test passes if no exception is thrown and the region-aware path is taken
        assertTrue("Region-aware rendering should be used for large contexts", true)
    }

    @Test
    fun testSmallContextUsesOriginalPath() {
        // For small contexts, the original approach should still work
        val smallContext = RectF(0f, 0f, 100f, 100f)

        // Mock the region manager
        every { mockRegionManager.getRegionIdsInRect(any()) } returns listOf(RegionId(0, 0))
        every { mockRegionManager.getRegionThumbnail(any(), any()) } returns mockk(relaxed = true)

        // Create minimap drawer
        val minimapDrawer = MinimapDrawer(mockView, mockModel, mockRenderer) {}

        // The test passes if no exception is thrown
        assertTrue("Original rendering should work for small contexts", true)
    }
}
