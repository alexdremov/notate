package com.alexdremov.notate.data

import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.LinkItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.model.TextItem
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles serialization and deserialization of the Canvas model.
 * Decouples the InfiniteCanvasModel from the data transfer format.
 */
object CanvasSerializer {
    private const val TAG = "CanvasSerializer"

    fun toStrokeData(item: Stroke): StrokeData {
        val count = item.points.size
        val stride = StrokeData.PACKED_POINT_STRIDE
        val floats = FloatArray(count * stride)
        val longs = LongArray(count)

        for (i in 0 until count) {
            val p = item.points[i]
            floats[i * stride] = p.x
            floats[i * stride + 1] = p.y
            floats[i * stride + 2] = p.pressure
            floats[i * stride + 3] = p.size
            floats[i * stride + 4] = p.tiltX.toFloat()
            floats[i * stride + 5] = p.tiltY.toFloat()
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
            x = item.logicalBounds.left,
            y = item.logicalBounds.top,
            width = item.logicalBounds.width(),
            height = item.logicalBounds.height(),
            zIndex = item.zIndex,
            order = item.order,
            rotation = item.rotation,
            opacity = item.opacity,
        )

    fun fromCanvasImageData(cData: CanvasImageData): com.alexdremov.notate.model.CanvasImage {
        val logicalBounds = RectF(cData.x, cData.y, cData.x + cData.width, cData.y + cData.height)
        val bounds = StrokeGeometry.computeRotatedBounds(logicalBounds, cData.rotation)
        return com.alexdremov.notate.model.CanvasImage(
            uri = cData.uri,
            logicalBounds = logicalBounds,
            bounds = bounds,
            zIndex = cData.zIndex,
            order = cData.order,
            rotation = cData.rotation,
            opacity = cData.opacity,
        )
    }

    fun toTextItemData(item: com.alexdremov.notate.model.TextItem): TextItemData =
        TextItemData(
            text = item.text,
            x = item.logicalBounds.left,
            y = item.logicalBounds.top,
            width = item.logicalBounds.width(),
            height = item.logicalBounds.height(),
            fontSize = item.fontSize,
            color = item.color,
            zIndex = item.zIndex,
            order = item.order,
            rotation = item.rotation,
            opacity = item.opacity,
            alignment =
                when (item.alignment) {
                    android.text.Layout.Alignment.ALIGN_OPPOSITE -> 1
                    android.text.Layout.Alignment.ALIGN_CENTER -> 2
                    else -> 0
                },
            backgroundColor = item.backgroundColor,
        )

    fun fromTextItemData(tData: TextItemData): com.alexdremov.notate.model.TextItem {
        val logicalBounds = RectF(tData.x, tData.y, tData.x + tData.width, tData.y + tData.height)
        val bounds = StrokeGeometry.computeRotatedBounds(logicalBounds, tData.rotation)
        return com.alexdremov.notate.model.TextItem(
            text = tData.text,
            fontSize = tData.fontSize,
            color = tData.color,
            logicalBounds = logicalBounds,
            bounds = bounds,
            alignment =
                when (tData.alignment) {
                    1 -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                    2 -> android.text.Layout.Alignment.ALIGN_CENTER
                    else -> android.text.Layout.Alignment.ALIGN_NORMAL
                },
            backgroundColor = tData.backgroundColor,
            zIndex = tData.zIndex,
            order = tData.order,
            rotation = tData.rotation,
            opacity = tData.opacity,
        )
    }

    fun toLinkItemData(item: LinkItem): LinkItemData =
        LinkItemData(
            label = item.label,
            target = item.target,
            type = item.type,
            x = item.logicalBounds.left,
            y = item.logicalBounds.top,
            width = item.logicalBounds.width(),
            height = item.logicalBounds.height(),
            fontSize = item.fontSize,
            color = item.color,
            zIndex = item.zIndex,
            order = item.order,
            rotation = item.rotation,
        )

    fun fromLinkItemData(lData: LinkItemData): LinkItem {
        val logicalBounds = RectF(lData.x, lData.y, lData.x + lData.width, lData.y + lData.height)
        val bounds = StrokeGeometry.computeRotatedBounds(logicalBounds, lData.rotation)
        return LinkItem(
            label = lData.label,
            target = lData.target,
            type = lData.type,
            fontSize = lData.fontSize,
            color = lData.color,
            logicalBounds = logicalBounds,
            bounds = bounds,
            zIndex = lData.zIndex,
            order = lData.order,
            rotation = lData.rotation,
        )
    }

    fun serializeRegion(
        items: List<CanvasItem>,
        idX: Int,
        idY: Int,
        outputStream: OutputStream,
    ) {
        val strokeData = ArrayList<StrokeData>()
        val imageData = ArrayList<CanvasImageData>()
        val textData = ArrayList<TextItemData>()
        val linkData = ArrayList<LinkItemData>()

        for (item in items) {
            when (item) {
                is Stroke -> strokeData.add(toStrokeData(item))
                is com.alexdremov.notate.model.CanvasImage -> imageData.add(toCanvasImageData(item))
                is com.alexdremov.notate.model.TextItem -> textData.add(toTextItemData(item))
                is LinkItem -> linkData.add(toLinkItemData(item))
            }
        }

        val regionProto =
            RegionProto(
                idX = idX,
                idY = idY,
                strokes = strokeData,
                images = imageData,
                texts = textData,
                links = linkData,
            )
        outputStream.write(ProtoBuf.encodeToByteArray(RegionProto.serializer(), regionProto))
    }

    // --- Deserialization ---
    fun deserializeRegion(inputStream: InputStream): RegionProto {
        val bytes = inputStream.readBytes()
        return ProtoBuf.decodeFromByteArray(RegionProto.serializer(), bytes)
    }

    fun toCanvasItems(regionProto: RegionProto): List<CanvasItem> {
        val items = ArrayList<CanvasItem>()
        regionProto.strokes.mapTo(items) { fromStrokeData(it) }
        regionProto.images.mapTo(items) { fromCanvasImageData(it) }
        regionProto.texts.mapTo(items) { fromTextItemData(it) }
        regionProto.links.mapTo(items) { fromLinkItemData(it) }
        return items
    }

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
        val uuid: String? = null,
    )

    fun serializeCanvasData(canvasData: CanvasData): ByteArray = ProtoBuf.encodeToByteArray(CanvasData.serializer(), canvasData)

    fun deserializeCanvasData(byteArray: ByteArray): CanvasData = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), byteArray)

    fun deserializeCanvasData(inputStream: InputStream): CanvasData {
        val bytes = inputStream.readBytes()
        return ProtoBuf.decodeFromByteArray(CanvasData.serializer(), bytes)
    }

    // --- Helper for debugging packed float arrays ---
    fun floatArrayToString(arr: FloatArray?): String = if (arr == null) "null" else arr.joinToString(prefix = "[", postfix = "]")

    fun longArrayToString(arr: LongArray?): String = if (arr == null) "null" else arr.joinToString(prefix = "[", postfix = "]")

    fun byteBufferToString(buffer: ByteBuffer?): String {
        if (buffer == null) return "null"
        val originalPosition = buffer.position()
        val builder = StringBuilder("[")
        while (buffer.hasRemaining()) {
            builder.append(String.format("%02X ", buffer.get()))
        }
        buffer.position(originalPosition) // Reset to original position
        builder.append("]")
        return builder.toString()
    }

    // --- For `CanvasDataPreview` in `FileIndexManager` ---
    fun serializeCanvasDataPreview(canvasDataPreview: CanvasDataPreview): ByteArray =
        ProtoBuf.encodeToByteArray(CanvasDataPreview.serializer(), canvasDataPreview)

    fun deserializeCanvasDataPreview(byteArray: ByteArray): CanvasDataPreview =
        ProtoBuf.decodeFromByteArray(CanvasDataPreview.serializer(), byteArray)

    fun toData(
        canvasType: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
        backgroundStyle: BackgroundStyle,
        viewportScale: Float,
        viewportOffsetX: Float,
        viewportOffsetY: Float,
        toolbarItems: List<com.alexdremov.notate.model.ToolbarItem>,
        tagIds: List<String>,
        tagDefinitions: List<Tag>,
        regionSize: Float,
        nextStrokeOrder: Long,
        uuid: String? = null,
    ): CanvasData =
        CanvasData(
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
            uuid = uuid,
        )
}
