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
)

@Serializable
data class CachedFileMetadata(
    val name: String,
    val lastModified: Long,
    val tagIds: List<String>,
    val embeddedTags: List<Tag>,
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
            val existingFiles = index.files

            try {
                storage.walkFiles { path, name, lastModified ->
                    val cached = existingFiles[path]
                    if (cached != null && cached.lastModified == lastModified) {
                        currentFiles[path] = cached
                    } else {
                        val metadata = storage.getFileMetadata(path)
                        if (metadata != null) {
                            currentFiles[path] =
                                CachedFileMetadata(
                                    name = name,
                                    lastModified = lastModified,
                                    tagIds = metadata.tagIds,
                                    embeddedTags = metadata.tagDefinitions,
                                )
                        }
                    }
                }
                index = index.copy(files = currentFiles)
                saveIndex()
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

    suspend fun getFileMetadata(path: String): CachedFileMetadata? {
        mutex.withLock {
            return index.files[path]
        }
    }

    suspend fun updateFileEntry(
        path: String,
        metadata: CachedFileMetadata,
    ) {
        mutex.withLock {
            val newFiles = index.files.toMutableMap()
            newFiles[path] = metadata
            index = index.copy(files = newFiles)
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
