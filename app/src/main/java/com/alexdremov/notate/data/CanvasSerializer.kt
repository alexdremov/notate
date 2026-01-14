package com.alexdremov.notate.data

import android.graphics.RectF
import android.util.Log
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Handles serialization and deserialization of the Canvas model.
 * Decouples the InfiniteCanvasModel from the data transfer format.
 */
object CanvasSerializer {
    private const val TAG = "CanvasSerializer"

    fun toData(
        allStrokes: List<Stroke>,
        canvasType: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
        backgroundStyle: com.alexdremov.notate.model.BackgroundStyle,
        zoomLevel: Float,
        offsetX: Float,
        offsetY: Float,
    ): CanvasData {
        val strokeDataList =
            allStrokes.map { stroke ->
                val count = stroke.points.size
                val floats = FloatArray(count * 6)
                val longs = LongArray(count)

                for (i in 0 until count) {
                    val p = stroke.points[i]
                    floats[i * 6] = p.x
                    floats[i * 6 + 1] = p.y
                    floats[i * 6 + 2] = p.pressure
                    floats[i * 6 + 3] = p.size
                    floats[i * 6 + 4] = p.tiltX.toFloat()
                    floats[i * 6 + 5] = p.tiltY.toFloat()
                    longs[i] = p.timestamp
                }

                StrokeData(
                    points = emptyList(),
                    pointsPacked = floats,
                    timestampsPacked = longs,
                    color = stroke.color,
                    width = stroke.width,
                    style = stroke.style,
                    strokeOrder = stroke.strokeOrder,
                    zIndex = stroke.zIndex,
                )
            }
        return CanvasData(
            version = 3,
            strokes = strokeDataList,
            canvasType = canvasType,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            backgroundStyle = backgroundStyle,
            zoomLevel = zoomLevel,
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }

    fun fromData(
        data: CanvasData,
        onStrokeLoaded: (Stroke) -> Unit,
    ) {
        val sysPressure = EpdController.getMaxTouchPressure()
        val defaultMaxPressure = if (sysPressure > 0f) sysPressure else 4096f

        try {
            data.strokes.forEach { sData ->
                val points = ArrayList<TouchPoint>()

                if (sData.pointsPacked != null && sData.timestampsPacked != null) {
                    val floats = sData.pointsPacked
                    val longs = sData.timestampsPacked
                    val count = longs.size
                    val stride = floats.size / count

                    for (i in 0 until count) {
                        val base = i * stride
                        val x = floats[base]
                        val y = floats[base + 1]
                        val rawP = floats[base + 2]
                        val s = floats[base + 3]
                        val tiltX = if (stride >= 6) floats[base + 4] else 0f
                        val tiltY = if (stride >= 6) floats[base + 5] else 0f
                        val t = longs[i]

                        val pressure = if (rawP.isNaN() || rawP <= 0f) defaultMaxPressure else rawP
                        points.add(TouchPoint(x, y, pressure, s, tiltX.toInt(), tiltY.toInt(), t))
                    }
                } else {
                    sData.points.forEach { pData ->
                        val pressure =
                            if (pData.pressure.isNaN() || pData.pressure <= 0f) {
                                defaultMaxPressure
                            } else {
                                pData.pressure
                            }
                        points.add(
                            TouchPoint(
                                pData.x,
                                pData.y,
                                pressure,
                                pData.size,
                                pData.tiltX.toInt(),
                                pData.tiltY.toInt(),
                                pData.timestamp,
                            ),
                        )
                    }
                }

                val path = android.graphics.Path()
                if (points.isNotEmpty()) {
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        val p1 = points[i - 1]
                        val p2 = points[i]
                        val cx = (p1.x + p2.x) / 2
                        val cy = (p1.y + p2.y) / 2
                        path.quadTo(p1.x, p1.y, cx, cy)
                    }
                    path.lineTo(points.last().x, points.last().y)
                }

                val bounds = StrokeGeometry.computeStrokeBounds(path, sData.width, sData.style)

                onStrokeLoaded(
                    Stroke(
                        path = path,
                        points = points,
                        color = sData.color,
                        width = sData.width,
                        style = sData.style,
                        bounds = bounds,
                        strokeOrder = sData.strokeOrder,
                        zIndex = sData.zIndex,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading canvas data", e)
        }
    }

    data class LoadedCanvasState(
        val strokes: List<Stroke>,
        val quadtree: com.alexdremov.notate.util.Quadtree,
        val contentBounds: RectF,
        val nextStrokeOrder: Long,
        val canvasType: CanvasType,
        val pageWidth: Float,
        val pageHeight: Float,
        val backgroundStyle: com.alexdremov.notate.model.BackgroundStyle,
        val viewportScale: Float,
        val viewportOffsetX: Float,
        val viewportOffsetY: Float,
    )

    /**
     * Parses CanvasData into a fully prepared state model.
     * Heavily computationally expensive (Path reconstruction, Bounds calc, Quadtree insert).
     * optimized with parallel processing.
     */
    suspend fun parseCanvasData(data: CanvasData): LoadedCanvasState =
        kotlinx.coroutines.coroutineScope {
            val startTime = System.currentTimeMillis()
            val sysPressure = EpdController.getMaxTouchPressure()
            val defaultMaxPressure = if (sysPressure > 0f) sysPressure else 4096f

            // 1. Parallel Processing: Reconstruct Paths & Bounds
            // We chunk the work to avoid creating too many coroutines overhead for huge stroke counts,
            // though for <10k strokes, individual async might be fine. Let's just map async.
            val deferredStrokes =
                data.strokes.map { sData ->
                    async(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            val points = ArrayList<TouchPoint>()

                            if (sData.pointsPacked != null && sData.timestampsPacked != null) {
                                val floats = sData.pointsPacked
                                val longs = sData.timestampsPacked
                                val count = longs.size
                                val stride = floats.size / count

                                for (i in 0 until count) {
                                    val base = i * stride
                                    val x = floats[base]
                                    val y = floats[base + 1]
                                    val rawP = floats[base + 2]
                                    val s = floats[base + 3]
                                    val tiltX = if (stride >= 6) floats[base + 4] else 0f
                                    val tiltY = if (stride >= 6) floats[base + 5] else 0f
                                    val t = longs[i]

                                    val pressure =
                                        if (rawP.isNaN() || rawP <= 0f) defaultMaxPressure else rawP
                                    points.add(TouchPoint(x, y, pressure, s, tiltX.toInt(), tiltY.toInt(), t))
                                }
                            } else {
                                sData.points.forEach { pData ->
                                    val pressure =
                                        if (pData.pressure.isNaN() || pData.pressure <= 0f) {
                                            defaultMaxPressure
                                        } else {
                                            pData.pressure
                                        }
                                    points.add(
                                        TouchPoint(
                                            pData.x,
                                            pData.y,
                                            pressure,
                                            pData.size,
                                            pData.tiltX.toInt(),
                                            pData.tiltY.toInt(),
                                            pData.timestamp,
                                        ),
                                    )
                                }
                            }

                            // Reconstruct Path
                            val path = android.graphics.Path()
                            if (points.isNotEmpty()) {
                                path.moveTo(points[0].x, points[0].y)
                                for (i in 1 until points.size) {
                                    val p1 = points[i - 1]
                                    val p2 = points[i]
                                    val cx = (p1.x + p2.x) / 2
                                    val cy = (p1.y + p2.y) / 2
                                    path.quadTo(p1.x, p1.y, cx, cy)
                                }
                                path.lineTo(points.last().x, points.last().y)
                            }

                            val bounds =
                                StrokeGeometry.computeStrokeBounds(path, sData.width, sData.style)

                            Stroke(
                                path = path,
                                points = points,
                                color = sData.color,
                                width = sData.width,
                                style = sData.style,
                                bounds = bounds,
                                strokeOrder = sData.strokeOrder,
                                zIndex = sData.zIndex,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing stroke", e)
                            null
                        }
                    }
                }

            val strokes = deferredStrokes.mapNotNull { it.await() }.toMutableList()
            val parallelTime = System.currentTimeMillis()

            // 2. Compute Global Bounds & Max Order
            val contentBounds = RectF()
            var nextStrokeOrder: Long = 0

            if (strokes.isNotEmpty()) {
                contentBounds.set(strokes[0].bounds)
                for (s in strokes) {
                    contentBounds.union(s.bounds)
                    if (s.strokeOrder >= nextStrokeOrder) {
                        nextStrokeOrder = s.strokeOrder + 1
                    }
                }
            }
            val boundsTime = System.currentTimeMillis()

            // 3. Initialize Quadtree with correct bounds
            // Use a default minimum size if content is empty or too small
            val qBounds = RectF(contentBounds)
            if (qBounds.width() < 1000f) qBounds.inset(-500f, 0f)
            if (qBounds.height() < 1000f) qBounds.inset(0f, -500f)
            // Ensure it covers at least the default area
            qBounds.union(RectF(-5000f, -5000f, 5000f, 5000f))

            var quadtree =
                com.alexdremov.notate.util
                    .Quadtree(0, qBounds)

            // 4. Batch Insert (Sequential but fast as bounds are pre-set)
            for (s in strokes) {
                quadtree = quadtree.insert(s)
            }
            val insertTime = System.currentTimeMillis()

            Log.d(TAG, "Canvas Load Stats: Strokes=${strokes.size}")
            Log.d(TAG, "  Parallel Parse: ${parallelTime - startTime}ms")
            Log.d(TAG, "  Bounds Calc:    ${boundsTime - parallelTime}ms")
            Log.d(TAG, "  Quadtree Build: ${insertTime - boundsTime}ms")
            Log.d(TAG, "  Total Parse:    ${insertTime - startTime}ms")

            LoadedCanvasState(
                strokes = strokes,
                quadtree = quadtree,
                contentBounds = contentBounds,
                nextStrokeOrder = nextStrokeOrder,
                canvasType = data.canvasType,
                pageWidth = data.pageWidth,
                pageHeight = data.pageHeight,
                backgroundStyle = data.backgroundStyle,
                viewportScale = data.zoomLevel,
                viewportOffsetX = data.offsetX,
                viewportOffsetY = data.offsetY,
            )
        }
}
