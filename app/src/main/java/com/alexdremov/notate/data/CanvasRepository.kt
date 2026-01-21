package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ThumbnailGenerator
import com.alexdremov.notate.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Repository responsible for loading and saving Canvas data (V2: ZIP Format only).
 * Handles session extraction and atomic persistence.
 */
class CanvasRepository(
    private val context: Context,
) {
    data class SaveResult(
        val savedPath: String,
        val newLastModified: Long,
        val newSize: Long,
    )

    private val sessionsDir: File by lazy {
        File(context.cacheDir, "sessions").apply { mkdirs() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun openCanvasSession(path: String): CanvasSession? =
        withContext(Dispatchers.IO) {
            try {
                val sessionName = "${hashPath(path)}_${java.util.UUID.randomUUID()}"
                val sessionDir = File(sessionsDir, sessionName)
                sessionDir.mkdirs()

                val inputStream = openInputStream(path)
                if (inputStream == null) {
                    // New file - create empty session
                    Logger.i("CanvasRepository", "Creating new session for:  $path")
                    val storage = RegionStorage(sessionDir)
                    storage.init()
                    val regionManager = RegionManager(storage, CanvasConfig.DEFAULT_REGION_SIZE)
                    val metadata = CanvasData(version = 3, regionSize = CanvasConfig.DEFAULT_REGION_SIZE)
                    return@withContext CanvasSession(
                        sessionDir = sessionDir,
                        regionManager = regionManager,
                        originLastModified = 0L,
                        originSize = 0L,
                        metadata = metadata,
                    )
                }

                val isZip = isZipFile(inputStream)
                inputStream.close()

                if (!isZip) {
                    Logger.e("CanvasRepository", "Legacy file format not supported in V2: $path")
                    sessionDir.deleteRecursively()
                    return@withContext null
                }

                // Re-open for reading
                val sourceStream = openInputStream(path) ?: return@withContext null

                val storage = RegionStorage(sessionDir)
                storage.init()

                // Unzip to session directory
                ZipUtils.unzip(sourceStream, sessionDir)
                sourceStream.close()

                // Load metadata
                val manifestFile = File(sessionDir, "manifest.bin")
                val metadata: CanvasData
                if (manifestFile.exists()) {
                    try {
                        metadata = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), manifestFile.readBytes())
                    } catch (e: Exception) {
                        Logger.e("CanvasRepository", "Failed to decode manifest", e)
                        sessionDir.deleteRecursively()
                        return@withContext null
                    }
                } else {
                    Logger.e("CanvasRepository", "Manifest missing in ZIP")
                    sessionDir.deleteRecursively()
                    return@withContext null
                }

                // Initialize RegionManager AFTER unzipping so it sees the index. bin
                val regionManager = RegionManager(storage, metadata.regionSize)

                val originFile = File(path)
                val (ts, size) =
                    if (originFile.exists()) {
                        originFile.lastModified() to originFile.length()
                    } else {
                        0L to 0L
                    }

                CanvasSession(
                    sessionDir = sessionDir,
                    regionManager = regionManager,
                    originLastModified = ts,
                    originSize = size,
                    metadata = metadata,
                )
            } catch (e: Exception) {
                Logger.e("CanvasRepository", "Failed to open session", e, showToUser = true)
                null
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveCanvasSession(
        path: String,
        session: CanvasSession,
    ): SaveResult =
        withContext(Dispatchers.IO) {
            // Acquire session for operation - prevents close() from deleting directory
            if (!session.acquireForOperation()) {
                throw IllegalStateException("Cannot save:  session is closed")
            }

            try {
                // Verify session directory still exists
                if (!session.sessionDir.exists()) {
                    throw java.io.IOException("Session directory does not exist: ${session.sessionDir.absolutePath}")
                }

                var targetPath = path
                val targetFile = File(path)

                // Conflict Detection (Local Files only)
                if (targetFile.exists() && session.originLastModified > 0 && !path.startsWith("content://")) {
                    if (targetFile.lastModified() != session.originLastModified ||
                        targetFile.length() != session.originSize
                    ) {
                        Logger.w("CanvasRepository", "Conflict detected!  File changed on disk.  Saving as conflict copy.")
                        val parent = targetFile.parentFile
                        val name = targetFile.nameWithoutExtension
                        val ext = targetFile.extension
                        val timestamp = System.currentTimeMillis()
                        val newName = "${name}_conflict_$timestamp.$ext"
                        targetPath = File(parent, newName).absolutePath
                    }
                }

                // 1. Flush RegionManager (writes to session directory)
                session.regionManager.saveAll()

                // 2. Generate Thumbnail
                val thumbBase64 = ThumbnailGenerator.generateBase64(session.regionManager, session.metadata, context)
                val metadataWithThumb =
                    if (thumbBase64 != null) {
                        session.metadata.copy(thumbnail = thumbBase64)
                    } else {
                        session.metadata
                    }

                // 3. Save Metadata to session directory
                val manifestFile = File(session.sessionDir, "manifest.bin")
                val metaBytes = ProtoBuf.encodeToByteArray(CanvasData.serializer(), metadataWithThumb)
                manifestFile.writeBytes(metaBytes)

                if (!manifestFile.exists() || manifestFile.length() == 0L) {
                    throw java.io.IOException("Manifest generation failed")
                }

                // 4. Create ZIP in a temp location OUTSIDE the session directory
                val tempZip = File(context.cacheDir, "save_${System.currentTimeMillis()}.zip.tmp")
                try {
                    ZipUtils.zip(session.sessionDir, tempZip)

                    // Verify ZIP
                    if (!tempZip.exists() || tempZip.length() < 22) {
                        throw java.io.IOException("Zip generation failed:  File empty or too small (${tempZip.length()} bytes)")
                    }

                    tempZip.inputStream().use {
                        if (!isZipFile(it)) throw java.io.IOException("Zip generation failed: Invalid header")
                    }

                    // Log success
                    val files =
                        session.sessionDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .toList()
                    Logger.i("CanvasRepository", "ZIP created:  ${tempZip.length()} bytes from ${files.size} files")

                    // 5. Write to target using atomic operations
                    writeSafe(targetPath, tempZip)
                } finally {
                    // Always clean up temp ZIP
                    if (tempZip.exists()) {
                        tempZip.delete()
                    }
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
            } finally {
                // Always release the operation lock
                session.releaseOperation()
            }
        }

    private fun isZipFile(input: InputStream): Boolean {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(4)
        val signature = ByteArray(4)
        val read = buffered.read(signature)
        buffered.reset()
        if (read < 4) return false
        return signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte()
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

    private fun openInputStream(path: String): InputStream? =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            val file = File(path)
            if (file.exists() && file.length() > 0) file.inputStream() else null
        }

    /**
     * Atomically writes a source file to the target path.
     * For local files:  rename with backup
     * For SAF: stream copy
     */
    private fun writeSafe(
        targetPath: String,
        sourceFile: File,
    ) {
        if (targetPath.startsWith("content://")) {
            // SAF: Stream copy
            context.contentResolver.openOutputStream(Uri.parse(targetPath), "wt")?.use { os ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(os)
                }
            } ?: throw java.io.IOException("Failed to open SAF output stream")
        } else {
            // Local File: Atomic Rename with backup
            val targetFile = File(targetPath)
            val backupFile = File(targetFile.parent, "${targetFile.name}.bak")

            // Ensure parent directory exists
            targetFile.parentFile?. mkdirs()

            if (targetFile.exists()) {
                if (backupFile.exists()) backupFile.delete()
                if (!targetFile.renameTo(backupFile)) {
                    throw java.io.IOException("Failed to create backup: ${backupFile.absolutePath}")
                }
            }

            // Try rename first (fast, atomic if same filesystem)
            if (!sourceFile.renameTo(targetFile)) {
                // Rename failed (cross-filesystem) - copy instead
                try {
                    sourceFile.inputStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Verify copy
                    if (targetFile.length() != sourceFile.length()) {
                        throw java.io.IOException("Copy verification failed: size mismatch")
                    }
                } catch (e: Exception) {
                    // Restore backup on failure
                    if (backupFile.exists()) {
                        targetFile.delete()
                        backupFile.renameTo(targetFile)
                    }
                    throw java.io.IOException("Failed to commit file to ${targetFile.absolutePath}", e)
                }
            }

            // Success - delete backup
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }
    }
}
