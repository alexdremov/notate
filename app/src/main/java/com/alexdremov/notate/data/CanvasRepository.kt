package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
                    Log.d("CanvasRepository", "Loaded via Protobuf")
                } catch (e: Exception) {
                    // 2. Fallback to JSON
                    try {
                        val jsonString = String(bytes, Charsets.UTF_8)
                        data = Json.decodeFromString<CanvasData>(jsonString)
                        wasJson = true
                        Log.d("CanvasRepository", "Loaded via JSON Fallback")
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }
                tDecode = System.currentTimeMillis()

                if (data == null) return@withContext null

                // Parse heavy geometry
                val loadedState = CanvasSerializer.parseCanvasData(data)
                val tParse = System.currentTimeMillis()

                Log.d("CanvasRepository", "Load Timings:")
                Log.d("CanvasRepository", "  Read File: ${tRead - tStart}ms")
                Log.d("CanvasRepository", "  Decode: ${tDecode - tRead}ms")
                Log.d("CanvasRepository", "  Parse Geometry: ${tParse - tDecode}ms")
                Log.d("CanvasRepository", "  Total IO/Parse: ${tParse - tStart}ms")

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
                e.printStackTrace()
                null
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveCanvas(
        path: String,
        data: CanvasData,
    ) = withContext(Dispatchers.IO) {
        // Generate thumbnail
        val thumbBase64 = ThumbnailGenerator.generateBase64(data)
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
            context.contentResolver.openOutputStream(Uri.parse(path), "wt")?.use {
                it.write(bytes)
            }
        } else {
            // Atomic write for local files
            val targetFile = File(path)
            val tmpFile = File(targetFile.parent, "${targetFile.name}.tmp")
            tmpFile.writeBytes(bytes)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tmpFile.renameTo(targetFile)
        }
    }

    /**
     * Renames a .json file to .notate
     * Returns the new path if successful, null otherwise.
     */
    private fun migrateFile(originalPath: String): String? {
        Log.d("CanvasRepository", "Migration needed for $originalPath")
        if (originalPath.startsWith("content://")) {
            // SAF path: Rename the document
            val originalUri = Uri.parse(originalPath)
            val document = DocumentFile.fromSingleUri(context, originalUri)
            if (document != null && document.exists() && document.name?.endsWith(".json") == true) {
                val newName = (document.name ?: "").replace(".json", ".notate")
                if (document.renameTo(newName)) {
                    Log.d("CanvasRepository", "Successfully renamed SAF file to ${document.uri}")
                    return document.uri.toString()
                } else {
                    Log.e("CanvasRepository", "Failed to rename SAF file.")
                }
            }
        } else {
            // Local file path: Rename the file
            val originalFile = File(originalPath)
            if (originalFile.exists() && originalPath.endsWith(".json")) {
                val newPath = originalPath.replace(".json", ".notate")
                val newFile = File(newPath)
                if (originalFile.renameTo(newFile)) {
                    Log.d("CanvasRepository", "Successfully renamed local file to $newPath")
                    return newPath
                } else {
                    Log.e("CanvasRepository", "Failed to rename local file.")
                }
            }
        }
        return null
    }
}
