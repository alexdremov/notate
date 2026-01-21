package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.util.Quadtree
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InfiniteCanvasModelRaceTest {
    private fun createStroke(
        x: Float,
        y: Float,
        w: Float,
    ): Stroke {
        val rect = RectF(x, y, x + w, y + w)
        val path = Path()
        path.addRect(rect, Path.Direction.CW)
        return Stroke(
            path = path,
            points = listOf(TouchPoint(x, y, 0.5f, 1.0f, 0L)),
            color = -16777216,
            width = 2f,
            style = StrokeType.FINELINER,
            bounds = rect,
        )
    }

    @Test
    fun `test erase race condition`() {
        val model = InfiniteCanvasModel()

        // Try multiple iterations to catch the race
        for (i in 0 until 100) {
            model.clear()

            // 1. Add a target stroke
            val target = createStroke(100f, 100f, 50f)
            model.addItem(target)

            // 2. Prepare an eraser stroke that intersects (Standard Eraser)
            val eraser = createStroke(110f, 90f, 10f) // Vertical cut

            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            // Thread A: Erase (Read -> Write)
            executor.execute {
                latch.await()
                try {
                    // Standard eraser splits the stroke
                    model.erase(eraser, EraserType.STANDARD)
                } catch (e: Exception) {
                    // Ignore concurrency exceptions if any (shouldn't happen with locks)
                }
            }

            // Thread B: Delete (Write)
            executor.execute {
                latch.await()
                try {
                    // Simulating external deletion (e.g. sync or user action)
                    Thread.sleep(Random.nextLong(0, 2)) // Add jitter to hit the window
                    model.deleteItems(listOf(target))
                } catch (e: Exception) {
                }
            }

            latch.countDown()
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)

            // 3. Verify State
            val remainingItems = model.queryItems(model.getContentBounds())

            // Expected: Target is gone.
            // If race happened: Target is gone, BUT split parts (new strokes) are present.
            // If correct: Target is gone, split parts are NOT present.

            val splitParts = remainingItems.filter { it != target } // items that are not the original

            if (splitParts.isNotEmpty()) {
                // Check if target is gone
                if (!remainingItems.contains(target)) {
                    fail(
                        "Race Condition Reproduced at iteration $i: Target was deleted, but split parts were added! Remaining: ${splitParts.size}",
                    )
                }
            }
        }
    }
}
