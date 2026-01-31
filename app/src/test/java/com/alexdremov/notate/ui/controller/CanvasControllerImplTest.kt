package com.alexdremov.notate.ui.controller

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasControllerImplTest {
    private lateinit var context: Context
    private lateinit var model: InfiniteCanvasModel
    private lateinit var renderer: CanvasRenderer
    private lateinit var controller: CanvasControllerImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        model = mockk(relaxed = true)
        renderer = mockk(relaxed = true)

        every { context.cacheDir } returns File(".")

        controller = CanvasControllerImpl(context, model, renderer)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `commitMoveSelection triggers large selection path when many items selected`() =
        runTest {
            // Arrange
            val largeCount = 130
            val items =
                (0 until largeCount).map { i ->
                    Stroke(
                        path = android.graphics.Path(),
                        points = emptyList(),
                        color = 0,
                        width = 1f,
                        style = com.alexdremov.notate.model.StrokeType.BALLPOINT,
                        bounds = RectF(0f, 0f, 10f, 10f),
                        strokeOrder = i.toLong(),
                    )
                }
            val ids = items.map { it.strokeOrder }.toSet()

            // Mock model behavior
            coEvery { model.stashItems(any(), any(), any()) } returns items.size
            // Return a shifted rect to verify we capture the new bounds
            val newBounds = RectF(100f, 100f, 110f, 110f)
            coEvery { model.unstashItems(any(), any(), any()) } returns Pair(ids, newBounds)
            coEvery { model.getItem(any(), any()) } returns items[0] // Simplify fetchSelectedItems

            // Act
            controller.selectItems(items)
            controller.moveSelection(100f, 100f) // Move to trigger transform
            testDispatcher.scheduler.advanceUntilIdle()

            controller.commitMoveSelection(shouldReselect = false)
            testDispatcher.scheduler.advanceUntilIdle()

            // Assert
            // 1. Verify stashItems was called (Large path active)
            coVerify { model.stashItems(any(), any(), any()) }

            // 2. Verify unstashItems was called
            coVerify { model.unstashItems(any(), any(), any()) }

            // 3. Verify renderer invalidation
            // We expect it to be called for BOTH original and new bounds.
            // Original bounds: (0, 0, 10, 10) -> Union of all items
            // New bounds: (100, 100, 110, 110) -> Returned by unstashItems

            val originalBounds = RectF(0f, 0f, 10f, 10f)
            val expectedNewBounds = RectF(100f, 100f, 110f, 110f)

            coVerify { renderer.invalidateTiles(originalBounds) }
            coVerify { renderer.invalidateTiles(expectedNewBounds) }
        }
}
