package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.util.Quadtree
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
    fun `test erase race condition`() =
        runTest {
            val model = InfiniteCanvasModel()

            // Try multiple iterations to catch the race
            for (i in 0 until 100) {
                model.clear()

                // 1. Add a target stroke
                val target = createStroke(100f, 100f, 50f)
                model.addItem(target)

                // 2. Prepare an eraser stroke that intersects (Standard Eraser)
                val eraser = createStroke(110f, 90f, 10f) // Vertical cut

                // Thread A: Erase (Read -> Write)
                val jobA =
                    launch {
                        try {
                            model.erase(eraser, EraserType.STANDARD)
                        } catch (e: Exception) {
                        }
                    }

                // Thread B: Delete (Write)
                val jobB =
                    launch {
                        try {
                            // Simulating external deletion (e.g. sync or user action)
                            kotlinx.coroutines.delay(Random.nextLong(0, 2))
                            model.deleteItems(listOf(target))
                        } catch (e: Exception) {
                        }
                    }

                jobA.join()
                jobB.join()

                // 3. Verify State
                val remainingItems = model.queryItems(model.getContentBounds())

                val splitParts = remainingItems.filter { it != target }

                if (splitParts.isNotEmpty()) {
                    if (!remainingItems.contains(target)) {
                        fail(
                            "Race Condition Reproduced at iteration $i: Target was deleted, but split parts were added! Remaining: ${splitParts.size}",
                        )
                    }
                }
            }
        }
}
