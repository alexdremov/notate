package com.alexdremov.notate.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun unzip(
        zipFile: File,
        targetDir: File,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                // Security check for Zip Slip
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(file)).use { bos ->
                        zis.copyTo(bos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    fun zip(
        sourceDir: File,
        zipFile: File,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zipRecursive(sourceDir, sourceDir, zos)
        }
    }

    private fun zipRecursive(
        rootDir: File,
        currentDir: File,
        zos: ZipOutputStream,
    ) {
        val files = currentDir.listFiles() ?: throw java.io.IOException("Failed to list files in ${currentDir.absolutePath}")
        for (file in files) {
            if (file.isDirectory) {
                zipRecursive(rootDir, file, zos)
            } else {
                val relPath = file.relativeTo(rootDir).path
                val entry = ZipEntry(relPath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    // For Content URI (Input Stream)
    fun unzip(
        inputStream: java.io.InputStream,
        targetDir: File,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(file)).use { bos ->
                        zis.copyTo(bos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Extracts a specific file from the ZIP archive to the target destination.
     * Returns true if found and extracted, false otherwise.
     */
    fun extractFile(
        zipFile: File,
        entryName: String,
        targetFile: File,
    ): Boolean {
        if (!zipFile.exists()) return false

        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entry = findEntry(zip, entryName) ?: return false
                targetFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            com.alexdremov.notate.util.Logger
                .e("ZipUtils", "Failed to extract $entryName from ${zipFile.name}", e)
            return false
        }
    }

    /**
     * Reads the manifest.bin from the ZIP without extracting it to disk.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun readManifest(zipFile: File): com.alexdremov.notate.data.CanvasData? {
        if (!zipFile.exists()) return null
        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entry = findEntry(zip, "manifest.bin") ?: return null
                zip.getInputStream(entry).use { input ->
                    val bytes = input.readBytes()
                    return kotlinx.serialization.protobuf.ProtoBuf
                        .decodeFromByteArray(
                            com.alexdremov.notate.data.CanvasData
                                .serializer(),
                            bytes,
                        )
                }
            }
        } catch (e: Exception) {
            com.alexdremov.notate.util.Logger
                .e("ZipUtils", "Failed to read manifest from ${zipFile.name}", e)
            return null
        }
    }

    /**
     * Robustly find an entry by name, handling common ZIP structure variations.
     */
    private fun findEntry(
        zip: java.util.zip.ZipFile,
        name: String,
    ): java.util.zip.ZipEntry? {
        // 1. Direct match
        zip.getEntry(name)?.let { return it }

        // 2. Iterate and check for suffix match (handles prefixes or different separators)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name == name || entry.name.endsWith("/$name") || entry.name.endsWith("\\$name")) {
                return entry
            }
        }
        return null
    }

    /**
     * Unzips all files from zipFile to targetDir, skipping files that already exist.
     */
    fun unzipSkippingExisting(
        zipFile: File,
        targetDir: File,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()

        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val file = File(targetDir, entry.name)

                    // Security check
                    if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        continue
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        if (!file.exists()) {
                            file.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                java.io.BufferedOutputStream(java.io.FileOutputStream(file)).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            com.alexdremov.notate.util.Logger
                .e("ZipUtils", "Failed to unzip skipping existing: ${zipFile.name}", e)
        }
    }
}
