package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.export.PdfExporter
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class SyncManager(
    private val context: Context,
    private val canvasRepository: CanvasRepository,
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

    suspend fun syncProject(
        projectId: String,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        Logger.d("SyncManager", "Starting sync for project ID: $projectId")
        val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId)

        if (config == null) {
            Logger.w("SyncManager", "No sync config found for project $projectId")
            return@withContext
        }

        if (!config.isEnabled) {
            Logger.d("SyncManager", "Sync disabled for project $projectId")
            return@withContext
        }

        val storageConfig = SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
        if (storageConfig == null) {
            Logger.e("SyncManager", "Storage config not found for ID: ${config.remoteStorageId}", showToUser = true)
            return@withContext
        }

        val password = SyncPreferencesManager.getPassword(context, storageConfig.id) ?: ""

        val provider: RemoteStorageProvider =
            when (storageConfig.type) {
                RemoteStorageType.WEBDAV -> WebDavProvider(storageConfig, password)
                RemoteStorageType.GOOGLE_DRIVE -> GoogleDriveProvider(context, storageConfig)
            }

        try {
            progressCallback?.invoke(0, "Initializing sync...")

            // Check/Create Root Directory
            try {
                // Just check existence of root
                provider.listFiles(config.remotePath)
            } catch (e: java.io.FileNotFoundException) {
                Logger.w("SyncManager", "Remote directory not found, creating: ${config.remotePath}")
                progressCallback?.invoke(5, "Creating remote directory...")
                if (!provider.createDirectory(config.remotePath)) {
                    throw java.io.IOException("Failed to create remote directory: ${config.remotePath}")
                }
            } catch (e: Exception) {
                Logger.e("SyncManager", "Error checking remote root", e)
                // Continue, listFiles might fail later or succeed
            }

            progressCallback?.invoke(10, "Listing remote files recursively...")
            Logger.d("SyncManager", "Scanning remote files at ${config.remotePath}")
            val allRemoteFiles = scanRemoteFilesRecursively(provider, config.remotePath, "")

            progressCallback?.invoke(15, "Scanning local project...")
            val projects = PreferencesManager.getProjects(context)
            val projectConfig = projects.find { it.id == projectId } ?: return@withContext
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

            Logger.d("SyncManager", "Found ${localFiles.size} local files and ${allRemoteFiles.size} remote files")

            val totalSteps = localFiles.size + allRemoteFiles.size
            var currentStep = 0

            progressCallback?.invoke(20, "Synchronizing files...")

            // 1. Upload/Update local files to remote
            for (localFile in localFiles) {
                // Match by RELATIVE PATH
                val remoteEntry = allRemoteFiles.find { it.relativePath == localFile.relativePath }
                val remoteFile = remoteEntry?.file

                // Ensure we use forward slashes for remote paths regardless of local OS
                val cleanRelativePath = localFile.relativePath.replace("\\", "/")
                val remotePath = "${config.remotePath.trimEnd('/')}/$cleanRelativePath"

                if (remoteFile == null || localFile.lastModified > remoteFile.lastModified) {
                    Logger.d(
                        "SyncManager",
                        "Uploading ${localFile.name} (Local: ${localFile.lastModified}, Remote: ${remoteFile?.lastModified})",
                    )
                    progressCallback?.invoke((20 + (currentStep++ * 60 / totalSteps)), "Uploading ${localFile.name}...")
                    localFile.openInputStream()?.use { input ->
                        provider.uploadFile(remotePath, input)
                    }

                    // Also sync PDF if enabled
                    if (config.syncPdf) {
                        Logger.d("SyncManager", "Generating/Uploading PDF for ${localFile.name}")
                        syncPdf(localFile, config.remotePath, provider)
                    }
                }
            }

            // 2. Download remote files that don't exist locally or are newer
            for (remoteEntry in allRemoteFiles) {
                val remoteFile = remoteEntry.file
                if (remoteFile.isDirectory || !remoteFile.name.endsWith(".notate")) continue

                val localFile = localFiles.find { it.relativePath == remoteEntry.relativePath }
                if (localFile == null || remoteFile.lastModified > localFile.lastModified) {
                    Logger.d(
                        "SyncManager",
                        "Downloading ${remoteFile.name} (Remote: ${remoteFile.lastModified}, Local: ${localFile?.lastModified})",
                    )
                    progressCallback?.invoke((20 + (currentStep++ * 60 / totalSteps)), "Downloading ${remoteFile.name}...")

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
                }
            }

            // Update last sync time
            SyncPreferencesManager.updateProjectSyncConfig(context, config.copy(lastSyncTimestamp = System.currentTimeMillis()))
            progressCallback?.invoke(100, "Sync complete")
            Logger.d("SyncManager", "Sync finished successfully")
        } catch (e: Exception) {
            Logger.e("SyncManager", "Sync failed", e, showToUser = true)
            progressCallback?.invoke(0, "Sync failed: ${e.message}")
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
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
            val pdfRelativePath = cleanRelativePath.substringBeforeLast(". ") + ".pdf"
            val remotePdfPath = "${remoteDir.trimEnd('/')}/$pdfRelativePath"

            val out = ByteArrayOutputStream()
            PdfExporter.export(context, model, out, isVector = true, callback = null)

            val pdfInput = ByteArrayInputStream(out.toByteArray())
            provider.uploadFile(remotePdfPath, pdfInput)
        } catch (e: Exception) {
            Logger.e("SyncManager", "Failed to sync PDF for ${localFile.name}", e)
        } finally {
            // Always close the session to clean up the temporary directory
            session?.close()
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
