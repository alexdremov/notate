package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.util.Logger
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class GoogleDriveProvider(
    private val context: Context,
    private val config: RemoteStorageConfig,
) : RemoteStorageProvider {
    private val driveService: Drive? by lazy {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@lazy null
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
        credential.selectedAccount = account.account

        Drive
            .Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential,
            ).setApplicationName("Notate")
            .build()
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val service = driveService ?: throw java.io.IOException("Google Drive not authenticated.")

            // In GDrive, we usually search by folder ID or name.
            // For simplicity, we assume remotePath is a folder name in root for now.
            val folderId = findFolderId(remotePath) ?: throw java.io.FileNotFoundException("Remote folder not found: $remotePath")

            val result =
                service
                    .files()
                    .list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name, mimeType, modifiedTime, size)")
                    .execute()

            result.files.map { file ->
                RemoteFile(
                    name = file.name,
                    path = file.id, // Use ID as path in GDrive
                    lastModified = file.modifiedTime.value,
                    size = file.getSize() ?: 0L,
                    isDirectory = file.mimeType == "application/vnd.google-apps.folder",
                )
            }
        }

    override suspend fun uploadFile(
        remotePath: String,
        inputStream: InputStream,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val service = driveService ?: throw java.io.IOException("Google Drive not authenticated.")

            // remotePath here is actually "directory/filename"
            val parts = remotePath.split('/')
            val fileName = parts.last()
            val folderPath = parts.dropLast(1).joinToString("/")

            val folderId = findOrCreateFolder(folderPath) ?: return@withContext false

            val existingFile = findFileInFolder(folderId, fileName)

            val fileMetadata =
                com.google.api.services.drive.model.File().apply {
                    name = fileName
                    parents = listOf(folderId)
                }

            val mediaContent = InputStreamContent("application/octet-stream", inputStream)

            if (existingFile != null) {
                service.files().update(existingFile.id, null, mediaContent).execute()
            } else {
                service.files().create(fileMetadata, mediaContent).execute()
            }
            true
        }

    override suspend fun downloadFile(remotePath: String): InputStream? =
        withContext(Dispatchers.IO) {
            val service = driveService ?: throw java.io.IOException("Google Drive not authenticated.")
            // remotePath is the file ID for GDrive in our implementation
            service.files().get(remotePath).executeMediaAsInputStream()
        }

    override suspend fun createDirectory(remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val service = driveService ?: throw java.io.IOException("Google Drive not authenticated.")
            findOrCreateFolder(remotePath) != null
        }

    override suspend fun deleteFile(remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val service = driveService ?: throw java.io.IOException("Google Drive not authenticated.")

            // Resolve ID from Path
            val parts = remotePath.split('/')
            val fileName = parts.last()
            val folderPath = parts.dropLast(1).joinToString("/")

            // If path is just "filename", folder is root (or implicitly search root?)
            // findFolderId handles empty/root logic? check implementation.
            // findFolderId("notate/epfl") returns ID of epfl.
            // If remotePath is "notate/epfl/b", folderPath is "notate/epfl".
            
            val folderId = if (folderPath.isEmpty()) "root" else findFolderId(folderPath)

            if (folderId == null) {
                Logger.w("GoogleDriveProvider", "Cannot delete $remotePath: Parent folder not found")
                // Treat as success (already gone)? Or fail?
                // If parent doesn't exist, file definitely doesn't exist.
                // Return false to indicate "we didn't delete anything", or throw FileNotFound?
                // SyncManager catches FileNotFoundException and removes pending deletion.
                throw java.io.FileNotFoundException("Parent folder not found: $folderPath")
            }

            val file = findFileInFolder(folderId, fileName)
            if (file == null) {
                Logger.w("GoogleDriveProvider", "Cannot delete $remotePath: File not found")
                throw java.io.FileNotFoundException("File not found: $remotePath")
            }

            service.files().delete(file.id).execute()
            true
        }

    private fun findFolderId(path: String): String? {
        val service = driveService ?: return null
        var currentParentId = "root"
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "root"

        for (part in parts) {
            Logger.d("GoogleDriveProvider", "Looking for folder: $part in $currentParentId")
            val result =
                service
                    .files()
                    .list()
                    .setQ(
                        "name = '$part' and mimeType = 'application/vnd.google-apps.folder' and '$currentParentId' in parents and trashed = false",
                    ).setFields("files(id)")
                    .execute()
            currentParentId = result.files.firstOrNull()?.id ?: run {
                Logger.d("GoogleDriveProvider", "Folder not found: $part")
                return null
            }
        }
        return currentParentId
    }

    private fun findOrCreateFolder(path: String): String? {
        val service = driveService ?: return null
        var currentParentId = "root"
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "root"

        for (part in parts) {
            Logger.d("GoogleDriveProvider", "Find or create folder: $part in $currentParentId")
            val result =
                service
                    .files()
                    .list()
                    .setQ(
                        "name = '$part' and mimeType = 'application/vnd.google-apps.folder' and '$currentParentId' in parents and trashed = false",
                    ).setFields("files(id)")
                    .execute()

            val existingId = result.files.firstOrNull()?.id
            if (existingId != null) {
                currentParentId = existingId
            } else {
                Logger.d("GoogleDriveProvider", "Creating folder: $part")
                val folderMetadata =
                    com.google.api.services.drive.model.File().apply {
                        name = part
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(currentParentId)
                    }
                val folder =
                    service
                        .files()
                        .create(folderMetadata)
                        .setFields("id")
                        .execute()
                currentParentId = folder.id
            }
        }
        return currentParentId
    }

    private fun findFileInFolder(
        folderId: String,
        fileName: String,
    ): com.google.api.services.drive.model.File? {
        val service = driveService ?: return null
        val result =
            service
                .files()
                .list()
                .setQ("name = '$fileName' and '$folderId' in parents and trashed = false")
                .setFields("files(id, name)")
                .execute()
        return result.files.firstOrNull()
    }
}
