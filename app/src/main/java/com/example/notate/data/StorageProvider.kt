package com.example.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

                    it.extension == "json" -> {
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
        val fileName = "$safeName.json"

        val parent = File(targetPath)
        val file = File(parent, fileName)

        return if (!file.exists()) {
            val json = Json.encodeToString(data)
            file.writeText(json)
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

                    it.name?.endsWith(".json") == true -> {
                        CanvasItem(
                            name = it.name?.removeSuffix(".json") ?: "Unknown",
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
        val fileName = "$safeName.json"

        if (parentDir.findFile(fileName) != null) return null

        val newFile = parentDir.createFile("application/json", safeName) ?: return null

        return try {
            val json = Json.encodeToString(data)
            context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                os.write(json.toByteArray())
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
                if (currentName.endsWith(".json", ignoreCase = true) && !newName.endsWith(".json", ignoreCase = true)) {
                    "$newName.json"
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
