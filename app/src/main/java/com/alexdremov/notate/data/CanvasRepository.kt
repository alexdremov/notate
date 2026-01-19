package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

/**
 * Repository responsible for loading, saving, and migrating Canvas data.
 * Abstracts away the details of JSON vs Protobuf and file system operations (SAF vs Local).
 */
class CanvasRepository(
    private val context: Context,
) {
    data class LoadResult(
        val canvasState: CanvasSerializer.LoadedCanvasState,
        val migrationPerformed: Boolean,
        val newPath: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadCanvas(path: String): LoadResult? =
        withContext(Dispatchers.IO) {
            try {
                val tStart = System.currentTimeMillis()
                val bytes = readBytes(path) ?: return@withContext null
                val tRead = System.currentTimeMillis()

                var data: CanvasData? = null
                var tDecode = 0L
                var wasJson = false

                // 1. Try Protobuf
                try {
                    data = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), bytes)
                    Logger.d("CanvasRepository", "Loaded via Protobuf")
                } catch (e: Exception) {
                    // 2. Fallback to JSON
                    try {
                        val jsonString = String(bytes, Charsets.UTF_8)
                        data = Json.decodeFromString<CanvasData>(jsonString)
                        wasJson = true
                        Logger.d("CanvasRepository", "Loaded via JSON Fallback")
                    } catch (e2: Exception) {
                        Logger.e("CanvasRepository", "JSON Fallback failed", e2)
                        throw java.io.IOException("Failed to parse canvas data. Protobuf: ${e.message}, JSON: ${e2.message}", e)
                    }
                }
                tDecode = System.currentTimeMillis()

                if (data == null) return@withContext null

                // Parse heavy geometry
                val loadedState = CanvasSerializer.parseCanvasData(data)
                val tParse = System.currentTimeMillis()

                Logger.d("CanvasRepository", "Load Timings:")
                Logger.d("CanvasRepository", "  Read File: ${tRead - tStart}ms")
                Logger.d("CanvasRepository", "  Decode: ${tDecode - tRead}ms")
                Logger.d("CanvasRepository", "  Parse Geometry: ${tParse - tDecode}ms")
                Logger.d("CanvasRepository", "  Total IO/Parse: ${tParse - tStart}ms")

                var newPath: String? = null
                var migrationPerformed = false

                if (wasJson) {
                    newPath = migrateFile(path)
                    migrationPerformed = newPath != null
                    if (migrationPerformed) {
                        // Save immediately in new format to complete migration
                        saveCanvas(newPath!!, data)
                    }
                }

                LoadResult(
                    canvasState = loadedState,
                    migrationPerformed = migrationPerformed,
                    newPath = newPath,
                )
            } catch (e: Exception) {
                Logger.e("CanvasRepository", "Load Failed", e, showToUser = true)
                null
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveCanvas(
        path: String,
        data: CanvasData,
    ) = withContext(Dispatchers.IO) {
        // Generate thumbnail
        val thumbBase64 = ThumbnailGenerator.generateBase64(data, context)
        val dataWithThumb = data.copy(thumbnail = thumbBase64)

        // Encode
        val bytes = ProtoBuf.encodeToByteArray(dataWithThumb)

        withContext(NonCancellable) {
            writeBytes(path, bytes)
        }
    }

    private fun readBytes(path: String): ByteArray? =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
        } else {
            val file = File(path)
            if (file.exists() && file.length() > 0) file.readBytes() else null
        }

    private fun writeBytes(
        path: String,
        bytes: ByteArray,
    ) {
        if (path.startsWith("content://")) {
            val os =
                context.contentResolver.openOutputStream(Uri.parse(path), "wt")
                    ?: throw java.io.IOException("Failed to open output stream for SAF path: $path")
            os.use {
                it.write(bytes)
            }
        } else {
            // Atomic write for local files
            val targetFile = File(path)
            val tmpFile = File(targetFile.parent, "${targetFile.name}.tmp")
            val backupFile = File(targetFile.parent, "${targetFile.name}.bak")

            try {
                tmpFile.writeBytes(bytes)

                if (targetFile.exists()) {
                    // Create backup
                    if (backupFile.exists()) backupFile.delete()
                    if (!targetFile.renameTo(backupFile)) {
                        throw java.io.IOException("Failed to create backup file: ${backupFile.absolutePath}")
                    }
                }

                if (!tmpFile.renameTo(targetFile)) {
                    // Restore from backup if rename fails
                    if (backupFile.exists()) {
                        backupFile.renameTo(targetFile)
                    }
                    throw java.io.IOException("Failed to rename temp file to: ${targetFile.absolutePath}")
                }

                // Success, delete backup
                if (backupFile.exists()) {
                    backupFile.delete()
                }
            } finally {
                // Cleanup tmp file if it still exists (e.g. if delete or rename failed)
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        }
    }

    /**
     * Renames a .json file to .notate
     * Returns the new path if successful, null otherwise.
     */
    private fun migrateFile(originalPath: String): String? {
        Logger.d("CanvasRepository", "Migration needed for $originalPath")
        if (originalPath.startsWith("content://")) {
            // SAF path: Rename the document
            val originalUri = Uri.parse(originalPath)
            val document = DocumentFile.fromSingleUri(context, originalUri)
            if (document != null && document.exists() && document.name?.endsWith(".json") == true) {
                val newName = (document.name ?: "").replace(".json", ".notate")
                if (document.renameTo(newName)) {
                    Logger.d("CanvasRepository", "Successfully renamed SAF file to ${document.uri}")
                    return document.uri.toString()
                } else {
                    Logger.e("CanvasRepository", "Failed to rename SAF file.", showToUser = true)
                }
            }
        } else {
            // Local file path: Rename the file
            val originalFile = File(originalPath)
            if (originalFile.exists() && originalPath.endsWith(".json")) {
                val newPath = originalPath.replace(".json", ".notate")
                val newFile = File(newPath)
                if (originalFile.renameTo(newFile)) {
                    Logger.d("CanvasRepository", "Successfully renamed local file to $newPath")
                    return newPath
                } else {
                    Logger.e("CanvasRepository", "Failed to rename local file.", showToUser = true)
                }
            }
        }
        return null
    }
}
