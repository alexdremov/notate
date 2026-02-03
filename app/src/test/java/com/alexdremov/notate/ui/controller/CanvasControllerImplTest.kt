package com.alexdremov.notate.ui.controller

import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.util.ClipboardManager
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
    fun `commitStroke adds stroke to model and updates renderer`() =
        runTest {
            val stroke = createTestStroke(1L, RectF(0f, 0f, 10f, 10f))
            coEvery { model.addStroke(any()) } returns stroke

            controller.commitStroke(stroke)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { model.addStroke(stroke) }
            coVerify { renderer.updateTilesWithStroke(stroke) }
        }

    @Test
    fun `previewEraser with standard type updates renderer with erasure`() =
        runTest {
            val stroke = createTestStroke(1L, RectF(0f, 0f, 10f, 10f))

            controller.previewEraser(stroke, EraserType.STANDARD)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { model.erase(stroke, EraserType.STANDARD) }
            coVerify { renderer.updateTilesWithErasure(stroke) }
        }

    @Test
    fun `deleteSelection removes items from model and invalidates renderer`() =
        runTest {
            val stroke = createTestStroke(1L, RectF(0f, 0f, 10f, 10f))
            controller.selectItem(stroke)

            controller.deleteSelection()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { model.deleteItemsByIds(any(), setOf(1L), any()) }
            coVerify { renderer.invalidateTiles(any()) }
            coVerify { renderer.setHiddenItems(emptySet()) }
        }

    @Test
    fun `commitMoveSelection triggers large selection path when many items selected`() =
        runTest {
            // Arrange
            val largeCount = 700
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
            val expectedNewBounds = RectF(100f, 100f, 110f, 110f)
            coVerify { renderer.invalidateTiles(expectedNewBounds) }
        }

    private fun createTestStroke(
        order: Long,
        bounds: RectF,
    ): Stroke =
        Stroke(
            path = Path(),
            points = emptyList(),
            color = 0,
            width = 2f,
            style = StrokeType.FINELINER,
            bounds = bounds,
            strokeOrder = order,
        )
}
