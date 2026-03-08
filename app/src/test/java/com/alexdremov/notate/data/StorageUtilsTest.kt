package com.alexdremov.notate.data

import com.alexdremov.notate.model.Tag
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StorageUtilsTest {
    @Test
    fun `test updateMetadata with ZIP correctly updates manifest`() {
        // 1. Create a mock ZIP with manifest.bin and another file
        val bos = ByteArrayOutputStream()
        val zos = ZipOutputStream(bos)

        // Add manifest.bin (legacy empty or simple protobuf)
        val manifestEntry = ZipEntry("manifest.bin")
        zos.putNextEntry(manifestEntry)
        // Just some dummy protobuf-like data
        zos.write(byteArrayOf(0x08, 0x01)) // Field 1 (thumbnail), Varint 1
        zos.closeEntry()

        // Add another file
        val otherEntry = ZipEntry("other.txt")
        zos.putNextEntry(otherEntry)
        zos.write("hello".toByteArray())
        zos.closeEntry()

        zos.finish()
        val originalZip = bos.toByteArray()

        // 2. Update metadata
        val inputStream = ByteArrayInputStream(originalZip)
        val outputStream = ByteArrayOutputStream()
        val tagIds = listOf("tag1", "tag2")
        val tagDefinitions = listOf(Tag("tag1", "Tag 1", 0xFF0000))
        val uuid = "new-uuid"

        StorageUtils.updateMetadata(inputStream, outputStream, tagIds, tagDefinitions, uuid)

        val updatedZip = outputStream.toByteArray()

        // 3. Verify
        val zis = ZipInputStream(ByteArrayInputStream(updatedZip))
        var entry = zis.nextEntry
        var manifestFound = false
        var otherFound = false

        while (entry != null) {
            if (entry.name == "manifest.bin") {
                manifestFound = true
                // Wrap in non-closable stream because extractMetadata will close it
                val nonClosable =
                    object : java.io.FilterInputStream(zis) {
                        override fun close() {
                            // Do nothing
                        }
                    }
                val preview = StorageUtils.extractMetadata("manifest.bin", { nonClosable })
                assertEquals(uuid, preview?.uuid)
                assertEquals(tagIds, preview?.tagIds)
                assertEquals(1, preview?.tagDefinitions?.size)
                assertEquals("Tag 1", preview?.tagDefinitions?.get(0)?.name)
            } else if (entry.name == "other.txt") {
                otherFound = true
                val bytes = zis.readBytes()
                val content = String(bytes)
                assertEquals("hello", content)
            }
            entry = zis.nextEntry
        }

        assertTrue("manifest.bin should be present", manifestFound)
        assertTrue("other.txt should be preserved", otherFound)
    }
}
