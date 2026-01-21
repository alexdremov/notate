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
        val files = currentDir.listFiles() ?: return
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
}
