package com.alexdremov.notate.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

object ImageImportHelper {
    private const val TAG = "ImageImportHelper"
    private const val IMPORT_DIR = "imported_images"

    /**
     * Copies the content from sourceUri to the app's internal storage.
     * Returns the "file://" URI string of the imported file, or null if failed.
     */
    fun importImage(
        context: Context,
        sourceUri: Uri,
    ): String? {
        try {
            val destDir = File(context.filesDir, IMPORT_DIR)
            if (!destDir.exists() && !destDir.mkdirs()) {
                Logger.e(TAG, "Failed to create import directory: ${destDir.absolutePath}")
                return null
            }

            // Attempt to guess extension, default to "img" if unknown
            val mimeType = context.contentResolver.getType(sourceUri)
            val extension =
                if (mimeType != null) {
                    android.webkit.MimeTypeMap
                        .getSingleton()
                        .getExtensionFromMimeType(mimeType) ?: "img"
                } else {
                    // Fallback: try to parse from filename if available, or just use img
                    "img"
                }

            val fileName = "${UUID.randomUUID()}.$extension"
            val destFile = File(destDir, fileName)

            val success =
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    true
                } ?: false

            return if (success) {
                Uri.fromFile(destFile).toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import image from $sourceUri", e)
            return null
        }
    }
}
