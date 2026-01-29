package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.data.CanvasImageData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.RegionBoundsProto
import com.alexdremov.notate.data.RegionProto
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

class RegionStorage(
    private val baseDir: File,
    private val zipSource: File? = null,
) {
    fun init() {
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                Logger.e("RegionStorage", "Failed to create session directory: $baseDir")
            }
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? {
        try {
            val imagesDir = File(baseDir, "images")
            if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                Logger.e("RegionStorage", "Failed to create images directory: $imagesDir")
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

            val fileName = "${java.util.UUID.randomUUID()}.$extension"
            val destFile = File(imagesDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            return destFile.absolutePath
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to import image", e)
            return null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun saveRegion(data: RegionData): Boolean {
        val strokeData = ArrayList<StrokeData>()
        val imageData = ArrayList<CanvasImageData>()

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
            }
        }

        val proto = RegionProto(data.id.x, data.id.y, strokeData, imageData)
        val file = getRegionFile(data.id)

        return try {
            val bytes = ProtoBuf.encodeToByteArray(RegionProto.serializer(), proto)
            writeAtomic(file, bytes)
            true
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to save region ${data.id}", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadRegion(id: RegionId): RegionData? {
        val file = getRegionFile(id)

        // JIT Extraction Strategy
        if (!file.exists() && zipSource != null && zipSource.exists()) {
            // Attempt to extract from ZIP
            val entryName = file.name // "r_X_Y.bin"
            if (com.alexdremov.notate.util.ZipUtils
                    .extractFile(zipSource, entryName, file)
            ) {
                Logger.d("RegionStorage", "JIT Extracted region $id from ZIP")
            }
        }

        if (!file.exists()) {
            // Normal case for new regions, verbose log only
            Logger.v("RegionStorage", "Region file not found: $id")
            return null
        }

        return try {
            val bytes = file.readBytes()
            val proto = ProtoBuf.decodeFromByteArray(RegionProto.serializer(), bytes)

            val data = RegionData(id)

            // Convert Strokes
            proto.strokes.forEach { sData ->
                val stroke = CanvasSerializer.fromStrokeData(sData)
                data.items.add(stroke)
            }

            // Convert Images
            proto.images.forEach { iData ->
                var uri = iData.uri
                if (uri.isNotEmpty() && !uri.startsWith("/") && !uri.startsWith("file:") && !uri.startsWith("content:")) {
                    uri = File(baseDir, uri).absolutePath
                }

                val image =
                    com.alexdremov.notate.model.CanvasImage(
                        uri = uri,
                        bounds = android.graphics.RectF(iData.x, iData.y, iData.x + iData.width, iData.y + iData.height),
                        zIndex = iData.zIndex,
                        order = iData.order,
                        rotation = iData.rotation,
                        opacity = iData.opacity,
                    )
                data.items.add(image)
            }

            Logger.d("RegionStorage", "Loaded region $id (${data.items.size} items)")
            data
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to load region $id (File: ${file.absolutePath}, Size: ${file.length()})", e)
            null
        }
    }

    fun deleteRegion(id: RegionId) {
        val file = getRegionFile(id)
        if (file.exists()) {
            if (!file.delete()) {
                Logger.w("RegionStorage", "Failed to delete region file: $file")
            }
        }
    }

    fun listStoredRegions(): List<RegionId> {
        val ids = ArrayList<RegionId>()
        val files = baseDir.listFiles() ?: return ids
        val regex = Regex("^r_(-?\\d+)_(-?\\d+)\\.bin$")

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

    private fun getRegionFile(id: RegionId): File = File(baseDir, "r_${id.x}_${id.y}.bin")

    private fun getThumbnailFile(id: RegionId): File {
        val thumbDir = File(baseDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        return File(thumbDir, "t_${id.x}_${id.y}.png")
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
            Logger.e("RegionStorage", "Failed to save thumbnail for region $id", e)
            false
        }

    fun loadThumbnail(id: RegionId): android.graphics.Bitmap? {
        val file = getThumbnailFile(id)
        if (!file.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to load thumbnail for region $id", e)
            null
        }
    }

    fun deleteThumbnail(id: RegionId) {
        val file = getThumbnailFile(id)
        if (file.exists()) {
            if (!file.delete()) {
                Logger.w("RegionStorage", "Failed to delete thumbnail file: $file")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun saveIndex(index: Map<RegionId, RectF>): Boolean {
        val list =
            index.map { (id, rect) ->
                RegionBoundsProto(id.x, id.y, rect.left, rect.top, rect.right, rect.bottom)
            }
        val file = File(baseDir, "index.bin")
        return try {
            val bytes = ProtoBuf.encodeToByteArray(ListSerializer(RegionBoundsProto.serializer()), list)
            writeAtomic(file, bytes)
            Logger.d("RegionStorage", "Saved index (${index.size} regions)")
            true
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to save index", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadIndex(): Map<RegionId, RectF> {
        val file = File(baseDir, "index.bin")

        // JIT Extraction for Index
        if (!file.exists() && zipSource != null && zipSource.exists()) {
            if (com.alexdremov.notate.util.ZipUtils
                    .extractFile(zipSource, "index.bin", file)
            ) {
                Logger.i("RegionStorage", "JIT Extracted index.bin from ZIP")
            }
        }

        if (!file.exists()) {
            Logger.i("RegionStorage", "No index found (Fresh session)")
            return emptyMap()
        }

        return try {
            val bytes = file.readBytes()
            val list = ProtoBuf.decodeFromByteArray(ListSerializer(RegionBoundsProto.serializer()), bytes)
            val map =
                list.associate { proto ->
                    RegionId(proto.idX, proto.idY) to RectF(proto.left, proto.top, proto.right, proto.bottom)
                }
            Logger.d("RegionStorage", "Loaded index (${map.size} regions)")
            map
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Failed to load index", e)
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
                Logger.e("RegionStorage", "Atomic write: Failed to recreate directory $parent")
            } else {
                Logger.i("RegionStorage", "Atomic write: Recreated directory $parent")
            }
        }

        val tmpFile = File(parent, "${file.name}.tmp")
        try {
            tmpFile.writeBytes(bytes)

            // Verify temp file was written correctly
            if (!tmpFile.exists() || tmpFile.length() != bytes.size.toLong()) {
                throw java.io.IOException("Temp file verification failed: expected ${bytes.size} bytes, got ${tmpFile.length()}")
            }

            if (file.exists()) {
                if (!file.delete()) {
                    // Delete failed - try overwriting instead
                    Logger.w("RegionStorage", "Atomic write: Failed to delete existing target $file, attempting overwrite")
                    file.writeBytes(bytes)
                    // Verify the overwrite
                    if (file.length() != bytes.size.toLong()) {
                        throw java.io.IOException("Overwrite verification failed")
                    }
                    tmpFile.delete()
                    return
                }
            }
            if (!tmpFile.renameTo(file)) {
                // Rename failed - try copy instead
                Logger.w("RegionStorage", "Atomic write: Failed to rename temp file to $file, attempting copy")
                tmpFile.inputStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Verify the copy
                if (file.length() != bytes.size.toLong()) {
                    throw java.io.IOException("Copy verification failed")
                }
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Logger.e("RegionStorage", "Atomic write failed for $file", e)
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }
}
