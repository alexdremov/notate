package com.alexdremov.notate.testutil

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matcher

/**
 * A custom Espresso ViewAction that allows injecting real Stylus events (with pressure and tilt)
 * into a view (like OnyxCanvasView) on a standard Android emulator.
 * 
 * This enables VISIBLE E2E testing of shape recognition, pressure brushes, and scribble-to-erase
 * without needing physical Onyx hardware.
 */
class StylusAction(private val points: List<StylusPoint>) : ViewAction {
    
    data class StylusPoint(val x: Float, val y: Float, val pressure: Float = 1.0f, val tiltX: Float = 0f)

    override fun getConstraints(): Matcher<View> = isDisplayed()

    override fun getDescription(): String = "Draw strokes with Stylus tool type, pressure, and tilt"

    override fun perform(uiController: UiController, view: View) {
        if (points.isEmpty()) return

        val downTime = SystemClock.uptimeMillis()
        
        points.forEachIndexed { index, point ->
            val eventTime = downTime + (index * 16) // Simulate ~60fps input speed
            
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            
            val pointerProperties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
            
            val pointerCoords = MotionEvent.PointerCoords().apply {
                x = point.x
                y = point.y
                pressure = point.pressure
                setAxisValue(MotionEvent.AXIS_TILT, point.tiltX)
            }

            val event = MotionEvent.obtain(
                downTime, eventTime, action, 1,
                arrayOf(pointerProperties), arrayOf(pointerCoords), 
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_STYLUS, 0
            )

            // Inject the event directly into the canvas view
            view.dispatchTouchEvent(event)
            event.recycle()
            
            // Allow the main thread to process the event so you can literally WATCH it draw frame-by-frame
            uiController.loopMainThreadForAtLeast(16)
        }
    }
}

// Helper extension function
fun drawWithStylus(points: List<StylusAction.StylusPoint>): ViewAction {
    return StylusAction(points)
}
