package com.alexdremov.notate.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class RemoteStorageType {
    @ProtoNumber(1)
    WEBDAV,

    @ProtoNumber(2)
    GOOGLE_DRIVE,
}

@Serializable
data class RemoteStorageConfig(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val type: RemoteStorageType,
    @ProtoNumber(4)
    val baseUrl: String? = null,
    @ProtoNumber(5)
    val username: String? = null,
    // Password will be stored in EncryptedSharedPreferences using the id as key
)

@Serializable
data class ProjectSyncConfig(
    @ProtoNumber(1)
    val projectId: String,
    @ProtoNumber(2)
    val remoteStorageId: String,
    @ProtoNumber(3)
    val remotePath: String,
    @ProtoNumber(4)
    val isEnabled: Boolean = true,
    @ProtoNumber(5)
    val lastSyncTimestamp: Long = 0,
    @ProtoNumber(6)
    val syncPdf: Boolean = true,
)

@Serializable
data class FileSyncState(
    @ProtoNumber(1)
    val lastLocalModified: Long = 0,
    @ProtoNumber(2)
    val lastRemoteModified: Long = 0,
    @ProtoNumber(3)
    val lastSyncTime: Long = 0
)

@Serializable
data class SyncMetadata(
    @ProtoNumber(1)
    val fileHashes: Map<String, String> = emptyMap(), // localPath -> hash
    @ProtoNumber(2)
    val files: Map<String, FileSyncState> = emptyMap() // relativePath -> state
)

@Serializable
data class PendingDeletion(
    @ProtoNumber(1)
    val projectId: String,
    @ProtoNumber(2)
    val relativePath: String,
    @ProtoNumber(3)
    val timestamp: Long
)
