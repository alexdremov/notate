@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.InputStream

interface StorageProvider {
    fun isApplicable(path: String?): Boolean

    fun getRootPath(): String

    fun getItems(path: String?): List<FileSystemItem>

    fun createFolder(
        name: String,
        parentPath: String?,
    ): Boolean

    fun createCanvas(
        name: String,
        parentPath: String?,
        data: CanvasData,
    ): String?

    fun deleteItem(path: String): Boolean

    fun renameItem(
        path: String,
        newName: String,
    ): Boolean

    fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean
}

class LocalStorageProvider(
    private val context: Context,
    private val rootDir: File,
) : StorageProvider {
    override fun isApplicable(path: String?): Boolean = path == null || !path.startsWith("content://")

    override fun getRootPath(): String = rootDir.absolutePath

    override fun getItems(path: String?): List<FileSystemItem> {
        val targetPath = path ?: rootDir.absolutePath
        val targetDir = File(targetPath)
        if (!targetDir.exists()) return emptyList()

        return targetDir
            .listFiles()
            ?.mapNotNull {
                when {
                    it.isDirectory -> {
                        ProjectItem(
                            name = it.name,
                            path = it.absolutePath,
                            lastModified = it.lastModified(),
                            itemsCount = it.list()?.size ?: 0,
                        )
                    }

                    it.extension == "json" || it.extension == "notate" -> {
                        CanvasItem(
                            name = it.nameWithoutExtension,
                            path = it.absolutePath,
                            lastModified = it.lastModified(),
                            thumbnail = extractThumbnail(it),
                        )
                    }

                    else -> {
                        null
                    }
                }
            }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    private fun extractThumbnail(file: File): String? {
        // Peek at the first byte to determine format
        // JSON starts with '{' (0x7B)
        // Protobuf with thumbnail (field 1) starts with 0x0A
        var isJson = false
        try {
            file.inputStream().use { stream ->
                val firstByte = stream.read()
                if (firstByte == 0x7B) {
                    isJson = true
                }
            }
        } catch (e: Exception) {
            // If read fails, assume based on extension as fallback
            isJson = file.extension == "json"
        }

        return if (isJson) extractThumbnailJson(file) else extractThumbnailProtobuf(file)
    }

    private fun extractThumbnailJson(file: File): String? {
        return try {
            file.reader().use { reader ->
                val buffer = CharArray(500 * 1024)
                val read = reader.read(buffer)
                if (read <= 0) return null
                val header = String(buffer, 0, read)
                val match = Regex("\"thumbnail\"\\s*:\\s*\"([^\"]+)\"").find(header)
                match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.i("Thumbnail", "Failed to extract thumbnail from JSON")
            null
        }
    }

    private fun extractThumbnailProtobuf(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            ProtoBuf.decodeFromByteArray(CanvasDataPreview.serializer(), bytes).thumbnail
        } catch (e: Exception) {
            e.printStackTrace()
            Log.i("Thumbnail", "Failed to extract thumbnail from protobuf")
            null
        }
    }

    override fun createFolder(
        name: String,
        parentPath: String?,
    ): Boolean {
        val targetPath = parentPath ?: rootDir.absolutePath
        val parent = File(targetPath)
        val newDir = File(parent, name)
        return if (!newDir.exists()) newDir.mkdirs() else false
    }

    override fun createCanvas(
        name: String,
        parentPath: String?,
        data: CanvasData,
    ): String? {
        val targetPath = parentPath ?: rootDir.absolutePath
        val safeName = name.replace("[^a-zA-Z0-9\\s]".toRegex(), "").trim()
        val fileName = "$safeName.notate"

        val parent = File(targetPath)
        val file = File(parent, fileName)

        return if (!file.exists()) {
            val bytes = ProtoBuf.encodeToByteArray(data)
            file.writeBytes(bytes)
            file.absolutePath
        } else {
            null
        }
    }

    override fun deleteItem(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) file.deleteRecursively() else false
    }

    override fun renameItem(
        path: String,
        newName: String,
    ): Boolean {
        val file = File(path)
        if (!file.exists()) return false

        val finalName =
            if (file.isDirectory) {
                newName
            } else {
                val ext = file.extension
                if (newName.endsWith(".$ext", ignoreCase = true)) {
                    newName
                } else {
                    "$newName.$ext"
                }
            }

        val newFile = File(file.parent, finalName)
        return file.renameTo(newFile)
    }

    override fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean {
        val file = File(path)
        if (!file.exists()) return false

        if (file.isDirectory) {
            val newDir = File(file.parent, "${file.name} Copy")
            return file.copyRecursively(newDir, overwrite = false)
        } else {
            val ext = file.extension
            val name = file.nameWithoutExtension
            val newFile = File(file.parent, "$name Copy.$ext")
            return try {
                file.copyTo(newFile, overwrite = false)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

class SafStorageProvider(
    private val context: Context,
    private val rootUriString: String,
) : StorageProvider {
    override fun isApplicable(path: String?): Boolean = path != null && path.startsWith("content://")

    override fun getRootPath(): String = rootUriString

    override fun getItems(path: String?): List<FileSystemItem> {
        val targetUri = if (path != null) Uri.parse(path) else Uri.parse(rootUriString)
        val dir = DocumentFile.fromTreeUri(context, targetUri) ?: return emptyList()

        return dir
            .listFiles()
            .mapNotNull {
                when {
                    it.isDirectory -> {
                        ProjectItem(
                            name = it.name ?: "Unknown",
                            path = it.uri.toString(),
                            lastModified = it.lastModified(),
                            itemsCount = 0,
                        )
                    }

                    it.name?.endsWith(".json") == true || it.name?.endsWith(".notate") == true -> {
                        val name = it.name ?: "Unknown"
                        val isJsonExt = name.endsWith(".json")
                        CanvasItem(
                            name = name.removeSuffix(if (isJsonExt) ".json" else ".notate"),
                            path = it.uri.toString(),
                            lastModified = it.lastModified(),
                            thumbnail = extractThumbnail(it),
                        )
                    }

                    else -> {
                        null
                    }
                }
            }.sortedByDescending { it.lastModified }
    }

    private fun extractThumbnail(file: DocumentFile): String? {
        var isJson = false
        try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val firstByte = stream.read()
                if (firstByte == 0x7B) {
                    isJson = true
                }
            }
        } catch (e: Exception) {
            isJson = file.name?.endsWith(".json") == true
        }
        return if (isJson) extractThumbnailJson(file) else extractThumbnailProtobuf(file)
    }

    private fun extractThumbnailJson(file: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val buffer = ByteArray(500 * 1024) // 500KB
                val read = stream.read(buffer)
                if (read <= 0) return null
                val header = String(buffer, 0, read, Charsets.UTF_8)
                val match = Regex("\"thumbnail\"\\s*:\\s*\"([^\"]+)\"").find(header)
                match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractThumbnailProtobuf(file: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) return null
                ProtoBuf.decodeFromByteArray(CanvasDataPreview.serializer(), bytes).thumbnail
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun createFolder(
        name: String,
        parentPath: String?,
    ): Boolean {
        val targetUri = if (parentPath != null) Uri.parse(parentPath) else Uri.parse(rootUriString)
        val parentDir = DocumentFile.fromTreeUri(context, targetUri) ?: return false

        return if (parentDir.findFile(name) == null) {
            parentDir.createDirectory(name) != null
        } else {
            false
        }
    }

    override fun createCanvas(
        name: String,
        parentPath: String?,
        data: CanvasData,
    ): String? {
        val targetUri = if (parentPath != null) Uri.parse(parentPath) else Uri.parse(rootUriString)
        val parentDir = DocumentFile.fromTreeUri(context, targetUri) ?: return null

        val safeName = name.replace("[^a-zA-Z0-9\\s]".toRegex(), "").trim()
        val fileName = "$safeName.notate"

        if (parentDir.findFile(fileName) != null) return null

        // Using octet-stream for .notate
        val newFile = parentDir.createFile("application/octet-stream", fileName) ?: return null

        return try {
            val bytes = ProtoBuf.encodeToByteArray(data)
            context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                os.write(bytes)
            }
            newFile.uri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun deleteItem(path: String): Boolean {
        val uri = Uri.parse(path)
        val df = DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
        return df?.delete() == true
    }

    override fun renameItem(
        path: String,
        newName: String,
    ): Boolean {
        val uri = Uri.parse(path)
        // Try TreeUri first, then SingleUri
        val df = DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri) ?: return false

        val finalName =
            if (df.isDirectory) {
                newName
            } else {
                val currentName = df.name ?: ""
                val ext = if (currentName.endsWith(".json")) ".json" else ".notate"
                if (currentName.endsWith(ext, ignoreCase = true) && !newName.endsWith(ext, ignoreCase = true)) {
                    "$newName$ext"
                } else {
                    newName
                }
            }

        return df.renameTo(finalName)
    }

    override fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean {
        val srcUri = Uri.parse(path)
        // We need the parent directory to create the new file.
        val targetParentUri = if (parentPath != null) Uri.parse(parentPath) else Uri.parse(rootUriString)
        val parentDir = DocumentFile.fromTreeUri(context, targetParentUri) ?: return false

        // Get source info
        val srcFile = DocumentFile.fromSingleUri(context, srcUri) ?: return false
        if (srcFile.isDirectory) return false

        val name = srcFile.name ?: "Unknown"
        val newName =
            if (name.contains(".")) {
                val ext = name.substringAfterLast(".")
                val base = name.substringBeforeLast(".")
                "$base Copy.$ext"
            } else {
                "$name Copy"
            }

        val mime = srcFile.type ?: "application/octet-stream"
        val newFile = parentDir.createFile(mime, newName) ?: return false

        return try {
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            newFile.delete()
            false
        }
    }
}

/**
 * Reads the 'thumbnail' field from a Protobuf stream.
 * Assumes 'thumbnail' is field #1 and String type (WireType 2).
 */
private fun readProtobufThumbnail(stream: InputStream): String? {
    val firstByte = stream.read()
    // Tag for Field 1, WireType 2 is 0x0A (0000 1010)
    if (firstByte != 0x0A) return null

    // Read Varint Length
    var length = 0
    var shift = 0
    while (true) {
        val b = stream.read()
        if (b == -1) return null
        length = length or ((b and 0x7F) shl shift)
        if ((b and 0x80) == 0) break
        shift += 7
    }

    // Limit length to prevent OOM on malformed data (e.g., 2MB max for thumb)
    if (length > 2 * 1024 * 1024) return null

    val bytes = ByteArray(length)
    var totalRead = 0
    while (totalRead < length) {
        val r = stream.read(bytes, totalRead, length - totalRead)
        if (r == -1) break
        totalRead += r
    }

    if (totalRead < length) return null

    return String(bytes, Charsets.UTF_8)
}
