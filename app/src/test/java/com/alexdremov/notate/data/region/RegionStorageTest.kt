package com.alexdremov.notate.data.region

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RegionStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk()
    private val uri: Uri = mockk()

    private lateinit var baseDir: File
    private lateinit var regionStorage: RegionStorage

    @Before
    fun setup() {
        baseDir = tempFolder.newFolder("session_data")
        regionStorage = RegionStorage(baseDir)
        every { context.contentResolver } returns contentResolver
    }

    @Test
    fun `test init creates directory`() {
        val newDir = File(tempFolder.root, "new_session")
        val storage = RegionStorage(newDir)
        storage.init()
        assertTrue(newDir.exists())
        assertTrue(newDir.isDirectory)
    }

    @Test
    fun `test importImage success`() {
        val testContent = "Test Image Data".toByteArray()
        val inputStream = ByteArrayInputStream(testContent)

        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } returns inputStream

        val resultPath = regionStorage.importImage(uri, context)

        assertNotNull(resultPath)
        val resultFile = File(resultPath!!)
        assertTrue(resultFile.exists())
        assertEquals("Test Image Data", resultFile.readText())
        assertEquals("images", resultFile.parentFile.name)
        assertTrue(resultFile.name.endsWith(".png"))
    }

    @Test
    fun `test importImage fails gracefully on stream error`() {
        every { contentResolver.getType(uri) } returns "image/jpeg"
        every { contentResolver.openInputStream(uri) } throws RuntimeException("Stream Error")

        val resultPath = regionStorage.importImage(uri, context)

        assertNull(resultPath)
    }

    @Test
    fun `test listStoredRegions parses correctly`() {
        // Create dummy region files
        File(baseDir, "r_0_0.bin").createNewFile()
        File(baseDir, "r_-1_5.bin").createNewFile()
        File(baseDir, "r_10_-20.bin").createNewFile()
        File(baseDir, "junk.txt").createNewFile()
        File(baseDir, "r_invalid_name.bin").createNewFile()

        val regions = regionStorage.listStoredRegions()

        assertEquals(3, regions.size)
        assertTrue(regions.contains(RegionId(0, 0)))
        assertTrue(regions.contains(RegionId(-1, 5)))
        assertTrue(regions.contains(RegionId(10, -20)))
    }
}
