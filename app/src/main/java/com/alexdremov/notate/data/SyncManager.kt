package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.export.PdfExporter
import com.alexdremov.notate.model.InfiniteCanvasModel
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

    suspend fun syncProject(
        projectId: String,
        progressCallback: ((Int, String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        Log.d("SyncManager", "Starting sync for project ID: $projectId")
        val config = SyncPreferencesManager.getProjectSyncConfig(context, projectId)

        if (config == null) {
            Log.w("SyncManager", "No sync config found for project $projectId")
            return@withContext
        }

        if (!config.isEnabled) {
            Log.d("SyncManager", "Sync disabled for project $projectId")
            return@withContext
        }

        val storageConfig = SyncPreferencesManager.getRemoteStorages(context).find { it.id == config.remoteStorageId }
        if (storageConfig == null) {
            Log.e("SyncManager", "Storage config not found for ID: ${config.remoteStorageId}")
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
            val remoteFiles =
                try {
                    progressCallback?.invoke(5, "Listing remote files...")
                    Log.d("SyncManager", "Listing remote files at ${config.remotePath}")
                    provider.listFiles(config.remotePath)
                } catch (e: java.io.FileNotFoundException) {
                    Log.w("SyncManager", "Remote directory not found, creating: ${config.remotePath}")
                    progressCallback?.invoke(10, "Creating remote directory...")
                    if (provider.createDirectory(config.remotePath)) {
                        emptyList()
                    } else {
                        throw java.io.IOException("Failed to create remote directory: ${config.remotePath}")
                    }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error listing files", e)
                    throw e
                }

            progressCallback?.invoke(15, "Scanning local project...")
            val projects = PreferencesManager.getProjects(context)
            val projectConfig = projects.find { it.id == projectId } ?: return@withContext
            Log.d("SyncManager", "Syncing local project at ${projectConfig.uri}")

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

            Log.d("SyncManager", "Found ${localFiles.size} local files and ${remoteFiles.size} remote files")

            val totalSteps = localFiles.size + remoteFiles.size
            var currentStep = 0

            progressCallback?.invoke(20, "Synchronizing files...")

            // 1. Upload/Update local files to remote
            for (localFile in localFiles) {
                // Note: For nested files, remoteFiles (which is shallow) won't find a match,
                // so we defaults to upload/update. This ensures nested files are synced,
                // relying on the provider to handle overwrite logic.
                val remoteFile = remoteFiles.find { it.name == localFile.name }

                // Ensure we use forward slashes for remote paths regardless of local OS
                val cleanRelativePath = localFile.relativePath.replace("\\", "/")
                val remotePath = "${config.remotePath.trimEnd('/')}/$cleanRelativePath"

                if (remoteFile == null || localFile.lastModified > remoteFile.lastModified) {
                    Log.d(
                        "SyncManager",
                        "Uploading ${localFile.name} (Local: ${localFile.lastModified}, Remote: ${remoteFile?.lastModified})",
                    )
                    progressCallback?.invoke((20 + (currentStep++ * 60 / totalSteps)), "Uploading ${localFile.name}...")
                    localFile.openInputStream()?.use { input ->
                        provider.uploadFile(remotePath, input)
                    }

                    // Also sync PDF if enabled
                    if (config.syncPdf) {
                        Log.d("SyncManager", "Generating/Uploading PDF for ${localFile.name}")
                        syncPdf(localFile, config.remotePath, provider)
                    }
                }
            }

            // 2. Download remote files that don't exist locally or are newer
            for (remoteFile in remoteFiles) {
                if (remoteFile.isDirectory || !remoteFile.name.endsWith(".notate")) continue

                val localFile = localFiles.find { it.name == remoteFile.name }
                if (localFile == null || remoteFile.lastModified > localFile.lastModified) {
                    Log.d(
                        "SyncManager",
                        "Downloading ${remoteFile.name} (Remote: ${remoteFile.lastModified}, Local: ${localFile?.lastModified})",
                    )
                    progressCallback?.invoke((20 + (currentStep++ * 60 / totalSteps)), "Downloading ${remoteFile.name}...")
                    provider.downloadFile(remoteFile.path)?.use { input ->
                        if (projectConfig.uri.startsWith("content://")) {
                            val dir = DocumentFile.fromTreeUri(context, Uri.parse(projectConfig.uri))
                            val existing = dir?.findFile(remoteFile.name)
                            val file = existing ?: dir?.createFile("application/octet-stream", remoteFile.name)
                            file?.let {
                                context.contentResolver.openOutputStream(it.uri)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } else {
                            val file = File(projectConfig.uri, remoteFile.name)
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
            Log.d("SyncManager", "Sync finished successfully")
        } catch (e: Exception) {
            Log.e("SyncManager", "Sync failed", e)
            progressCallback?.invoke(0, "Sync failed: ${e.message}")
        }
    }

    suspend fun findProjectForFile(filePath: String): String? =
        withContext(Dispatchers.IO) {
            Log.d("SyncManager", "Searching project for file: $filePath")
            val projects = PreferencesManager.getProjects(context)

            // Normalize file path if it's content://
            val targetUri = if (filePath.startsWith("content://")) Uri.parse(filePath) else Uri.fromFile(File(filePath))

            for (project in projects) {
                Log.d("SyncManager", "Checking against project: ${project.name} (${project.uri})")

                if (filePath.startsWith("content://") && project.uri.startsWith("content://")) {
                    // For SAF, simple string prefix check is weak but often sufficient for tree URIs
                    // A better check would be seeing if the file URI contains the project Tree URI's ID
                    if (filePath.contains(project.uri) || filePath.startsWith(project.uri)) {
                        Log.d("SyncManager", "Match found via SAF prefix")
                        return@withContext project.id
                    }
                } else if (!filePath.startsWith("content://") && !project.uri.startsWith("content://")) {
                    // Local File
                    try {
                        val fileCanonical = File(filePath).canonicalPath
                        val projectCanonical = File(project.uri).canonicalPath
                        if (fileCanonical.startsWith(projectCanonical)) {
                            Log.d("SyncManager", "Match found via File path")
                            return@withContext project.id
                        }
                    } catch (e: Exception) {
                        Log.w("SyncManager", "Path comparison error", e)
                    }
                }
            }
            Log.w("SyncManager", "No matching project found for $filePath")
            return@withContext null
        }

    private suspend fun syncPdf(
        localFile: LocalFile,
        remoteDir: String,
        provider: RemoteStorageProvider,
    ) {
        try {
            val loadResult = canvasRepository.loadCanvas(localFile.path) ?: return
            val model = InfiniteCanvasModel()
            model.setLoadedState(loadResult.canvasState)

            val cleanRelativePath = localFile.relativePath.replace("\\", "/")
            val pdfRelativePath = cleanRelativePath.substringBeforeLast(".") + ".pdf"
            val remotePdfPath = "${remoteDir.trimEnd('/')}/$pdfRelativePath"

            val out = ByteArrayOutputStream()
            PdfExporter.export(context, model, out, isVector = true, callback = null)

            val pdfInput = ByteArrayInputStream(out.toByteArray())
            provider.uploadFile(remotePdfPath, pdfInput)
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to sync PDF for ${localFile.name}", e)
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
