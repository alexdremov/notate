@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.Tag
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
    @ProtoNumber(12)
    val toolbarItems: List<com.alexdremov.notate.model.ToolbarItem> = emptyList(),
    @ProtoNumber(13)
    val tagIds: List<String> = emptyList(),
    @ProtoNumber(14)
    val tagDefinitions: List<Tag> = emptyList(),
    @ProtoNumber(15)
    val regionSize: Float = CanvasConfig.DEFAULT_REGION_SIZE,
    @ProtoNumber(16)
    val nextStrokeOrder: Long = 0,
    @ProtoNumber(17)
    val uuid: String? = null,
)

enum class LinkType {
    INTERNAL_NOTE,
    EXTERNAL_URL,
}

@Serializable
data class LinkItemData(
    @ProtoNumber(1) val label: String,
    @ProtoNumber(2) val target: String, // UUID for internal, URI for external
    @ProtoNumber(3) val x: Float,
    @ProtoNumber(4) val y: Float,
    @ProtoNumber(5) val width: Float,
    @ProtoNumber(6) val height: Float,
    @ProtoNumber(7) val zIndex: Float,
    @ProtoNumber(8) val order: Long,
    @ProtoNumber(9) val color: Int,
    @ProtoNumber(10) val rotation: Float = 0f,
    @ProtoNumber(11) val type: LinkType = LinkType.INTERNAL_NOTE,
    @ProtoNumber(12) val fontSize: Float = 24f,
)

@Serializable
data class RegionProto(
    @ProtoNumber(1) val idX: Int,
    @ProtoNumber(2) val idY: Int,
    @ProtoNumber(3) val strokes: List<StrokeData> = emptyList(),
    @ProtoNumber(4) val images: List<CanvasImageData> = emptyList(),
    @ProtoNumber(5) val texts: List<TextItemData> = emptyList(),
    @ProtoNumber(6) val links: List<LinkItemData> = emptyList(),
)

@Serializable
data class RegionBoundsProto(
    @ProtoNumber(1) val idX: Int,
    @ProtoNumber(2) val idY: Int,
    @ProtoNumber(3) val left: Float,
    @ProtoNumber(4) val top: Float,
    @ProtoNumber(5) val right: Float,
    @ProtoNumber(6) val bottom: Float,
)

@Serializable
data class CanvasImageData(
    @ProtoNumber(1)
    val uri: String,
    @ProtoNumber(2)
    val x: Float,
    @ProtoNumber(3)
    val y: Float,
    @ProtoNumber(4)
    val width: Float,
    @ProtoNumber(5)
    val height: Float,
    @ProtoNumber(6)
    val zIndex: Float,
    @ProtoNumber(7)
    val order: Long,
    @ProtoNumber(8)
    val rotation: Float = 0f,
    @ProtoNumber(9)
    val opacity: Float = 1.0f,
)

@Serializable
data class TextItemData(
    @ProtoNumber(1) val text: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
    @ProtoNumber(4) val width: Float,
    @ProtoNumber(5) val height: Float,
    @ProtoNumber(6) val fontSize: Float,
    @ProtoNumber(7) val color: Int,
    @ProtoNumber(8) val zIndex: Float,
    @ProtoNumber(9) val order: Long,
    @ProtoNumber(10) val rotation: Float = 0f,
    @ProtoNumber(11) val opacity: Float = 1.0f,
    @ProtoNumber(12) val alignment: Int = 0, // 0: Normal, 1: Opposite, 2: Center
    @ProtoNumber(13) val backgroundColor: Int = 0,
)

@Serializable
data class StrokeData(
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
    companion object {
        const val PACKED_POINT_STRIDE = 6
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StrokeData

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
        var result = pointsPacked?.contentHashCode() ?: 0
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
data class CanvasDataPreview(
    @ProtoNumber(1)
    val thumbnail: String? = null,
    @ProtoNumber(13)
    val tagIds: List<String> = emptyList(),
    @ProtoNumber(14)
    val tagDefinitions: List<Tag> = emptyList(),
    @ProtoNumber(17)
    val uuid: String? = null,
)
