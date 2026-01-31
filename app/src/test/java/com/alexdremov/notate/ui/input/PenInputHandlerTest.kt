package com.alexdremov.notate.ui.input

import android.graphics.Matrix
import android.view.View
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.controller.CanvasController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PenInputHandlerTest {
    private lateinit var controller: CanvasController
    private lateinit var view: View
    private lateinit var inputHandler: PenInputHandler
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        controller = mockk(relaxed = true)
        view = mockk(relaxed = true)

        // Mock Context for DwellDetector if needed (relaxed mocks usually handle this)
        // But since we pass view, we might need view.context
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        every { view.context } returns context

        inputHandler =
            PenInputHandler(
                controller = controller,
                view = view,
                scope = testScope,
                matrix = Matrix(),
                inverseMatrix = Matrix(),
                onStrokeFinished = {},
            )
    }

    @Test
    fun `test batched points are processed`() =
        runTest {
            val startPoint = TouchPoint(0f, 0f, 0.5f, 1f, 1000L)
            val endPoint = TouchPoint(100f, 100f, 0.5f, 1f, 2000L)

            // Batch points
            val batchPoints = ArrayList<TouchPoint>()
            batchPoints.add(TouchPoint(25f, 25f, 0.5f, 1f, 1250L))
            batchPoints.add(TouchPoint(50f, 50f, 0.5f, 1f, 1500L))
            batchPoints.add(TouchPoint(75f, 75f, 0.5f, 1f, 1750L))

            val touchPointList = mockk<TouchPointList>()
            every { touchPointList.points } returns batchPoints

            // 1. Begin
            inputHandler.onBeginRawDrawing(false, startPoint)

            // 2. Receive Batch
            inputHandler.onRawDrawingTouchPointListReceived(touchPointList)

            // 3. End
            inputHandler.onEndRawDrawing(false, endPoint)

            // Verify that commitStroke was called with a stroke containing ALL points
            // Start(1) + Batch(3) + End(1) = 5 points
            val strokeSlot = slot<Stroke>()
            coVerify { controller.commitStroke(capture(strokeSlot)) }

            val committedStroke = strokeSlot.captured
            assertEquals(5, committedStroke.points.size)

            assertEquals(0f, committedStroke.points[0].x, 0.01f)
            assertEquals(25f, committedStroke.points[1].x, 0.01f)
            assertEquals(50f, committedStroke.points[2].x, 0.01f)
            assertEquals(75f, committedStroke.points[3].x, 0.01f)
            assertEquals(100f, committedStroke.points[4].x, 0.01f)
        }

    @Test
    fun `test consecutive strokes do not race`() =
        runTest {
            val start1 = TouchPoint(0f, 0f, 0.5f, 1f, 1000L)
            val end1 = TouchPoint(10f, 10f, 0.5f, 1f, 1100L)

            val start2 = TouchPoint(20f, 20f, 0.5f, 1f, 1200L)
            val end2 = TouchPoint(30f, 30f, 0.5f, 1f, 1300L)

            // Stroke 1
            inputHandler.onBeginRawDrawing(false, start1)
            inputHandler.onEndRawDrawing(false, end1)

            // IMMEDIATE Stroke 2 (Simulating next touch before coroutine 1 finished if it was async)
            inputHandler.onBeginRawDrawing(false, start2)
            inputHandler.onEndRawDrawing(false, end2)

            val capturedStrokes = mutableListOf<Stroke>()
            coVerify(exactly = 2) { controller.commitStroke(capture(capturedStrokes)) }

            assertEquals(2, capturedStrokes.size)

            // Verify first stroke has its points (was not cleared by start2)
            val s1 = capturedStrokes[0]
            assertEquals(2, s1.points.size)
            assertEquals(0f, s1.points[0].x, 0.01f)
            assertEquals(10f, s1.points[1].x, 0.01f)

            // Verify second stroke
            val s2 = capturedStrokes[1]
            assertEquals(2, s2.points.size)
            assertEquals(20f, s2.points[0].x, 0.01f)
            assertEquals(30f, s2.points[1].x, 0.01f)
        }
}
