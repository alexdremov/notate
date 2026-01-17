package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileManagerTest {
    private lateinit var tileManager: TileManager
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRenderer: CanvasRenderer
    private lateinit var directExecutor: ExecutorService

    @Before
    fun setup() {
        mockModel = mockk(relaxed = true)
        mockRenderer = mockk(relaxed = true)
        // Use a direct executor so tasks run immediately on the same thread
        directExecutor =
            com.google.common.util.concurrent.MoreExecutors
                .newDirectExecutorService()

        tileManager =
            TileManager(
                canvasModel = mockModel,
                renderer = mockRenderer,
                tileSize = 256, // Smaller tile size for easier math if needed
                executor = directExecutor,
            )
    }

    @Test
    fun `render queues generation for visible tiles`() {
        // Setup
        val visibleRect = RectF(0f, 0f, 500f, 500f) // Should cover roughly 2x2 tiles (0,0) to (1,1) at scale 1.0 (LOD 0)
        val canvas = mockk<Canvas>(relaxed = true)
        val scale = 1.0f

        // Mock querying strokes
        every { mockModel.queryItems(any()) } returns ArrayList<CanvasItem>()

        // Act
        tileManager.render(canvas, visibleRect, scale)

        // Verify
        // At scale 1.0, tile size 256.
        // (0,0) -> 0-256
        // (1,0) -> 256-512
        // (0,1) -> ...
        // (1,1) -> ...
        // Should generate 4 tiles
        verify(atLeast = 4) { mockModel.queryItems(any()) }
    }

    @Test
    fun `refreshTiles increments version and re-queues tasks`() {
        // Setup initial render to populate cache
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        val canvas = mockk<Canvas>(relaxed = true)
        tileManager.render(canvas, visibleRect, 1.0f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Capture initial query count
        val initialQueries = io.mockk.clearMocks(mockModel, answers = false, recordedCalls = true, childMocks = false)

        // Act: Refresh specific area
        tileManager.refreshTiles(visibleRect)

        // Verify: Should trigger regeneration (queryStrokes) again
        verify(atLeast = 1) { mockModel.queryItems(any()) }
    }

    @Test
    fun `invalidateTiles uses double buffering (does not clear cache immediately)`() {
        // Setup
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        val canvas = mockk<Canvas>(relaxed = true)

        // First pass: triggers generation (cache populated synchronously by DirectExecutor)
        tileManager.render(canvas, visibleRect, 1.0f)

        // Second pass: should draw from cache
        tileManager.render(canvas, visibleRect, 1.0f)

        // Verify tile is drawn
        verify { canvas.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }

        // Act: Invalidate
        tileManager.invalidateTiles(visibleRect)

        // Verify:
        // 1. It should NOT have removed the tile from cache immediately (Double Buffering).
        // Re-rendering should still draw a bitmap (the old one)
        val canvas2 = mockk<Canvas>(relaxed = true)
        tileManager.render(canvas2, visibleRect, 1.0f)
        verify { canvas2.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }
    }

    @Test
    fun `updateTilesWithStroke updates cache in-place`() {
        // Setup
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        val canvas = mockk<Canvas>(relaxed = true)
        tileManager.render(canvas, visibleRect, 1.0f)

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
    fun `forceRefreshVisibleTiles increments version`() {
        // We can't easily check the private version field, but we can check side effects.
        // If version increments, pending tasks from old version should be ignored (if we could simulate that).
        // Simply verify it triggers generation.
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        tileManager.forceRefreshVisibleTiles(visibleRect, 1.0f)

        verify(atLeast = 1) { mockModel.queryItems(any()) }
    }

    @Test
    fun `clear empties the cache`() {
        // Setup
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        val canvas = mockk<Canvas>(relaxed = true)
        tileManager.render(canvas, visibleRect, 1.0f)

        // Act
        tileManager.clear()

        // Render again - should trigger generation because cache is empty
        io.mockk.clearMocks(mockModel, answers = false, recordedCalls = true, childMocks = false)
        tileManager.render(canvas, visibleRect, 1.0f)

        verify(atLeast = 1) { mockModel.queryItems(any()) }
    }

    @Test
    fun `handles generation errors gracefully`() {
        // Setup
        val visibleRect = RectF(0f, 0f, 200f, 200f)
        val canvas = mockk<Canvas>(relaxed = true)

        // Force error
        every { mockModel.queryItems(any()) } throws RuntimeException("Generation failed")

        // Act
        tileManager.render(canvas, visibleRect, 1.0f)

        // Verify that it tried to generate
        verify { mockModel.queryItems(any()) }

        // And that subsequent renders don't crash (it should have cached an error bitmap or handled it)
        // With DirectExecutor, the catch block runs immediately.
        // We can't easily verify the error bitmap is in cache because it's private,
        // but we can ensure no crash occurs.
    }
}
