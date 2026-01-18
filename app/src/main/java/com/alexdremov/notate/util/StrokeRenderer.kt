package com.alexdremov.notate.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import java.util.ArrayList

/**
 * SOTA Stroke Rendering Engine for Onyx Boox Devices.
 * Features:
 * 1. Clean Strategy Pattern for different brush types.
 * 2. Thread-safe integration.
 * 3. High-performance path caching.
 */
object StrokeRenderer {
    private interface StrokeRenderingStrategy {
        fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        )
    }

    // --- Strategies ---

    private object FountainStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            paint.style = Paint.Style.FILL
            val path = FountainPenRenderer.getPath(stroke, maxPressure)
            canvas.drawPath(path, paint)
        }
    }

    private object BallpointStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            BallpointPenRenderer.render(canvas, paint, stroke, maxPressure)
        }
    }

    private object CharcoalStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            CharcoalPenRenderer.render(canvas, paint, stroke, maxPressure)
        }
    }

    private object BrushStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            paint.style = Paint.Style.FILL
            // Native brushes don't handle Canvas transforms well.
            // We must manually transform points and render on an Identity canvas.
            OnyxNativeRenderHelper.renderWithTransformFallback(canvas, stroke.points) { localCanvas, localPoints, scale ->
                paint.strokeWidth = stroke.width * scale
                NeoBrushPenWrapper.drawStroke(
                    localCanvas,
                    paint,
                    localPoints,
                    stroke.width * scale,
                    maxPressure,
                    false,
                )
            }
        }
    }

    private object HighlighterStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            // Highlighter Requirement: Consistent Color (No internal overlap darkening).
            // We achieve this by drawing the full stroke OPAQUE into an offscreen layer,
            // then compositing that layer back with the desired Alpha.

            val originalColor = paint.color
            val opaqueColor = originalColor or -0x1000000 // Force Alpha 255

            // Use cached bounds from Stroke (already calculated during creation)
            val bounds = stroke.bounds

            // saveLayer uses the Paint's current alpha for the composition on restore.
            // So we keep 'paint' as is (with alpha) for the saveLayer call.
            val saveCount = canvas.saveLayer(bounds, paint)

            try {
                paint.color = opaqueColor
                paint.style = Paint.Style.STROKE
                // Ensure caps/joins are round for smooth path
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(stroke.path, paint)
            } finally {
                canvas.restoreToCount(saveCount)
                // Restore paint color just in case, though usually reset() is called by caller
                paint.color = originalColor
            }
        }
    }

    private object SimplePathStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(stroke.path, paint)
        }
    }

    private object DashStrategy : StrokeRenderingStrategy {
        override fun render(
            canvas: Canvas,
            paint: Paint,
            stroke: Stroke,
            maxPressure: Float,
        ) {
            paint.style = Paint.Style.STROKE
            paint.pathEffect = DashPathEffect(floatArrayOf(stroke.width * 2, stroke.width * 4), 0f)
            canvas.drawPath(stroke.path, paint)
        }
    }

    /**
     * Contextual data for a single draw call.
     */
    private data class RenderContext(
        val canvas: Canvas,
        val paint: Paint,
        val stroke: Stroke,
        val displayColor: Int,
        val maxPressure: Float,
    )

    private val debugPaint =
        Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

    fun drawItem(
        canvas: Canvas,
        item: com.alexdremov.notate.model.CanvasItem,
        debug: Boolean = false,
        paint: Paint = Paint(), // Optional paint to reuse
        context: android.content.Context? = null,
        scale: Float = 1.0f,
    ) {
        com.alexdremov.notate.util.PerformanceProfiler.trace("StrokeRenderer.drawItem") {
            when (item) {
                is Stroke -> drawStroke(canvas, paint, item, debug)
                is com.alexdremov.notate.model.CanvasImage -> ImageRenderer.draw(canvas, paint, item, context, scale)
            }
        }
    }

    fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        stroke: Stroke,
        debug: Boolean = false,
        forceVector: Boolean = false,
    ) {
        com.alexdremov.notate.util.PerformanceProfiler.trace("StrokeRenderer.drawStroke") {
            if (stroke.points.isEmpty()) {
                DashStrategy.render(canvas, paint, stroke, 0f) // Fallback
                return@trace
            }

            if (CanvasConfig.DEBUG_USE_SIMPLE_RENDERER) {
                SimplePathStrategy.render(canvas, paint, stroke, 0f)
                return@trace
            }

            val maxPressure = getSafeMaxPressure(stroke)
            val displayColor = calculateDisplayColor(stroke)

            // Setup Paint
            paint.reset()
            paint.apply {
                color = displayColor
                strokeWidth = stroke.width
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = null
                shader = null
                colorFilter = null
                isAntiAlias = true
                isDither = true
            }

            // Dispatch to Strategy
            val strategy =
                if (forceVector) {
                    when (stroke.style) {
                        StrokeType.FOUNTAIN -> FountainStrategy

                        StrokeType.HIGHLIGHTER -> HighlighterStrategy

                        StrokeType.DASH -> DashStrategy

                        // Complex types (Ballpoint, Charcoal, Brush) fallback to Simple Path for Vector PDF
                        else -> SimplePathStrategy
                    }
                } else {
                    when (stroke.style) {
                        StrokeType.FOUNTAIN -> FountainStrategy
                        StrokeType.BALLPOINT -> BallpointStrategy
                        StrokeType.CHARCOAL -> CharcoalStrategy
                        StrokeType.BRUSH -> BrushStrategy
                        StrokeType.HIGHLIGHTER -> HighlighterStrategy
                        StrokeType.DASH -> DashStrategy
                        else -> SimplePathStrategy
                    }
                }

            try {
                strategy.render(canvas, paint, stroke, maxPressure)
            } catch (e: Exception) {
                // Fallback on error
                paint.style = Paint.Style.STROKE
                canvas.drawPath(stroke.path, paint)
            }

            if (debug || CanvasConfig.DEBUG_SHOW_BOUNDING_BOX) drawDebugBounds(canvas, stroke.bounds)
        }
    }

    // --- Helpers ---

    private fun calculateDisplayColor(stroke: Stroke): Int {
        val alpha = (Color.alpha(stroke.color) * stroke.style.alphaMultiplier).toInt()
        return (stroke.color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun getSafeMaxPressure(stroke: Stroke): Float {
        var maxObserved = 0f
        for (p in stroke.points) {
            if (p.pressure > maxObserved) maxObserved = p.pressure
        }
        if (maxObserved > 0f && maxObserved <= 1.0f) {
            return 1.0f
        }
        val hwMax = EpdController.getMaxTouchPressure()
        return if (hwMax <= 0f) 4096f else hwMax
    }

    private fun drawDebugBounds(
        canvas: Canvas,
        bounds: RectF,
    ) {
        canvas.drawRect(bounds, debugPaint)
    }

    /**
     * Helper for Onyx Native Wrappers that require Identity Matrix rendering.
     */
    private object OnyxNativeRenderHelper {
        fun renderWithTransformFallback(
            canvas: Canvas,
            points: List<TouchPoint>,
            renderAction: (Canvas, List<TouchPoint>, Float) -> Unit,
        ) {
            val matrix = Matrix()
            canvas.getMatrix(matrix)

            if (matrix.isIdentity) {
                renderAction(canvas, points, 1.0f)
                return
            }

            // Map points to screen space
            val src = FloatArray(points.size * 2)
            for (i in points.indices) {
                src[i * 2] = points[i].x
                src[i * 2 + 1] = points[i].y
            }
            val dst = FloatArray(points.size * 2)
            matrix.mapPoints(dst, src)

            // Calculate scale
            val scale = matrix.mapRadius(1.0f)

            // Create transformed points
            val transformedPoints = ArrayList<TouchPoint>(points.size)
            for (i in points.indices) {
                val p = points[i]
                transformedPoints.add(
                    TouchPoint(
                        dst[i * 2],
                        dst[i * 2 + 1],
                        p.pressure,
                        p.size,
                        p.tiltX,
                        p.tiltY,
                        p.timestamp,
                    ),
                )
            }

            // Render on Identity Canvas
            canvas.save()
            val identity = Matrix()
            canvas.setMatrix(identity)
            try {
                renderAction(canvas, transformedPoints, scale)
            } finally {
                canvas.restore()
            }
        }
    }
}
