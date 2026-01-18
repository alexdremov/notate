package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileManagerTest {
    private lateinit var tileManager: TileManager
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRenderer: CanvasRenderer
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        mockModel = mockk(relaxed = true)
        mockRenderer = mockk(relaxed = true)
        // Ensure events flow is mocked
        every { mockModel.events } returns kotlinx.coroutines.flow.MutableSharedFlow()

        tileManager =
            TileManager(
                canvasModel = mockModel,
                renderer = mockRenderer,
                tileSize = 256, // Smaller tile size for easier math if needed
                scope = testScope,
                dispatcher = testDispatcher,
            )
    }

    @Test
    fun `render queues generation for visible tiles`() =
        runTest(testDispatcher) {
            // Setup
            val visibleRect = RectF(0f, 0f, 500f, 500f) // Should cover roughly 2x2 tiles (0,0) to (1,1) at scale 1.0 (LOD 0)
            val canvas = mockk<Canvas>(relaxed = true)
            val scale = 1.0f

            // Mock querying strokes
            every { mockModel.queryItems(any()) } returns ArrayList<CanvasItem>()

            // Act
            tileManager.render(canvas, visibleRect, scale)
            advanceUntilIdle()

            // Verify
            verify(atLeast = 4) { mockModel.queryItems(any()) }
        }

    @Test
    fun `refreshTiles triggers re-queues tasks`() =
        runTest(testDispatcher) {
            // Setup initial render to populate cache
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            // Capture initial query count
            io.mockk.clearMocks(mockModel, answers = false, recordedCalls = true, childMocks = false)

            // Act: Refresh specific area
            tileManager.refreshTiles(visibleRect)
            advanceUntilIdle()

            // Verify: Should trigger regeneration (queryItems) again
            verify(atLeast = 1) { mockModel.queryItems(any()) }
        }

    @Test
    fun `invalidateTiles uses double buffering`() =
        runTest(testDispatcher) {
            // Setup
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)

            // First pass: triggers generation
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            // Second pass: should draw from cache
            tileManager.render(canvas, visibleRect, 1.0f)

            // Verify tile is drawn
            verify { canvas.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }

            // Act: Invalidate (refresh)
            tileManager.refreshTiles(visibleRect)

            // Even after starting refresh, render should still draw SOMETHING (the old bitmap)
            // because we haven't advanced time/idle for the NEW generation to complete.
            val canvas2 = mockk<Canvas>(relaxed = true)
            tileManager.render(canvas2, visibleRect, 1.0f)
            verify { canvas2.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }
        }

    @Test
    fun `updateTilesWithStroke updates cache in-place`() =
        runTest(testDispatcher) {
            // Setup
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            val stroke = mockk<Stroke>(relaxed = true)
            every { stroke.bounds } returns RectF(10f, 10f, 50f, 50f)
            every { stroke.style } returns com.alexdremov.notate.model.StrokeType.FOUNTAIN

            // Act
            tileManager.updateTilesWithItem(stroke)

            // Verify
            // Should call renderer to draw stroke onto the cached bitmap
            verify { mockRenderer.drawItemToCanvas(any(), stroke, any()) }
        }

    @Test
    fun `forceRefreshVisibleTiles triggers generation`() =
        runTest(testDispatcher) {
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            tileManager.forceRefreshVisibleTiles(visibleRect, 1.0f)
            advanceUntilIdle()

            verify(atLeast = 1) { mockModel.queryItems(any()) }
        }

    @Test
    fun `clear empties the cache`() =
        runTest(testDispatcher) {
            // Setup
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            // Act
            tileManager.clear()

            // Render again - should trigger generation because cache is empty
            io.mockk.clearMocks(mockModel, answers = false, recordedCalls = true, childMocks = false)
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            verify(atLeast = 1) { mockModel.queryItems(any()) }
        }

    @Test
    fun `handles generation errors gracefully`() =
        runTest(testDispatcher) {
            // Setup
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)

            // Force error
            every { mockModel.queryItems(any()) } throws RuntimeException("Generation failed")

            // Act
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()

            // Verify that it tried to generate
            verify { mockModel.queryItems(any()) }
        }
}
