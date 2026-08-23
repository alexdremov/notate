package com.alexdremov.notate.data.region

import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
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

/**
 * Real-usecase-shaped tests: a note-taking SESSION as a user would actually
 * experience it — write a page, erase mistakes, undo/redo, close the app,
 * reopen, keep writing, close again — verified end-to-end through the public
 * model + store API (no internals).
 *
 * These complement the chaos tests: chaos proves the CONCURRENCY contract
 * under adversarial scheduling; these prove the PRODUCT contract under the
 * operation sequences real users perform.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealWorldSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSession(dir: File): Pair<RegionManager, InfiniteCanvasModel> {
        val rm = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val model = InfiniteCanvasModel()
        runBlocking { model.initializeSession(rm) }
        return rm to model
    }

    /** A realistic handwriting stroke: short, slightly wavy, left-to-right. */
    private fun inkLine(
        startX: Float,
        baselineY: Float,
        widthPx: Float = 220f,
        order: Long,
    ): Stroke {
        val points = ArrayList<TouchPoint>(24)
        val path = android.graphics.Path()
        for (i in 0 until 24) {
            val t = i / 23f
            val x = startX + widthPx * t
            // Simulate pen pressure/baseline wobble of real handwriting.
            val y = baselineY + 12f * kotlin.math.sin(t * 6.28f * 1.5f) + t * 4f
            points.add(TouchPoint(x, y, 0.4f + 0.3f * t, 4f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path,
            points,
            0xFF101010.toInt(),
            width = 2.2f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(startX, baselineY - 20f, startX + widthPx, baselineY + 24f),
            strokeOrder = order,
        )
    }

    /**
     * Adds via the model and returns the item WITH ITS MODEL-ASSIGNED order.
     * (model.addItem re-orders strokes via nextOrder — the caller-supplied
     * strokeOrder is only a construction detail.)
     */
    private fun addLine(
        model: InfiniteCanvasModel,
        startX: Float,
        baselineY: Float,
        widthPx: Float = 220f,
        seedOrder: Long,
    ): Stroke =
        runBlocking {
            model.addItem(inkLine(startX, baselineY, widthPx, seedOrder)) as Stroke
        }

    private fun ordersOf(rm: RegionManager): Set<Long> =
        runBlocking {
            rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f))
        }.map { it.order }.toSet()

    @Test
    fun `note session - write erase undo redo across app restarts`() {
        val dir = tmp.newFolder()
        var (rm, model) = newSession(dir)

        // ---- Session 1: take a page of notes ----
        var order = 1L
        val pageOneOrders = LinkedHashSet<Long>()
        repeat(14) { line ->
            val added = addLine(model, 100f + (line % 2) * 60f, 150f + line * 90f, seedOrder = order++)
            pageOneOrders.add(added.order)
        }
        assertEquals(pageOneOrders, ordersOf(rm))

        // ---- User erases two wrong lines ----
        val erased = pageOneOrders.toList().subList(3, 5)
        runBlocking { model.deleteItems(rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)).filter { it.order in erased }) }
        val afterErase = pageOneOrders - erased
        assertEquals(afterErase, ordersOf(rm))

        // ---- Undo semantics: undo#1 restores the deletion, undo#2 removes
        // the most recent stroke (history is strictly linear) ----
        val lastStrokeOrder = pageOneOrders.last()
        runBlocking {
            model.undo()
            model.undo()
        }
        assertEquals(
            "after two undos: deletion restored AND last stroke undone",
            (pageOneOrders - erased + setOf(lastStrokeOrder)) - setOf(lastStrokeOrder) + erased,
            ordersOf(rm),
        )
        runBlocking {
            model.redo()
            model.redo()
        }
        assertEquals(afterErase, ordersOf(rm))

        // ---- Close & reopen the document ----
        rm.saveAll()
        rm.clear()
        val (rm2, model2) = newSession(dir)
        assertEquals("session content must survive restart", afterErase, ordersOf(rm2))

        // ---- Continue writing on page two of the SAME canvas ----
        val pageTwoOrders = LinkedHashSet<Long>()
        runBlocking {
            repeat(8) { line ->
                val added =
                    addLine(
                        model2,
                        startX = 120f + line * 40f,
                        baselineY = 5100f + line * 90f, // far below page one → new regions
                        widthPx = 260f,
                        seedOrder = order++,
                    )
                pageTwoOrders.add(added.order)
            }
        }
        assertEquals(afterErase + pageTwoOrders, ordersOf(rm2))

        // ---- Final save/restart: everything still there ----
        rm2.saveAll()
        rm2.clear()
        val (rm3, _) = newSession(dir)
        assertEquals(afterErase + pageTwoOrders, ordersOf(rm3))

        // Undo history does NOT survive restarts by design — but content does.
        rm3.clear()
    }

    @Test
    fun `rapid-fire writing then immediate app close persists everything`() {
        // Users slam the home button right after finishing a stroke. The
        // atomic-persistence pipeline must flush on close.
        val dir = tmp.newFolder()
        val (rm, model) = newSession(dir)

        val expected = LinkedHashSet<Long>()
        runBlocking {
            repeat(60) { i ->
                val added =
                    addLine(
                        model,
                        startX = 80f + (i % 6) * 240f,
                        baselineY = 120f + (i / 6) * 110f,
                        widthPx = 200f,
                        seedOrder = (i + 1).toLong(),
                    )
                expected.add(added.order)
            }
        }

        // "Close": saveAll is what CanvasRepository invokes on session end.
        rm.saveAll()
        rm.clear()

        val (rm2, _) = newSession(dir)
        assertEquals("rapid-close must not lose strokes", expected, ordersOf(rm2))
        rm2.clear()
    }

    @Test
    fun `pan and zoom style viewport queries stay consistent while writing`() {
        // Simulates the render loop: viewport-sized rect queries while the
        // user keeps writing. Results must always be a subset-consistent view.
        val dir = tmp.newFolder()
        val (rm, model) = newSession(dir)

        val allOrders = LinkedHashSet<Long>()
        runBlocking {
            repeat(30) { i ->
                val s =
                    addLine(
                        model,
                        startX = 100f + (i % 5) * 700f,
                        baselineY = 100f + (i / 5) * 800f,
                        seedOrder = (i + 1).toLong(),
                    )
                allOrders.add(s.order)

                // Viewport query around the freshly written area.
                val viewport =
                    RectF(
                        s.bounds.left - 500f,
                        s.bounds.top - 500f,
                        s.bounds.right + 500f,
                        s.bounds.bottom + 500f,
                    )
                val hits = rm.queryItems(viewport)
                assertTrue(
                    "viewport query must contain the just-written stroke",
                    hits.any { it.order == s.order },
                )
                // And a far-away viewport must not contain it.
                val farAway = RectF(-4000f, -4000f, -3000f, -3000f)
                assertTrue(
                    "far viewport must be empty of the new stroke",
                    rm.queryItems(farAway).none { it.order == s.order },
                )
            }
        }
        assertEquals(allOrders, ordersOf(rm))
        rm.clear()
    }
}
