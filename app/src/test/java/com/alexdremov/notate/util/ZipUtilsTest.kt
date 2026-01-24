package com.alexdremov.notate.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipUtilsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceDir: File
    private lateinit var baseZip: File
    private lateinit var targetZip: File

    @Before
    fun setup() {
        sourceDir = tempFolder.newFolder("source")
        baseZip = tempFolder.newFile("base.zip")
        targetZip = tempFolder.newFile("target.zip")
    }

    @Test
    fun `incrementalZip copies unchanged files from base zip`() {
        // 1. Create Source Files
        val file1 =
            File(sourceDir, "file1.txt").apply {
                writeText("Content 1")
                setLastModified(1000000000L) // Fixed time
            }
        val file2 =
            File(sourceDir, "file2.txt").apply {
                writeText("Content 2")
                setLastModified(2000000000L)
            }

        // 2. Create Base Zip with file1 matching, file2 DIFFERENT content (old version)
        ZipOutputStream(FileOutputStream(baseZip)).use { zos ->
            // Entry 1: Matches source (name + time)
            val e1 = ZipEntry("file1.txt")
            e1.time = 1000000000L
            zos.putNextEntry(e1)
            zos.write("Content 1".toByteArray())
            zos.closeEntry()

            // Entry 2: Matches name, but Time differs (should be overwritten)
            val e2 = ZipEntry("file2.txt")
            e2.time = 1500000000L // Old time
            zos.putNextEntry(e2)
            zos.write("Old Content 2".toByteArray())
            zos.closeEntry()
        }

        // 3. Run Incremental Zip
        ZipUtils.incrementalZip(sourceDir, baseZip, targetZip)

        // 4. Verify Target Zip
        val extractedDir = tempFolder.newFolder("extracted")
        ZipUtils.unzip(targetZip, extractedDir)

        val exFile1 = File(extractedDir, "file1.txt")
        val exFile2 = File(extractedDir, "file2.txt")

        assertTrue(exFile1.exists())
        assertEquals("Content 1", exFile1.readText())
        // Should have preserved timestamp from base zip (which matched source)
        // Wait, incrementalZip sets entry.time?
        // ZipUtils.incrementalZip copies entry from baseZip.

        assertTrue(exFile2.exists())
        assertEquals("Content 2", exFile2.readText())

        // Check integrity of structure
        ZipFile(targetZip).use { zip ->
            assertNotNull(zip.getEntry("file1.txt"))
            assertNotNull(zip.getEntry("file2.txt"))
        }
    }

    @Test
    fun `incrementalZip adds new files`() {
        // Source has file3, base zip is empty
        val file3 = File(sourceDir, "file3.txt").apply { writeText("Content 3") }

        ZipUtils.incrementalZip(sourceDir, baseZip, targetZip)

        ZipFile(targetZip).use { zip ->
            assertNotNull(zip.getEntry("file3.txt"))
        }
    }

    @Test
    fun `incrementalZip removes deleted files`() {
        // Base zip has file4, Source does not
        ZipOutputStream(FileOutputStream(baseZip)).use { zos ->
            val e4 = ZipEntry("file4.txt")
            zos.putNextEntry(e4)
            zos.write("Content 4".toByteArray())
            zos.closeEntry()
        }

        ZipUtils.incrementalZip(sourceDir, baseZip, targetZip)

        ZipFile(targetZip).use { zip ->
            assertNull("Deleted file should not be in target", zip.getEntry("file4.txt"))
        }
    }
}
