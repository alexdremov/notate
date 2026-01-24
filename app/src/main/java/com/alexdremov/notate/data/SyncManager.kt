package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.export.PdfExporter
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

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

        override fun openInputStream() = context.contentResolver.openInputStream(file.uri)

        override val path: String get() = file.uri.toString()
        override val isDirectory: Boolean get() = file.isDirectory
    }

    private data class RemoteFileWithRelativePath(
        val file: RemoteFile,
        val relativePath: String,
    )

    companion object {
        @Volatile
        var isCanvasOpen: Boolean = false

        // Global Semaphore to limit concurrent syncs to 1 (prevents OOM)
        private val globalSyncSemaphore = Semaphore(1)

        // Track active jobs: Job -> ProjectID
        private val activeSyncJobs = java.util.concurrent.ConcurrentHashMap<Job, String>()

        // Track projects that were syncing when cancelled
        private val interruptedProjects = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        // Global progress flow: ProjectID -> (Progress, Message)
        private val _globalSyncProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, String>>>(emptyMap())
        val globalSyncProgress = _globalSyncProgress.asStateFlow()

        fun cancelAllSyncs() {
            Logger.i("SyncManager", "Cancelling all active sync jobs due to canvas activity")
            // No need to synchronize activeSyncJobs for iteration if it's ConcurrentHashMap,
            // but for atomic clear+copy logic, synchronized block is safer/simpler.
            synchronized(activeSyncJobs) {
                activeSyncJobs.forEach { (job, projectId) ->
                    interruptedProjects.add(projectId)
                    job.cancel()
                }
                activeSyncJobs.clear()
            }
            // Clear progress for cancelled jobs
            _globalSyncProgress.value = emptyMap()
        }

        fun getInterruptedProjects(): Set<String> {
            val set = HashSet(interruptedProjects)
            interruptedProjects.clear()
            return set
        }
    }

    suspend fun syncProject(
        projectId: String,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) = withContext(ioDispatcher) {
        if (isCanvasOpen) {
            Logger.w("SyncManager", "Skipping sync because canvas is open")
            return@withContext
        }

        val job = currentCoroutineContext()[Job]
        if (job != null) {
            activeSyncJobs[job] = projectId
            job.invokeOnCompletion {
                activeSyncJobs.remove(job)
            }
        }

        // Remove from interrupted if we are restarting it
        interruptedProjects.remove(projectId)

        Logger.d("SyncManager", "Queued sync for project ID: $projectId (Waiting for semaphore)")

        globalSyncSemaphore.withPermit {
            Logger.d("SyncManager", "Starting sync execution for project ID: $projectId")

            // Helper to update both global and local callback
            val updateProgress: (Int, String) -> Unit = { p, m ->
                _globalSyncProgress.update { it + (projectId to (p to m)) }
                progressCallback?.invoke(p, m)
            }

            try {
                updateProgress(0, "Initializing sync...")

                val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId)

                if (config == null) {
                    Logger.w("SyncManager", "No sync config found for project $projectId")
                    return@withPermit
                }

                if (!config.isEnabled) {
                    Logger.d("SyncManager", "Sync disabled for project $projectId")
                    return@withPermit
                }

                val storageConfig = SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
                if (storageConfig == null) {
                    Logger.e("SyncManager", "Storage config not found for ID: ${config.remoteStorageId}", showToUser = true)
                    return@withPermit
                }

                val password = SyncPreferencesManager.getPassword(context, storageConfig.id) ?: ""

                val provider: RemoteStorageProvider = providerFactory(context, storageConfig, password)

                updateProgress(0, "Initializing sync...")

                // Check/Create Root Directory
                try {
                    // Just check existence of root
                    provider.listFiles(config.remotePath)
                } catch (e: java.io.FileNotFoundException) {
                    Logger.w("SyncManager", "Remote directory not found, creating: ${config.remotePath}")
                    updateProgress(5, "Creating remote directory...")
                    if (!provider.createDirectory(config.remotePath)) {
                        throw java.io.IOException("Failed to create remote directory: ${config.remotePath}")
                    }
                } catch (e: Exception) {
                    Logger.e("SyncManager", "Error checking remote root", e)
                    // Continue, listFiles might fail later or succeed
                }

                // Load Sync Metadata
                var syncMetadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
                val fileStates = syncMetadata.files.toMutableMap()

                // 1. Scan Local Project (Moved up to validate pending deletions)
                updateProgress(10, "Scanning local project...")
                val projects = PreferencesManager.getProjects(context)
                val projectConfig = projects.find { it.id == projectId } ?: return@withPermit
                Logger.d("SyncManager", "Syncing local project at ${projectConfig.uri}")

                val localFiles = mutableListOf<LocalFile>()

                if (projectConfig.uri.startsWith("content://")) {
                    DocumentFile.fromTreeUri(context, Uri.parse(projectConfig.uri))?.let { rootDir ->
                        scanDocumentFilesRecursively(context, rootDir, "", localFiles)
                    }
                } else {
                    val rootDir = File(projectConfig.uri)
                    if (rootDir.exists()) {
                        scanJavaFilesRecursively(rootDir, rootDir, localFiles)
                    }
                }

                // --- Pending Deletions Processing ---
                val pendingDeletions = SyncPreferencesManager.getPendingDeletions(context).filter { it.projectId == projectId }
                if (pendingDeletions.isNotEmpty()) {
                    Logger.d("SyncManager", "Processing ${pendingDeletions.size} pending deletions")
                    updateProgress(12, "Processing deletions...")

                    for (deletion in pendingDeletions) {
                        try {
                            val cleanRelativePath = deletion.relativePath.replace("\\", "/")

                            // CRITICAL FIX: If local file exists, the user re-created it.
                            // Do NOT delete from remote. Instead, clear the pending deletion and let Upload logic handle it.
                            val localExists = localFiles.any { it.relativePath.replace("\\", "/") == cleanRelativePath }

                            if (localExists) {
                                Logger.i(
                                    "SyncManager",
                                    "Pending deletion for $cleanRelativePath skipped because local file exists (Superseded).",
                                )
                                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                                continue
                            }

                            val remotePath = "${config.remotePath.trimEnd('/')}/$cleanRelativePath"

                            Logger.d("SyncManager", "Deleting pending remote file: $remotePath")

                            try {
                                provider.deleteFile(remotePath)
                                if (config.syncPdf && deletion.relativePath.endsWith(".notate")) {
                                    val pdfRelativePath = cleanRelativePath.substringBeforeLast(".") + ".pdf"
                                    val remotePdfPath = "${config.remotePath.trimEnd('/')}/$pdfRelativePath"
                                    try {
                                        provider.deleteFile(remotePdfPath)
                                    } catch (ignored: Exception) {
                                    }
                                }

                                // Success! Remove from pending and metadata
                                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                                fileStates.remove(cleanRelativePath)
                            } catch (e: java.io.FileNotFoundException) {
                                // Already gone
                                SyncPreferencesManager.removePendingDeletion(context, projectId, deletion.relativePath)
                                fileStates.remove(cleanRelativePath)
                            } catch (e: Exception) {
                                Logger.w("SyncManager", "Failed to process pending deletion for ${deletion.relativePath}", e)
                                // Keep in pending list to retry later, AND prevent download below
                            }
                        } catch (e: Exception) {
                            Logger.e("SyncManager", "Error in pending deletion loop", e)
                        }
                    }
                }

                // Reload pending deletions in case some failed - we must filter them out from download list
                val remainingPendingDeletions =
                    SyncPreferencesManager
                        .getPendingDeletions(context)
                        .filter { it.projectId == projectId }
                        .map { it.relativePath.replace("\\", "/") }
                        .toSet()

                updateProgress(15, "Listing remote files recursively...")
                Logger.d("SyncManager", "Scanning remote files at ${config.remotePath}")
                val allRemoteFilesRaw = scanRemoteFilesRecursively(provider, config.remotePath, "")

                // Filter out files that are pending deletion
                val allRemoteFiles =
                    allRemoteFilesRaw.filter {
                        val cleanPath = it.relativePath.replace("\\", "/")
                        !remainingPendingDeletions.contains(cleanPath)
                    }

                Logger.d("SyncManager", "Found ${localFiles.size} local files and ${allRemoteFiles.size} remote files")

                val totalSteps = localFiles.size + allRemoteFiles.size
                var currentStep = 0

                updateProgress(20, "Synchronizing files...")

                // 1. Upload/Update local files to remote
                for (localFile in localFiles) {
                    if (SaveStatusManager.isSaving(localFile.path)) {
                        Logger.d("SyncManager", "Skipping ${localFile.name} because it is currently saving")
                        continue
                    }

                    val cleanRelativePath = localFile.relativePath.replace("\\", "/")
                    val fileState = fileStates[cleanRelativePath]

                    // Logic:
                    // - If no state (new file), upload.
                    // - If local modified > stored last local modified, upload.
                    // - Ignore remote timestamp here; we handle conflicts by preferring local if it changed

                    val shouldUpload = fileState == null || localFile.lastModified > fileState.lastLocalModified

                    if (shouldUpload) {
                        val remoteEntry = allRemoteFiles.find { it.relativePath.replace("\\", "/") == cleanRelativePath }
                        val remoteFile = remoteEntry?.file

                        Logger.d(
                            "SyncManager",
                            "Uploading ${localFile.name} (Local: ${localFile.lastModified}, LastSyncedLocal: ${fileState?.lastLocalModified})",
                        )
                        updateProgress((20 + (currentStep++ * 60 / totalSteps)), "Uploading ${localFile.name}...")

                        val remotePath = "${config.remotePath.trimEnd('/')}/$cleanRelativePath"
                        localFile.openInputStream()?.use { input ->
                            provider.uploadFile(remotePath, input)
                        }

                        // Also sync PDF if enabled
                        if (config.syncPdf) {
                            Logger.d("SyncManager", "Generating/Uploading PDF for ${localFile.name}")
                            val baseProgress = 20 + ((currentStep - 1) * 60 / totalSteps)
                            val stepSize = 60.0 / totalSteps
                            syncPdf(localFile, config.remotePath, provider) { p, m ->
                                val pdfProgress = (baseProgress + (p / 100.0) * stepSize).toInt()
                                updateProgress(pdfProgress, "PDF ${localFile.name}: $m")
                            }
                        }

                        // Update State
                        try {
                            val updatedRemoteItems = provider.listFiles(remotePath.substringBeforeLast('/'))
                            val updatedRemoteFile = updatedRemoteItems.find { it.name == localFile.name }

                            if (updatedRemoteFile != null) {
                                fileStates[cleanRelativePath] =
                                    FileSyncState(
                                        lastLocalModified = localFile.lastModified,
                                        lastRemoteModified = updatedRemoteFile.lastModified,
                                        lastSyncTime = System.currentTimeMillis(),
                                    )
                            } else {
                                // Weird, we just uploaded it.
                                Logger.w("SyncManager", "Uploaded file not found immediately after upload: $cleanRelativePath")
                            }
                        } catch (e: Exception) {
                            Logger.w("SyncManager", "Failed to update metadata after upload for $cleanRelativePath", e)
                        }
                    }
                }

                // 2. Download remote files that don't exist locally or are newer
                for (remoteEntry in allRemoteFiles) {
                    val remoteFile = remoteEntry.file
                    if (remoteFile.isDirectory || !remoteFile.name.endsWith(".notate")) continue
                    val cleanRelativePath = remoteEntry.relativePath.replace("\\", "/")

                    val localFile = localFiles.find { it.relativePath.replace("\\", "/") == cleanRelativePath }
                    val fileState = fileStates[cleanRelativePath]

                    // Logic:
                    // - If no local file, download (unless we deleted it pending? No, we filtered those).
                    // - If remote timestamp > stored last remote modified, it's a remote change -> Download.
                    // - BUT if local also changed (conflict), we currently prefer local (handled in step 1).
                    //   So here, we only download if we DID NOT just upload.

                    // If we just uploaded, fileState.lastLocalModified should equal localFile.lastModified
                    val localChanged = localFile != null && (fileState == null || localFile.lastModified > fileState.lastLocalModified)

                    if (localChanged) {
                        // We have local changes that haven't been synced (or we just synced them).
                        // In either case, don't overwrite with remote unless we implement conflict resolution.
                        continue
                    }

                    val isNewRemote = fileState == null || remoteFile.lastModified > fileState.lastRemoteModified

                    if (localFile == null || isNewRemote) {
                        Logger.d(
                            "SyncManager",
                            "Downloading ${remoteFile.name} (Remote: ${remoteFile.lastModified}, LastSyncedRemote: ${fileState?.lastRemoteModified})",
                        )
                        updateProgress((20 + (currentStep++ * 60 / totalSteps)), "Downloading ${remoteFile.name}...")

                        provider.downloadFile(remoteFile.path)?.use { input ->
                            if (projectConfig.uri.startsWith("content://")) {
                                val dir = DocumentFile.fromTreeUri(context, Uri.parse(projectConfig.uri))
                                // Handle nested directories for SAF
                                val relativeParts = remoteEntry.relativePath.split('/').dropLast(1)
                                var currentDir = dir
                                for (part in relativeParts) {
                                    currentDir = currentDir?.findFile(part) ?: currentDir?.createDirectory(part)
                                }

                                val existing = currentDir?.findFile(remoteFile.name)
                                val file = existing ?: currentDir?.createFile("application/octet-stream", remoteFile.name)
                                file?.let {
                                    context.contentResolver.openOutputStream(it.uri)?.use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            } else {
                                val file = File(projectConfig.uri, remoteEntry.relativePath)
                                file.parentFile?.mkdirs() // Ensure parent exists
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                                file.setLastModified(remoteFile.lastModified)
                            }
                        }

                        // Update State after download
                        var newLocalTimestamp = remoteFile.lastModified
                        if (projectConfig.uri.startsWith("content://")) {
                            // Re-query SAF to get actual timestamp
                        } else {
                            // We called file.setLastModified(remoteFile.lastModified)
                            newLocalTimestamp = remoteFile.lastModified
                        }

                        fileStates[cleanRelativePath] =
                            FileSyncState(
                                lastLocalModified = newLocalTimestamp,
                                lastRemoteModified = remoteFile.lastModified,
                                lastSyncTime = System.currentTimeMillis(),
                            )
                    }
                }

                // Save updated metadata
                SyncPreferencesManager.saveProjectSyncMetadata(
                    context,
                    projectId,
                    syncMetadata.copy(files = fileStates),
                )

                // Update last sync time
                SyncPreferencesManager.updateProjectSyncConfig(context, config.copy(lastSyncTimestamp = System.currentTimeMillis()))
                updateProgress(100, "Sync complete")
                Logger.d("SyncManager", "Sync finished successfully")
            } catch (e: CancellationException) {
                Logger.d("SyncManager", "Sync cancelled")
                updateProgress(0, "Sync cancelled: ${e.message}")
            } catch (e: Exception) {
                Logger.e("SyncManager", "Sync failed", e, showToUser = true)
                updateProgress(0, "Sync failed: ${e.message}")
            } finally {
                _globalSyncProgress.update { it - projectId }
            }
        }
    }

    private suspend fun scanRemoteFilesRecursively(
        provider: RemoteStorageProvider,
        currentRemotePath: String,
        currentRelativePath: String,
    ): List<RemoteFileWithRelativePath> {
        val results = mutableListOf<RemoteFileWithRelativePath>()
        try {
            val items = provider.listFiles(currentRemotePath)
            for (item in items) {
                // Ensure proper path separation
                val itemRelativePath = if (currentRelativePath.isEmpty()) item.name else "$currentRelativePath/${item.name}"

                if (item.isDirectory) {
                    val childRemotePath = "$currentRemotePath/${item.name}"
                    results.addAll(scanRemoteFilesRecursively(provider, childRemotePath, itemRelativePath))
                } else {
                    results.add(RemoteFileWithRelativePath(item, itemRelativePath))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            Logger.w("SyncManager", "Error scanning $currentRemotePath", e)
            throw e
        }
        return results
    }

    suspend fun findProjectForFile(filePath: String): String? =
        withContext(ioDispatcher) {
            Logger.d("SyncManager", "Searching project for file: $filePath")
            val projects = PreferencesManager.getProjects(context)

            // Normalize file path if it's content://
            val targetUri = if (filePath.startsWith("content://")) Uri.parse(filePath) else Uri.fromFile(File(filePath))

            for (project in projects) {
                Logger.d("SyncManager", "Checking against project: ${project.name} (${project.uri})")

                if (filePath.startsWith("content://") && project.uri.startsWith("content://")) {
                    // For SAF, simple string prefix check is weak but often sufficient for tree URIs
                    // A better check would be seeing if the file URI contains the project Tree URI's ID
                    if (filePath.contains(project.uri) || filePath.startsWith(project.uri)) {
                        Logger.d("SyncManager", "Match found via SAF prefix")
                        return@withContext project.id
                    }
                } else if (!filePath.startsWith("content://") && !project.uri.startsWith("content://")) {
                    // Local File
                    try {
                        val fileCanonical = File(filePath).canonicalPath
                        val projectCanonical = File(project.uri).canonicalPath
                        if (fileCanonical.startsWith(projectCanonical)) {
                            Logger.d("SyncManager", "Match found via File path")
                            return@withContext project.id
                        }
                    } catch (e: Exception) {
                        Logger.w("SyncManager", "Path comparison error", e)
                    }
                }
            }
            Logger.w("SyncManager", "No matching project found for $filePath")
            return@withContext null
        }

    suspend fun deleteFromRemote(
        projectId: String,
        relativePath: String,
    ): Boolean =
        withContext(ioDispatcher) {
            val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId)
            if (config == null || !config.isEnabled) return@withContext false

            val storageConfig = SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
            if (storageConfig == null) return@withContext false

            val password = SyncPreferencesManager.getPassword(context, storageConfig.id) ?: ""

            val provider: RemoteStorageProvider =
                when (storageConfig.type) {
                    RemoteStorageType.WEBDAV -> WebDavProvider(storageConfig, password)
                    RemoteStorageType.GOOGLE_DRIVE -> GoogleDriveProvider(context, storageConfig)
                }

            val cleanRelativePath = relativePath.replace("\\", "/")
            val remotePath = "${config.remotePath.trimEnd('/')}/$cleanRelativePath"

            try {
                Logger.d("SyncManager", "Deleting remote file: $remotePath")
                var success = provider.deleteFile(remotePath)

                if (config.syncPdf && relativePath.endsWith(".notate")) {
                    val pdfRelativePath = cleanRelativePath.substringBeforeLast(".") + ".pdf"
                    val remotePdfPath = "${config.remotePath.trimEnd('/')}/$pdfRelativePath"
                    Logger.d("SyncManager", "Deleting remote PDF: $remotePdfPath")
                    // We don't fail the whole operation if PDF delete fails, but we try
                    try {
                        provider.deleteFile(remotePdfPath)
                    } catch (e: Exception) {
                        Logger.w("SyncManager", "Failed to delete remote PDF", e)
                    }
                }

                // Also remove from metadata
                if (success) {
                    val metadata = SyncPreferencesManager.getProjectSyncMetadata(context, projectId)
                    val newFiles = metadata.files.toMutableMap()
                    newFiles.remove(cleanRelativePath)
                    SyncPreferencesManager.saveProjectSyncMetadata(context, projectId, metadata.copy(files = newFiles))
                }

                return@withContext success
            } catch (e: Exception) {
                Logger.e("SyncManager", "Failed to delete remote file", e)
                return@withContext false
            }
        }

    private suspend fun syncPdf(
        localFile: LocalFile,
        remoteDir: String,
        provider: RemoteStorageProvider,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) {
        var session: CanvasSession? = null
        try {
            // Open a session for reading the canvas
            session = canvasRepository.openCanvasSession(localFile.path) ?: return

            // Create a model and initialize it with the session's region manager
            val model = InfiniteCanvasModel()
            model.initializeSession(session.regionManager)
            model.loadFromCanvasData(session.metadata)

            val cleanRelativePath = localFile.relativePath.replace("\\", "/")
            // Fixed: removed extra space from substringBeforeLast
            val pdfRelativePath = cleanRelativePath.substringBeforeLast(".") + ".pdf"
            val remotePdfPath = "${remoteDir.trimEnd('/')}/$pdfRelativePath"

            val pdfCallback =
                if (progressCallback != null) {
                    object : PdfExporter.ProgressCallback {
                        override fun onProgress(
                            progress: Int,
                            message: String,
                        ) {
                            progressCallback(progress, message)
                        }
                    }
                } else {
                    null
                }

            val syncType = PreferencesManager.getSyncPdfType(context)
            val isVector = syncType == "VECTOR"
            val scale = PreferencesManager.getPdfExportScale(context)

            val out = ByteArrayOutputStream()
            PdfExporter.export(
                context,
                model,
                out,
                isVector = isVector,
                callback = pdfCallback,
                bitmapScale = scale,
            )

            val pdfInput = ByteArrayInputStream(out.toByteArray())
            provider.uploadFile(remotePdfPath, pdfInput)
        } catch (e: Exception) {
            Logger.e("SyncManager", "Failed to sync PDF for ${localFile.name}", e)
        } finally {
            // Always release the session properly
            if (session != null) {
                canvasRepository.releaseCanvasSession(session)
            }
        }
    }

    private fun scanDocumentFilesRecursively(
        context: Context,
        dir: DocumentFile,
        relativePath: String,
        result: MutableList<LocalFile>,
    ) {
        dir.listFiles().forEach { file ->
            val fileName = file.name ?: return@forEach
            if (file.isDirectory) {
                val newRelativePath = if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"
                scanDocumentFilesRecursively(context, file, newRelativePath, result)
            } else if (fileName.endsWith(".notate")) {
                val fileRelativePath = if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"
                result.add(DocumentFileWrapper(context, file, fileRelativePath))
            }
        }
    }

    private fun scanJavaFilesRecursively(
        file: File,
        root: File,
        result: MutableList<LocalFile>,
    ) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                scanJavaFilesRecursively(child, root, result)
            }
        } else if (file.extension == "notate") {
            result.add(JavaFileWrapper(file, root))
        }
    }
}
