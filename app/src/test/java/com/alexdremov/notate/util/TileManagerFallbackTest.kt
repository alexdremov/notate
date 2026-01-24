package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.alexdremov.notate.model.InfiniteCanvasModel
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
class TileManagerFallbackTest {
    private lateinit var tileManager: TileManager
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRenderer: CanvasRenderer
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val TILE_SIZE = 256

    @Before
    fun setup() {
        mockModel = mockk(relaxed = true)
        mockRenderer = mockk(relaxed = true)
        every { mockModel.events } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { mockModel.getRegionManager() } returns null
        // Mock queryItems to return empty list so generation produces blank tiles quickly
        io.mockk.coEvery { mockModel.queryItems(any()) } returns ArrayList()

        tileManager =
            TileManager(
                context = org.robolectric.RuntimeEnvironment.getApplication(),
                canvasModel = mockModel,
                renderer = mockRenderer,
                tileSize = TILE_SIZE,
                scope = testScope,
                dispatcher = testDispatcher,
            )
    }

    @org.junit.After
    fun tearDown() {
        tileManager.destroy()
    }

    @Test
    fun `fallback to parent works for negative coordinates`() =
        runTest(testDispatcher) {
            // Scenario:
            // We are at Level 0 (Scale 1.0). TILE_SIZE = 256.
            // We want to test fallback for Tile (-2, 0) at Level 0.
            // Tile (-2, 0) range: [-512, -256].
            // Parent is Level 1. Parent Tile Size = 512.
            // Parent Tile (-1, 0) range: [-512, 0].
            // So Parent (-1, 0) COVERS Child (-2, 0).
            //
            // 1. Force generate Parent Tile (-1, 0) at Level 1.
            // We do this by asking to render a view that implies Level 1, or just hacking the internal cache if possible?
            // TileManager doesn't expose cache put. We can simulate it by rendering at Zoom Level 1 first.

            val canvas = mockk<Canvas>(relaxed = true)

            // Step 1: Populate Cache with Parent Tile L1 (-1, 0)
            // Scale 0.5f implies Level 1.
            // Visible Rect: [-500, 0, -100, 100]. Should cover L1 (-1, 0).
            val parentViewRect = RectF(-400f, 0f, -100f, 100f)
            tileManager.render(canvas, parentViewRect, 0.5f)
            advanceUntilIdle() // Allow generation to complete and populate cache

            // Verify parent generation was requested (we assume it succeeded)
            // We can't easily peek into cache, but if render() works later without generating, or if fallback is used, we know.

            // Step 2: Render Child Tile L0 (-2, 0) at Scale 1.0
            // This tile should NOT be in cache.
            // But Parent L1 (-1, 0) IS in cache.
            // We expect drawFallbackParent to find it and draw it.

            val childViewRect = RectF(-500f, 0f, -300f, 100f) // Inside L0 (-2, 0) -> [-512, -256]

            // Reset mocks to track new draw calls
            io.mockk.clearMocks(canvas, answers = false, recordedCalls = true, childMocks = false)

            // Act
            tileManager.render(canvas, childViewRect, 1.0f)

            // Note: We do NOT advanceUntilIdle(). We want to see if it drew IMMEDIATELY using fallback.
            // If it waits for generation, it means fallback failed.

            // Verify
            // drawBitmap should be called.
            // If fallback failed, it would queue generation and return (drawing nothing initially).
            verify(atLeast = 1) { canvas.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }

            // Also verify clipping was used (fallback parent always clips)
            verify(atLeast = 1) { canvas.clipRect(any<RectF>()) }
        }

    @Test
    fun `fallback to parent works for boundary negative coordinates`() =
        runTest(testDispatcher) {
            // Test specifically the -1 case which was less buggy but worth checking.
            // Child L0 (-1, 0) -> [-256, 0].
            // Parent L1 (-1, 0) -> [-512, 0].

            val canvas = mockk<Canvas>(relaxed = true)

            // 1. Populate Parent L1 (-1, 0)
            val parentViewRect = RectF(-200f, 0f, -50f, 50f)
            tileManager.render(canvas, parentViewRect, 0.5f)
            advanceUntilIdle()

            // 2. Render Child L0 (-1, 0)
            val childViewRect = RectF(-200f, 0f, -50f, 50f)
            io.mockk.clearMocks(canvas, answers = false, recordedCalls = true, childMocks = false)

            tileManager.render(canvas, childViewRect, 1.0f)

            verify(atLeast = 1) { canvas.drawBitmap(any<Bitmap>(), any(), any<RectF>(), any()) }
            verify(atLeast = 1) { canvas.clipRect(any<RectF>()) }
        }
}
