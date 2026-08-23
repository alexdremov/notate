package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Performance contract of the render read path.
 *
 * The rendering pipeline must be blocked ONLY by drawing itself: region
 * loading, saving and tile logic all happen on background coroutines and must
 * stay invisible to query latency. Additionally, because the read path is
 * lock-free (CoW published lists), aggregate query throughput must SCALE with
 * available CPU cores.
 *
 * What these tests assert:
 *  1. **IO invisibility** — while a background storm of saves and loads is in
 *     flight, per-query latency stays within a small multiple of the quiescent
 *     baseline (the render thread never waits on disk).
 *  2. **Parallel scaling** — P concurrent reader threads achieve ≈P× the
 *     single-thread query rate (lock-free reads ⇒ no contention point).
 *
 * Thresholds are deliberately generous (CI machines are slow and shared);
 * this is a regression tripwire for ORDER-OF-MAGNITUDE breakage — e.g. someone
 * reintroducing locking or IO into the read path — not a benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPipelinePerformanceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** Dense-ish stroke: 40 points, ~1KB payload. */
    private fun stroke(
        order: Long,
        x: Float,
        y: Float,
    ): Stroke {
        val pts = ArrayList<TouchPoint>(40)
        val path = android.graphics.Path()
        for (i in 0 until 40) {
            val t = i / 39f
            val px = x + 300f * t + kotlin.math.sin(t * 20f) * 8f
            val py = y + 60f * kotlin.math.sin(t * 6.28f) + i * 0.5f
            pts.add(TouchPoint(px, py, 0.5f + 0.4f * t, 4f, i.toLong()))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return Stroke(
            path,
            pts,
            0xFF101010.toInt(),
            width = 2.5f,
            style = StrokeType.FOUNTAIN,
            bounds = RectF(x, y - 65f, x + 300f, y + 65f),
            strokeOrder = order,
        )
    }

    /**
     * Builds a canvas with `regions` regions × `perRegion` strokes, flushes to
     * disk, and returns a manager whose cache starts EMPTY (all reads exercise
     * the real load path at least once before measurement windows begin).
     */
    private fun populatedCanvas(
        dir: File,
        regions: Int,
        perRegion: Int,
    ): RegionManager {
        val rm =
            RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f, memoryLimitBytes = 256 * 1024L)
        runBlocking {
            for (rx in 0 until regions) {
                for (j in 0 until perRegion) {
                    rm.addItem(stroke((rx * perRegion + j).toLong(), rx * 1000f + 50f, 50f + j * 25f))
                }
            }
        }
        rm.saveAll()
        return rm
    }

    private fun percentile(
        latenciesNs: List<Long>,
        p: Double,
    ): Double {
        val sorted = latenciesNs.sorted()
        val idx = ((sorted.size - 1) * p).toInt()
        return sorted[idx] / 1_000.0 // µs
    }

    // ------------------------------------------------------------------
    // 1. IO invisibility: queries during a save/load storm stay fast.
    // ------------------------------------------------------------------
    @Test
    fun `query latency is unaffected by concurrent save-load IO storm`() {
        val dir = tmp.newFolder()
        val rm = populatedCanvas(dir, regions = 12, perRegion = 30)

        // Quiescent baseline: single-threaded query rate over resident data.
        val probe = RectF(0f, 0f, 50000f, 50000f)
        val baseline = ArrayList<Long>()
        repeat(200) {
            val t0 = System.nanoTime()
            runBlocking { rm.queryItems(probe) }
            baseline.add(System.nanoTime() - t0)
        }
        val baselineP99 = percentile(baseline, 0.99)

        // IO storm: continuously mutate (dirtying regions → background saves)
        // while queries run; the store's own scope does the disk work off the
        // read path.
        var stopStorm = false // visibility via Thread.start/join happens-before
        val storm =
            Thread {
                runBlocking {
                    var n = 10_000L
                    while (!stopStorm) {
                        rm.addItem(stroke(n++, rngX(), rngY()))
                        rm.saveAll()
                    }
                }
            }
        storm.start()

        // Measure query latency UNDER the storm.
        val underLoad = ArrayList<Long>()
        try {
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val t0 = System.nanoTime()
                runBlocking { rm.queryItems(probe) }
                underLoad.add(System.nanoTime() - t0)
            }
        } finally {
            stopStorm = true
            storm.join(10_000)
        }

        val loadP99 = percentile(underLoad, 0.99)
        val loadMedian = percentile(underLoad, 0.5)

        // The render path must not regress by an order of magnitude while IO
        // churns in the background. Generous multiple: CI-safe tripwire.
        assertTrue(
            "p99 under IO storm (${loadP99}µs) regressed >20× vs baseline " +
                "(${baselineP99}µs) — read path is blocking on background work",
            loadP99 < max(baselineP99 * 20, 5_000.0),
        )
        // Median should be essentially untouched.
        assertTrue(
            "median under IO storm (${loadMedian}µs) regressed >10× vs baseline",
            loadMedian < max(baselineP99 * 10, 2_000.0),
        )
    }

    private fun rngX(): Float = (System.nanoTime() % 4000).toFloat()

    private fun rngY(): Float = (System.nanoTime() % 4000).toFloat()

    // ------------------------------------------------------------------
    // 2. Parallel scaling: readers are lock-free → throughput scales with cores.
    // ------------------------------------------------------------------
    @Test
    fun `parallel readers scale with available cores`() {
        val dir = tmp.newFolder()
        val rm = populatedCanvas(dir, regions = 16, perRegion = 40)
        val probe = RectF(0f, 0f, 50000f, 50000f)

        fun rateWith(
            parallelism: Int,
            millis: Long,
        ): Double {
            val pool = Executors.newFixedThreadPool(parallelism)
            val total =
                java.util.concurrent.atomic
                    .AtomicLong()
            val start = System.nanoTime()
            val futures =
                (0 until parallelism).map {
                    pool.submit {
                        var ops = 0L
                        val localEnd = System.nanoTime() + millis * 1_000_000
                        while (System.nanoTime() < localEnd) {
                            runBlocking { rm.queryItems(probe) }
                            ops++
                        }
                        total.addAndGet(ops)
                    }
                }
            futures.forEach { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
            val elapsedSec = (System.nanoTime() - start) / 1e9
            return total.get() / elapsedSec
        }

        val single = rateWith(1, millis = 1_200)
        val parallel = rateWith(cores, millis = 1_500)

        val scaling = parallel / single
        println(
            "!!!! SCALING cores=$cores single=%.0f q/s parallel=%.0f q/s scaling=%.2fx"
                .format(single, parallel, scaling),
        )

        // Lock-free reads must scale; require at least 2× from ≥2 cores
        // (near-linear would be ~cores×; 2× leaves huge headroom for slow CI).
        assertTrue(
            "parallel scaling only ${"%.2f".format(scaling)}× with $cores cores " +
                "(single=$single q/s, parallel=$parallel q/s) — read path has a contention point",
            scaling >= 2.0,
        )
    }

    // ------------------------------------------------------------------
    // 3. Mutation latency budget: addItem stays O(ms) even when the store is
    //    large and background saves are running (write path never waits on
    //    disk either).
    // ------------------------------------------------------------------
    @Test
    fun `addItem latency stays bounded during background saves`() {
        val dir = tmp.newFolder()
        val rm = populatedCanvas(dir, regions = 8, perRegion = 40)

        val stopFlag =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val saver =
            Thread {
                runBlocking {
                    while (!stopFlag.get()) {
                        rm.saveAll()
                        Thread.sleep(5)
                    }
                }
            }
        saver.start()

        val latencies = ArrayList<Long>()
        try {
            var n = 50_000L
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                val t0 = System.nanoTime()
                runBlocking { rm.addItem(stroke(n++, rngX(), rngY())) }
                latencies.add(System.nanoTime() - t0)
            }
        } finally {
            stopFlag.set(true)
            saver.join(10_000)
        }

        val p95 = percentile(latencies, 0.95)
        assertTrue(
            "addItem p95=${"%.0f".format(p95)}µs exceeded 25ms budget — write path is blocking",
            p95 < 25_000.0,
        )
    }
}
