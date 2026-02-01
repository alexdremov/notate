package com.alexdremov.notate.ui.controller.input

import android.content.Context
import android.graphics.Matrix
import android.view.View
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.controller.CanvasController
import com.alexdremov.notate.ui.input.PenInputHandler
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PenInputHandlerTest {
    @Test
    fun `race condition test - ensure batched points are not lost if processed during stroke commit`() =
        runTest {
            // Arrange
            val controller = mockk<CanvasController>(relaxed = true)
            val view = mockk<View>(relaxed = true)
            val matrix = Matrix()
            val inverseMatrix = Matrix()
            val context = mockk<Context>(relaxed = true)
            every { view.context } returns context

            // Mock DwellDetector dependency chain (simplified via relaxed mocks in constructor if possible,
            // but PenInputHandler instantiates DwellDetector internally which uses context.
            // Roboelectric context should handle basic resource lookups if needed)

            val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

            val handler =
                PenInputHandler(
                    controller = controller,
                    view = view,
                    scope = testScope,
                    matrix = matrix,
                    inverseMatrix = inverseMatrix,
                    onStrokeStarted = {},
                    onStrokeFinished = {},
                )

            // Capture the stroke passed to commitStroke
            val strokeSlot = slot<Stroke>()
            coEvery { controller.commitStroke(capture(strokeSlot)) } just Runs
            every { controller.getItemAtSync(any(), any()) } returns null

            // Start
            handler.onBeginRawDrawing(false, TouchPoint(10f, 10f, 0.5f, 1.0f, 0, 0, 1000))

            // Move
            handler.onRawDrawingTouchPointMoveReceived(TouchPoint(20f, 20f, 0.5f, 1.0f, 0, 0, 1010))

            // Batch
            val batch =
                listOf(
                    TouchPoint(30f, 30f, 0.5f, 1.0f, 0, 0, 1020),
                    TouchPoint(40f, 40f, 0.5f, 1.0f, 0, 0, 1030),
                )
            val touchPointList = TouchPointList()
            // Accessing the internal list via reflection or helper if needed,
            // but TouchPointList usually exposes the list.
            // Wait, TouchPointList in Onyx SDK might be tricky to mock if final/native.
            // Assuming we can instantiate it or mock it.
            // If TouchPointList is final/native, we might need a wrapper or use reflection to set 'points'.
            // Let's assume standard constructor or settable field for now based on typical Android SDKs.
            // Actually, looking at the code `val points = touchPointList.points`, it seems accessible.

            // We need to mock TouchPointList behavior since we can't easily instantiate SDK data classes sometimes.
            val mockList = mockk<TouchPointList>()
            every { mockList.points } returns ArrayList(batch)

            handler.onRawDrawingTouchPointListReceived(mockList)

            // End
            handler.onEndRawDrawing(false, TouchPoint(50f, 50f, 0.5f, 1.0f, 0, 0, 1040))

            // Assert
            coVerify(exactly = 1) { controller.commitStroke(any()) }

            val capturedPoints = strokeSlot.captured.points

            // 1 (Start) + 1 (Move) + 2 (Batch) + 1 (Finish) = 5 points
            assert(capturedPoints.size >= 5) { "Expected at least 5 points, got ${capturedPoints.size}" }
            assert(capturedPoints.any { it.x == 30f && it.timestamp == 1020L })
            assert(capturedPoints.any { it.x == 40f && it.timestamp == 1030L })
        }

    @Test
    fun `deduplication test - ensure redundant points from driver are ignored`() =
        runTest {
            val controller = mockk<CanvasController>(relaxed = true)
            val view = mockk<View>(relaxed = true)
            val matrix = Matrix()
            val inverseMatrix = Matrix()
            val context = mockk<Context>(relaxed = true)
            every { view.context } returns context

            val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

            val handler =
                PenInputHandler(
                    controller = controller,
                    view = view,
                    scope = testScope,
                    matrix = matrix,
                    inverseMatrix = inverseMatrix,
                    onStrokeStarted = {},
                    onStrokeFinished = {},
                )

            val strokeSlot = slot<Stroke>()
            coEvery { controller.commitStroke(capture(strokeSlot)) } just Runs
            every { controller.getItemAtSync(any(), any()) } returns null

            // 1. Start
            handler.onBeginRawDrawing(false, TouchPoint(10f, 10f, 0.5f, 1.0f, 0, 0, 1000))

            // 2. Move (Individual)
            handler.onRawDrawingTouchPointMoveReceived(TouchPoint(20f, 20f, 0.5f, 1.0f, 0, 0, 1010))

            // 3. Batch (Contains overlap)
            val batch =
                listOf(
                    TouchPoint(20f, 20f, 0.5f, 1.0f, 0, 0, 1010), // Duplicate
                    TouchPoint(30f, 30f, 0.5f, 1.0f, 0, 0, 1020), // New
                )
            val mockList = mockk<TouchPointList>()
            every { mockList.points } returns ArrayList(batch)

            handler.onRawDrawingTouchPointListReceived(mockList)

            // 4. Finish
            handler.onEndRawDrawing(false, TouchPoint(40f, 40f, 0.5f, 1.0f, 0, 0, 1030))

            coVerify(exactly = 1) { controller.commitStroke(any()) }

            val capturedPoints = strokeSlot.captured.points

            // Verify we don't have duplicate 1010 timestamps
            val pointsAt1010 = capturedPoints.filter { it.timestamp == 1010L }
            assert(pointsAt1010.size == 1) { "Expected exactly 1 point at ts=1010, found ${pointsAt1010.size}" }

            assert(capturedPoints.size == 4) { "Expected 4 distinct points, found ${capturedPoints.size}" }
        }
}
