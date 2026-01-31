package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.data.note.TouchPoint
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.google.common.truth.Truth.assertThat
import java.util.ArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InfiniteCanvasModelTest {

    private lateinit var model: InfiniteCanvasModel
    private lateinit var regionManager: RegionManager

    @Before
    fun setup() {
        model = InfiniteCanvasModel()
        regionManager = mockk(relaxed = true)
        
        // Mock getContentBounds to return empty initially
        every { regionManager.getContentBounds() } returns RectF()
        every { regionManager.regionSize } returns 1000f
        
        // Explicitly mock void suspend functions to ensure stability
        coEvery { regionManager.addItem(any()) } just Runs
        coEvery { regionManager.removeItems(any()) } just Runs
        coEvery { regionManager.clear() } just Runs
        
        mockkObject(StrokeGeometry)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createTestStroke(order: Long = 0, bounds: RectF = RectF(10f, 10f, 20f, 20f)): Stroke {
        val points = listOf(TouchPoint(bounds.left, bounds.top, 0.5f, 5f, 100L), TouchPoint(bounds.right, bounds.bottom, 0.5f, 5f, 110L))
        return Stroke(
            path = Path(),
            points = points,
            color = 0,
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = bounds,
            strokeOrder = order
        )
    }

    @Test
    fun `initializeSession should set regionManager and initial bounds`() = runTest {
        val initialBounds = RectF(0f, 0f, 100f, 100f)
        every { regionManager.getContentBounds() } returns initialBounds
        
        model.initializeSession(regionManager)
        
        assertThat(model.getContentBounds()).isEqualTo(initialBounds)
        assertThat(model.getRegionManager()).isEqualTo(regionManager)
    }

    @Test
    fun `addItem should update content bounds`() = runTest {
        model.initializeSession(regionManager)
        
        val stroke = createTestStroke()
        val addedItem = model.addItem(stroke)
        
        assertThat(addedItem).isNotNull()
        assertThat(addedItem).isInstanceOf(Stroke::class.java)
        
        val currentBounds = model.getContentBounds()
        assertThat(currentBounds.isEmpty).isFalse()
        
        coVerify { regionManager.addItem(any()) }
    }

    @Test
    fun `undo and redo should revert and re-apply actions`() = runTest {
        model.initializeSession(regionManager)
        val stroke = createTestStroke()
        
        model.addItem(stroke)
        coVerify(exactly = 1) { regionManager.addItem(any()) }
        
        model.undo()
        coVerify(exactly = 1) { regionManager.removeItems(any()) }
        
        model.redo()
        coVerify(exactly = 2) { regionManager.addItem(any()) }
    }

    @Test
    fun `clear should reset state`() = runTest {
        model.initializeSession(regionManager)
        val stroke = createTestStroke()
        model.addItem(stroke)
        
        model.clear()
        
        assertThat(model.getContentBounds().isEmpty).isTrue()
        coVerify { regionManager.clear() }
    }

    @Test
    fun `hitTest should query regionManager and return item`() = runTest {
        model.initializeSession(regionManager)
        val stroke = createTestStroke()
        
        // Use real RegionData and Quadtree
        val regionId = com.alexdremov.notate.data.region.RegionId(0, 0)
        val region = com.alexdremov.notate.data.region.RegionData(regionId)
        val quadtree = com.alexdremov.notate.util.Quadtree(0, RectF(0f, 0f, 100f, 100f))
        region.quadtree = quadtree
        quadtree.insert(stroke)

        coEvery { regionManager.getRegionIdsInRect(any()) } returns listOf(regionId)
        every { regionManager.getRegionReadOnly(any()) } returns region
        
        val hit = model.hitTest(15f, 15f)
        assertThat(hit).isEqualTo(stroke)
    }

    @Test
    fun `erase with STROKE type should remove intersected items`() = runBlocking {
        model.initializeSession(regionManager)
        val stroke = createTestStroke(order = 1)
        
        // Use real RegionData and Quadtree
        val regionId = com.alexdremov.notate.data.region.RegionId(0, 0)
        val region = com.alexdremov.notate.data.region.RegionData(regionId)
        val quadtree = com.alexdremov.notate.util.Quadtree(0, RectF(0f, 0f, 100f, 100f))
        region.quadtree = quadtree
        quadtree.insert(stroke)

        coEvery { regionManager.getRegionsInRect(any()) } returns listOf(region)
        
        every { StrokeGeometry.strokeIntersects(any(), any()) } returns true
        
        val eraserStroke = createTestStroke(bounds = RectF(10f, 10f, 20f, 20f))
        model.erase(eraserStroke, EraserType.STROKE)
        
        coVerify { regionManager.removeItems(any()) }
    }

    @Test
    fun `erase with LASSO type should remove contained items`() = runBlocking {
        model.initializeSession(regionManager)
        val stroke = createTestStroke(order = 1, bounds = RectF(12f, 12f, 18f, 18f))
        
        // Use real RegionData and Quadtree
        val regionId = com.alexdremov.notate.data.region.RegionId(0, 0)
        val region = com.alexdremov.notate.data.region.RegionData(regionId)
        val quadtree = com.alexdremov.notate.util.Quadtree(0, RectF(0f, 0f, 100f, 100f))
        region.quadtree = quadtree
        quadtree.insert(stroke)

        coEvery { regionManager.getRegionsInRect(any()) } returns listOf(region)
        
        every { StrokeGeometry.isPointInPolygon(any(), any(), any()) } returns true
        
        val eraserStroke = createTestStroke(bounds = RectF(10f, 10f, 20f, 20f))
        model.erase(eraserStroke, EraserType.LASSO)
        
        coVerify { regionManager.removeItems(any()) }
    }
    
    @Test
    fun `deleteItems should remove items via regionManager`() = runTest {
        model.initializeSession(regionManager)
        val stroke = createTestStroke(order = 100)
        
        model.deleteItems(listOf(stroke))
        
        coVerify { regionManager.removeItems(match { it.size == 1 && it[0] == stroke }) }
        
        // Test Undo
        model.undo()
        coVerify { regionManager.addItem(any()) }
    }
    
    @Test
    fun `replaceItems should remove old and add new items`() = runTest {
        model.initializeSession(regionManager)
        val oldStroke = createTestStroke(order = 100)
        val newStroke = createTestStroke(order = 101)
        
        model.replaceItems(listOf(oldStroke), listOf(newStroke))
        
        coVerify { regionManager.removeItems(match { it.size == 1 && it[0] == oldStroke }) }
        coVerify { regionManager.addItem(any()) }
        
        // Undo
        model.undo()
        // Should add old back
        coVerify(exactly = 2) { regionManager.addItem(any()) } // 1 for replace call, 1 for undo (adding old back)
    }
    
    @Test
    fun `toCanvasData should return serialized data`() = runTest {
        model.initializeSession(regionManager)
        model.addItem(createTestStroke())
        
        val data = model.toCanvasData()
        
        assertThat(data).isNotNull()
        assertThat(data.regionSize).isEqualTo(1000f)
    }
}
