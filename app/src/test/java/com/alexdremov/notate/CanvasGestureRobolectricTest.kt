package com.alexdremov.notate

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexdremov.notate.testutil.StylusAction.StylusPoint
import com.alexdremov.notate.testutil.drawWithStylus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.alexdremov.notate.R
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.alexdremov.notate.ui.OnyxCanvasView

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasGestureRobolectricTest {

    // Note: If you require creating a note first before CanvasActivity launches, 
    // you may want to start from MainActivity and navigate, but this demonstrates the injection.
    @get:Rule
    val activityRule = ActivityScenarioRule(CanvasActivity::class.java)

    @Test
    fun testVisibleStylusDrawing() {
        // Generate a sequence of points simulating a real stylus stroke with pressure
        val points = mutableListOf<StylusPoint>()
        for (i in 0..30) {
            points.add(
                StylusPoint(
                    x = 200f + (i * 15f), 
                    y = 500f + (if (i % 2 == 0) 5f else -5f), // slight wobble
                    pressure = 0.3f + (i * 0.02f) // increasing pressure
                )
            )
        }

        // You will literally watch this stroke being drawn on the emulator screen!
        // Make sure the ID matches your OnyxCanvasView ID in XML
        onView(isAssignableFrom(OnyxCanvasView::class.java))
            .perform(drawWithStylus(points))
            
        // You can then assert against the CanvasController or InfiniteCanvasModel
        // to verify the stroke was committed.
    }
}
