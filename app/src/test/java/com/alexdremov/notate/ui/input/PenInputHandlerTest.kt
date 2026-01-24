package com.alexdremov.notate.ui.input

import android.graphics.Matrix
import android.view.View
import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.model.Stroke
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
}
