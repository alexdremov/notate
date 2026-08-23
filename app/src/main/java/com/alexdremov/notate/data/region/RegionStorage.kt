package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.data.CanvasImageData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.LinkItemData
import com.alexdremov.notate.data.RegionBoundsProto
import com.alexdremov.notate.data.RegionProto
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.data.TextItemData
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.IOException
import java.util.UUID

open class RegionStorage(
    private val baseDir: File,
    private val zipSource: File? = null,
) {
    companion object {
        private const val TAG = "RegionStorage"
        private const val DIR_IMAGES = "images"
        private const val DIR_THUMBNAILS = "thumbnails"
        private const val FILE_INDEX = "index.bin"
        private const val FILE_PREFIX_REGION = "r_"
        private const val FILE_PREFIX_THUMB = "t_"
        private const val EXT_BIN = ".bin"
        private const val EXT_PNG = ".png"
    }

    open fun init() {
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                Logger.e(TAG, "Failed to create session directory: $baseDir")
            }
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? {
        try {
            val imagesDir = File(baseDir, DIR_IMAGES)
            if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                Logger.e(TAG, "Failed to create images directory: $imagesDir")
                return null
            }

            val mimeType = context.contentResolver.getType(uri)
            val extension =
                if (mimeType != null) {
                    android.webkit.MimeTypeMap
                        .getSingleton()
                        .getExtensionFromMimeType(mimeType) ?: "img"
                } else {
                    "img"
                }

            val fileName = "${UUID.randomUUID()}.$extension"
            val destFile = File(imagesDir, fileName)

            // Atomic write for image import
            val parent = destFile.parentFile ?: imagesDir
            val tmpFile = File(parent, "${destFile.name}.tmp")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return null

                if (tmpFile.renameTo(destFile)) {
                    return destFile.absolutePath
                } else {
                    // Fallback copy if rename fails (e.g. cross-filesystem)
                    Logger.w(TAG, "Image atomic rename failed, attempting copy")
                    tmpFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tmpFile.delete()
                    return destFile.absolutePath
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to write image stream", e)
                if (tmpFile.exists()) tmpFile.delete()
                return null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import image", e)
            return null
        }
    }

    /**
     * Serializes a region to protobuf bytes WITHOUT any disk IO.
     *
     * Split from [saveRegion] so callers can snapshot a region's content while
     * holding only a read lock (serialization is pure CPU work over the item
     * list) and then perform the actual file write outside any lock — see
     * [RegionManager.saveAll].
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun serializeRegion(data: RegionData): ByteArray {
        val strokeData = ArrayList<StrokeData>()
        val imageData = ArrayList<CanvasImageData>()
        val textData = ArrayList<TextItemData>()
        val linkData = ArrayList<LinkItemData>()

        for (item in data.items) {
            when (item) {
                is Stroke -> {
                    strokeData.add(CanvasSerializer.toStrokeData(item))
                }

                is com.alexdremov.notate.model.CanvasImage -> {
                    var uri = item.uri
                    if (uri.startsWith(baseDir.absolutePath)) {
                        uri = uri.removePrefix(baseDir.absolutePath).trimStart('/')
                    }
                    val relativeItem = item.copy(uri = uri)
                    imageData.add(CanvasSerializer.toCanvasImageData(relativeItem))
                }

                is com.alexdremov.notate.model.TextItem -> {
                    textData.add(CanvasSerializer.toTextItemData(item))
                }

                is com.alexdremov.notate.model.LinkItem -> {
                    linkData.add(CanvasSerializer.toLinkItemData(item))
                }
            }
        }

        val proto = RegionProto(data.id.x, data.id.y, strokeData, imageData, textData, linkData)
        return ProtoBuf.encodeToByteArray(RegionProto.serializer(), proto)
    }

    /**
     * Atomically writes pre-serialized region bytes (from [serializeRegion])
     * to the region's file. Pure IO, no locking assumptions.
     */
    open fun saveRegionBytes(
        id: RegionId,
        bytes: ByteArray,
    ): Boolean =
        try {
            writeAtomic(getRegionFile(id), bytes)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save region $id", e)
            false
        }

    @OptIn(ExperimentalSerializationApi::class)
    open fun saveRegion(data: RegionData): Boolean {
        // Serialize may throw on malformed items; keep legacy behavior of
        // returning false instead of propagating.
        val bytes =
            try {
                serializeRegion(data)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to serialize region ${data.id}", e)
                return false
            }
        return saveRegionBytes(data.id, bytes)
    }

    @OptIn(ExperimentalSerializationApi::class)
    open fun loadRegion(id: RegionId): RegionData? {
        if (com.alexdremov.notate.data.region.RegionForensics.enabled) {
            // Gated BEFORE evaluation: building this string performs a disk
            // stat (getRegionFile().exists()) — never do that on the hot path.
            com.alexdremov.notate.data.region.RegionForensics
                .log("LOAD-FILE id=$id exists=${getRegionFile(id).exists()}")
        }
        val file = getRegionFile(id)

        // JIT Extraction Strategy
        if (!file.exists() && zipSource != null && zipSource.exists()) {
            // Attempt to extract from ZIP
            val entryName = file.name // "r_X_Y.bin"
            if (com.alexdremov.notate.util.ZipUtils
                    .extractFile(zipSource, entryName, file)
            ) {
                Logger.d(TAG, "JIT Extracted region $id from ZIP")
            }
        }

        if (!file.exists()) {
            // Normal case for new regions, verbose log only
            Logger.v(TAG, "Region file not found: $id")
            return null
        }

        return try {
            val bytes = file.readBytes()
            val proto = ProtoBuf.decodeFromByteArray(RegionProto.serializer(), bytes)

            val loaded = ArrayList<com.alexdremov.notate.model.CanvasItem>()
            val data = RegionData(id, items = loaded)

            // Convert Strokes
            proto.strokes.forEach { sData ->
                val stroke = CanvasSerializer.fromStrokeData(sData)
                loaded.add(stroke)
            }

            // Convert Images
            proto.images.forEach { iData ->
                var uri = iData.uri
                if (uri.isNotEmpty() && !uri.startsWith("/") && !uri.startsWith("file:") && !uri.startsWith("content:")) {
                    uri = File(baseDir, uri).absolutePath
                }

                val logical = android.graphics.RectF(iData.x, iData.y, iData.x + iData.width, iData.y + iData.height)
                val aabb =
                    com.alexdremov.notate.util.StrokeGeometry
                        .computeRotatedBounds(logical, iData.rotation)

                val image =
                    com.alexdremov.notate.model.CanvasImage(
                        uri = uri,
                        logicalBounds = logical,
                        bounds = aabb,
                        zIndex = iData.zIndex,
                        order = iData.order,
                        rotation = iData.rotation,
                        opacity = iData.opacity,
                    )
                loaded.add(image)
            }

            // Convert Text
            proto.texts.forEach { tData ->
                val textItem = CanvasSerializer.fromTextItemData(tData)
                loaded.add(textItem)
            }

            // Convert Links
            proto.links.forEach { lData ->
                val linkItem = CanvasSerializer.fromLinkItemData(lData)
                loaded.add(linkItem)
            }

            Logger.d("RegionStorage", "Loaded region $id (${data.items.size} items)")
            data
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load region $id (File: ${file.absolutePath}, Size: ${file.length()})", e)
            null
        }
    }

    open fun deleteRegion(id: RegionId) {
        val file = getRegionFile(id)
        if (file.exists()) {
            if (!file.delete()) {
                Logger.w(TAG, "Failed to delete region file: $file")
            }
        }
    }

    /** Removes the spatial index so a reopen treats the store as wiped,
     *  not as "index lost, rebuild from whatever files remain". */
    open fun deleteIndex() {
        val file = File(baseDir, FILE_INDEX)
        if (file.exists() && !file.delete()) {
            Logger.w(TAG, "Failed to delete index file: $file")
        }
    }

    open fun listStoredRegions(): List<RegionId> {
        val ids = ArrayList<RegionId>()
        val files = baseDir.listFiles() ?: return ids
        val regex = Regex("^${FILE_PREFIX_REGION}(-?\\d+)_(-?\\d+)${Regex.escape(EXT_BIN)}$")

        for (file in files) {
            val match = regex.find(file.name)
            if (match != null) {
                try {
                    val x = match.groupValues[1].toInt()
                    val y = match.groupValues[2].toInt()
                    ids.add(RegionId(x, y))
                } catch (e: NumberFormatException) {
                    // Ignore malformed
                }
            }
        }
        return ids
    }

    private fun getRegionFile(id: RegionId): File = File(baseDir, "${FILE_PREFIX_REGION}${id.x}_${id.y}$EXT_BIN")

    private fun getThumbnailFile(id: RegionId): File {
        val thumbDir = File(baseDir, DIR_THUMBNAILS)
        if (!thumbDir.exists()) thumbDir.mkdirs()
        return File(thumbDir, "${FILE_PREFIX_THUMB}${id.x}_${id.y}$EXT_PNG")
    }

    fun saveThumbnail(
        id: RegionId,
        bitmap: android.graphics.Bitmap,
    ): Boolean =
        try {
            val file = getThumbnailFile(id)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save thumbnail for region $id", e)
            false
        }

    fun loadThumbnail(id: RegionId): android.graphics.Bitmap? {
        val file = getThumbnailFile(id)
        if (!file.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load thumbnail for region $id", e)
            null
        }
    }

    fun deleteThumbnail(id: RegionId) {
        val file = getThumbnailFile(id)
        if (file.exists()) {
            if (!file.delete()) {
                Logger.w(TAG, "Failed to delete thumbnail file: $file")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    open fun saveIndex(index: Map<RegionId, RectF>): Boolean {
        val list =
            index.map { (id, rect) ->
                RegionBoundsProto(id.x, id.y, rect.left, rect.top, rect.right, rect.bottom)
            }
        val file = File(baseDir, FILE_INDEX)
        return try {
            val bytes = ProtoBuf.encodeToByteArray(ListSerializer(RegionBoundsProto.serializer()), list)
            writeAtomic(file, bytes)
            Logger.d(TAG, "Saved index (${index.size} regions)")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save index", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    open fun loadIndex(): Map<RegionId, RectF> {
        val file = File(baseDir, FILE_INDEX)

        // JIT Extraction for Index
        if (!file.exists() && zipSource != null && zipSource.exists()) {
            if (com.alexdremov.notate.util.ZipUtils
                    .extractFile(zipSource, FILE_INDEX, file)
            ) {
                Logger.i(TAG, "JIT Extracted $FILE_INDEX from ZIP")
            }
        }

        if (!file.exists()) {
            Logger.i(TAG, "No index found (Fresh session)")
            return emptyMap()
        }

        return try {
            val bytes = file.readBytes()
            val list = ProtoBuf.decodeFromByteArray(ListSerializer(RegionBoundsProto.serializer()), bytes)
            val map =
                list.associate { proto ->
                    RegionId(proto.idX, proto.idY) to RectF(proto.left, proto.top, proto.right, proto.bottom)
                }
            Logger.d(TAG, "Loaded index (${map.size} regions)")
            map
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load index", e)
            emptyMap()
        }
    }

    private fun writeAtomic(
        file: File,
        bytes: ByteArray,
    ) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                Logger.e(TAG, "Atomic write: Failed to recreate directory $parent")
            } else {
                Logger.i(TAG, "Atomic write: Recreated directory $parent")
            }
        }

        val tmpFile = File(parent, "${file.name}.tmp")
        try {
            tmpFile.writeBytes(bytes)

            // Verify temp file was written correctly
            if (!tmpFile.exists() || tmpFile.length() != bytes.size.toLong()) {
                throw IOException("Temp file verification failed: expected ${bytes.size} bytes, got ${tmpFile.length()}")
            }

            // RENAME FIRST: POSIX rename(2) atomically REPLACES an existing
            // target, so the previous version stays intact up to the last
            // instant. The old delete-then-rename order opened a crash window
            // where NEITHER version existed on disk.
            if (tmpFile.renameTo(file)) return

            Logger.w(TAG, "Atomic write: rename failed for $file, attempting fallbacks")
            if (file.exists() && !file.delete()) {
                // Fallback 1: overwrite in place (non-atomic) and verify.
                Logger.w(TAG, "Atomic write: Failed to delete existing target $file, attempting overwrite")
                file.writeBytes(bytes)
                // Verify the overwrite
                if (file.length() != bytes.size.toLong()) {
                    throw IOException("Overwrite verification failed")
                }
                tmpFile.delete()
                return
            }
            if (tmpFile.renameTo(file)) return
            // Fallback 2: copy + verify (cross-filesystem safety net).
            Logger.w(TAG, "Atomic write: Failed to rename temp file to $file, attempting copy")
            tmpFile.inputStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Verify the copy
            if (file.length() != bytes.size.toLong()) {
                throw IOException("Copy verification failed")
            }
            tmpFile.delete()
        } catch (e: Exception) {
            Logger.e(TAG, "Atomic write failed for $file", e)
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }
}
