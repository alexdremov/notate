package com.alexdremov.notate.data

import java.io.InputStream

interface RemoteStorageProvider {
    suspend fun listFiles(remotePath: String): List<RemoteFile>

    suspend fun uploadFile(
        remotePath: String,
        inputStream: InputStream,
    ): Boolean

    suspend fun downloadFile(remotePath: String): InputStream?

    suspend fun createDirectory(remotePath: String): Boolean

    suspend fun deleteFile(remotePath: String): Boolean
}

data class RemoteFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val size: Long,
    val isDirectory: Boolean,
)
