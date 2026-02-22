package com.alexdremov.notate.data

import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FileIndex(
    val files: Map<String, CachedFileMetadata> = emptyMap(),
    val uuidToPath: Map<String, String> = emptyMap(), // Add this for fast lookups
)

@Serializable
data class CachedFileMetadata(
    val name: String,
    val lastModified: Long,
    val tagIds: List<String>,
    val embeddedTags: List<Tag>,
    val uuid: String? = null, // Add this
)

class FileIndexManager(
    private val indexFile: File,
) {
    private var index: FileIndex = FileIndex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val mutex = Mutex()

    init {
        loadIndex()
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            val content = indexFile.readText()
            index = json.decodeFromString(content)
        } catch (e: Exception) {
            Logger.e("FileIndex", "Failed to load index", e)
            index = FileIndex()
        }
    }

    private fun saveIndex() {
        try {
            val content = json.encodeToString(index)
            indexFile.writeText(content)
        } catch (e: Exception) {
            Logger.e("FileIndex", "Failed to save index", e)
        }
    }

    suspend fun updateIndex(storage: StorageProvider) {
        mutex.withLock {
            val currentFiles = mutableMapOf<String, CachedFileMetadata>()
            val currentUuidToPath = mutableMapOf<String, String>()
            val existingFiles = index.files

            Logger.d("FileIndex", "Starting index update. Existing files: ${existingFiles.size}")

            try {
                storage.walkFiles { path, name, lastModified ->
                    val cached = existingFiles[path]
                    val isFresh = cached != null && cached.lastModified == lastModified

                    // Force reload if UUID is missing to ensure migration is picked up
                    // even if timestamp hasn't changed (e.g. fast save or coarse filesystem)
                    if (isFresh && cached!!.uuid != null) {
                        currentFiles[path] = cached
                        cached.uuid?.let { currentUuidToPath[it] = path }
                        // Logger.d("FileIndex", "Reusing cached entry for $name (UUID: ${cached.uuid})")
                    } else {
                        Logger.d("FileIndex", "Reloading metadata for $name (Fresh: $isFresh, Cached UUID: ${cached?.uuid})")
                        val metadata = storage.getFileMetadata(path)
                        if (metadata != null) {
                            Logger.d("FileIndex", "Parsed metadata for $name -> UUID: ${metadata.uuid}")
                            val newMetadata =
                                CachedFileMetadata(
                                    name = name,
                                    lastModified = lastModified,
                                    tagIds = metadata.tagIds,
                                    embeddedTags = metadata.tagDefinitions,
                                    uuid = metadata.uuid,
                                )
                            currentFiles[path] = newMetadata
                            newMetadata.uuid?.let { currentUuidToPath[it] = path }
                        } else {
                            Logger.w("FileIndex", "Failed to parse metadata for $name")
                        }
                    }
                }
                index = index.copy(files = currentFiles, uuidToPath = currentUuidToPath)
                saveIndex()
                Logger.d("FileIndex", "Index update complete. Total indexed: ${currentFiles.size}, With UUIDs: ${currentUuidToPath.size}")
            } catch (e: Exception) {
                Logger.e("FileIndex", "Failed to update index", e)
            }
        }
    }

    suspend fun getFilesWithTag(tagId: String): List<String> {
        mutex.withLock {
            return index.files.filter { it.value.tagIds.contains(tagId) }.map { it.key }
        }
    }

    suspend fun getIndexedFiles(tagId: String): List<Pair<String, CachedFileMetadata>> {
        mutex.withLock {
            return index.files.filter { it.value.tagIds.contains(tagId) }.toList()
        }
    }

    suspend fun getAllIndexedFiles(): List<Pair<String, CachedFileMetadata>> {
        mutex.withLock {
            return index.files.toList()
        }
    }

    suspend fun getFileMetadata(path: String): CachedFileMetadata? {
        mutex.withLock {
            return index.files[path]
        }
    }

    suspend fun getPathForUuid(uuid: String): String? {
        mutex.withLock {
            return index.uuidToPath[uuid]
        }
    }

    suspend fun updateFileEntry(
        path: String,
        metadata: CachedFileMetadata,
    ) {
        mutex.withLock {
            val newFiles = index.files.toMutableMap()
            newFiles[path] = metadata
            val newUuidToPath = index.uuidToPath.toMutableMap()
            metadata.uuid?.let { newUuidToPath[it] = path }
            index = index.copy(files = newFiles, uuidToPath = newUuidToPath)
            saveIndex()
        }
    }

    suspend fun getAllTags(): List<Tag> {
        mutex.withLock {
            val tags = mutableMapOf<String, Tag>()
            index.files.values.forEach { metadata ->
                metadata.embeddedTags.forEach { tag ->
                    if (!tags.containsKey(tag.id)) {
                        tags[tag.id] = tag
                    }
                }
            }
            return tags.values.toList()
        }
    }
}
