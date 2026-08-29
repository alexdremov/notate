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

@RunWith(AndroidJUnit4::class)
class CanvasGestureTest {

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
        onView(withId(R.id.canvas_view))
            .perform(drawWithStylus(points))
            
        // You can then assert against the CanvasController or InfiniteCanvasModel
        // to verify the stroke was committed.
    }
}
