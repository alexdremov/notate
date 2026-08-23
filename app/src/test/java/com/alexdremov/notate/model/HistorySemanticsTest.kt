package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Model-level HISTORY semantics: undo/redo across every action type,
 * including the STANDARD eraser's stroke-SPLITTING (one stroke → N parts),
 * redo-stack invalidation, and undo-past-boundary no-ops.
 *
 * These are the product behaviors users rely on for "safe editing" — none of
 * them were covered before this suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistorySemanticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStack(dir: File): Pair<RegionManager, InfiniteCanvasModel> {
        val rm = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val model = InfiniteCanvasModel()
        runBlocking { model.initializeSession(rm) }
        return rm to model
    }

    /** Horizontal stroke centered at (cx, cy), length `w`, height ~2px. */
    private fun hLine(cx: Float, cy: Float, w: Float, order: Long): Stroke {
        val pts = ArrayList<TouchPoint>(30)
        val path = android.graphics.Path()
        for (i in 0..30) {
            val t = i / 30f
            val x = cx - w / 2 + w * t
            val y = cy + kotlin.math.sin(t * 12f)
            pts.add(TouchPoint(x, y, 0.5f, 3f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path, pts, 0xFF000000.toInt(), width = 2f, style = StrokeType.FOUNTAIN,
            bounds = RectF(cx - w / 2, cy - 2f, cx + w / 2, cy + 2f),
            strokeOrder = order,
        )
    }

    private fun verticalEraser(x: Float, yTop: Float, height: Float): Stroke {
        val pts = ArrayList<TouchPoint>(20)
        val path = android.graphics.Path()
        for (i in 0..20) {
            val t = i / 20f
            val y = yTop + height * t
            val xx = x + kotlin.math.sin(t * 10f) * 1f
            pts.add(TouchPoint(xx, y, 0.6f, 6f, i.toLong()))
            if (i == 0) path.moveTo(xx, y) else path.lineTo(xx, y)
        }
        return Stroke(
            path, pts, 0x80FF0000.toInt(), width = 8f, style = StrokeType.FOUNTAIN,
            bounds = RectF(x - 4f, yTop, x + 4f, yTop + height),
            strokeOrder = -1L,
        )
    }

    private fun liveOrders(model: InfiniteCanvasModel): Set<Long> =
        runBlocking {
            model.queryItems(RectF(-50000f, -50000f, 50000f, 50000f))
        }.map { it.order }.toSet()

    // ------------------------------------------------------------------
    // 1. add/delete undo-redo basics + redo-stack invalidation
    // ------------------------------------------------------------------
    @Test
    fun `undo redo basics and redo stack invalidation`() {
        val dir = tmp.newFolder()
        val (rm, model) = newStack(dir)

        val a1 = runBlocking { model.addItem(hLine(500f, 300f, 300f, order = 1)) }!!
        val a2 = runBlocking { model.addItem(hLine(500f, 600f, 300f, order = 2)) }!!
        assertEquals(setOf(a1.order, a2.order), liveOrders(model))

        // Undo both adds.
        runBlocking { model.undo(); model.undo() }
        assertEquals(emptySet<Long>(), liveOrders(model))

        // Undo at boundary: clean no-op.
        assertNull(runBlocking { model.undo() })
        assertEquals(emptySet<Long>(), liveOrders(model))

        // Redo one add; then a NEW action must clear the redo stack.
        runBlocking { model.redo() }
        assertEquals(setOf(a1.order), liveOrders(model))
        val a3 = runBlocking { model.addItem(hLine(500f, 900f, 300f, order = 3)) }!!
        assertEquals(setOf(a1.order, a3.order), liveOrders(model))

        // Redo must now be a no-op (stack was invalidated by the new add).
        assertNull(runBlocking { model.redo() })
        assertEquals(setOf(a1.order, a3.order), liveOrders(model))
        rm.clear()
    }

    // ------------------------------------------------------------------
    // 2. delete/undo/redo round trip
    // ------------------------------------------------------------------
    @Test
    fun `delete undo redo restores identical orders`() {
        val dir = tmp.newFolder()
        val (rm, model) = newStack(dir)

        val strokes = listOf(
            hLine(500f, 200f, 400f, 1),
            hLine(500f, 500f, 400f, 2),
            hLine(500f, 800f, 400f, 3),
        )
        // IMPORTANT: delete must use the MODEL-ASSIGNED items (returned), not
        // the construction copies — the store matches by assigned order.
        val added = strokes.map { runBlocking { model.addItem(it) }!! }
        runBlocking { model.deleteItems(listOf(added[1])) }
        assertEquals(setOf(added[0].order, added[2].order), liveOrders(model))

        runBlocking { model.undo() }
        assertEquals(added.map { it.order }.toSet(), liveOrders(model))

        runBlocking { model.redo() }
        assertEquals(setOf(added[0].order, added[2].order), liveOrders(model))
        rm.clear()
    }

    // ------------------------------------------------------------------
    // 3. STANDARD eraser SPLITS a stroke into parts; undo restores original
    // ------------------------------------------------------------------
    @Test
    fun `standard eraser splits a stroke and undo restores it whole`() {
        val dir = tmp.newFolder()
        val (rm, model) = newStack(dir)

        val addedOriginal =
            runBlocking { model.addItem(hLine(1000f, 1000f, 800f, order = 1)) }!!
        val originalOrder = addedOriginal.order
        assertEquals(setOf(originalOrder), liveOrders(model))

        // Vertical eraser slicing through the middle of the line.
        val eraser = verticalEraser(x = 1000f, yTop = 960f, height = 80f)
        runBlocking { model.erase(eraser, EraserType.STANDARD) }

        val afterSplit = liveOrders(model)
        // The original order MUST be gone and replaced by ≥2 part orders.
        assertTrue("split did not remove original", originalOrder !in afterSplit)
        assertTrue("split produced fewer than 2 parts (${afterSplit.size})", afterSplit.size >= 2)

        // Undo must restore the ORIGINAL single stroke with its order.
        runBlocking { model.undo() }
        assertEquals("undo after split must restore original", setOf(originalOrder), liveOrders(model))

        // Redo re-splits: different part orders than before is acceptable
        // (splitting is geometric), but the count and original-removal hold.
        runBlocking { model.redo() }
        val afterResplit = liveOrders(model)
        assertTrue("resplit lost original", originalOrder !in afterResplit)
        assertEquals(afterSplit.size, afterResplit.size)
        rm.clear()
    }

    // ------------------------------------------------------------------
    // 4. Undo past a save/reopen boundary: history intentionally resets,
    //    content must remain exactly as last flushed.
    // ------------------------------------------------------------------
    @Test
    fun `history does not cross reopen boundary but content persists`() {
        val dir = tmp.newFolder()
        val (rm, model) = newStack(dir)

        val a1 = runBlocking { model.addItem(hLine(500f, 300f, 300f, order = 1)) }!!
        runBlocking { model.addItem(hLine(500f, 600f, 300f, order = 2)) }
        runBlocking { model.undo() } // remove the second stroke
        rm.saveAll()
        rm.clear()

        val (_, model2) = newStack(dir)
        // Content persisted in its post-undo state.
        assertEquals(setOf(a1.order), liveOrders(model2))
        // History is empty: undo is a clean no-op.
        assertNull(runBlocking { model2.undo() })
        assertEquals(setOf(a1.order), liveOrders(model2))
    }

    // ------------------------------------------------------------------
    // 5. Long mixed history converges: 40 random ops, then undo-to-empty
    // ------------------------------------------------------------------
    @Test
    fun `long mixed history can be fully unwound`() {
        val dir = tmp.newFolder()
        val (rm, model) = newStack(dir)
        val rng = kotlin.random.Random(5)
        var nextOrder = 1L

        repeat(40) {
            when (rng.nextInt(3)) {
                0 -> runBlocking { model.addItem(hLine(rng.nextFloat() * 3000f, rng.nextFloat() * 3000f, 250f, nextOrder++)) }
                else -> {
                    val all = liveOrders(model)
                    if (all.isNotEmpty()) {
                        val victim =
                            runBlocking { model.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
                                .firstOrNull { it.order == all.first() }
                        if (victim != null) runBlocking { model.deleteItems(listOf(victim)) }
                    }
                }
            }
        }

        // Unwind everything: repeated undo until BOTH live set is empty AND
        // history is exhausted (net-zero op sequences still occupy history).
        var guard = 0
        var result = runBlocking { model.undo() }
        while ((liveOrders(model).isNotEmpty() || result != null) && guard++ < 300) {
            result = runBlocking { model.undo() }
        }
        assertTrue("unwind exceeded guard ($guard)", guard < 300)
        assertEquals("fully unwound session must be empty", emptySet<Long>(), liveOrders(model))
        assertNull(runBlocking { model.undo() }) // boundary
        rm.clear()
    }
}
