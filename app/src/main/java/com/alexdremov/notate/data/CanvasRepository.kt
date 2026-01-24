package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.io.AtomicContainerStorage
import com.alexdremov.notate.data.io.FileLockManager
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Repository responsible for loading and saving Canvas data (V2: ZIP Format only).
 * Handles session extraction and atomic persistence using strict file locking.
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

    private val atomicStorage = AtomicContainerStorage(context)
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    companion object {
        // Cache active sessions to handle quick close/re-open and share state within the SAME process.
        private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, CanvasSession>()
        private val sessionLock = Mutex()
    }

    class CanvasLockedException(
        message: String,
    ) : Exception(message)

    /**
     * Checks if a local file is currently locked by another process.
     * Note: This attempts to acquire and immediately release the lock to test.
     */
    fun isLocked(path: String): Boolean {
        if (path.startsWith("content://")) return false // Cannot check lock on content URI
        return try {
            val lock = FileLockManager.acquire(path)
            lock.close()
            false
        } catch (e: Exception) {
            true
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun openCanvasSession(path: String): CanvasSession? =
        withContext(Dispatchers.IO) {
            sessionLock.withLock {
                val sessionName = hashPath(path)

                // 1. Check Active Session Cache (Hot Handoff - Same Process)
                val existingSession = activeSessions[sessionName]
                if (existingSession != null && !existingSession.isClosed()) {
                    Logger.i("CanvasRepository", "Attaching to active session: $path")
                    existingSession.retain()
                    return@withContext existingSession
                }

                // Remove stale reference
                if (existingSession != null) {
                    activeSessions.remove(sessionName)
                }

                // 2. Acquire System File Lock (Cross-Process Exclusion)
                // Prevents Process B from opening while Process A has it open.
                var fileLock: FileLockManager.LockedFileHandle? = null
                if (!path.startsWith("content://")) {
                    try {
                        fileLock = FileLockManager.acquire(path)
                    } catch (e: Exception) {
                        Logger.e("CanvasRepository", "Cannot open session. File is locked: $path", e)
                        throw CanvasLockedException("File is currently open in another window or process.")
                    }
                }

                try {
                    val sessionDir = File(sessionsDir, sessionName)

                    // Track source info
                    val originFile = if (path.startsWith("content://")) null else File(path)
                    val originLastModified = originFile?.lastModified() ?: 0L
                    val originSize = originFile?.length() ?: 0L

                    // 3. Initialize Session Directory
                    var sessionValid = false
                    if (sessionDir.exists()) {
                        // Check if we can resume this session (crash recovery / persistence)
                        val manifestFile = File(sessionDir, "manifest.bin")
                        val sourcePathFile = File(sessionDir, "source_path.txt")

                        if (manifestFile.exists()) {
                            val storedPath = if (sourcePathFile.exists()) sourcePathFile.readText().trim() else ""
                            if (storedPath == path) {
                                // Check timestamps to ensure the session cache isn't older than the file
                                // (e.g. file updated by sync or another device)
                                if (originFile != null && originFile.exists()) {
                                    if (manifestFile.lastModified() > originLastModified) {
                                        Logger.i("CanvasRepository", "Resuming existing session (newer than file): $path")
                                        sessionValid = true
                                    } else {
                                        Logger.i("CanvasRepository", "Existing session stale. Reloading from file.")
                                    }
                                } else {
                                    // Remote file - assume valid if path matches
                                    sessionValid = true
                                }
                            }
                        }
                    }

                    val finalSession: CanvasSession
                    if (!sessionValid) {
                        // Clean start: Wipe directory
                        if (sessionDir.exists()) sessionDir.deleteRecursively()
                        sessionDir.mkdirs()
                        File(sessionDir, "source_path.txt").writeText(path)

                        val inputStream = openInputStream(path)
                        if (inputStream == null) {
                            // New file
                            Logger.i("CanvasRepository", "Creating new session for: $path")
                            val storage = RegionStorage(sessionDir)
                            storage.init()

                            val metadata = CanvasData(version = 3, regionSize = CanvasConfig.DEFAULT_REGION_SIZE)
                            val manifestFile = File(sessionDir, "manifest.bin")
                            val metaBytes = ProtoBuf.encodeToByteArray(CanvasData.serializer(), metadata)
                            manifestFile.writeBytes(metaBytes)

                            val regionManager = RegionManager(storage, CanvasConfig.DEFAULT_REGION_SIZE)
                            finalSession =
                                CanvasSession(
                                    sessionDir = sessionDir,
                                    regionManager = regionManager,
                                    originLastModified = originLastModified,
                                    originSize = originSize,
                                    metadata = metadata,
                                    lockHandle = fileLock,
                                )
                        } else {
                            val format = checkFileFormat(inputStream)
                            inputStream.close()

                            if (format == FileFormat.EMPTY) {
                                // Treat as new file
                                Logger.i("CanvasRepository", "Empty file detected via stream. Creating new session for: $path")
                                val storage = RegionStorage(sessionDir)
                                storage.init()

                                val metadata = CanvasData(version = 3, regionSize = CanvasConfig.DEFAULT_REGION_SIZE)
                                val manifestFile = File(sessionDir, "manifest.bin")
                                val metaBytes = ProtoBuf.encodeToByteArray(CanvasData.serializer(), metadata)
                                manifestFile.writeBytes(metaBytes)

                                val regionManager = RegionManager(storage, CanvasConfig.DEFAULT_REGION_SIZE)
                                finalSession =
                                    CanvasSession(
                                        sessionDir = sessionDir,
                                        regionManager = regionManager,
                                        originLastModified = originLastModified,
                                        originSize = originSize,
                                        metadata = metadata,
                                        lockHandle = fileLock,
                                    )
                            } else if (format != FileFormat.ZIP) {
                                Logger.e("CanvasRepository", "Legacy/Unknown file format not supported in V2: $path")
                                sessionDir.deleteRecursively()
                                fileLock?.close()
                                return@withContext null
                            } else {
                                // Optimized Load (JIT Unzip) for Local Files
                                // For local files, we skip full unpack and let RegionStorage extract on demand.
                                var metadata: CanvasData? = null
                                var storage: RegionStorage? = null
                                var useOptimizedPath = false

                                if (!path.startsWith("content://")) {
                                    val sourceFile = File(path)
                                    // Try optimized load
                                    val manifest =
                                        com.alexdremov.notate.util.ZipUtils
                                            .readManifest(sourceFile)
                                    if (manifest != null) {
                                        Logger.i("CanvasRepository", "Using Optimized JIT Load for: $path")

                                        // We need to extract manifest.bin to disk so RegionStorage sees it?
                                        // Actually RegionManager loads metadata passed to constructor.
                                        // But we should extract it for consistency if we save later.
                                        com.alexdremov.notate.util.ZipUtils.extractFile(
                                            sourceFile,
                                            "manifest.bin",
                                            File(sessionDir, "manifest.bin"),
                                        )

                                        metadata = manifest
                                        storage = RegionStorage(sessionDir, zipSource = sourceFile)
                                        storage.init()
                                        useOptimizedPath = true
                                    }
                                }

                                if (!useOptimizedPath) {
                                    // Fallback: Full Unpack
                                    Logger.i("CanvasRepository", "Using Legacy Full Unpack for: $path")
                                    val sourceStream =
                                        openInputStream(path) ?: run {
                                            fileLock?.close()
                                            return@withContext null
                                        }

                                    try {
                                        atomicStorage.unpack(sourceStream, sessionDir)
                                    } finally {
                                        sourceStream.close()
                                    }

                                    // Load Metadata
                                    val manifestFile = File(sessionDir, "manifest.bin")
                                    if (manifestFile.exists()) {
                                        metadata = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), manifestFile.readBytes())
                                    } else {
                                        Logger.e("CanvasRepository", "Manifest missing in ZIP")
                                        sessionDir.deleteRecursively()
                                        fileLock?.close()
                                        return@withContext null
                                    }

                                    storage = RegionStorage(sessionDir) // No zipSource fallback needed
                                    storage.init()
                                }

                                val regionManager = RegionManager(storage!!, metadata!!.regionSize)

                                finalSession =
                                    CanvasSession(
                                        sessionDir = sessionDir,
                                        regionManager = regionManager,
                                        originLastModified = originLastModified,
                                        originSize = originSize,
                                        metadata = metadata,
                                        lockHandle = fileLock,
                                    )

                                if (useOptimizedPath && !path.startsWith("content://")) {
                                    // Launch background unzip to ensure full session integrity for saving
                                    val sourceFile = File(path)
                                    finalSession.initializationJob =
                                        repositoryScope.launch {
                                            try {
                                                Logger.i("CanvasRepository", "Starting background unzip for remainder of session...")
                                                com.alexdremov.notate.util.ZipUtils
                                                    .unzipSkippingExisting(sourceFile, sessionDir)
                                                Logger.i("CanvasRepository", "Background unzip complete.")
                                            } catch (e: Exception) {
                                                Logger.e("CanvasRepository", "Background unzip failed", e)
                                            }
                                        }
                                }
                            }
                        }
                    } else {
                        // Resuming
                        val storage = RegionStorage(sessionDir)
                        storage.init()
                        val manifestFile = File(sessionDir, "manifest.bin")
                        val metadata = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), manifestFile.readBytes())
                        val regionManager = RegionManager(storage, metadata.regionSize)

                        finalSession =
                            CanvasSession(
                                sessionDir = sessionDir,
                                regionManager = regionManager,
                                originLastModified = originLastModified,
                                originSize = originSize,
                                metadata = metadata,
                                lockHandle = fileLock,
                            )
                    }

                    activeSessions[sessionName] = finalSession
                    return@withContext finalSession
                } catch (e: Exception) {
                    Logger.e("CanvasRepository", "Failed to open session", e, showToUser = true)
                    fileLock?.close() // Ensure lock is released on error
                    null
                }
            }
        }

    suspend fun releaseCanvasSession(session: CanvasSession) =
        withContext(Dispatchers.IO) {
            sessionLock.withLock {
                val lastClient = session.release()
                if (lastClient) {
                    val name = session.sessionDir.name
                    activeSessions.remove(name)
                    session.close() // Releases lock
                    Logger.i("CanvasRepository", "Session released and closed: $name")
                } else {
                    Logger.i("CanvasRepository", "Session released (retained by other clients)")
                }
            }
        }

    /**
     * Initiates a background save and then closes the session.
     * Use this when exiting the editor to allow the UI to close immediately while
     * the large file saves in the background. The FileLock remains held until save completes.
     */
    suspend fun saveAndCloseSession(
        path: String,
        session: CanvasSession,
    ) = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            val lastClient = session.release()
            if (lastClient) {
                val name = session.sessionDir.name
                activeSessions.remove(name) // Remove from active cache immediately so new opens fail fast (Lock held)

                Logger.i("CanvasRepository", "Launching background save and close for: $name")

                // Launch in Repository Scope to survive Activity death
                repositoryScope.launch {
                    try {
                        saveCanvasSession(path, session, commitToZip = true)
                    } catch (e: Exception) {
                        Logger.e("CanvasRepository", "Background save failed for $path", e)
                    } finally {
                        session.close() // Releases lock
                        Logger.i("CanvasRepository", "Background save complete, session closed: $name")
                    }
                }
            } else {
                Logger.i("CanvasRepository", "saveAndCloseSession: Session retained by other clients, performing standard save.")
                saveCanvasSession(path, session, commitToZip = true)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveCanvasSession(
        path: String,
        session: CanvasSession,
        commitToZip: Boolean = true,
    ): SaveResult =
        withContext(Dispatchers.IO) {
            if (!session.acquireForOperation()) {
                throw IllegalStateException("Cannot save: session is closed")
            }

            try {
                // Ensure background unzip is complete before saving to prevent data loss
                session.waitForInitialization()

                // Mutex ensures sequential saves for this session
                session.saveMutex.withLock {
                    if (!session.sessionDir.exists()) {
                        throw java.io.IOException("Session directory missing")
                    }

                    // 1. Flush RegionManager (writes to session dir)
                    session.regionManager.saveAll()

                    // 2. Generate Thumbnail
                    val thumbBase64 = ThumbnailGenerator.generateBase64(session.regionManager, session.metadata, context)
                    val metadataWithThumb =
                        if (thumbBase64 != null) {
                            session.metadata.copy(thumbnail = thumbBase64)
                        } else {
                            session.metadata
                        }
                    session.updateMetadata(metadataWithThumb)

                    // 3. Save Manifest
                    val manifestFile = File(session.sessionDir, "manifest.bin")
                    val metaBytes = ProtoBuf.encodeToByteArray(CanvasData.serializer(), metadataWithThumb)
                    manifestFile.writeBytes(metaBytes)

                    if (!commitToZip) {
                        Logger.d("CanvasRepository", "Session flushed (no-zip).")
                        return@withLock SaveResult(
                            savedPath = session.sessionDir.absolutePath,
                            newLastModified = session.originLastModified,
                            newSize = session.originSize,
                        )
                    }

                    // 4. Atomic Pack & Commit
                    var targetPath = path

                    // Conflict logic for Local files
                    // If we hold the lock, we should be the only writer.
                    // However, if the file was modified EXTERNALLY (while we held lock? unlikely on local fs,
                    // but possible if lock was broken or over network), we check.
                    val targetFile = File(path)
                    if (targetFile.exists() && session.originLastModified > 0 && !path.startsWith("content://")) {
                        // Note: If we hold an exclusive lock, lastModified shouldn't change unless WE changed it.
                        // But FileLock doesn't prevent non-cooperating processes on all OSs/Filesystems.
                        // Or user replaced file via adb.
                        if (targetFile.lastModified() != session.originLastModified) {
                            Logger.w("CanvasRepository", "External modification detected despite lock! Saving copy.")
                            val parent = targetFile.parentFile
                            val name = targetFile.nameWithoutExtension
                            val ext = targetFile.extension
                            targetPath = File(parent, "${name}_conflict_${System.currentTimeMillis()}.$ext").absolutePath
                        }
                    }

                    atomicStorage.pack(session.sessionDir, targetPath)

                    val savedFile = File(targetPath)
                    val newLastModified = savedFile.lastModified()
                    val newSize = savedFile.length()

                    // Update session origin to match what we just wrote
                    session.updateOrigin(newLastModified, newSize)

                    SaveResult(targetPath, newLastModified, newSize)
                }
            } catch (e: Exception) {
                Logger.e("CanvasRepository", "Failed to save session", e, showToUser = true)
                throw e
            } finally {
                session.releaseOperation()
            }
        }

    private enum class FileFormat {
        ZIP,
        EMPTY,
        UNKNOWN,
    }

    private fun checkFileFormat(input: InputStream): FileFormat {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(4)
        val signature = ByteArray(4)
        val read = buffered.read(signature)
        buffered.reset()
        if (read <= 0) return FileFormat.EMPTY
        if (read < 4) return FileFormat.UNKNOWN
        if (signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte()) return FileFormat.ZIP
        return FileFormat.UNKNOWN
    }

    private fun hashPath(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes =
            if (!path.startsWith("content://")) {
                try {
                    File(path).canonicalPath.toByteArray()
                } catch (e: Exception) {
                    path.toByteArray()
                }
            } else {
                path.toByteArray()
            }
        digest.update(bytes)
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
            context.contentResolver.openInputStream(android.net.Uri.parse(path))
        } else {
            val file = File(path)
            if (file.exists() && file.length() > 0) file.inputStream() else null
        }
}
