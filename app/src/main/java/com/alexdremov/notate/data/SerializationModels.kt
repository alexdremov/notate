@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.StrokeType
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

enum class CanvasType {
    INFINITE,
    FIXED_PAGES,
}

@Serializable
data class CanvasData(
    @ProtoNumber(1)
    val thumbnail: String? = null, // Base64 encoded PNG
    @ProtoNumber(2)
    val version: Int = 3,
    @ProtoNumber(3)
    val strokes: List<StrokeData> = emptyList(),
    @ProtoNumber(4)
    val offsetX: Float = 0f,
    @ProtoNumber(5)
    val offsetY: Float = 0f,
    @ProtoNumber(6)
    val zoomLevel: Float = 1f,
    @ProtoNumber(7)
    val canvasType: CanvasType = CanvasType.INFINITE,
    @ProtoNumber(8)
    val pageWidth: Float = 0f,
    @ProtoNumber(9)
    val pageHeight: Float = 0f,
    @ProtoNumber(10)
    val backgroundStyle: BackgroundStyle = BackgroundStyle.Blank(),
)

@Serializable
data class StrokeData(
    @ProtoNumber(1)
    val points: List<PointData> = emptyList(),
    @ProtoNumber(2)
    val pointsPacked: FloatArray? = null, // [x, y, pressure, size, tiltX, tiltY, ...]
    @ProtoNumber(3)
    val timestampsPacked: LongArray? = null, // [t, t, ...]
    @ProtoNumber(4)
    val color: Int,
    @ProtoNumber(5)
    val width: Float,
    @ProtoNumber(6)
    val style: StrokeType,
    @ProtoNumber(7)
    val strokeOrder: Long = 0,
    @ProtoNumber(8)
    val zIndex: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StrokeData

        if (points != other.points) return false
        if (pointsPacked != null) {
            if (other.pointsPacked == null) return false
            if (!pointsPacked.contentEquals(other.pointsPacked)) return false
        } else if (other.pointsPacked != null) {
            return false
        }
        if (timestampsPacked != null) {
            if (other.timestampsPacked == null) return false
            if (!timestampsPacked.contentEquals(other.timestampsPacked)) return false
        } else if (other.timestampsPacked != null) {
            return false
        }
        if (color != other.color) return false
        if (width != other.width) return false
        if (style != other.style) return false
        if (strokeOrder != other.strokeOrder) return false
        return zIndex == other.zIndex
    }

    override fun hashCode(): Int {
        var result = points.hashCode()
        result = 31 * result + (pointsPacked?.contentHashCode() ?: 0)
        result = 31 * result + (timestampsPacked?.contentHashCode() ?: 0)
        result = 31 * result + color
        result = 31 * result + width.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + strokeOrder.hashCode()
        result = 31 * result + zIndex.hashCode()
        return result
    }
}

@Serializable
data class PointData(
    @ProtoNumber(1)
    val x: Float,
    @ProtoNumber(2)
    val y: Float,
    @ProtoNumber(3)
    val pressure: Float,
    @ProtoNumber(4)
    val size: Float,
    @ProtoNumber(5)
    val tiltX: Float = 0f,
    @ProtoNumber(6)
    val tiltY: Float = 0f,
    @ProtoNumber(7)
    val timestamp: Long,
)

@Serializable
data class CanvasDataPreview(
    @ProtoNumber(1)
    val thumbnail: String? = null,
)
