@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.util.Logger
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

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

    fun setTags(
        path: String,
        tagIds: List<String>,
        tagDefinitions: List<Tag> = emptyList(),
    ): Boolean

    fun findFilesWithTag(tagId: String): List<CanvasItem>

    // New indexing support
    fun walkFiles(visitor: (String, String, Long) -> Unit)

    fun getFileMetadata(path: String): CanvasDataPreview?
}

internal object StorageUtils {
    private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024 // 10MB

    fun getSafeFileName(name: String): String = name.replace("[^a-zA-Z0-9\\s]".toRegex(), "").trim()

    fun getDuplicateName(originalName: String): String =
        if (originalName.contains(".")) {
            val ext = originalName.substringAfterLast(".")
            val base = originalName.substringBeforeLast(".")
            "$base Copy.$ext"
        } else {
            "$originalName Copy"
        }

    fun extractMetadata(
        fileName: String?,
        streamProvider: () -> InputStream?,
        fileSize: Long = 0,
    ): CanvasDataPreview? {
        if (fileSize > LARGE_FILE_THRESHOLD) {
            Logger.w("Storage", "Reading metadata from large file: $fileName (${fileSize / 1024 / 1024}MB)")
        }

        var isJson = false
        try {
            streamProvider()?.use { stream ->
                val firstByte = stream.read()
                if (firstByte == 0x7B) {
                    isJson = true
                }
            }
        } catch (e: Exception) {
            isJson = fileName?.endsWith(".json") == true
        }

        return if (isJson) extractMetadataJson(streamProvider) else extractMetadataProtobuf(streamProvider)
    }

    private fun extractMetadataJson(streamProvider: () -> InputStream?): CanvasDataPreview? {
        return try {
            streamProvider()?.use { stream ->
                val buffer = ByteArray(500 * 1024) // 500KB
                val read = stream.read(buffer)
                if (read <= 0) return null
                val header = String(buffer, 0, read, Charsets.UTF_8)
                val match = Regex("""thumbnail"\s*:\s*"([^"]+)""").find(header)
                val thumbnail = match?.groupValues?.get(1)
                CanvasDataPreview(thumbnail = thumbnail)
            }
        } catch (e: Exception) {
            Logger.e("Metadata", "Failed to extract metadata from JSON", e)
            null
        }
    }

    private fun extractMetadataProtobuf(streamProvider: () -> InputStream?): CanvasDataPreview? =
        try {
            streamProvider()?.use { stream ->
                var thumbnail: String? = null
                val tagIds = mutableListOf<String>()
                val tagDefinitions = mutableListOf<Tag>()

                while (true) {
                    val tag = readVarint(stream)
                    if (tag == -1L) break

                    val fieldNumber = (tag ushr 3).toInt()
                    val wireType = (tag and 0x07).toInt()

                    when (fieldNumber) {
                        1 -> { // thumbnail
                            if (wireType != 2) {
                                skipField(stream, wireType)
                            } else {
                                val length = readVarint(stream)
                                if (length > 0) {
                                    val bytes = ByteArray(length.toInt())
                                    readFully(stream, bytes)
                                    thumbnail = String(bytes, Charsets.UTF_8)
                                }
                            }
                        }

                        13 -> { // tagIds
                            if (wireType != 2) {
                                skipField(stream, wireType)
                            } else {
                                val length = readVarint(stream)
                                if (length > 0) {
                                    val bytes = ByteArray(length.toInt())
                                    readFully(stream, bytes)
                                    tagIds.add(String(bytes, Charsets.UTF_8))
                                }
                            }
                        }

                        14 -> { // tagDefinitions
                            if (wireType != 2) {
                                skipField(stream, wireType)
                            } else {
                                val length = readVarint(stream)
                                if (length > 0) {
                                    val bytes = ByteArray(length.toInt())
                                    readFully(stream, bytes)
                                    try {
                                        val tagVal = ProtoBuf.decodeFromByteArray(Tag.serializer(), bytes)
                                        tagDefinitions.add(tagVal)
                                    } catch (e: Exception) {
                                        // Ignore malformed tag
                                    }
                                }
                            }
                        }

                        else -> {
                            skipField(stream, wireType)
                        }
                    }
                }
                CanvasDataPreview(thumbnail, tagIds, tagDefinitions)
            }
        } catch (e: Exception) {
            Logger.e(
                "Metadata",
                "Failed to extract metadata from protobuf; the data may be malformed or the schema may be incompatible",
                e,
            )
            null
        }

    fun createUpdatedProtobuf(
        inputStream: InputStream,
        outputStream: OutputStream,
        newTagIds: List<String>,
        newTagDefinitions: List<Tag>,
    ) {
        while (true) {
            val tag = readVarint(inputStream)
            if (tag == -1L) break

            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (fieldNumber == 13 || fieldNumber == 14) {
                // Skip this field
                skipField(inputStream, wireType)
            } else {
                // Write tag
                writeVarint(outputStream, tag)
                // Copy payload based on wire type
                when (wireType) {
                    0 -> { // Varint
                        val value = readVarint(inputStream)
                        writeVarint(outputStream, value)
                    }

                    1 -> { // 64-bit
                        val bytes = ByteArray(8)
                        readFully(inputStream, bytes)
                        outputStream.write(bytes)
                    }

                    2 -> { // Length Delimited
                        val length = readVarint(inputStream)
                        writeVarint(outputStream, length)
                        copyBytes(inputStream, outputStream, length)
                    }

                    5 -> { // 32-bit
                        val bytes = ByteArray(4)
                        readFully(inputStream, bytes)
                        outputStream.write(bytes)
                    }

                    else -> {
                        throw java.io.IOException("Unsupported wire type: $wireType")
                    }
                }
            }
        }

        // Append new tagIds
        val tagIdFieldNumber = 13
        val tagIdTag = (tagIdFieldNumber shl 3) or 2
        for (id in newTagIds) {
            val bytes = id.toByteArray(Charsets.UTF_8)
            writeVarint(outputStream, tagIdTag.toLong())
            writeVarint(outputStream, bytes.size.toLong())
            outputStream.write(bytes)
        }

        // Append new tagDefinitions
        val tagDefFieldNumber = 14
        val tagDefTag = (tagDefFieldNumber shl 3) or 2
        for (def in newTagDefinitions) {
            val bytes = ProtoBuf.encodeToByteArray(Tag.serializer(), def)
            writeVarint(outputStream, tagDefTag.toLong())
            writeVarint(outputStream, bytes.size.toLong())
            outputStream.write(bytes)
        }
    }

    private fun readVarint(stream: InputStream): Long {
        var value = 0L
        var shift = 0
        var count = 0
        while (true) {
            val b = stream.read()
            if (b == -1) {
                if (count == 0) return -1L
                throw java.io.EOFException("Unexpected EOF inside varint")
            }
            value = value or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            count++
            if (shift > 63) throw java.io.IOException("Varint too long")
        }
        return value
    }

    private fun writeVarint(
        stream: OutputStream,
        value: Long,
    ) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0L) {
                stream.write(v.toInt())
                break
            } else {
                stream.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun readFully(
        stream: InputStream,
        bytes: ByteArray,
    ) {
        var pos = 0
        while (pos < bytes.size) {
            val r = stream.read(bytes, pos, bytes.size - pos)
            if (r == -1) throw java.io.EOFException("Unexpected EOF reading bytes")
            pos += r
        }
    }

    private fun copyBytes(
        input: InputStream,
        output: OutputStream,
        count: Long,
    ) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) throw java.io.EOFException("Unexpected EOF copying bytes")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipField(
        stream: InputStream,
        wireType: Int,
    ) {
        when (wireType) {
            0 -> {
                readVarint(stream)
            }

            1 -> {
                skipBytes(stream, 8)
            }

            2 -> {
                val length = readVarint(stream)
                skipBytes(stream, length)
            }

            5 -> {
                skipBytes(stream, 4)
            }

            else -> {
                throw java.io.IOException("Unsupported wire type: $wireType")
            }
        }
    }

    private fun skipBytes(
        stream: InputStream,
        count: Long,
    ) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() == -1) throw java.io.EOFException("Unexpected EOF skipping bytes")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
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
        if (!targetDir.exists()) {
            Logger.w("Storage", "Directory not found: $targetPath")
            return emptyList()
        }

        return try {
            targetDir
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
                            val metadata = StorageUtils.extractMetadata(it.name, { it.inputStream() }, it.length())
                            CanvasItem(
                                name = it.nameWithoutExtension,
                                path = it.absolutePath,
                                lastModified = it.lastModified(),
                                thumbnail = metadata?.thumbnail,
                                tagIds = metadata?.tagIds ?: emptyList(),
                                embeddedTags = metadata?.tagDefinitions ?: emptyList(),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }?.sortedByDescending { it.lastModified } ?: emptyList()
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to list items in $targetPath", e, showToUser = true)
            emptyList()
        }
    }

    override fun createFolder(
        name: String,
        parentPath: String?,
    ): Boolean {
        val targetPath = parentPath ?: rootDir.absolutePath
        val parent = File(targetPath)
        val newDir = File(parent, name)
        return try {
            if (!newDir.exists()) newDir.mkdirs() else false
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to create folder $name", e, showToUser = true)
            false
        }
    }

    override fun createCanvas(
        name: String,
        parentPath: String?,
        data: CanvasData,
    ): String? {
        val targetPath = parentPath ?: rootDir.absolutePath
        val safeName = StorageUtils.getSafeFileName(name)
        val fileName = "$safeName.notate"

        val parent = File(targetPath)
        val file = File(parent, fileName)

        return try {
            if (!file.exists()) {
                val bytes = ProtoBuf.encodeToByteArray(data)
                file.writeBytes(bytes)
                file.absolutePath
            } else {
                Logger.w("Storage", "File already exists: $fileName")
                null
            }
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to create canvas $name", e, showToUser = true)
            null
        }
    }

    override fun deleteItem(path: String): Boolean {
        val file = File(path)
        return try {
            if (file.exists()) file.deleteRecursively() else false
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to delete item $path", e, showToUser = true)
            false
        }
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
        return try {
            file.renameTo(newFile)
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to rename item $path", e, showToUser = true)
            false
        }
    }

    override fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean {
        val file = File(path)
        if (!file.exists()) return false

        try {
            if (file.isDirectory) {
                val newDir = File(file.parent, "${file.name} Copy")
                return file.copyRecursively(newDir, overwrite = false)
            } else {
                val newName = StorageUtils.getDuplicateName(file.name)
                val newFile = File(file.parent, newName)
                return file.copyTo(newFile, overwrite = false).exists()
            }
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to duplicate item $path", e, showToUser = true)
            return false
        }
    }

    override fun setTags(
        path: String,
        tagIds: List<String>,
        tagDefinitions: List<Tag>,
    ): Boolean {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return false

        if (file.extension != "notate") return false

        val tempFile = File(file.parent, file.name + ".tmp")
        return try {
            file.inputStream().use { input ->
                tempFile.outputStream().use { output ->
                    StorageUtils.createUpdatedProtobuf(input, output, tagIds, tagDefinitions)
                }
            }
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to set tags for $path", e, showToUser = true)
            tempFile.delete()
            false
        }
    }

    override fun findFilesWithTag(tagId: String): List<CanvasItem> =
        try {
            rootDir
                .walk()
                .maxDepth(20) // Limit recursion depth
                .filter { it.isFile && (it.extension == "json" || it.extension == "notate") }
                .mapNotNull {
                    val metadata = StorageUtils.extractMetadata(it.name, { it.inputStream() }, it.length())
                    if (metadata?.tagIds?.contains(tagId) == true) {
                        CanvasItem(
                            name = it.nameWithoutExtension,
                            path = it.absolutePath,
                            lastModified = it.lastModified(),
                            thumbnail = metadata.thumbnail,
                            tagIds = metadata.tagIds,
                            embeddedTags = metadata.tagDefinitions,
                        )
                    } else {
                        null
                    }
                }.toList()
                .sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to search files in ${rootDir.absolutePath}", e)
            emptyList()
        }

    override fun walkFiles(visitor: (String, String, Long) -> Unit) {
        rootDir
            .walk()
            .maxDepth(20)
            .filter { it.isFile && (it.extension == "json" || it.extension == "notate") }
            .forEach {
                visitor(it.absolutePath, it.nameWithoutExtension, it.lastModified())
            }
    }

    override fun getFileMetadata(path: String): CanvasDataPreview? {
        val file = File(path)
        if (!file.exists()) return null
        return StorageUtils.extractMetadata(file.name, { file.inputStream() }, file.length())
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

        return try {
            dir
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
                            val metadata =
                                StorageUtils.extractMetadata(it.name, {
                                    try {
                                        context.contentResolver.openInputStream(it.uri)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }, it.length())
                            CanvasItem(
                                name = name.removeSuffix(if (isJsonExt) ".json" else ".notate"),
                                path = it.uri.toString(),
                                lastModified = it.lastModified(),
                                thumbnail = metadata?.thumbnail,
                                tagIds = metadata?.tagIds ?: emptyList(),
                                embeddedTags = metadata?.tagDefinitions ?: emptyList(),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }.sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to list items in $rootUriString", e, showToUser = true)
            emptyList()
        }
    }

    override fun createFolder(
        name: String,
        parentPath: String?,
    ): Boolean {
        val targetUri = if (parentPath != null) Uri.parse(parentPath) else Uri.parse(rootUriString)
        val parentDir = DocumentFile.fromTreeUri(context, targetUri) ?: return false

        return try {
            if (parentDir.findFile(name) == null) {
                parentDir.createDirectory(name) != null
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to create folder $name", e, showToUser = true)
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

        val safeName = StorageUtils.getSafeFileName(name)
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
            Logger.e("Storage", "Failed to create SAF canvas", e, showToUser = true)
            null
        }
    }

    override fun deleteItem(path: String): Boolean {
        val uri = Uri.parse(path)
        val df = DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
        return try {
            df?.delete() == true
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to delete item $path", e, showToUser = true)
            false
        }
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

        return try {
            df.renameTo(finalName)
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to rename item $path", e, showToUser = true)
            false
        }
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
        val newName = StorageUtils.getDuplicateName(name)

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
            Logger.e("Storage", "Failed to duplicate item $path", e, showToUser = true)
            newFile.delete()
            false
        }
    }

    override fun setTags(
        path: String,
        tagIds: List<String>,
        tagDefinitions: List<Tag>,
    ): Boolean {
        val uri = Uri.parse(path)
        val file = DocumentFile.fromSingleUri(context, uri) ?: return false

        if (file.isDirectory) return false
        // Basic check for file extension from name
        if (file.name?.endsWith(".notate") != true) return false

        val tempFile = File.createTempFile("saf_update", ".tmp", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    StorageUtils.createUpdatedProtobuf(input, output, tagIds, tagDefinitions)
                }
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to set tags SAF", e, showToUser = true)
            false
        } finally {
            tempFile.delete()
        }
    }

    override fun findFilesWithTag(tagId: String): List<CanvasItem> {
        val rootUri = Uri.parse(rootUriString)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val results = mutableListOf<CanvasItem>()
        try {
            searchIterative(rootDir, tagId, results)
        } catch (e: Exception) {
            Logger.e("Storage", "Failed to search SAF", e)
        }
        return results.sortedByDescending { it.lastModified }
    }

    private fun searchIterative(
        rootDir: DocumentFile,
        tagId: String,
        results: MutableList<CanvasItem>,
    ) {
        val stack = ArrayDeque<Pair<DocumentFile, Int>>()
        stack.add(rootDir to 0)

        while (!stack.isEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > 20) continue // Limit recursion depth

            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    stack.add(file to depth + 1)
                } else if (file.name?.endsWith(".json") == true || file.name?.endsWith(".notate") == true) {
                    val metadata =
                        StorageUtils.extractMetadata(file.name, {
                            try {
                                context.contentResolver.openInputStream(file.uri)
                            } catch (e: Exception) {
                                null
                            }
                        }, file.length())
                    if (metadata?.tagIds?.contains(tagId) == true) {
                        val name = file.name ?: "Unknown"
                        val isJsonExt = name.endsWith(".json")
                        results.add(
                            CanvasItem(
                                name = name.removeSuffix(if (isJsonExt) ".json" else ".notate"),
                                path = file.uri.toString(),
                                lastModified = file.lastModified(),
                                thumbnail = metadata.thumbnail,
                                tagIds = metadata.tagIds,
                                embeddedTags = metadata.tagDefinitions,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun walkFiles(visitor: (String, String, Long) -> Unit) {
        val rootUri = Uri.parse(rootUriString)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri) ?: return
        val stack = ArrayDeque<Pair<DocumentFile, Int>>()
        stack.add(rootDir to 0)

        while (!stack.isEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > 20) continue // Limit recursion depth

            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    stack.add(file to depth + 1)
                } else if (file.name?.endsWith(".json") == true || file.name?.endsWith(".notate") == true) {
                    val name = file.name ?: "Unknown"
                    val isJsonExt = name.endsWith(".json")
                    val displayName = name.removeSuffix(if (isJsonExt) ".json" else ".notate")
                    visitor(file.uri.toString(), displayName, file.lastModified())
                }
            }
        }
    }

    override fun getFileMetadata(path: String): CanvasDataPreview? {
        val uri = Uri.parse(path)
        val name = DocumentFile.fromSingleUri(context, uri)?.name
        return StorageUtils.extractMetadata(name, {
            try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
        })
    }
}
