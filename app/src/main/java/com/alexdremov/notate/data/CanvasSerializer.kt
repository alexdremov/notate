package com.alexdremov.notate.data

import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint

/**
 * Handles serialization and deserialization of the Canvas model.
 * Decouples the InfiniteCanvasModel from the data transfer format.
 */
object CanvasSerializer {
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
                val floats = FloatArray(count * 4)
                val longs = LongArray(count)

                for (i in 0 until count) {
                    val p = stroke.points[i]
                    floats[i * 4] = p.x
                    floats[i * 4 + 1] = p.y
                    floats[i * 4 + 2] = p.pressure
                    floats[i * 4 + 3] = p.size
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
            version = 2,
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

                    for (i in 0 until count) {
                        val x = floats[i * 4]
                        val y = floats[i * 4 + 1]
                        val rawP = floats[i * 4 + 2]
                        val s = floats[i * 4 + 3]
                        val t = longs[i]

                        val pressure = if (rawP.isNaN() || rawP <= 0f) defaultMaxPressure else rawP
                        points.add(TouchPoint(x, y, pressure, s, t))
                    }
                } else {
                    sData.points.forEach { pData ->
                        val pressure =
                            if (pData.pressure.isNaN() || pData.pressure <= 0f) {
                                defaultMaxPressure
                            } else {
                                pData.pressure
                            }
                        points.add(TouchPoint(pData.x, pData.y, pressure, pData.size, pData.timestamp))
                    }
                }

                val path = Path()
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
            Log.e("CanvasSerializer", "Error loading canvas data", e)
        }
    }
}
