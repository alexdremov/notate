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
    private const val THUMB_WIDTH = 128
    private const val THUMB_HEIGHT = 128
    private const val PADDING = 4f

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
        val regionIds = regionManager.getRegionIdsInRect(bounds)

        if (regionIds.isEmpty()) {
            return
        }

        val regionsToProcess = regionIds.size
        val regionSize = regionManager.regionSize

        // Process each region individually to avoid memory spikes
        for ((index, regionId) in regionIds.withIndex()) {
            val bitmap = regionManager.getRegionThumbnail(regionId, context) ?: continue

            val dstRect =
                RectF(
                    regionId.x * regionSize,
                    regionId.y * regionSize,
                    (regionId.x + 1) * regionSize,
                    (regionId.y + 1) * regionSize,
                )

            // Draw the cached bitmap directly
            // Canvas is already transformed to World->Thumbnail space
            canvas.drawBitmap(bitmap, null, dstRect, null)

            // Yield periodically to avoid blocking and allow GC
            if (index % 5 == 0) {
                Thread.yield()
                System.gc() // Suggest GC to clean up temporary objects
            }
        }
    }
}
