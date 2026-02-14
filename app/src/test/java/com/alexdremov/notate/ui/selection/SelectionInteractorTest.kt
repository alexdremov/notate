package com.alexdremov.notate.ui.selection

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.ui.OnyxCanvasView
import com.alexdremov.notate.ui.controller.CanvasController
import com.alexdremov.notate.ui.controller.SelectionManager
import com.alexdremov.notate.util.EpdFastModeController
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionInteractorTest {
    @MockK(relaxed = true)
    lateinit var view: OnyxCanvasView

    @MockK(relaxed = true)
    lateinit var controller: CanvasController

    @MockK(relaxed = true)
    lateinit var selectionManager: SelectionManager

    @MockK
    lateinit var mockContext: Context

    private lateinit var interactor: SelectionInteractor
    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { controller.getSelectionManager() } returns selectionManager
        every { view.context } returns mockContext
        every { view.getCurrentScale() } returns 1.0f

        // Mock PreferencesManager
        mockkObject(PreferencesManager)
        every { PreferencesManager.isAxisLockingEnabled(any()) } returns false
        every { PreferencesManager.isAngleSnappingEnabled(any()) } returns false

        // Mock EpdFastModeController
        mockkObject(EpdFastModeController)
        every { EpdFastModeController.enterFastMode() } returns Unit
        every { EpdFastModeController.exitFastMode() } returns Unit

        // Default Identity Matrices
        viewMatrix.reset()
        inverseMatrix.reset()
    }

    @After
    fun tearDown() {
        unmockkObject(PreferencesManager)
        unmockkObject(EpdFastModeController)
    }

    @Test
    fun `onDown should detect top-left handle`() =
        runTest {
            interactor = SelectionInteractor(view, controller, this, viewMatrix, inverseMatrix)

            // Arrange
            val bounds = RectF(100f, 100f, 200f, 200f)
            setupSelection(bounds)

            // Act
            val result = interactor.onDown(100f, 100f)

            // Assert
            assertTrue(result)
            advanceUntilIdle()
            coVerify { controller.startMoveSelection() }
        }

    @Test
    fun `onDown should detect rotate handle`() =
        runTest {
            interactor = SelectionInteractor(view, controller, this, viewMatrix, inverseMatrix)

            // Arrange
            val bounds = RectF(100f, 100f, 200f, 200f)
            setupSelection(bounds)

            // Act
            val result = interactor.onDown(150f, 0f)

            // Assert
            assertTrue("Should hit rotate handle", result)
        }

    @Test
    fun `handleNonUniformScale should scale horizontally when pulling Right Mid Handle`() =
        runTest {
            interactor = SelectionInteractor(view, controller, this, viewMatrix, inverseMatrix)

            // Arrange
            val bounds = RectF(100f, 100f, 300f, 300f)
            setupSelection(bounds)

            // 1. Grab Mid-Right Handle (300, 200)
            interactor.onDown(300f, 200f)

            val matrixSlot = slot<Matrix>()
            io.mockk.coEvery { controller.transformSelection(capture(matrixSlot)) } returns Unit

            // Act
            val event = mockk<android.view.MotionEvent>(relaxed = true)
            every { event.pointerCount } returns 1
            every { event.x } returns 400f
            every { event.y } returns 200f

            interactor.onMove(event)
            advanceUntilIdle()

            // Assert
            assertTrue("Matrix should be captured", matrixSlot.isCaptured)
            val m = matrixSlot.captured
            val values = FloatArray(9)
            m.getValues(values)

            assertEquals(1.5f, values[Matrix.MSCALE_X], 0.01f)
            assertEquals(1.0f, values[Matrix.MSCALE_Y], 0.01f)
        }

    private fun setupSelection(bounds: RectF) {
        every { selectionManager.hasSelection() } returns true
        every { selectionManager.getTransformedBounds() } returns bounds

        val corners =
            floatArrayOf(
                bounds.left,
                bounds.top, // TL
                bounds.right,
                bounds.top, // TR
                bounds.right,
                bounds.bottom, // BR
                bounds.left,
                bounds.bottom, // BL
            )
        every { selectionManager.getTransformedCorners() } returns corners
        every { selectionManager.getTransform() } returns Matrix()
    }
}
