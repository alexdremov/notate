package com.alexdremov.notate.util

import android.graphics.Canvas
import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.ui.render.CanvasRenderer
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileManagerConcurrencyTest {
    private lateinit var tileManager: TileManager
    private lateinit var mockModel: InfiniteCanvasModel
    private lateinit var mockRenderer: CanvasRenderer
    private lateinit var modelEvents: MutableSharedFlow<InfiniteCanvasModel.ModelEvent>
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockModel = mockk(relaxed = true)
        mockRenderer = mockk(relaxed = true)
        modelEvents = MutableSharedFlow()
        every { mockModel.events } returns modelEvents
        every { mockModel.getRegionManager() } returns null

        tileManager =
            TileManager(
                context = org.robolectric.RuntimeEnvironment.getApplication(),
                canvasModel = mockModel,
                renderer = mockRenderer,
                tileSize = 256,
                scope = testScope,
                dispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        tileManager.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun `ItemsAdded event triggers tile regeneration`() =
        runTest(testDispatcher) {
            // Setup: Initial render to populate cache
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceUntilIdle()
            clearMocks(mockModel, answers = false, recordedCalls = true)

            // Act: Emit ItemsAdded event
            val items =
                listOf(
                    mockk<CanvasItem>(relaxed = true).apply {
                        every { bounds } returns RectF(10f, 10f, 50f, 50f)
                    },
                )

            launch {
                modelEvents.emit(InfiniteCanvasModel.ModelEvent.ItemsAdded(items))
            }
            advanceUntilIdle()

            // Verify: Should trigger regeneration (queryItems)
            io.mockk.coVerify(atLeast = 1) { mockModel.queryItems(any()) }
        }

    @Test
    fun `Rapid updates are throttled`() =
        runTest(testDispatcher) {
            // Setup
            var uiUpdateCount = 0
            tileManager.onTileReady = { uiUpdateCount++ }

            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)

            // Act: Trigger multiple refreshes in quick succession
            tileManager.render(canvas, visibleRect, 1.0f)
            advanceTimeBy(5)
            tileManager.refreshTiles(visibleRect)
            advanceTimeBy(5)
            tileManager.refreshTiles(visibleRect)
            advanceTimeBy(5)

            // Now wait for debounce (approx 33ms)
            advanceTimeBy(50)
            advanceUntilIdle()

            // Verify throttling
            assert(uiUpdateCount in 1..2) { "Expected throttled updates (1 or 2), got $uiUpdateCount" }
        }

    @Test
    fun `Tiles are generated in parallel`() =
        runTest {
            // Use a real multi-threaded dispatcher to verify parallelism
            val parallelDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
            val parallelScope = CoroutineScope(parallelDispatcher + Job())

            try {
                every { mockModel.getRegionManager() } returns null
                val parallelTileManager =
                    TileManager(
                        context = org.robolectric.RuntimeEnvironment.getApplication(),
                        canvasModel = mockModel,
                        renderer = mockRenderer,
                        tileSize = 256,
                        scope = parallelScope,
                        dispatcher = parallelDispatcher,
                    )

                val latch = CountDownLatch(2) // Wait for 2 tiles

                // Mock queryItems to block for 100ms
                io.mockk.coEvery { mockModel.queryItems(any()) } coAnswers {
                    Thread.sleep(100)
                    latch.countDown()
                    ArrayList()
                }

                val visibleRect = RectF(0f, 0f, 512f, 256f) // Covers exactly 2 tiles
                val canvas = mockk<Canvas>(relaxed = true)

                val startTime = System.currentTimeMillis()

                // This call is synchronous on UI thread but launches generation in background
                parallelTileManager.render(canvas, visibleRect, 1.0f)

                // Wait for both tasks to complete
                val finished = latch.await(1000, TimeUnit.MILLISECONDS)
                val duration = System.currentTimeMillis() - startTime

                assert(finished) { "Parallel tasks timed out" }

                // Parallel: ~100ms. Serial: ~200ms.
                // Check if it took less than serial time.
                assert(duration < 180) { "Expected parallel rendering (<180ms), took $duration ms" }

                parallelTileManager.destroy()
            } finally {
                parallelScope.cancel()
                parallelDispatcher.close()
            }
        }

    @Test
    fun `Concurrent renders handle same tile keys gracefully`() =
        runTest(testDispatcher) {
            // setup
            val visibleRect = RectF(0f, 0f, 200f, 200f) // 1 tile
            val canvas = mockk<Canvas>(relaxed = true)
            tileManager.isInteracting = true // Disable neighbor pre-caching to focus on a single tile

            // Act
            repeat(5) {
                tileManager.render(canvas, visibleRect, 1.0f)
            }
            advanceUntilIdle()

            // Verify: Should only have queried model once for that specific tile (since it's in generatingKeys)
            io.mockk.coVerify(exactly = 1) { mockModel.queryItems(any()) }
        }

    @Test
    fun `Cancellation happens when destroy is called`() =
        runTest(testDispatcher) {
            // setup
            val visibleRect = RectF(0f, 0f, 200f, 200f)
            val canvas = mockk<Canvas>(relaxed = true)

            // Mock model to suspend
            io.mockk.coEvery { mockModel.queryItems(any()) } coAnswers {
                delay(1000)
                ArrayList()
            }

            // Act
            tileManager.render(canvas, visibleRect, 1.0f)

            // We advance just a bit to ensure coroutine started.
            advanceTimeBy(100)

            // Destroy
            tileManager.destroy()

            // Now advanceUntilIdle should return immediately because tasks are cancelled
            val timeBefore = testScheduler.currentTime
            advanceUntilIdle()
            val timeAfter = testScheduler.currentTime

            // It shouldn't have waited for the full 1000ms delay
            assert(timeAfter - timeBefore < 500L) { "Tasks were not cancelled by destroy()" }
        }
}
