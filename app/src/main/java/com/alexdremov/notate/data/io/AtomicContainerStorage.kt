package com.alexdremov.notate.data.io

import android.content.Context
import android.net.Uri
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ZipUtils
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Handles atomic packing and unpacking of Container files (ZIPs).
 * Ensures that writes are either fully successful or don't happen at all.
 */
class AtomicContainerStorage(
    private val context: Context,
) {
    /**
     * Unpacks a container stream to a target directory.
     * @throws IOException if unpacking fails.
     */
    fun unpack(
        inputStream: InputStream,
        targetDir: File,
    ) {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()
        ZipUtils.unzip(inputStream, targetDir)
    }

    /**
     * Atomically packs the source directory into the target file.
     *
     * Strategy:
     * 1. Zip sourceDir to a temporary file.
     * 2. Verify temporary file integrity.
     * 3. Atomically replace target file with temporary file.
     *
     * @param sourceDir The directory containing unzipped project files.
     * @param targetPath Absolute path or content URI of the destination.
     */
    fun pack(
        sourceDir: File,
        targetPath: String,
    ) {
        val tempZip = File(context.cacheDir, "save_${System.currentTimeMillis()}.zip.tmp")
        try {
            // 1. Create ZIP
            ZipUtils.zip(sourceDir, tempZip)

            // 2. Verify
            verifyZip(tempZip)

            // 3. Commit
            commit(tempZip, targetPath)
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    private fun verifyZip(file: File) {
        if (!file.exists() || file.length() < 22) { // Empty zip is 22 bytes
            throw IOException("Generated ZIP is invalid: Too small or missing")
        }
        // Basic header check
        file.inputStream().use {
            val header = ByteArray(4)
            if (it.read(header) < 4) throw IOException("Generated ZIP is invalid: header missing")
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                throw IOException("Generated ZIP is invalid: Bad signature")
            }
        }

        // Ensure at least one entry exists
        var hasEntries = false
        try {
            java.util.zip.ZipFile(file).use { zip ->
                hasEntries = zip.entries().hasMoreElements()
            }
        } catch (e: Exception) {
            throw IOException("Generated ZIP is corrupted: ${e.message}", e)
        }

        if (!hasEntries) {
            throw IOException("Generated ZIP is empty (no entries)")
        }
    }

    private fun commit(
        sourceFile: File,
        targetPath: String,
    ) {
        if (targetPath.startsWith("content://")) {
            commitToSaf(sourceFile, Uri.parse(targetPath))
        } else {
            commitToLocalFile(sourceFile, File(targetPath))
        }
    }

    private fun commitToLocalFile(
        source: File,
        target: File,
    ) {
        val backup = File(target.parent, "${target.name}.bak")

        // 1. Create backup of existing
        if (target.exists()) {
            if (backup.exists()) backup.delete()
            if (!target.renameTo(backup)) {
                // Try copy-delete if rename fails (e.g. windows/special fs)
                // But for Android local storage rename usually works.
                // If it fails, we might not have permission or it's locked.
                throw IOException("Failed to create backup. Target file might be locked: ${target.absolutePath}")
            }
        }

        // 2. Rename new file to target
        if (!source.renameTo(target)) {
            // Rename failed (cross-fs?). Try stream copy.
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // Restore backup
                if (backup.exists()) {
                    target.delete()
                    backup.renameTo(target)
                }
                throw IOException("Failed to commit file. Restored backup.", e)
            }
        }

        // 3. Cleanup backup
        if (backup.exists()) {
            backup.delete()
        }
    }

    private fun commitToSaf(
        source: File,
        targetUri: Uri,
    ) {
        // SAF doesn't support atomic replace natively.
        // We truncate and write. This is the best we can do without creating new files
        // and updating pointers (which breaks persistent permission grants).
        try {
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open SAF stream")
        } catch (e: Exception) {
            throw IOException("Failed to commit to SAF URI", e)
        }
    }
}
