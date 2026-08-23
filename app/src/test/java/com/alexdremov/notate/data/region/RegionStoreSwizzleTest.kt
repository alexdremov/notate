package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Seeded random-operation "swizzle" tests for the RegionStore (Phase 3 redesign).
 *
 * Unlike the targeted chaos tests (which hammer one invariant), these drive a
 * real [InfiniteCanvasModel] + [RegionManager] stack through a RANDOM
 * INTERLEAVING of the public operation set — add, delete, undo, redo, save,
 * rect queries, full close/reopen — and verify the SEMANTIC contract after
 * every verification window:
 *
 *  1. Exactly the strokes that "should" be visible are visible (no more,
 *     no fewer) — across eviction churn, reloads and restarts.
 *  2. items list ⇔ quadtree consistency per region (no ghosts/phantoms).
 *  3. Disk converges to memory after quiescence (fresh manager over same dir).
 *
 * Every seed is deterministic: a failure prints the seed for reproduction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionStoreSwizzleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val managers = ArrayList<RegionManager>()

    private fun newManager(
        dir: File,
        memoryLimitBytes: Long = 48 * 1024L,
    ): RegionManager =
        RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f, memoryLimitBytes = memoryLimitBytes)
            .also { managers.add(it) }

    private fun stroke(
        centerX: Float,
        centerY: Float,
        order: Long,
        span: Float = 300f,
    ): Stroke {
        val points = ArrayList<TouchPoint>(60)
        val path = android.graphics.Path()
        for (i in 0 until 60) {
            val t = i / 59f
            val x = centerX - span / 2 + span * t
            val y = centerY + (if (i % 2 == 0) -span / 4 else span / 4)
            points.add(TouchPoint(x, y, 0.5f, 5f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path,
            points,
            0xFF000000.toInt(),
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(centerX - span / 2, centerY - span / 4, centerX + span / 2, centerY + span / 4),
            strokeOrder = order,
        )
    }

    /** Mirrors model history so we know exactly what SHOULD be visible. */
    private class Mirror {
        val live = LinkedHashSet<Long>()
        val undoStack = ArrayDeque<Op>()
        val redoStack = ArrayDeque<Op>()

        sealed interface Op {
            data class Add(
                val item: CanvasItem,
            ) : Op

            data class Delete(
                val item: CanvasItem,
            ) : Op
        }

        fun applyAdd(item: CanvasItem) {
            live.add(item.order)
            undoStack.addLast(Op.Add(item))
            redoStack.clear()
        }

        fun applyDelete(item: CanvasItem) {
            live.remove(item.order)
            undoStack.addLast(Op.Delete(item))
            redoStack.clear()
        }

        fun applyUndo(): Op? {
            val op = undoStack.removeLastOrNull() ?: return null
            when (op) {
                is Op.Add -> live.remove(op.item.order)
                is Op.Delete -> live.add(op.item.order)
            }
            redoStack.addLast(op)
            return op
        }

        fun applyRedo(): Op? {
            val op = redoStack.removeLastOrNull() ?: return null
            when (op) {
                is Op.Add -> live.add(op.item.order)
                is Op.Delete -> live.remove(op.item.order)
            }
            undoStack.addLast(op)
            return op
        }
    }

    private fun assertSemanticState(
        rm: RegionManager,
        mirror: Mirror,
        context: String,
    ) {
        val all = runBlocking { rm.queryItems(RectF(-20000f, -20000f, 20000f, 20000f)) }
        val actual = all.map { it.order }.toSet()
        val missing = mirror.live - actual
        val extra = actual - mirror.live
        assertTrue(
            "$context: MISSING=${missing.sorted()} EXTRA=${extra.sorted()}",
            missing.isEmpty() && extra.isEmpty(),
        )

        // Quadtree/items consistency per active region.
        for (rid in rm.getActiveRegionIds()) {
            val r = runBlocking { rm.acquireRegion(rid) } ?: continue
            try {
                val itemOrders = r.items.map { it.order }
                val treeOrders = HashSet<Long>()
                r.quadtree?.visit(RectF(-1e9f, -1e9f, 1e9f, 1e9f)) { treeOrders.add(it.order) }
                val ghosts = itemOrders.filter { it !in treeOrders }
                val phantoms = treeOrders.filter { it !in itemOrders.toSet() }
                assertTrue(
                    "$context: region $rid ghosts=$ghosts phantoms=$phantoms",
                    ghosts.isEmpty() && phantoms.isEmpty(),
                )
            } finally {
                rm.releaseRegion(r)
            }
        }
    }

    @Test
    fun `swizzle of all ops preserves semantic state across seeds`() {
        val failures = ArrayList<String>()
        for (seed in 1L..6L) {
            try {
                swizzleOnce(seed)
            } catch (e: Throwable) {
                failures.add("seed=$seed → ${e.message}")
            }
        }
        assertTrue("swizzle failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun swizzleOnce(seed: Long) {
        val rng = Random(seed)
        val dir = tmp.newFolder()
        var rm = newManager(dir)
        var model = InfiniteCanvasModel()
        runBlocking { model.initializeSession(rm) }
        val mirror = Mirror()

        // Distinct coordinates per order so deletes/undoes target unique strokes.
        fun makeStroke(order: Long) =
            stroke(
                centerX = 500f + (order % 5) * 900f + rng.nextFloat() * 100f,
                centerY = 500f + (order / 5 % 5) * 900f + rng.nextFloat() * 100f,
                order = order,
            )

        fun quiesceAndVerify(context: String) {
            rm.saveAll()
            assertSemanticState(rm, mirror, "seed=$seed $context")
        }

        try {
            var nextOrder = 1L
            val liveItems = LinkedHashMap<Long, CanvasItem>()

            repeat(120) { op ->
                val roll = rng.nextInt(100)
                when {
                    roll < 40 -> {
                        runBlocking {
                            val added = model.addItem(makeStroke(nextOrder))
                            checkNotNull(added)
                            mirror.applyAdd(added)
                            liveItems[added.order] = added
                            nextOrder++
                        }
                    }

                    roll < 58 && liveItems.isNotEmpty() -> {
                        runBlocking {
                            val victim = liveItems.values.elementAt(rng.nextInt(liveItems.size))
                            model.deleteItems(listOf(victim))
                            mirror.applyDelete(victim)
                            liveItems.remove(victim.order)
                        }
                    }

                    roll < 70 -> {
                        runBlocking {
                            if (model.undo() != null) {
                                mirror.applyUndo()?.let { o ->
                                    if (o is Mirror.Op.Add) liveItems.remove(o.item.order)
                                    if (o is Mirror.Op.Delete) liveItems[o.item.order] = o.item
                                }
                            }
                        }
                    }

                    roll < 78 -> {
                        runBlocking {
                            if (model.redo() != null) {
                                mirror.applyRedo()?.let { o ->
                                    if (o is Mirror.Op.Add) liveItems[o.item.order] = o.item
                                    if (o is Mirror.Op.Delete) liveItems.remove(o.item.order)
                                }
                            }
                        }
                    }

                    roll < 86 -> {
                        rm.saveAll()
                    }

                    roll < 93 -> {
                        runBlocking {
                            rm.queryItems(
                                RectF(
                                    rng.nextFloat() * 6000f - 1000f,
                                    rng.nextFloat() * 6000f - 1000f,
                                    rng.nextFloat() * 6000f + 1000f,
                                    rng.nextFloat() * 6000f + 1000f,
                                ),
                            )
                        }
                    }

                    else -> {
                        // Full close/reopen: persistence round-trip mid-session.
                        rm.saveAll()
                        rm.clear()
                        rm = newManager(dir)
                        model = InfiniteCanvasModel()
                        runBlocking { model.initializeSession(rm) }
                    }
                }
                if (op % 24 == 23) quiesceAndVerify("op=$op")
            }
            quiesceAndVerify("final")

            // Disk convergence: fresh manager over same dir must agree.
            rm.saveAll()
            val rm2 = newManager(dir)
            val reloaded =
                runBlocking { rm2.queryItems(RectF(-20000f, -20000f, 20000f, 20000f)) }
                    .map { it.order }
                    .toSet()
            assertEquals(
                "seed=$seed: disk did not converge to memory (missing=${mirror.live - reloaded} extra=${reloaded - mirror.live})",
                mirror.live,
                reloaded,
            )
        } finally {
            rm.clear()
        }
    }
}
