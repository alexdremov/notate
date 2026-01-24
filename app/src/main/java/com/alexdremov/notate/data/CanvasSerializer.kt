package com.alexdremov.notate.data

import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint

/**
 * Handles serialization and deserialization of the Canvas model.
 * Decouples the InfiniteCanvasModel from the data transfer format.
 */
object CanvasSerializer {
    private const val TAG = "CanvasSerializer"

    fun toStrokeData(item: Stroke): StrokeData {
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

        return StrokeData(
            pointsPacked = floats,
            timestampsPacked = longs,
            color = item.color,
            width = item.width,
            style = item.style,
            strokeOrder = item.strokeOrder,
            zIndex = item.zIndex,
        )
    }

    fun toCanvasImageData(item: com.alexdremov.notate.model.CanvasImage): CanvasImageData =
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
        )

    fun toData(
        canvasType: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
        backgroundStyle: BackgroundStyle,
        viewportScale: Float,
        viewportOffsetX: Float,
        viewportOffsetY: Float,
        toolbarItems: List<com.alexdremov.notate.model.ToolbarItem> = emptyList(),
        tagIds: List<String> = emptyList(),
        tagDefinitions: List<Tag> = emptyList(),
        regionSize: Float = CanvasConfig.DEFAULT_REGION_SIZE,
        nextStrokeOrder: Long = 0,
    ): CanvasData =
        CanvasData(
            version = 3,
            canvasType = canvasType,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            backgroundStyle = backgroundStyle,
            zoomLevel = viewportScale,
            offsetX = viewportOffsetX,
            offsetY = viewportOffsetY,
            toolbarItems = toolbarItems,
            tagIds = tagIds,
            tagDefinitions = tagDefinitions,
            regionSize = regionSize,
            nextStrokeOrder = nextStrokeOrder,
        )

    fun fromStrokeData(sData: StrokeData): Stroke {
        val sysPressure = EpdController.getMaxTouchPressure()
        val defaultMaxPressure = if (sysPressure > 0f) sysPressure else 4096f
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

        return Stroke(
            path = path,
            points = points,
            color = sData.color,
            width = sData.width,
            style = sData.style,
            bounds = bounds,
            strokeOrder = sData.strokeOrder,
            zIndex = sData.zIndex,
        )
    }

    data class LoadedCanvasState(
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
        val tagIds: List<String> = emptyList(),
        val tagDefinitions: List<Tag> = emptyList(),
    )
}
