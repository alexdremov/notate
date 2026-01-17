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
        allItems: List<com.alexdremov.notate.model.CanvasItem>,
        canvasType: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
        backgroundStyle: com.alexdremov.notate.model.BackgroundStyle,
        zoomLevel: Float,
        offsetX: Float,
        offsetY: Float,
        toolbarItems: List<com.alexdremov.notate.model.ToolbarItem> = emptyList(),
    ): CanvasData {
        val strokeDataList = ArrayList<StrokeData>()
        val imageDataList = ArrayList<CanvasImageData>()

        for (item in allItems) {
            when (item) {
                is Stroke -> {
                    val count = item.points.size
                    val floats = FloatArray(count * 6)
                    val longs = LongArray(count)

                    for (i in 0 until count) {
                        val p = item.points[i]
                        floats[i * 6] = p.x
                        floats[i * 6 + 1] = p.y
                        floats[i * 6 + 2] = p.pressure
                        floats[i * 6 + 3] = p.size
                        floats[i * 6 + 4] = p.tiltX.toFloat()
                        floats[i * 6 + 5] = p.tiltY.toFloat()
                        longs[i] = p.timestamp
                    }

                    strokeDataList.add(
                        StrokeData(
                            points = emptyList(),
                            pointsPacked = floats,
                            timestampsPacked = longs,
                            color = item.color,
                            width = item.width,
                            style = item.style,
                            strokeOrder = item.strokeOrder,
                            zIndex = item.zIndex,
                        ),
                    )
                }

                is com.alexdremov.notate.model.CanvasImage -> {
                    imageDataList.add(
                        CanvasImageData(
                            uri = item.uri,
                            x = item.bounds.left,
                            y = item.bounds.top,
                            width = item.bounds.width(),
                            height = item.bounds.height(),
                            zIndex = item.zIndex,
                            order = item.order,
                            rotation = item.rotation,
                            opacity = item.opacity,
                        ),
                    )
                }
            }
        }

        return CanvasData(
            version = 3,
            strokes = strokeDataList,
            images = imageDataList,
            canvasType = canvasType,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            backgroundStyle = backgroundStyle,
            zoomLevel = zoomLevel,
            offsetX = offsetX,
            offsetY = offsetY,
            toolbarItems = toolbarItems,
        )
    }

    fun fromData(
        data: CanvasData,
        onItemLoaded: (com.alexdremov.notate.model.CanvasItem) -> Unit,
    ) {
        val sysPressure = EpdController.getMaxTouchPressure()
        val defaultMaxPressure = if (sysPressure > 0f) sysPressure else 4096f

        try {
            // Load Strokes
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

                onItemLoaded(
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

            // Load Images
            data.images.forEach { iData ->
                onItemLoaded(
                    com.alexdremov.notate.model.CanvasImage(
                        uri = iData.uri,
                        bounds = RectF(iData.x, iData.y, iData.x + iData.width, iData.y + iData.height),
                        zIndex = iData.zIndex,
                        order = iData.order,
                        rotation = iData.rotation,
                        opacity = iData.opacity,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading canvas data", e)
        }
    }

    data class LoadedCanvasState(
        val items: List<com.alexdremov.notate.model.CanvasItem>,
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
        val toolbarItems: List<com.alexdremov.notate.model.ToolbarItem> = emptyList(),
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

            // 1. Parallel Processing: Reconstruct Paths & Bounds for Strokes
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

            val strokes = deferredStrokes.mapNotNull { it.await() }

            // Load Images (simple mapping, no async needed usually, but consistent)
            val images =
                data.images.map { iData ->
                    com.alexdremov.notate.model.CanvasImage(
                        uri = iData.uri,
                        bounds = RectF(iData.x, iData.y, iData.x + iData.width, iData.y + iData.height),
                        zIndex = iData.zIndex,
                        order = iData.order,
                        rotation = iData.rotation,
                        opacity = iData.opacity,
                    )
                }

            val items = (strokes + images).toMutableList()
            val parallelTime = System.currentTimeMillis()

            // 2. Compute Global Bounds & Max Order
            val contentBounds = RectF()
            var nextStrokeOrder: Long = 0

            if (items.isNotEmpty()) {
                contentBounds.set(items[0].bounds)
                for (item in items) {
                    contentBounds.union(item.bounds)
                    if (item.order >= nextStrokeOrder) {
                        nextStrokeOrder = item.order + 1
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

            // 4. Batch Insert
            for (item in items) {
                quadtree = quadtree.insert(item)
            }
            val insertTime = System.currentTimeMillis()

            Log.d(TAG, "Canvas Load Stats: Items=${items.size}")
            Log.d(TAG, "  Parallel Parse: ${parallelTime - startTime}ms")
            Log.d(TAG, "  Bounds Calc:    ${boundsTime - parallelTime}ms")
            Log.d(TAG, "  Quadtree Build: ${insertTime - boundsTime}ms")
            Log.d(TAG, "  Total Parse:    ${insertTime - startTime}ms")

            LoadedCanvasState(
                items = items,
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
                toolbarItems = data.toolbarItems,
            )
        }
}
