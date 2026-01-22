package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ThumbnailGenerator {
    private const val THUMB_WIDTH = 300
    private const val THUMB_HEIGHT = 300
    private const val PADDING = 10f

    fun generateBase64(
        regionManager: RegionManager,
        metadata: CanvasData,
        context: android.content.Context,
    ): String? =
        try {
            val bitmap = generateBitmapFromRegions(regionManager, metadata, context)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val byteArray = outputStream.toByteArray()
            bitmap.recycle()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e("Thumbnail", "Generation from regions failed", e)
            null
        }

    private fun generateBitmapFromRegions(
        regionManager: RegionManager,
        metadata: CanvasData,
        context: android.content.Context,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val bounds =
            when (metadata.canvasType) {
                CanvasType.FIXED_PAGES -> {
                    val w = if (metadata.pageWidth > 0) metadata.pageWidth else 2480f
                    val h = if (metadata.pageHeight > 0) metadata.pageHeight else 3508f
                    RectF(0f, 0f, w, h)
                }

                CanvasType.INFINITE -> {
                    regionManager.getContentBounds().takeIf { !it.isEmpty } ?: RectF(0f, 0f, 1000f, 1000f)
                }
            }

        // Setup Transform
        val scaleX = (THUMB_WIDTH - PADDING * 2) / bounds.width()
        val scaleY = (THUMB_HEIGHT - PADDING * 2) / bounds.height()
        val scale = min(scaleX, scaleY).takeIf { it.isFinite() && it > 0 } ?: 1f

        val dx = (THUMB_WIDTH - bounds.width() * scale) / 2f - bounds.left * scale
        val dy = (THUMB_HEIGHT - bounds.height() * scale) / 2f - bounds.top * scale

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        // Use region-aware rendering to avoid OOM
        renderThumbnailFromRegions(canvas, regionManager, bounds, paint, scale, context)

        canvas.restore()
        return bitmap
    }

    /**
     * Region-aware thumbnail rendering that processes regions incrementally
     * to avoid loading all strokes into memory at once.
     */
    private fun renderThumbnailFromRegions(
        canvas: Canvas,
        regionManager: RegionManager,
        bounds: RectF,
        paint: Paint,
        scale: Float,
        context: android.content.Context,
    ) {
        // Get regions that intersect with our thumbnail bounds
        val regions = regionManager.getRegionsInRect(bounds)

        if (regions.isEmpty()) {
            return
        }

        // Dynamic region limit based on available memory and region count
        val availableMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val memoryBasedLimit =
            when {
                availableMemoryMB > 512 -> 100

                // High-end devices can handle more
                availableMemoryMB > 256 -> 50

                // Mid-range devices
                else -> 30 // Low-memory devices
            }

        // Also consider the actual region count - don't process more than we have
        val reasonableLimit = minOf(memoryBasedLimit, regions.size)

        // Smart prioritization: process regions with most content first
        val regionsToProcess =
            if (regions.size <= reasonableLimit) {
                regions
            } else {
                // Capture bounds center for use in lambda
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()

                // Get region size for bounds calculation
                val regionSize = regionManager.regionSize

                // Prioritize by content density (items per region) and visual importance (center regions)
                regions
                    .sortedWith(
                        compareByDescending<com.alexdremov.notate.data.region.RegionData> {
                            // Primary: regions with content come first
                            if (it.items.isEmpty()) 0 else 1
                        }.thenByDescending {
                            // Secondary: more items = higher priority
                            it.items.size
                        }.thenBy { region ->
                            // Tertiary: center regions first (more likely to be visible)
                            val regionBounds = region.id.getBounds(regionSize)
                            val regionCenterX = regionBounds.centerX()
                            val regionCenterY = regionBounds.centerY()
                            // Distance from center - closer regions have higher priority
                            val distance =
                                kotlin.math.sqrt(
                                    (centerX - regionCenterX) * (centerX - regionCenterX) +
                                        (centerY - regionCenterY) * (centerY - regionCenterY),
                                )
                            distance
                        },
                    ).take(reasonableLimit)
            }

        Logger.d(
            "ThumbnailGenerator",
            "Processing ${regionsToProcess.size} of ${regions.size} regions for thumbnail",
        )

        // Process each region individually to avoid memory spikes
        for ((index, region) in regionsToProcess.withIndex()) {
            val regionItems = ArrayList<CanvasItem>()
            region.quadtree?.retrieve(regionItems, bounds)

            // Draw Strokes from this region
            regionItems.filterIsInstance<Stroke>().forEach { stroke ->
                val sData = CanvasSerializer.toStrokeData(stroke)
                drawStroke(canvas, sData, paint, scale)
            }

            // Draw Images from this region
            regionItems.filterIsInstance<CanvasImage>().forEach { img ->
                StrokeRenderer.drawItem(canvas, img, false, paint, context, scale)
            }

            // Clear the temporary list to free memory
            regionItems.clear()

            // Yield periodically to avoid blocking and allow GC
            if (index % 5 == 0) {
                Thread.yield()
                System.gc() // Suggest GC to clean up temporary objects
            }
        }

        // Log only if we actually had to skip a significant number of regions
        if (regions.size > reasonableLimit && (regions.size - reasonableLimit) > 10) {
            Logger.w(
                "ThumbnailGenerator",
                "Thumbnail generation processed ${regionsToProcess.size} regions (${regions.size} total) - consider optimizing canvas density",
            )
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: StrokeData,
        paint: Paint,
        viewScale: Float,
    ) {
        paint.color = stroke.color

        // Ensure visibility on minimap/thumbnail (large canvases)
        val minPixels = 1.0f
        val minWidth = minPixels / viewScale
        var targetWidth = kotlin.math.max(stroke.width, minWidth)

        if (stroke.style == StrokeType.HIGHLIGHTER) {
            paint.alpha = 80
            targetWidth *= 1.5f
        } else {
            paint.alpha = 255
        }

        paint.strokeWidth = targetWidth

        val path = Path()

        if (stroke.pointsPacked != null && stroke.pointsPacked.size >= 2) {
            val arr = stroke.pointsPacked
            val stride = StrokeData.PACKED_POINT_STRIDE
            path.moveTo(arr[0], arr[1])
            var i = stride
            while (i + 1 < arr.size) {
                path.lineTo(arr[i], arr[i + 1])
                i += stride
            }
        }

        canvas.drawPath(path, paint)
    }
}
