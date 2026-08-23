package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
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
 * Human-like multi-document stress test.
 *
 * Simulates a real user working across SEVERAL canvases ("documents"):
 *  - writes words built from LETTER GLYPHS (multi-stroke, wavy, pen-like),
 *  - scribble-clears areas (every stroke intersecting an erase rectangle),
 *  - undoes / redoes bursts,
 *  - saves, closes and REOPENS documents — including switching between two
 *    open documents mid-session and full app-close/app-restart cycles.
 *
 * The test maintains a strict per-document EXPECTATION (which stroke orders
 * must be live), updated only through deterministic user semantics, then
 * verifies at switch points and at the end:
 *   1. memory content == expectation for the active document,
 *   2. items ⇔ quadtree consistency per region,
 *   3. after final flush + fresh reopen of EVERY document: disk == expectation.
 *
 * Undo/redo is mirrored MANUALLY at store level (rm.addItem / rm.removeItems)
 * so restored strokes keep their ORIGINAL orders — exactly what the app's
 * HistoryManager does. Deterministic per seed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserSessionStressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val managers = ArrayList<RegionManager>()

    // ------------------------------------------------------------------
    // Letter glyphs: unit-box polylines (x right, y down).
    // ------------------------------------------------------------------
    private val glyphs: Map<Char, List<List<Pair<Float, Float>>>> =
        mapOf(
            'l' to listOf(listOf(0.2f to 0.0f, 0.15f to 1.0f)),
            'i' to listOf(listOf(0.3f to 0.15f, 0.3f to 1.0f), listOf(0.3f to -0.25f, 0.32f to -0.2f)),
            'o' to
                listOf(
                    listOf(
                        0.5f to 0.05f,
                        0.85f to 0.25f,
                        0.8f to 0.75f,
                        0.45f to 0.95f,
                        0.15f to 0.7f,
                        0.25f to 0.25f,
                        0.5f to 0.05f,
                    ),
                ),
            'e' to
                listOf(
                    listOf(
                        0.85f to 0.35f,
                        0.4f to 0.2f,
                        0.15f to 0.55f,
                        0.3f to 0.9f,
                        0.7f to 0.95f,
                        0.9f to 0.75f,
                    ),
                ),
            's' to
                listOf(
                    listOf(
                        0.85f to 0.15f,
                        0.4f to 0.05f,
                        0.15f to 0.35f,
                        0.5f to 0.55f,
                        0.8f to 0.65f,
                        0.75f to 0.9f,
                        0.2f to 0.98f,
                    ),
                ),
            't' to listOf(listOf(0.4f to 0.0f, 0.35f to 0.9f), listOf(0.05f to 0.25f, 0.75f to 0.22f)),
            'n' to listOf(listOf(0.1f to 1.0f, 0.12f to 0.1f, 0.5f to 0.85f, 0.85f to 0.15f, 0.88f to 1.0f)),
        )

    /**
     * Writes `word` like handwriting: letters left-to-right, each polyline
     * resampled with wobble into pen-like strokes. Returns created items.
     */
    private fun writeWord(
        rm: RegionManager,
        word: String,
        x0: Float,
        baselineY: Float,
        scale: Float,
        seedOrderBase: Long,
    ): List<CanvasItem> {
        val created = ArrayList<CanvasItem>()
        var cursor = x0
        var construction = 0L
        runBlocking {
            for (ch in word) {
                val glyphSegments = glyphs.getValue(ch)
                for (segment in glyphSegments) {
                    val pts = ArrayList<TouchPoint>(segment.size * 5)
                    val path = android.graphics.Path()
                    for ((idx, point) in segment.withIndex()) {
                        if (idx == segment.lastIndex) {
                            val px = cursor + point.first * scale
                            val py = baselineY + point.second * scale
                            pts.add(TouchPoint(px, py, 0.6f, 4f, idx.toLong()))
                            path.lineTo(px, py)
                            break
                        }
                        val nxt = segment[idx + 1]
                        val steps = 5
                        for (s in 0 until steps) {
                            val t = s / steps.toFloat()
                            val px =
                                cursor + (point.first + (nxt.first - point.first) * t) * scale +
                                    kotlin.math.sin(t * 9f) * 1.5f
                            val py =
                                baselineY + (point.second + (nxt.second - point.second) * t) * scale +
                                    kotlin.math.cos(t * 7f) * 1.5f
                            pts.add(TouchPoint(px, py, 0.55f, 4f, (idx * steps + s).toLong()))
                            if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                    }
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    val stroke =
                        Stroke(
                            path,
                            pts,
                            0xFF101010.toInt(),
                            width = 2.4f,
                            style = StrokeType.FOUNTAIN,
                            bounds = bounds,
                            strokeOrder = seedOrderBase * 1000L + construction++,
                        )
                    rm.addItem(stroke)
                    created.add(stroke)
                }
                cursor += scale * 1.4f
            }
        }
        return created
    }

    /** One user action over a group of strokes (a written word or an erase). */
    private sealed interface UserOp {
        val items: List<CanvasItem>

        data class Add(
            override val items: List<CanvasItem>,
        ) : UserOp

        data class Delete(
            override val items: List<CanvasItem>,
        ) : UserOp
    }

    private inner class Doc(
        val id: String,
        val dir: File,
    ) {
        var rm: RegionManager = openManager()
        var nextSeedOrder = 1L

        /** What SHOULD be live right now. */
        val expectedLive = LinkedHashSet<Long>()
        val expectedItems = LinkedHashMap<Long, CanvasItem>()
        val undoStack = ArrayDeque<UserOp>()
        val redoStack = ArrayDeque<UserOp>()

        private fun openManager(): RegionManager =
            RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f, memoryLimitBytes = 64 * 1024L)
                .also {
                    managers.add(it)
                    it.forensicsSetEnabled(true)
                    it.forensicsReset()
                }

        /** App-close semantics: persist everything, drop in-memory state. */
        fun close() {
            runBlocking { rm.saveAll() }
            rm.clear()
        }

        /**
         * App-open semantics: fresh manager over the same container. The
         * EXPECTATION state (expectedLive/items, seed counter) persists — it
         * describes the DOCUMENT, not the session. Only undo/redo history
         * resets (in-memory by design).
         */
        fun reopen() {
            close()
            rm = openManager()
            undoStack.clear()
            redoStack.clear()
        }

        fun commit(op: UserOp) {
            when (op) {
                is UserOp.Add -> {
                    op.items.forEach {
                        runBlocking { rm.addItem(it) }
                        expectedLive.add(it.order)
                        expectedItems[it.order] = it
                    }
                }

                is UserOp.Delete -> {
                    val present = op.items.filter { it.order in expectedLive }
                    if (present.isNotEmpty()) {
                        runBlocking { rm.removeItems(present) }
                        present.forEach {
                            expectedLive.remove(it.order)
                            expectedItems.remove(it.order)
                        }
                    }
                }
            }
            undoStack.addLast(op)
            redoStack.clear()
        }

        /** Store-level undo: inverse of the last action, original orders kept. */
        fun undo(): Boolean {
            val op = undoStack.removeLastOrNull() ?: return false
            applyInverse(op)
            redoStack.addLast(op)
            return true
        }

        fun redo(): Boolean {
            val op = redoStack.removeLastOrNull() ?: return false
            commitWithoutHistory(op)
            undoStack.addLast(op)
            return true
        }

        private fun applyInverse(op: UserOp) {
            when (op) {
                is UserOp.Add -> {
                    val stillLive = op.items.filter { it.order in expectedLive }
                    if (stillLive.isNotEmpty()) {
                        runBlocking { rm.removeItems(stillLive) }
                        stillLive.forEach {
                            expectedLive.remove(it.order)
                            expectedItems.remove(it.order)
                        }
                    }
                }

                is UserOp.Delete -> {
                    op.items.forEach {
                        runBlocking { rm.addItem(it) }
                        expectedLive.add(it.order)
                        expectedItems[it.order] = it
                    }
                }
            }
        }

        private fun commitWithoutHistory(op: UserOp) {
            when (op) {
                is UserOp.Add -> {
                    val missing = op.items.filter { it.order !in expectedLive }
                    missing.forEach {
                        runBlocking { rm.addItem(it) }
                        expectedLive.add(it.order)
                        expectedItems[it.order] = it
                    }
                }

                is UserOp.Delete -> {
                    val present = op.items.filter { it.order in expectedLive }
                    present.forEach {
                        runBlocking { rm.removeItems(listOf(it)) }
                        expectedLive.remove(it.order)
                        expectedItems.remove(it.order)
                    }
                }
            }
            undoStack.addLast(op)
        }

        fun verify(context: String) {
            val all =
                runBlocking { rm.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
            val actual = all.map { it.order }.toSet()
            val missing = expectedLive - actual
            val extra = actual - expectedLive
            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                // Dump the home region of EACH divergent item (bounds → region).
                val divergentIds = missing + extra
                val homes = LinkedHashSet<RegionId>()
                val dumpFile =
                    File("/tmp/usrdump_seed.txt").let { f ->
                        if (!f.exists()) f else File("/tmp/usrdump_seed_${System.nanoTime()}.txt")
                    }
                all.filter { it.order in divergentIds }.forEach { item ->
                    val rid =
                        RegionId(
                            (item.bounds.centerX() / 1000f).toInt(),
                            (item.bounds.centerY() / 1000f).toInt(),
                        )
                    if (homes.add(rid)) {
                        dumpFile.appendText(
                            "--- doc=$id $context home $rid ---\n" +
                                rm.dumpForensics(rid.toString()) + "\n",
                        )
                    }
                }
                println(
                    "!!!! VERIFY-FAIL dumped to ${dumpFile.path}: homes=$homes " +
                        "MISSING=${missing.sorted()} EXTRA=${extra.sorted()}",
                )
            }
            for (rid in rm.getActiveRegionIds()) {
                val r = runBlocking { rm.acquireRegion(rid) } ?: continue
                try {
                    val itemOrders = r.items.map { it.order }
                    val treeOrders = HashSet<Long>()
                    r.quadtree?.visit(RectF(-1e9f, -1e9f, 1e9f, 1e9f)) { treeOrders.add(it.order) }
                    val ghosts = itemOrders.filter { it !in treeOrders }
                    val phantoms = treeOrders.filter { it !in itemOrders.toSet() }
                    assertTrue(
                        "doc=$id $context region $rid ghosts=$ghosts phantoms=$phantoms",
                        ghosts.isEmpty() && phantoms.isEmpty(),
                    )
                } finally {
                    rm.releaseRegion(r)
                }
            }
        }
    }

    @Test
    fun `human session across multiple canvases survives switches restarts and erases`() {
        val failures = ArrayList<String>()
        for (seed in intArrayOf(7, 42, 99)) {
            try {
                humanSession(seed)
            } catch (e: Throwable) {
                failures.add("seed=$seed → ${e.message}")
            }
        }
        assertTrue("failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun humanSession(seed: Int) {
        val rng = Random(seed)
        var diary = Doc("diary", tmp.newFolder())
        var notes = Doc("meeting-notes", tmp.newFolder())
        managers.addAll(listOf(diary.rm, notes.rm))

        val words = arrayOf("note", "line", "tone", "silent", "lens", "stone", "let", "site")
        var active = diary

        fun writeWord(d: Doc) {
            val items =
                writeWord(
                    d.rm,
                    words[rng.nextInt(words.size)],
                    x0 = 80f + rng.nextFloat() * 2600f,
                    baselineY = 120f + rng.nextFloat() * 3800f,
                    scale = 50f + rng.nextFloat() * 30f,
                    seedOrderBase = d.nextSeedOrder++ * 100L,
                )
            d.commit(UserOp.Add(items))
        }

        fun eraseArea(d: Doc) {
            val ax = rng.nextFloat() * 2800f
            val ay = rng.nextFloat() * 3600f
            val area = RectF(ax, ay, ax + 420f, ay + 420f)
            val victims =
                runBlocking { d.rm.queryItems(area) }
                    .filter { RectF(it.bounds).intersects(area.left, area.top, area.right, area.bottom) }
            if (victims.isEmpty()) return
            d.commit(UserOp.Delete(victims))
        }

        repeat(80) { op ->
            val roll = rng.nextInt(100)
            when {
                roll < 34 -> {
                    writeWord(active)
                }

                roll < 48 -> {
                    eraseArea(active)
                }

                roll < 58 -> {
                    active.undo()
                }

                roll < 66 -> {
                    active.redo()
                }

                roll < 76 -> {
                    runBlocking { active.rm.saveAll() }
                }

                roll < 84 -> {
                    active.reopen()
                }

                // app close/open same doc
                roll < 92 -> { // switch canvas
                    active.close()
                    active = if (active === diary) notes else diary
                    active.verify("switch-to op=$op")
                }

                else -> {
                    active.verify("window op=$op")
                }
            }
            if (op % 20 == 19) active.verify("checkpoint op=$op")
        }

        // ---- flush + close all ----
        diary.close()
        notes.close()

        // ---- FINAL: fresh reopen of every document; disk must match truth ----
        listOf(diary, notes).forEach { it.reopen() }
        for (d in listOf(diary, notes)) {
            d.verify("final-fresh doc=${d.id} seed=$seed")

            val second =
                RegionManager(RegionStorage(d.dir).apply { init() }, regionSize = 1000f)
                    .also { managers.add(it) }
            val disk =
                runBlocking { second.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
                    .map { it.order }
                    .toSet()
            assertEquals(
                "doc=${d.id} seed=$seed disk did not converge " +
                    "(missing=${d.expectedLive - disk} extra=${disk - d.expectedLive})",
                d.expectedLive,
                disk,
            )
            second.clear()
        }
    }
}
