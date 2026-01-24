package com.alexdremov.notate.data.region

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.alexdremov.notate.data.CanvasImageData
import com.alexdremov.notate.data.RegionProto
import com.alexdremov.notate.model.CanvasImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CanvasImagePersistenceTest {
    private lateinit var tempDir: File
    private lateinit var storage: RegionStorage

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)

        tempDir = File(System.getProperty("java.io.tmpdir"), "notate_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        storage = RegionStorage(tempDir)
        storage.init()

        every { context.contentResolver } returns contentResolver
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testImportImage_copiesFile_andReturnsAbsolutePath() {
        // Arrange
        val dummyData = "fake image content".toByteArray()
        val uri = Uri.parse("content://fake/image.png")

        every { contentResolver.getType(any()) } returns "image/png"
        every { contentResolver.openInputStream(any()) } answers { ByteArrayInputStream(dummyData) }

        // Act
        val resultPath = storage.importImage(uri, context)

        // Assert
        assertNotNull("Import result should not be null", resultPath)
        val importedFile = File(resultPath!!)
        assertTrue("Imported file should exist", importedFile.exists())
        assertEquals("Content should match", "fake image content", importedFile.readText())

        // Check location
        val imagesDir = File(tempDir, "images")
        assertEquals("File should be in images directory", imagesDir.absolutePath, importedFile.parentFile.absolutePath)
        assertTrue("Filename should end with .png", importedFile.name.endsWith(".png"))
    }

    @Test
    fun testSaveRegion_relativizesPaths_insideSession() {
        // Arrange
        // Create a dummy image file inside the session
        val imagesDir = File(tempDir, "images").apply { mkdirs() }
        val imgFile = File(imagesDir, "test.png")
        imgFile.writeText("test")

        val regionId = RegionId(0, 0)
        val regionData = RegionData(regionId)
        val imageItem =
            CanvasImage(
                uri = imgFile.absolutePath, // Absolute path!
                bounds = android.graphics.RectF(0f, 0f, 100f, 100f),
            )
        regionData.items.add(imageItem)

        // Act
        storage.saveRegion(regionData)

        // Assert
        // Read the binary file directly to verify relative path storage
        val regionFile = File(tempDir, "r_0_0.bin")
        assertTrue("Region file should exist", regionFile.exists())

        val bytes = regionFile.readBytes()
        val proto = ProtoBuf.decodeFromByteArray(RegionProto.serializer(), bytes)

        assertEquals("Should have 1 image", 1, proto.images.size)
        val savedPath = proto.images[0].uri
        assertEquals("Path should be relative", "images/test.png", savedPath)
    }

    @Test
    fun testLoadRegion_resolvesRelativePaths_toAbsolute() {
        // Arrange
        val regionId = RegionId(1, 1)
        val imagesDir = File(tempDir, "images").apply { mkdirs() }
        val imgFile = File(imagesDir, "loaded.png")
        imgFile.writeText("loaded")

        // Manually create a proto with relative path
        val imgProto =
            CanvasImageData(
                uri = "images/loaded.png", // Relative!
                x = 10f,
                y = 10f,
                width = 50f,
                height = 50f,
                zIndex = 0f,
                order = 0,
                rotation = 0f,
                opacity = 1f,
            )
        val proto = RegionProto(1, 1, emptyList(), listOf(imgProto))

        val regionFile = File(tempDir, "r_1_1.bin")
        val bytes = ProtoBuf.encodeToByteArray(RegionProto.serializer(), proto)
        regionFile.writeBytes(bytes)

        // Act
        val loadedRegion = storage.loadRegion(regionId)

        // Assert
        assertNotNull("Region should load", loadedRegion)
        assertEquals("Should have 1 item", 1, loadedRegion!!.items.size)
        val loadedImage = loadedRegion.items[0] as CanvasImage

        assertEquals("URI should be absolute path", imgFile.absolutePath, loadedImage.uri)
        assertTrue("Referenced file should exist", File(loadedImage.uri).exists())
    }

    @Test
    fun testLoadRegion_keepsExternalPaths_absolute() {
        // Edge case: if for some reason an absolute path (outside session) was stored (legacy?)
        // It should remain absolute.

        val regionId = RegionId(2, 2)
        val externalFile = File(System.getProperty("java.io.tmpdir"), "external.png")

        val imgProto =
            CanvasImageData(
                uri = externalFile.absolutePath, // Absolute!
                x = 0f,
                y = 0f,
                width = 10f,
                height = 10f,
                zIndex = 0f,
                order = 0,
                rotation = 0f,
                opacity = 1f,
            )
        val proto = RegionProto(2, 2, emptyList(), listOf(imgProto))

        val regionFile = File(tempDir, "r_2_2.bin")
        val bytes = ProtoBuf.encodeToByteArray(RegionProto.serializer(), proto)
        regionFile.writeBytes(bytes)

        // Act
        val loadedRegion = storage.loadRegion(regionId)
        val loadedImage = loadedRegion!!.items[0] as CanvasImage

        assertEquals("External absolute path should be preserved", externalFile.absolutePath, loadedImage.uri)
    }
}
