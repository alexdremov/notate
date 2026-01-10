package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter

sealed interface FileSystemItem {
    val name: String
    val path: String
    val lastModified: Long
}

data class ProjectItem(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val itemsCount: Int,
) : FileSystemItem

data class CanvasItem(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val thumbnail: String? = null,
) : FileSystemItem

class ProjectRepository(
    private val context: Context,
    private val rootUriString: String? = null,
) {
    private val localStorage by lazy {
        val dir = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }
        LocalStorageProvider(context, dir)
    }

    private val safStorage by lazy {
        if (rootUriString != null && rootUriString.startsWith("content://")) {
            SafStorageProvider(context, rootUriString)
        } else {
            null
        }
    }

    private fun getProvider(path: String?): StorageProvider {
        // If we have a specific SAF root, and the path is either null (root) or content://, use SAF
        if (safStorage != null && (path == null || path.startsWith("content://"))) {
            return safStorage!!
        }
        return localStorage
    }

    fun getRootPath(): String = rootUriString ?: localStorage.getRootPath()

    fun getItems(path: String?): List<FileSystemItem> = getProvider(path).getItems(path)

    fun createProject(
        name: String,
        parentPath: String?,
    ): Boolean = getProvider(parentPath).createFolder(name, parentPath)

    fun createCanvas(
        name: String,
        parentPath: String?,
        type: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
    ): String? {
        val emptyCanvas =
            com.alexdremov.notate.data.CanvasData(
                canvasType = type,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
            )
        return getProvider(parentPath).createCanvas(name, parentPath, emptyCanvas)
    }

    fun deleteItem(path: String): Boolean = getProvider(path).deleteItem(path)

    fun renameItem(
        path: String,
        newName: String,
    ): Boolean = getProvider(path).renameItem(path, newName)

    fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean = getProvider(path).duplicateItem(path, parentPath)
}
