package com.alexdremov.notate.util

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Concurrent chaos for [TileCache]: every public method hammered from many
 * threads in random order, including `clear()` mid-flight and cache resizing.
 *
 * Invariants verified:
 *  1. No operation ever throws (any exception fails the test).
 *  2. After quiescence, every snapshot entry holds a live, non-recycled bitmap
 *     sized to the tile (the pool/eviction logic must never retain destroyed
 *     or foreign bitmaps).
 *  3. `put -> get` coherence under contention.
 *
 * A separate deterministic test pins down the pool discipline: `obtainBitmap`
 * must NEVER return a bitmap that is currently resident in the cache — UI
 * threads may be drawing cached tiles at any moment (this is what makes the
 * double-buffering scheme safe).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileCacheChaosTest {
    @Test(timeout = 120_000)
    fun `concurrent random cache operations never corrupt state`() {
        val cache = TileCache(tileSize = 64) // small tiles: fast allocation
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        val futures: List<Future<*>> =
            (0 until 8).map { threadId ->
                pool.submit {
                    start.await()
                    val rng = Random(threadId * 7919L)
                    repeat(3000) {
                        val key =
                            TileCache.TileKey(
                                rng.nextInt(40) - 20,
                                rng.nextInt(40) - 20,
                                rng.nextInt(7),
                            )
                        when (rng.nextInt(100)) {
                            in 0..39 -> cache.put(key, cache.obtainBitmap(), rng.nextInt(1000))
                            in 40..59 -> cache.get(key)
                            in 60..69 -> cache.remove(key)
                            in 70..79 -> cache.snapshot()
                            in 80..87 -> cache.checkBudgetAndResizeIfNeeded(rng.nextInt(32))
                            in 88..93 -> cache.isFull(rng.nextInt(32))
                            in 94..97 -> cache.getVersion(key)
                            else -> if (rng.nextInt(20) == 0) cache.clear()
                        }
                    }
                }
            }
        start.countDown()

        for (f in futures) f.get(90, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        // Post-quiescence invariants.
        for ((key, cached) in cache.snapshot()) {
            assertFalse("recycled bitmap in cache for $key", cached.bitmap.isRecycled)
            assertTrue(cached.bitmap.width == 64 && cached.bitmap.height == 64)
        }

        // Coherence: put followed by get of the same key returns the same instance.
        val probe = cache.obtainBitmap()
        val probeKey = TileCache.TileKey(999, 999, 0)
        cache.put(probeKey, probe, 7)
        assertTrue("put->get coherence violated", cache.get(probeKey) === probe)
    }

    /**
     * Deterministic pool-discipline pin: a bitmap that is resident in the cache
     * must never be re-handed-out by [TileCache.obtainBitmap] — UI threads draw
     * cached tiles without holding locks, so handing out a cached bitmap would
     * let a worker erase/draw it mid-frame.
     */
    @Test(timeout = 60_000)
    fun `obtainBitmap never returns a bitmap that is resident in the cache`() {
        val cache = TileCache(tileSize = 32)
        // Shrink the backing LruCache to ~2 tiles so subsequent puts force real
        // eviction (which is what feeds the bitmap pool).
        shrinkMaxCacheBytes(cache, maxBytes = 8 * 1024)

        repeat(500) { cycle ->
            repeat(20) { i ->
                val bmp = cache.obtainBitmap()
                assertFalse(
                    "cycle=$cycle i=$i: obtain returned a bitmap that is resident in the cache",
                    cache.snapshot().values.any { it.bitmap === bmp },
                )
                cache.put(TileCache.TileKey(i % 4, cycle % 4, 0), bmp, i)
            }
        }
    }

    /** Reflection helper: [TileCache] derives its budget from runtime memory;
     *  tests need a tiny budget to force eviction deterministically. */
    private fun shrinkMaxCacheBytes(
        cache: TileCache,
        maxBytes: Int,
    ) {
        val field = TileCache::class.java.getDeclaredField("memoryCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lru = field.get(cache) as android.util.LruCache<Any, Any>
        lru.resize(maxBytes)
    }
}
