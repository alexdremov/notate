package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.CanvasImage
import java.util.concurrent.ConcurrentHashMap

object ImageRenderer {
    private const val TAG = "ImageRenderer"

    // Cache for bitmaps (downsampled)
    // Use configured % of available heap size for image cache to avoid thrashing with large images
    private val CACHE_SIZE = (Runtime.getRuntime().maxMemory() * CanvasConfig.IMAGE_CACHE_MEMORY_PERCENT).toInt()

    private val imageCache =
        object : LruCache<String, Bitmap>(CACHE_SIZE) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    // Lightweight cache for image dimensions (Width, Height) to avoid File IO
    private val metadataCache = LruCache<String, Pair<Int, Int>>(CanvasConfig.IMAGE_METADATA_CACHE_SIZE)

    // Locks to prevent thundering herd problem when multiple tiles request the same image
    private val loadingLocks = ConcurrentHashMap<String, Any>()

    init {
        Logger.i(TAG, "Initialized ImageCache with size: ${CACHE_SIZE / 1024 / 1024} MB")

        PerformanceProfiler.registerMemoryStats(
            "ImageRenderer",
            object : PerformanceProfiler.MemoryStatsProvider {
                override fun getStats(): Map<String, String> {
                    val iSize = imageCache.size() / (1024 * 1024)
                    val iMax = imageCache.maxSize() / (1024 * 1024)
                    val mSize = metadataCache.size()
                    val mMax = metadataCache.maxSize()

                    return mapOf(
                        "Image Cache (MB)" to "$iSize / $iMax",
                        "Metadata Cache" to "$mSize / $mMax",
                        "Active Locks" to "${loadingLocks.size}",
                    )
                }
            },
        )
    }

    fun draw(
        canvas: Canvas,
        paint: Paint,
        image: CanvasImage,
        context: Context?,
        scale: Float = 1.0f,
    ) {
        com.alexdremov.notate.util.PerformanceProfiler.trace("ImageRenderer.draw") {
            try {
                // Calculate target dimensions in pixels
                val targetWidth = (image.bounds.width() * scale).toInt().coerceAtLeast(1)
                val targetHeight = (image.bounds.height() * scale).toInt().coerceAtLeast(1)

                val uriStr = image.uri

                // 1. Resolve Image Dimensions (Metadata)
                var dimensions = metadataCache.get(uriStr)

                if (dimensions == null) {
                    // Metadata miss: Perform IO to get bounds
                    // Synchronize metadata loading as well
                    val metaLock = loadingLocks.computeIfAbsent("meta_$uriStr") { Any() }
                    synchronized(metaLock) {
                        dimensions = metadataCache.get(uriStr)
                        if (dimensions == null) {
                            val options = BitmapFactory.Options()
                            options.inJustDecodeBounds = true

                            val uri = Uri.parse(uriStr)
                            val isContentScheme = uri.scheme == "content"

                            try {
                                if (isContentScheme) {
                                    if (context != null) {
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            BitmapFactory.decodeStream(stream, null, options)
                                        }
                                    }
                                } else {
                                    BitmapFactory.decodeFile(uri.path, options)
                                }

                                if (options.outWidth > 0 && options.outHeight > 0) {
                                    dimensions = Pair(options.outWidth, options.outHeight)
                                    metadataCache.put(uriStr, dimensions!!)
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Failed to decode bounds for $uriStr", e, showToUser = true)
                            }
                        }
                    }
                }

                if (dimensions == null) return@trace // Failed to resolve dimensions

                val (srcWidth, srcHeight) = dimensions!!

                // 2. Calculate Sample Size (Pure Math, No IO)
                val sampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)

                // 3. Check Bitmap Cache
                val key = "${uriStr}_$sampleSize"
                var bitmap = imageCache.get(key)

                if (bitmap == null || bitmap.isRecycled) {
                    // Cache Miss
                    com.alexdremov.notate.util.PerformanceProfiler
                        .record("ImageRenderer.CacheMiss", 1)

                    // Bitmap miss: Perform IO to load pixel data
                    // Thundering Herd Protection: Synchronize per key
                    val lock = loadingLocks.computeIfAbsent(key) { Any() }

                    synchronized(lock) {
                        // Double check
                        bitmap = imageCache.get(key)
                        if (bitmap == null || bitmap!!.isRecycled) {
                            val options = BitmapFactory.Options()
                            options.inSampleSize = sampleSize
                            // Use RGB_565 to save memory if transparency is not strictly needed?
                            // But for now let's stick to default (ARGB_8888) to be safe with PNGs.
                            // We can optimize this later if needed.

                            val uri = Uri.parse(uriStr)
                            val isContentScheme = uri.scheme == "content"

                            try {
                                if (isContentScheme) {
                                    context?.contentResolver?.openInputStream(uri)?.use { stream ->
                                        bitmap = BitmapFactory.decodeStream(stream, null, options)
                                    }
                                } else {
                                    bitmap = BitmapFactory.decodeFile(uri.path, options)
                                }

                                if (bitmap != null) {
                                    imageCache.put(key, bitmap!!)
                                } else {
                                    Logger.e(TAG, "Failed to decode bitmap for $uriStr with sampleSize $sampleSize", showToUser = true)
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Error loading bitmap content $uriStr", e, showToUser = true)
                            }
                        }
                    }
                } else {
                    // Cache Hit
                    com.alexdremov.notate.util.PerformanceProfiler
                        .record("ImageRenderer.CacheHit", 1)
                }

                // 4. Draw
                if (bitmap != null && !bitmap!!.isRecycled) {
                    val originalAlpha = paint.alpha
                    paint.alpha = (image.opacity * 255).toInt()
                    canvas.save()
                    canvas.rotate(image.rotation, image.bounds.centerX(), image.bounds.centerY())
                    canvas.drawBitmap(bitmap!!, null, image.bounds, paint)
                    canvas.restore()
                    paint.alpha = originalAlpha
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error rendering image: ${image.uri}", e)
            }
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
