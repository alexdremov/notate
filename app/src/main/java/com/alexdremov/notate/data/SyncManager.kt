package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.export.PdfExporter
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the synchronization between local project files and remote storage providers.
 * Handles uploads, downloads, and conflict resolution (preferring local changes).
 */
class SyncManager(
    private val context: Context,
    private val canvasRepository: CanvasRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val providerFactory: (Context, RemoteStorageConfig, String) -> RemoteStorageProvider = { ctx, config, pass ->
        when (config.type) {
            RemoteStorageType.WEBDAV -> WebDavProvider(config, pass)
            RemoteStorageType.GOOGLE_DRIVE -> GoogleDriveProvider(ctx, config)
        }
    },
) {
    interface LocalFile {
        val name: String
        val relativePath: String
        val lastModified: Long
        val size: Long

        fun openInputStream(): InputStream?

        val path: String
        val isDirectory: Boolean
    }

    private class JavaFileWrapper(
        val file: File,
        val root: File,
    ) : LocalFile {
        override val name: String get() = file.name
        override val relativePath: String get() = file.relativeTo(root).path
        override val lastModified: Long get() = file.lastModified()
        override val size: Long get() = file.length()

        override fun openInputStream() = if (file.exists()) file.inputStream() else null

        override val path: String get() = file.absolutePath
        override val isDirectory: Boolean get() = file.isDirectory
    }

    private class DocumentFileWrapper(
        val context: Context,
        val file: DocumentFile,
        override val relativePath: String,
    ) : LocalFile {
        override val name: String get() = file.name ?: ""
        override val lastModified: Long get() = file.lastModified()
        override val size: Long get() = file.length()

        override fun openInputStream() = context.contentResolver.openInputStream(file.uri)

        override val path: String get() = file.uri.toString()
        override val isDirectory: Boolean get() = file.isDirectory
    }

    private data class RemoteFileWithRelativePath(
        val file: RemoteFile,
        val relativePath: String,
    )

    companion object {
        private const val TAG = "SyncManager"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour safety bound

        @Volatile
        var isCanvasOpen: Boolean = false

        private val globalSyncSemaphore = Semaphore(1)
        private val activeSyncJobs = ConcurrentHashMap<Job, String>()
        private val interruptedProjects = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        private val _globalSyncProgress = MutableStateFlow<Map<String, Pair<Int, String>>>(emptyMap())
        val globalSyncProgress = _globalSyncProgress.asStateFlow()

        fun cancelAllSyncs() {
            Logger.i(TAG, "Cancelling all active sync jobs due to canvas activity")
            synchronized(activeSyncJobs) {
                activeSyncJobs.forEach { (job, projectId) ->
                    interruptedProjects.add(projectId)
                    job.cancel()
                }
                activeSyncJobs.clear()
            }
            _globalSyncProgress.value = emptyMap()
        }

        fun getInterruptedProjects(): Set<String> {
            val set = HashSet(interruptedProjects)
            interruptedProjects.clear()
            return set
        }

        private fun normalizePath(path: String): String =
            path.replace("\\", "/").trim().trimStart('/')

        private fun remotePathFor(base: String, rel: String): String {
            val cleanBase = base.replace("\\", "/").trim().trim('/')
            val cleanRel = normalizePath(rel)
            return if (cleanBase.isEmpty()) cleanRel else "$cleanBase/$cleanRel"
        }
    }

    suspend fun syncProject(
        projectId: String,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) = withContext(ioDispatcher) {
        if (isCanvasOpen) {
            Logger.w(TAG, "Skipping sync for $projectId: Canvas is currently open")
            return@withContext
        }

        val job = currentCoroutineContext()[Job]
        if (job != null) {
            activeSyncJobs[job] = projectId
            job.invokeOnCompletion { activeSyncJobs.remove(job) }
        }

        interruptedProjects.remove(projectId)
        Logger.d(TAG, "Sync for $projectId queued, waiting for permit")

        globalSyncSemaphore.withPermit {
            performSyncWithLocks(projectId, progressCallback)
        }
    }

    private suspend fun performSyncWithLocks(
        projectId: String,
        progressCallback: ((Int, String) -> Unit)?,
    ) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Notate:SyncWakeLock")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Notate:SyncWifiLock")

        val updateProgress: (Int, String) -> Unit = { p, m ->
            _globalSyncProgress.update { it + (projectId to (p to m)) }
            progressCallback?.invoke(p, m)
        }

        try {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            wifiLock?.acquire()

            performSyncInternal(projectId, updateProgress)
        } catch (e: CancellationException) {
            Logger.i(TAG, "Sync for $projectId was cancelled")
            updateProgress(0, "Sync cancelled")
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Sync failed for project $projectId", e, showToUser = true)
            updateProgress(0, "Sync failed: ${e.localizedMessage ?: e.message}")
        } finally {
            _globalSyncProgress.update { it - projectId }
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error releasing locks", e)
            }
        }
    }

    private suspend fun performSyncInternal(
        projectId: String,
        updateProgress: (Int, String) -> Unit,
    ) {
        updateProgress(0, "Initializing...")

        val config =
            SyncPreferencesManager.getProjectSyncConfig(context, projectId)
                ?: throw IllegalStateException("No sync configuration for project $projectId")

        if (!config.isEnabled) {
            Logger.d(TAG, "Sync disabled for $projectId")
            return
        }

        val storageConfig =
            SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
                ?: throw IllegalStateException("Remote storage config not found for ID: ${config.remoteStorageId}")

        val password = SyncPreferencesManager.getPassword(context, storageConfig.id) ?: ""
        val provider = providerFactory(context, storageConfig, password)

        ensureRemoteDirectoryExists(provider, config.remotePath)

        // 1. Scan Local
        updateProgress(5, "Scanning local files...")
        val localFiles = scanLocalFiles(projectId)
            ?: throw IllegalStateException("Project $projectId not found in local projects")

        // 2. Load Metadata
        val syncMetadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
        val fileStates = syncMetadata.files.toMutableMap()

        // 3. Process Deletions
        updateProgress(10, "Processing pending deletions...")
        processPendingDeletions(projectId, config, provider, localFiles, fileStates)

        // 4. Scan Remote (precompute pending deletions set to avoid repeated SharedPrefs reads)
        updateProgress(15, "Scanning remote storage...")
        val pendingDeletionPaths =
            SyncPreferencesManager.getPendingDeletions(context)
                .filter { it.projectId == projectId }
                .map { normalizePath(it.relativePath) }
                .toSet()

        val allRemoteFiles =
            scanRemoteFilesRecursively(provider, config.remotePath, "")
                .filterNot { normalizePath(it.relativePath) in pendingDeletionPaths }

        Logger.d(TAG, "Found ${localFiles.size} local and ${allRemoteFiles.size} remote files")

        // 5. Sync Logic
        val totalSteps = localFiles.size + allRemoteFiles.size
        if (totalSteps == 0) {
            // Nothing to sync — finalize immediately
            SyncPreferencesManager.saveProjectSyncMetadata(context, projectId, syncMetadata.copy(files = fileStates))
            SyncPreferencesManager.updateProjectSyncConfig(context, config.copy(lastSyncTimestamp = System.currentTimeMillis()))
            updateProgress(100, "Sync complete")
            return
        }

        var currentStep = 0

        // 5a. Uploads
        for (local in localFiles) {
            if (SaveStatusManager.isSaving(local.path)) {
                currentStep++
                continue
            }

            val relPath = normalizePath(local.relativePath)
            val state = fileStates[relPath]
            val remote = allRemoteFiles.find { normalizePath(it.relativePath) == relPath }

            if (state == null || local.lastModified > state.lastLocalModified || remote == null) {
                val remotePath = remotePathFor(config.remotePath, relPath)
                Logger.d(
                    TAG,
                    "Queuing upload: local '${local.path}' (relative: '$relPath', modified: ${local.lastModified}) -> remote '$remotePath'",
                )
                val progress = 20 + (currentStep * 60.0 / totalSteps).toInt()
                val stepSize = 60.0 / totalSteps
                updateProgress(progress, "Uploading ${local.name}...")

                uploadFileWithRetries(local, config, provider, fileStates) { p, m ->
                    val subProgress = (progress + (p * stepSize / 100)).toInt()
                    updateProgress(subProgress, "${local.name}: $m")
                }
            }
            currentStep++
        }

        // 5b. Downloads
        for (remoteWrap in allRemoteFiles) {
            val remote = remoteWrap.file
            if (remote.isDirectory || !remote.name.endsWith(".notate")) {
                currentStep++
                continue
            }

            val relPath = normalizePath(remoteWrap.relativePath)
            val local = localFiles.find { normalizePath(it.relativePath) == relPath }
            val state = fileStates[relPath]

            // Conflict: If local changed but not uploaded yet, skip download
            val localHasUnsyncedChanges = local != null && (state == null || local.lastModified > state.lastLocalModified)
            if (localHasUnsyncedChanges) {
                currentStep++
                continue
            }

            if (local == null || state == null || remote.lastModified > state.lastRemoteModified) {
                val progress = 20 + (currentStep * 60.0 / totalSteps).toInt()
                updateProgress(progress, "Downloading ${remote.name}...")
                downloadFile(remoteWrap, projectId, provider, fileStates)
            }
            currentStep++
        }

        // 6. Finalize
        SyncPreferencesManager.saveProjectSyncMetadata(context, projectId, syncMetadata.copy(files = fileStates))
        SyncPreferencesManager.updateProjectSyncConfig(context, config.copy(lastSyncTimestamp = System.currentTimeMillis()))
        updateProgress(100, "Sync complete")
    }

    private suspend fun processPendingDeletions(
        projectId: String,
        config: ProjectSyncConfig,
        provider: RemoteStorageProvider,
        localFiles: List<LocalFile>,
        fileStates: MutableMap<String, FileSyncState>,
    ) {
        val pending = SyncPreferencesManager.getPendingDeletions(context).filter { it.projectId == projectId }
        for (deletion in pending) {
            val relPath = normalizePath(deletion.relativePath)

            // Re-creation check: if file exists locally, it was deleted and re-created
            if (localFiles.any { normalizePath(it.relativePath) == relPath }) {
                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                continue
            }

            val remotePath = remotePathFor(config.remotePath, relPath)
            try {
                if (provider.deleteFile(remotePath)) {
                    if (config.syncPdf && relPath.endsWith(".notate")) {
                        val pdfPath = relPath.substringBeforeLast(".") + ".pdf"
                        try {
                            provider.deleteFile(remotePathFor(config.remotePath, pdfPath))
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Failed to delete remote PDF for $relPath", e)
                        }
                    }
                    SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                    fileStates.remove(relPath)
                } else {
                    Logger.w(TAG, "deleteFile returned false for $relPath, will retry next sync")
                }
            } catch (e: java.io.FileNotFoundException) {
                // Already gone — treat as success
                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                fileStates.remove(relPath)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Deletion failed for $relPath, will retry", e)
            }
        }
    }

    private suspend fun uploadFileWithRetries(
        local: LocalFile,
        config: ProjectSyncConfig,
        provider: RemoteStorageProvider,
        fileStates: MutableMap<String, FileSyncState>,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) {
        val relPath = normalizePath(local.relativePath)
        val remotePath = remotePathFor(config.remotePath, relPath)
        val parentRemotePath = if (remotePath.contains('/')) remotePath.substringBeforeLast('/') else ""

        Logger.d(
            TAG,
            "uploadFileWithRetries: local path = '${local.path}', size = ${local.size} B -> remote path = '$remotePath' (parent = '$parentRemotePath')",
        )

        if (parentRemotePath.isNotEmpty()) {
            try {
                if (!provider.createDirectory(parentRemotePath)) {
                    Logger.w(TAG, "createDirectory returned false for $parentRemotePath, attempting upload anyway")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Failed to ensure parent directory $parentRemotePath", e)
            }
        }

        var success = false
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                Logger.d(TAG, "Upload attempt $attempt/3: '${local.path}' -> '$remotePath'")
                local.openInputStream()?.use { input ->
                    success = provider.uploadFile(remotePath, input, local.size)
                }
                if (success) {
                    Logger.d(TAG, "Upload succeeded on attempt $attempt: '${local.path}' -> '$remotePath'")
                    break
                }
                Logger.w(TAG, "Upload attempt $attempt returned false for '${local.path}' -> '$remotePath'")
                delay(1000L * attempt)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErr = e
                Logger.w(TAG, "Upload attempt $attempt failed for '${local.path}' -> '$remotePath'", e)
                delay(1000L * attempt)
            }
        }

        if (!success) throw IOException("Failed to upload ${local.name} after retries", lastErr)

        if (config.syncPdf && relPath.endsWith(".notate")) {
            syncPdf(local, config.remotePath, provider, progressCallback)
        }

        // Refresh remote state to get exact remote timestamp
        try {
            val remoteItems = provider.listFiles(parentRemotePath)
            remoteItems.find { it.name == local.name }?.let {
                fileStates[relPath] =
                    FileSyncState(
                        lastLocalModified = local.lastModified,
                        lastRemoteModified = it.lastModified,
                        lastSyncTime = System.currentTimeMillis(),
                    )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Metadata update failed for $relPath", e)
        }
    }

    private suspend fun downloadFile(
        remoteWrap: RemoteFileWithRelativePath,
        projectId: String,
        provider: RemoteStorageProvider,
        fileStates: MutableMap<String, FileSyncState>,
    ) {
        val relPath = normalizePath(remoteWrap.relativePath)
        val projects = PreferencesManager.getProjects(context)
        val project = projects.find { it.id == projectId } ?: return

        provider.downloadFile(remoteWrap.file.path)?.use { input ->
            if (project.uri.startsWith("content://")) {
                downloadToSaf(input, project.uri, remoteWrap)
            } else {
                val dest = File(project.uri, remoteWrap.relativePath)
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
                dest.setLastModified(remoteWrap.file.lastModified)
            }

            fileStates[relPath] =
                FileSyncState(
                    lastLocalModified = remoteWrap.file.lastModified, // Assumes perfect set
                    lastRemoteModified = remoteWrap.file.lastModified,
                    lastSyncTime = System.currentTimeMillis(),
                )
        }
    }

    private fun downloadToSaf(
        input: InputStream,
        rootUri: String,
        remote: RemoteFileWithRelativePath,
    ) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return
        val parts = remote.relativePath.split('/').filter { it.isNotEmpty() }
        var current: DocumentFile? = dir

        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            current = current?.findFile(part) ?: current?.createDirectory(part)
        }

        val fileName = parts.last()
        val file = current?.findFile(fileName) ?: current?.createFile("application/octet-stream", fileName)
        file?.let {
            context.contentResolver.openOutputStream(it.uri)?.use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun ensureRemoteDirectoryExists(
        provider: RemoteStorageProvider,
        path: String,
    ) {
        val cleanPath = path.replace("\\", "/").trim().trim('/')
        if (cleanPath.isEmpty()) return
        try {
            provider.listFiles(cleanPath)
        } catch (e: java.io.FileNotFoundException) {
            if (!provider.createDirectory(cleanPath)) throw IOException("Failed to create remote root: $cleanPath")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error verifying remote root: $cleanPath", e)
        }
    }

    private fun scanLocalFiles(projectId: String): List<LocalFile>? {
        val project = PreferencesManager.getProjects(context).find { it.id == projectId } ?: return null
        val result = mutableListOf<LocalFile>()
        if (project.uri.startsWith("content://")) {
            DocumentFile.fromTreeUri(context, Uri.parse(project.uri))?.let {
                scanDocumentFilesRecursively(context, it, "", result)
            }
        } else {
            val root = File(project.uri)
            if (root.exists()) scanJavaFilesRecursively(root, root, result)
        }
        return result
    }

    private suspend fun scanRemoteFilesRecursively(
        provider: RemoteStorageProvider,
        currentPath: String,
        relative: String,
    ): List<RemoteFileWithRelativePath> {
        val results = mutableListOf<RemoteFileWithRelativePath>()
        try {
            val items = provider.listFiles(currentPath)
            for (item in items) {
                val itemRel = if (relative.isEmpty()) item.name else "$relative/${item.name}"
                if (item.isDirectory) {
                    val nextRemotePath = if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}"
                    results.addAll(scanRemoteFilesRecursively(provider, nextRemotePath, itemRel))
                } else {
                    results.add(RemoteFileWithRelativePath(item, itemRel))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error scanning remote directory: $currentPath", e)
            throw e
        }
        return results
    }

    private suspend fun syncPdf(
        local: LocalFile,
        remoteDir: String,
        provider: RemoteStorageProvider,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) {
        var session: CanvasSession? = null
        try {
            session = canvasRepository.openCanvasSession(local.path) ?: return
            val openSession = session
            val model =
                InfiniteCanvasModel().apply {
                    initializeSession(openSession.regionManager)
                    loadFromCanvasData(openSession.metadata)
                }

            val pdfRelPath = normalizePath(local.relativePath).substringBeforeLast(".") + ".pdf"
            val remotePdfPath = remotePathFor(remoteDir, pdfRelPath)
            val parentRemotePath = if (remotePdfPath.contains('/')) remotePdfPath.substringBeforeLast('/') else ""

            Logger.d(
                TAG,
                "syncPdf: local canvas '${local.path}' -> remote PDF '$remotePdfPath' (parent: '$parentRemotePath')",
            )

            if (parentRemotePath.isNotEmpty()) {
                try {
                    provider.createDirectory(parentRemotePath)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Failed to ensure parent directory for PDF $parentRemotePath", e)
                }
            }

            val pdfCallback =
                object : PdfExporter.ProgressCallback {
                    override fun onProgress(
                        progress: Int,
                        message: String,
                    ) {
                        progressCallback?.invoke(progress, message)
                    }
                }

            val out = ByteArrayOutputStream()
            PdfExporter.export(
                context,
                model,
                out,
                isVector = PreferencesManager.getSyncPdfType(context) == "VECTOR",
                callback = pdfCallback,
                bitmapScale = PreferencesManager.getPdfExportScale(context),
            )

            val bytes = out.toByteArray()
            Logger.d(TAG, "PDF generated for '${local.path}': ${bytes.size} B. Starting upload to '$remotePdfPath'")
            var uploaded = false
            for (attempt in 1..3) {
                try {
                    Logger.d(TAG, "PDF upload attempt $attempt/3: '${local.path}' -> '$remotePdfPath'")
                    if (provider.uploadFile(remotePdfPath, ByteArrayInputStream(bytes), bytes.size.toLong())) {
                        uploaded = true
                        Logger.d(TAG, "PDF upload succeeded on attempt $attempt: '${local.path}' -> '$remotePdfPath'")
                        break
                    }
                    Logger.w(TAG, "PDF upload attempt $attempt returned false for '${local.path}' -> '$remotePdfPath'")
                    delay(1000L * attempt)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "PDF upload attempt $attempt failed for '${local.path}' -> '$remotePdfPath'", e)
                    delay(1000L * attempt)
                }
            }
            if (!uploaded) {
                Logger.e(TAG, "Failed to upload PDF for ${local.name} after 3 attempts")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "PDF sync failed for ${local.name}", e)
        } finally {
            session?.let { canvasRepository.releaseCanvasSession(it) }
        }
    }

    suspend fun deleteFromRemote(
        projectId: String,
        relativePath: String,
    ): Boolean =
        withContext(ioDispatcher) {
            globalSyncSemaphore.withPermit {
                val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId)
                if (config == null || !config.isEnabled) return@withPermit false

                val storage =
                    SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
                        ?: return@withPermit false
                val password = SyncPreferencesManager.getPassword(context, storage.id) ?: ""
                val provider = providerFactory(context, storage, password)

                val rel = normalizePath(relativePath)
                val remotePath = remotePathFor(config.remotePath, rel)

                try {
                    if (provider.deleteFile(remotePath)) {
                        if (config.syncPdf && rel.endsWith(".notate")) {
                            try {
                                provider.deleteFile(remotePathFor(config.remotePath, rel.substringBeforeLast(".") + ".pdf"))
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Logger.w(TAG, "Failed to delete remote PDF for $rel", e)
                            }
                        }
                        val metadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
                        val newFiles = metadata.files.toMutableMap().apply { remove(rel) }
                        SyncPreferencesManager.saveProjectSyncMetadata(context, projectId, metadata.copy(files = newFiles))
                        return@withPermit true
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Remote delete failed for $rel", e)
                }
                false
            }
        }

    suspend fun findProjectForFile(filePath: String): String? =
        withContext(ioDispatcher) {
            val projects = PreferencesManager.getProjects(context)
            for (project in projects) {
                if (filePath.startsWith("content://") && project.uri.startsWith("content://")) {
                    if (filePath.contains(project.uri)) {
                        Logger.d(TAG, "Match found via SAF prefix for $filePath")
                        return@withContext project.id
                    }
                } else if (!filePath.startsWith("content://") && !project.uri.startsWith("content://")) {
                    try {
                        val fileCan = File(filePath).canonicalPath
                        val projCan = File(project.uri).canonicalPath
                        if (fileCan.startsWith(projCan)) {
                            Logger.d(TAG, "Match found via File path for $filePath")
                            return@withContext project.id
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }
            Logger.w(TAG, "No matching project found for $filePath")
            null
        }

    private fun scanDocumentFilesRecursively(
        ctx: Context,
        dir: DocumentFile,
        rel: String,
        result: MutableList<LocalFile>,
    ) {
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            val itemRel = if (rel.isEmpty()) name else "$rel/$name"
            if (file.isDirectory) {
                scanDocumentFilesRecursively(ctx, file, itemRel, result)
            } else if (name.endsWith(".notate")) {
                result.add(DocumentFileWrapper(ctx, file, itemRel))
            }
        }
    }

    private fun scanJavaFilesRecursively(
        file: File,
        root: File,
        result: MutableList<LocalFile>,
    ) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { scanJavaFilesRecursively(it, root, result) }
        } else if (file.extension == "notate") {
            result.add(JavaFileWrapper(file, root))
        }
    }
}
