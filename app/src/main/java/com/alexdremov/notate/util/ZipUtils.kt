package com.alexdremov.notate.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
                val canonicalPath = file.canonicalPath
                if (!canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(file)).use { bos ->
                        zis.copyTo(bos)
                    }
                    // Restore timestamp to allow incremental updates later
                    if (entry.time != -1L) {
                        file.setLastModified(entry.time)
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

    /**
     * Creates a new ZIP file by incrementally updating a base ZIP with files from sourceDir.
     * Preserves unchanged entries from baseZip to optimize IOPS (sequential read).
     */
    fun incrementalZip(
        sourceDir: File,
        baseZip: File,
        targetZip: File,
    ) {
        // Track processed files to know what remains to be added
        val processedRelPaths = HashSet<String>()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zos ->
            // 1. Copy unchanged entries from Base ZIP
            if (baseZip.exists() && baseZip.length() > 0) {
                try {
                    ZipFile(baseZip).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val relPath = entry.name
                            val localFile = File(sourceDir, relPath)

                            // Check if local file exists and matches timestamp (within 2s DOS precision)
                            if (localFile.exists() && !localFile.isDirectory) {
                                val timeDiff = kotlin.math.abs(localFile.lastModified() - entry.time)
                                if (timeDiff < 2000) {
                                    // Match! Copy from ZIP.
                                    // Note: Standard API re-compresses, but this saves Random IOPS on the source dir.
                                    val newEntry = ZipEntry(relPath)
                                    // Copy metadata if needed?
                                    zos.putNextEntry(newEntry)
                                    zip.getInputStream(entry).use { it.copyTo(zos) }
                                    zos.closeEntry()
                                    processedRelPaths.add(relPath)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("ZipUtils", "Failed to read base ZIP for incremental update, falling back to full zip", e)
                    // If base zip fails, we just continue and write everything from source
                }
            }

            // 2. Write new or modified files from Source Dir
            zipRecursiveIncremental(sourceDir, sourceDir, zos, processedRelPaths)
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
                entry.time = file.lastModified() // Ensure timestamp is stored for next incremental check
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    private fun zipRecursiveIncremental(
        rootDir: File,
        currentDir: File,
        zos: ZipOutputStream,
        processedPaths: Set<String>,
    ) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                zipRecursiveIncremental(rootDir, file, zos, processedPaths)
            } else {
                val relPath = file.relativeTo(rootDir).path
                if (!processedPaths.contains(relPath)) {
                    val entry = ZipEntry(relPath)
                    entry.time = file.lastModified()
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    // For Content URI (Input Stream)
    fun unzip(
        inputStream: InputStream,
        targetDir: File,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                val canonicalPath = file.canonicalPath
                if (!canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(file)).use { bos ->
                        zis.copyTo(bos)
                    }
                    if (entry.time != -1L) {
                        file.setLastModified(entry.time)
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

                    // Security check for Zip Slip
                    val canonicalPath = file.canonicalPath
                    if (!canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
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
                            if (entry.time != -1L) {
                                file.setLastModified(entry.time)
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
