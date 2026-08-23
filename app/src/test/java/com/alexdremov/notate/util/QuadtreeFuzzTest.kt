package com.alexdremov.notate.util

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random

/**
 * Randomized differential fuzzing of [Quadtree] against a brute-force oracle.
 *
 * The quadtree must return EXACTLY the set of items whose bounds intersect the
 * query viewport (`retrieve` filters per-item with `RectF.intersects`). Any
 * divergence — missing item (index corruption, bad grow/split mapping) or extra
 * item (failed removal) — fails the test.
 *
 * The op mix deliberately stresses:
 *  - `grow()` in all four directions (items spread over a huge coordinate range,
 *    including negative coordinates),
 *  - deep `split()` chains (many items in tight clusters),
 *  - boundary-touching rects (strict-vs-inclusive comparison traps),
 *  - interleaved insert/remove/retrieve (structural churn).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuadtreeFuzzTest {
    private class Item(
        override val bounds: RectF,
        val id: Long,
    ) : CanvasItem {
        override val order: Long get() = id
        override val zIndex: Float get() = 0f

        override fun distanceToPoint(
            x: Float,
            y: Float,
        ): Float = 0f
    }

    private fun randomRect(rng: Random): RectF {
        val w = 1f + rng.nextFloat() * 2000f
        val h = 1f + rng.nextFloat() * 2000f
        val x = -20_000f + rng.nextFloat() * 40_000f
        val y = -20_000f + rng.nextFloat() * 40_000f
        // Occasionally produce edge-touching / tiny rects.
        if (rng.nextInt(10) == 0) return RectF(x, y, x + w, y + 0.001f)
        if (rng.nextInt(10) == 0) return RectF(0f, 0f, 1000f, 1000f)
        return RectF(x, y, x + w, y + h)
    }

    private fun queryRect(rng: Random): RectF {
        val w = 100f + rng.nextFloat() * 8000f
        val h = 100f + rng.nextFloat() * 8000f
        val x = -15_000f + rng.nextFloat() * 30_000f
        val y = -15_000f + rng.nextFloat() * 30_000f
        return RectF(x, y, x + w, y + h)
    }

    private fun fuzz(seed: Int) {
        val rng = Random(seed.toLong())
        var tree = Quadtree(0, RectF(-1000f, -1000f, 1000f, 1000f))
        val live = HashMap<Long, Item>()
        var nextId = 0L
        val result = ArrayList<CanvasItem>()

        repeat(6000) {
            when (rng.nextInt(100)) {
                in 0..54 -> { // insert
                    val item = Item(randomRect(rng), nextId++)
                    tree = tree.insert(item)
                    live[item.id] = item
                }

                in 55..74 -> { // remove random existing
                    if (live.isNotEmpty()) {
                        val victim = live.values.toTypedArray()[rng.nextInt(live.size)]
                        assertTrue(
                            "remove(${victim.id}) reported failure",
                            tree.remove(victim),
                        )
                        live.remove(victim.id)
                    }
                }

                else -> { // retrieve + differential check
                    result.clear()
                    val viewport = queryRect(rng)
                    tree.retrieve(result, viewport)
                    val got = result.mapTo(HashSet()) { (it as Item).id }
                    val expected =
                        live.values
                            .filter { RectF.intersects(it.bounds, viewport) }
                            .mapTo(HashSet()) { it.id }
                    assertEquals("seed=$seed op=$it missing/extra items", expected, got)
                }
            }
        }

        // Final exhaustive sweep on a fixed grid of viewports.
        for (gx in -4..4) {
            for (gy in -4..4) {
                result.clear()
                val viewport = RectF(gx * 5000f, gy * 5000f, gx * 5000f + 5000f, gy * 5000f + 5000f)
                tree.retrieve(result, viewport)
                val got = result.mapTo(HashSet()) { (it as Item).id }
                val expected = live.values.filter { RectF.intersects(it.bounds, viewport) }.mapTo(HashSet()) { it.id }
                assertEquals("seed=$seed final sweep ($gx,$gy)", expected, got)
            }
        }
    }

    @Test(timeout = 120_000)
    fun `fuzz seed 1`() = fuzz(1)

    @Test(timeout = 120_000)
    fun `fuzz seed 42`() = fuzz(42)

    @Test(timeout = 120_000)
    fun `fuzz seed 1337`() = fuzz(1337)

    @Test(timeout = 120_000)
    fun `fuzz seed 90210`() = fuzz(90210)
}
