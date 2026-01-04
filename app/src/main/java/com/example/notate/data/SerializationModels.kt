package com.example.notate.data

import com.example.notate.model.BackgroundStyle
import com.example.notate.model.StrokeType
import kotlinx.serialization.Serializable

enum class CanvasType {
    INFINITE,
    FIXED_PAGES,
}

@Serializable
data class CanvasData(
    val thumbnail: String? = null, // Base64 encoded PNG
    val version: Int = 2,
    val strokes: List<StrokeData> = emptyList(),
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoomLevel: Float = 1f,
    val canvasType: CanvasType = CanvasType.INFINITE,
    val pageWidth: Float = 0f,
    val pageHeight: Float = 0f,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.Blank(),
)

@Serializable
data class StrokeData(
    val points: List<PointData> = emptyList(),
    val pointsPacked: FloatArray? = null, // [x, y, pressure, size, x, y, ...]
    val timestampsPacked: LongArray? = null, // [t, t, ...]
    val color: Int,
    val width: Float,
    val style: StrokeType,
    val strokeOrder: Long = 0,
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
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val timestamp: Long,
)

@Serializable
data class CanvasDataPreview(
    val thumbnail: String? = null,
)
