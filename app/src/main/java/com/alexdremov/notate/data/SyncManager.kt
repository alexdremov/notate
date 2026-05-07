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

        private fun normalizePath(path: String): String = path.replace("\\", "/")
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

        // 2. Load Metadata
        val syncMetadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
        val fileStates = syncMetadata.files.toMutableMap()

        // 3. Process Deletions
        updateProgress(10, "Processing pending deletions...")
        processPendingDeletions(projectId, config, provider, localFiles, fileStates)

        // 4. Scan Remote
        updateProgress(15, "Scanning remote storage...")
        val allRemoteFiles =
            scanRemoteFilesRecursively(provider, config.remotePath, "")
                .filterNot { remote ->
                    SyncPreferencesManager
                        .getPendingDeletions(context)
                        .any { it.projectId == projectId && normalizePath(it.relativePath) == normalizePath(remote.relativePath) }
                }

        Logger.d(TAG, "Found ${localFiles.size} local and ${allRemoteFiles.size} remote files")

        // 5. Sync Logic
        val totalSteps = localFiles.size + allRemoteFiles.size
        var currentStep = 0

        // 5a. Uploads
        for (local in localFiles) {
            if (SaveStatusManager.isSaving(local.path)) continue

            val relPath = normalizePath(local.relativePath)
            val state = fileStates[relPath]
            val remote = allRemoteFiles.find { normalizePath(it.relativePath) == relPath }

            if (state == null || local.lastModified > state.lastLocalModified || remote == null) {
                val progress = 20 + (currentStep * 60 / totalSteps)
                val stepSize = 60 / totalSteps
                updateProgress(progress, "Uploading ${local.name}...")

                uploadFileWithRetries(local, config, provider, fileStates) { p, m ->
                    val subProgress = progress + (p * stepSize / 100)
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
                val progress = 20 + (currentStep * 60 / totalSteps)
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

            val remotePath = "${config.remotePath.trimEnd('/')}/$relPath"
            try {
                provider.deleteFile(remotePath)
                if (config.syncPdf && relPath.endsWith(".notate")) {
                    val pdfPath = relPath.substringBeforeLast(".") + ".pdf"
                    provider.deleteFile("${config.remotePath.trimEnd('/')}/$pdfPath")
                }
                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                fileStates.remove(relPath)
            } catch (e: java.io.FileNotFoundException) {
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
        val remotePath = "${config.remotePath.trimEnd('/')}/$relPath"

        provider.createDirectory(remotePath.substringBeforeLast('/'))

        var success = false
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                local.openInputStream()?.use { input ->
                    success = provider.uploadFile(remotePath, input, local.size)
                }
                if (success) break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErr = e
                delay(1000L * attempt)
            }
        }

        if (!success) throw IOException("Failed to upload ${local.name} after retries", lastErr)

        if (config.syncPdf && relPath.endsWith(".notate")) {
            syncPdf(local, config.remotePath, provider, progressCallback)
        }

        // Refresh remote state to get exact remote timestamp
        try {
            val remoteItems = provider.listFiles(remotePath.substringBeforeLast('/'))
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
        try {
            provider.listFiles(path)
        } catch (e: java.io.FileNotFoundException) {
            if (!provider.createDirectory(path)) throw IOException("Failed to create remote root: $path")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error verifying remote root", e)
        }
    }

    private fun scanLocalFiles(projectId: String): List<LocalFile> {
        val project = PreferencesManager.getProjects(context).find { it.id == projectId } ?: return emptyList()
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
        val items = provider.listFiles(currentPath)
        for (item in items) {
            val itemRel = if (relative.isEmpty()) item.name else "$relative/${item.name}"
            if (item.isDirectory) {
                results.addAll(scanRemoteFilesRecursively(provider, "$currentPath/${item.name}", itemRel))
            } else {
                results.add(RemoteFileWithRelativePath(item, itemRel))
            }
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
            val model =
                InfiniteCanvasModel().apply {
                    initializeSession(session!!.regionManager)
                    loadFromCanvasData(session!!.metadata)
                }

            val pdfRelPath = normalizePath(local.relativePath).substringBeforeLast(".") + ".pdf"
            val remotePdfPath = "${remoteDir.trimEnd('/')}/$pdfRelPath"

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
            for (attempt in 1..3) {
                try {
                    if (provider.uploadFile(remotePdfPath, ByteArrayInputStream(bytes), bytes.size.toLong())) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (attempt == 3) Logger.w(TAG, "Failed to upload PDF for ${local.name}", e)
                    delay(1000L * attempt)
                }
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
                val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId) ?: return@withContext false
                if (!config.isEnabled) return@withContext false

                val storage =
                    SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId } ?: return@withContext false
                val password = SyncPreferencesManager.getPassword(context, storage.id) ?: ""
                val provider = providerFactory(context, storage, password)

                val rel = normalizePath(relativePath)
                val remotePath = "${config.remotePath.trimEnd('/')}/$rel"

                try {
                    if (provider.deleteFile(remotePath)) {
                        if (config.syncPdf && rel.endsWith(".notate")) {
                            provider.deleteFile("${config.remotePath.trimEnd('/')}/${rel.substringBeforeLast(".")}.pdf")
                        }
                        val metadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
                        val newFiles = metadata.files.toMutableMap().apply { remove(rel) }
                        SyncPreferencesManager.saveProjectSyncMetadata(context, projectId, metadata.copy(files = newFiles))
                        return@withContext true
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
                    if (filePath.contains(project.uri)) return@withContext project.id
                } else if (!filePath.startsWith("content://") && !project.uri.startsWith("content://")) {
                    try {
                        val fileCan = File(filePath).canonicalPath
                        val projCan = File(project.uri).canonicalPath
                        if (fileCan.startsWith(projCan)) return@withContext project.id
                    } catch (ignored: Exception) {
                    }
                }
            }
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
