package com.alexdremov.notate.util

import android.util.Log
import com.alexdremov.notate.config.CanvasConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime

object PerformanceProfiler {
    private const val TAG = "Profiler"

    // Stats container
    private data class Stat(
        val count: AtomicLong = AtomicLong(0),
        val totalNanos: AtomicLong = AtomicLong(0),
        val maxNanos: AtomicLong = AtomicLong(0),
    )

    private val stats = ConcurrentHashMap<String, Stat>()
    private var lastReportTime = System.currentTimeMillis()

    /**
     * Measures the execution time of the given block.
     * Safe to call from any thread.
     */
    inline fun <T> trace(
        name: String,
        block: () -> T,
    ): T {
        if (!CanvasConfig.DEBUG_ENABLE_PROFILING) return block()

        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val duration = System.nanoTime() - start
            record(name, duration)
        }
    }

    fun record(
        name: String,
        durationNanos: Long,
    ) {
        if (!CanvasConfig.DEBUG_ENABLE_PROFILING) return

        val stat = stats.computeIfAbsent(name) { Stat() }
        stat.count.incrementAndGet()
        stat.totalNanos.addAndGet(durationNanos)

        // Update max atomically
        var currentMax = stat.maxNanos.get()
        while (durationNanos > currentMax) {
            if (stat.maxNanos.compareAndSet(currentMax, durationNanos)) break
            currentMax = stat.maxNanos.get()
        }
    }

    /**
     * Prints a report to Logcat if the interval has passed.
     * Should be called typically once per frame from the UI thread.
     */
    fun printReportIfNeeded() {
        if (!CanvasConfig.DEBUG_ENABLE_PROFILING) return

        val now = System.currentTimeMillis()
        if (now - lastReportTime > CanvasConfig.PROFILING_INTERVAL_MS) {
            printReport()
            lastReportTime = now
        }
    }

    private fun printReport() {
        val sb = StringBuilder()
        sb.append("\n=== Performance Report (").append(CanvasConfig.PROFILING_INTERVAL_MS).append("ms) ===\n")
        sb.append(String.format("%-35s | %-6s | %-8s | %-8s\n", "Section", "Count", "Avg(ms)", "Max(ms)"))
        sb.append("-".repeat(65)).append("\n")

        val sortedStats = stats.entries.sortedByDescending { it.value.totalNanos.get() }

        for ((name, stat) in sortedStats) {
            val count = stat.count.get()
            if (count == 0L) continue

            val total = stat.totalNanos.get()
            val max = stat.maxNanos.get()
            val avg = total.toDouble() / count.toDouble()

            sb.append(
                String.format(
                    "%-35s | %6d | %8.3f | %8.3f\n",
                    name.take(35),
                    count,
                    avg / 1_000_000.0,
                    max / 1_000_000.0,
                ),
            )

            // Reset for next interval
            stat.count.set(0)
            stat.totalNanos.set(0)
            stat.maxNanos.set(0)
        }
        sb.append("=================================================================\n")
        Log.d(TAG, sb.toString())
    }
}
