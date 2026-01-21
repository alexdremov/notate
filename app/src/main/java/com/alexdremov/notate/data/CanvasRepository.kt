package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ThumbnailGenerator
import com.alexdremov.notate.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class CanvasSession(
    val sessionDir: File,
    val metadata: CanvasData,
    val regionManager: RegionManager,
    val originLastModified: Long = 0L,
    val originSize: Long = 0L,
) {
    fun close() {
        try {
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
        } catch (e: Exception) {
            // Ignore close errors
        }
    }
}

/**
 * Repository responsible for loading and saving Canvas data (V2: ZIP Format only).
 * Handles session extraction and atomic persistence.
 */
class CanvasRepository(
    private val context: Context,
) {
    data class LoadResult(
        val canvasState: CanvasSerializer.LoadedCanvasState,
        val session: CanvasSession,
    )

    private val sessionsDir: File by lazy {
        File(context.cacheDir, "sessions").apply { mkdirs() }
    }

    suspend fun openCanvasSession(path: String): CanvasSession? =
        withContext(Dispatchers.IO) {
            try {
                // Use unique directory for each session to prevent concurrent processes (Sync, Export)
                // from clobbering the active editing session.
                val sessionName = "${hashPath(path)}_${java.util.UUID.randomUUID()}"
                val sessionDir = File(sessionsDir, sessionName)
                sessionDir.mkdirs()

                val inputStream = openInputStream(path) ?: return@withContext null
                val isZip = isZipFile(inputStream)
                inputStream.close()

                if (!isZip) {
                    Logger.e("CanvasRepository", "Legacy file format not supported in V2: $path")
                    return@withContext null
                }

                // Re-open for reading
                val sourceStream = openInputStream(path) ?: return@withContext null

                val storage = RegionStorage(sessionDir)
                storage.init()

                var metadata: CanvasData? = null
                var regionSize = CanvasConfig.DEFAULT_REGION_SIZE

                // ZIP Format (New)
                ZipUtils.unzip(sourceStream, sessionDir)
                // Load metadata immediately to get regionSize
                val manifestFile = File(sessionDir, "manifest.bin")
                if (manifestFile.exists()) {
                    try {
                        metadata = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), manifestFile.readBytes())
                        regionSize = metadata.regionSize
                    } catch (e: Exception) {
                        Logger.e("CanvasRepository", "Failed to decode manifest", e)
                    }
                } else {
                    Logger.e("CanvasRepository", "Manifest missing in ZIP")
                    return@withContext null
                }

                // Initialize RegionManager AFTER unzipping so it sees the index.bin
                val regionManager = RegionManager(storage, regionSize)

                sourceStream.close()

                if (metadata != null) {
                    val originFile = File(path)
                    val (ts, size) =
                        if (originFile.exists()) {
                            originFile.lastModified() to originFile.length()
                        } else {
                            0L to 0L
                        }
                    CanvasSession(sessionDir, metadata, regionManager, ts, size)
                } else {
                    null
                }
            } catch (e: Exception) {
                Logger.e("CanvasRepository", "Failed to open session", e, showToUser = true)
                null
            }
        }

    data class SaveResult(
        val savedPath: String,
        val newLastModified: Long,
        val newSize: Long,
    )

    suspend fun saveCanvasSession(
        path: String,
        session: CanvasSession,
    ): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                var targetPath = path
                val targetFile = File(path)

                // Conflict Detection (Local Files only)
                if (targetFile.exists() && session.originLastModified > 0 && !path.startsWith("content://")) {
                    if (targetFile.lastModified() != session.originLastModified || targetFile.length() != session.originSize) {
                        Logger.w("CanvasRepository", "Conflict detected! File changed on disk. Saving as conflict copy.")
                        val parent = targetFile.parentFile
                        val name = targetFile.nameWithoutExtension
                        val ext = targetFile.extension
                        val timestamp = System.currentTimeMillis()
                        val newName = "${name}_conflict_$timestamp.$ext"
                        targetPath = File(parent, newName).absolutePath
                    }
                }

                // 1. Flush RegionManager
                session.regionManager.saveAll()

                // 2. Generate Thumbnail
                val thumbBase64 = ThumbnailGenerator.generateBase64(session.regionManager, session.metadata, context)
                val metadataWithThumb =
                    if (thumbBase64 != null) {
                        session.metadata.copy(thumbnail = thumbBase64)
                    } else {
                        session.metadata
                    }

                // 3. Save Metadata
                val manifestFile = File(session.sessionDir, "manifest.bin")
                val metaBytes = ProtoBuf.encodeToByteArray(CanvasData.serializer(), metadataWithThumb)
                manifestFile.writeBytes(metaBytes)

                if (!manifestFile.exists() || manifestFile.length() == 0L) {
                    throw java.io.IOException("Manifest generation failed")
                }

                // 4. Zip to Target
                writeSafe(targetPath) { os ->
                    val tempZip = File(session.sessionDir.parent, "${session.sessionDir.name}.zip")
                    if (tempZip.exists()) tempZip.delete()

                    ZipUtils.zip(session.sessionDir, tempZip)

                    // Verify Zip
                    if (!tempZip.exists() || tempZip.length() < 22) { // Empty zip is 22 bytes
                        throw java.io.IOException("Zip generation failed: File empty or too small")
                    }

                    // Check header
                    tempZip.inputStream().use {
                        if (!isZipFile(it)) throw java.io.IOException("Zip generation failed: Invalid header")
                    }

                    // Log success and contents
                    val files =
                        session.sessionDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .toList()
                    Logger.i("CanvasRepository", "ZIP Success: ${tempZip.length()} bytes. Contents (${files.size} files):")
                    files.forEach { file ->
                        val relPath = file.relativeTo(session.sessionDir).path
                        Logger.d("CanvasRepository", "  - $relPath (${file.length()} bytes)")
                    }

                    tempZip.inputStream().use { input ->
                        input.copyTo(os)
                    }
                    tempZip.delete()
                }

                val savedFile = File(targetPath)
                SaveResult(
                    savedPath = targetPath,
                    newLastModified = savedFile.lastModified(),
                    newSize = savedFile.length(),
                )
            } catch (e: Exception) {
                Logger.e("CanvasRepository", "Failed to save session", e, showToUser = true)
                throw e
            }
        }

    private fun isZipFile(input: InputStream): Boolean {
        // Wrap in BufferedInputStream to ensure mark/reset is supported
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(4)
        val signature = ByteArray(4)
        val read = buffered.read(signature)
        buffered.reset()
        if (read < 4) return false
        return signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte() // PK
    }

    private fun hashPath(path: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(path.toByteArray())
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[j * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }

    // --- Modern Load ---
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadCanvas(path: String): LoadResult? =
        withContext(Dispatchers.IO) {
            val tStart = System.currentTimeMillis()
            val session = openCanvasSession(path) ?: return@withContext null

            // Construct lightweight state from metadata (no items)
            val metadata = session.metadata
            val loadedState =
                CanvasSerializer.LoadedCanvasState(
                    quadtree =
                        com.alexdremov.notate.util
                            .Quadtree(0, android.graphics.RectF()),
                    // Dummy
                    contentBounds = android.graphics.RectF(), // Will be updated by model
                    nextStrokeOrder = metadata.nextStrokeOrder,
                    canvasType = metadata.canvasType,
                    pageWidth = metadata.pageWidth,
                    pageHeight = metadata.pageHeight,
                    backgroundStyle = metadata.backgroundStyle,
                    viewportScale = metadata.zoomLevel,
                    viewportOffsetX = metadata.offsetX,
                    viewportOffsetY = metadata.offsetY,
                    toolbarItems = metadata.toolbarItems,
                    tagIds = metadata.tagIds,
                    tagDefinitions = metadata.tagDefinitions,
                )

            Logger.d("CanvasRepository", "Session Open Time: ${System.currentTimeMillis() - tStart}ms")

            LoadResult(
                canvasState = loadedState,
                session = session,
            )
        }

    private fun OutputStream.writeVarInt(value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private fun openInputStream(path: String): InputStream? =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            val file = File(path)
            if (file.exists() && file.length() > 0) file.inputStream() else null
        }

    private fun writeSafe(
        path: String,
        block: (OutputStream) -> Unit,
    ) {
        // ALWAYS write to a local temp file first to ensure atomic generation
        // For SAF, we copy the temp file to the stream only if generation succeeds.

        val localTempFile = File.createTempFile("save_", ".tmp", context.cacheDir)

        try {
            localTempFile.outputStream().use { os ->
                block(os)
            }

            // If block succeeded, localTempFile is valid.

            if (path.startsWith("content://")) {
                // SAF: Stream copy (Non-Atomic at filesystem level, but atomic generation)
                context.contentResolver.openOutputStream(Uri.parse(path), "wt")?.use { os ->
                    localTempFile.inputStream().use { input ->
                        input.copyTo(os)
                    }
                } ?: throw java.io.IOException("Failed to open SAF output stream")
            } else {
                // Local File: Atomic Rename
                val targetFile = File(path)
                val backupFile = File(targetFile.parent, "${targetFile.name}.bak")

                if (targetFile.exists()) {
                    if (backupFile.exists()) backupFile.delete()
                    if (!targetFile.renameTo(backupFile)) {
                        // If we can't create backup, try to proceed?
                        // Or fail safe? Fail safe is better.
                        throw java.io.IOException("Failed to create backup: ${backupFile.absolutePath}")
                    }
                }

                if (!localTempFile.renameTo(targetFile)) {
                    // Restore backup
                    if (backupFile.exists()) {
                        backupFile.renameTo(targetFile)
                    }
                    // Copy if rename fails (cross-filesystem)?
                    // Try copy
                    try {
                        localTempFile.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        throw java.io.IOException("Failed to commit file to ${targetFile.absolutePath}")
                    }
                }

                if (backupFile.exists()) {
                    backupFile.delete()
                }
            }
        } finally {
            if (localTempFile.exists()) {
                localTempFile.delete()
            }
        }
    }
}
