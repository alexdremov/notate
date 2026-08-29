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
class ScribbleToEraseE2ETest {

    @get:Rule
    val activityRule = ActivityScenarioRule(CanvasActivity::class.java)

    @Test
    fun testScribbleOverStrokeTriggersEraser() {
        // 1. Draw a regular baseline stroke (left to right)
        val baselineStroke = mutableListOf<StylusPoint>()
        for (i in 0..20) {
            baselineStroke.add(StylusPoint(x = 100f + (i * 10f), y = 300f))
        }
        
        onView(withId(R.id.canvas_view))
            .perform(drawWithStylus(baselineStroke))

        // Give the UI thread a moment to commit the stroke to the model
        Thread.sleep(300)

        // 2. Draw a fast, sharp zig-zag over the line to trigger the ScribbleDetector
        // ScribbleDetector looks for > 130 degree reversals and fast movement.
        val scribbleGesture = listOf(
            StylusPoint(x = 150f, y = 200f),
            StylusPoint(x = 170f, y = 400f), // sharp down
            StylusPoint(x = 190f, y = 200f), // sharp up
            StylusPoint(x = 210f, y = 400f), // sharp down
            StylusPoint(x = 230f, y = 200f)  // sharp up
        )

        // You will visually see the zig-zag draw, and then instantly disappear 
        // along with the underlying baseline stroke!
        onView(withId(R.id.canvas_view))
            .perform(drawWithStylus(scribbleGesture))

        // 3. (Optional) You can add an assertion here to verify InfiniteCanvasModel 
        // has 0 strokes or that the RegionManager updated.
    }
}
