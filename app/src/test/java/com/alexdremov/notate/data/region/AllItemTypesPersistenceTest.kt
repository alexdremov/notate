package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.LinkItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.TextItem
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Polymorphic persistence contract: ALL CanvasItem subtypes — Stroke,
 * TextItem, LinkItem, CanvasImage — must round-trip through the region
 * store (protobuf container) with type, identity and payload preserved,
 * individually AND mixed in one region, across save/reopen cycles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AllItemTypesPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleStroke(order: Long): Stroke {
        val pts = ArrayList<TouchPoint>(10)
        val path = android.graphics.Path()
        for (i in 0..10) {
            val x = 50f + i * 8f
            val y = 60f + i * 2f
            pts.add(TouchPoint(x, y, 0.5f, 4f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return Stroke(
            path, pts, 0xFF111111.toInt(), width = 3f, style = StrokeType.FOUNTAIN,
            bounds = RectF(50f, 55f, 140f, 75f),
            strokeOrder = order,
        )
    }

    private fun allSampleItems(): List<CanvasItem> = listOf(
        sampleStroke(1),
        TextItem(
            text = "# Heading\nSome **markdown** note",
            fontSize = 16f,
            color = 0xFF222222.toInt(),
            logicalBounds = RectF(200f, 100f, 400f, 200f),
            bounds = RectF(200f, 100f, 400f, 200f),
            zIndex = 1f,
            order = 2,
        ),
        LinkItem(
            label = "Related note",
            target = "some-note-uuid",
            type = com.alexdremov.notate.data.LinkType.INTERNAL_NOTE,
            color = 0xFF0044FF.toInt(),
            fontSize = 14f,
            logicalBounds = RectF(450f, 120f, 650f, 170f),
            bounds = RectF(450f, 120f, 650f, 170f),
            zIndex = 2f,
            order = 3,
        ),
        // Image points at an existing file inside the session dir so content
        // resolution succeeds on reload.
        CanvasImage(
            uri = "sample-image.png",
            logicalBounds = RectF(700f, 100f, 900f, 300f),
            bounds = RectF(700f, 100f, 900f, 300f),
            zIndex = 3f,
            order = 4,
        ),
    )

    @Test
    fun `mixed item types survive save reopen cycle`() {
        val dir = tmp.newFolder()
        val storage = RegionStorage(dir).apply { init() }
        val items = allSampleItems()

        // Give the image something real to resolve.
        File(dir, "sample-image.png").writeBytes(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, // PNG magic
                0x0D, 0x0A, 0x1A, 0x0A,
                1, 2, 3, 4, 5, 6, 7, 8,
            ),
        )

        val id = RegionId(0, 0)
        val region = RegionData(id).apply { this.items = items }
        assertTrue(storage.saveRegion(region))

        val reloaded = storage.loadRegion(id)
        assertEquals("all four types must survive", 4, reloaded?.items?.size)

        val byOrder = reloaded!!.items.associateBy { it.order }

        // Type identity per slot.
        assertEquals(Stroke::class.java, byOrder[1]!!::class.java)
        assertEquals(TextItem::class.java, byOrder[2]!!::class.java)
        assertEquals(LinkItem::class.java, byOrder[3]!!::class.java)
        assertEquals(CanvasImage::class.java, byOrder[4]!!::class.java)

        // Payload spot-checks.
        val text = byOrder[2] as TextItem
        assertEquals("# Heading\nSome **markdown** note", text.text)
        assertEquals(16f, text.fontSize)
        val link = byOrder[3] as LinkItem
        assertEquals("Related note", link.label)
        assertEquals(com.alexdremov.notate.data.LinkType.INTERNAL_NOTE, link.type)
        assertEquals("some-note-uuid", link.target)
        val image = byOrder[4] as CanvasImage
        assertTrue(image.uri.isNotEmpty())

        // Bounds: Text/Link/Image persist their explicit bounds exactly.
        // Stroke bounds are RECOMPUTED from points (with a pen-size margin),
        // so we verify they still contain every stored point instead.
        val reloadedStroke = byOrder[1] as Stroke
        assertEquals(11, reloadedStroke.points.size)
        reloadedStroke.points.forEach { p ->
            assertTrue(
                "point (${p.x},${p.y}) outside recomputed bounds ${reloadedStroke.bounds}",
                reloadedStroke.bounds.contains(p.x, p.y),
            )
        }
        listOf(2, 3, 4).forEach { o ->
            val original = items.first { it.order == o.toLong() }
            val back = byOrder.getValue(o.toLong())
            assertEquals(original.bounds.left, back.bounds.left, 0.01f)
            assertEquals(original.bounds.top, back.bounds.top, 0.01f)
            assertEquals(original.bounds.right, back.bounds.right, 0.01f)
            assertEquals(original.bounds.bottom, back.bounds.bottom, 0.01f)
        }
    }

    @Test
    fun `manager-level mixed content survives full close reopen`() {
        val dir = tmp.newFolder()
        val rm = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        File(dir, "img.png").writeBytes(byteArrayOf(1, 2, 3, 4))

        val id = RegionId(0, 0)
        val region = RegionData(id).apply { this.items = allSampleItems() }
        runBlocking { rm.addItem(region.items[0]) }
        region.items.drop(1).forEach { runBlocking { rm.addItem(it) } }

        rm.saveAll()
        rm.clear()

        val rm2 = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        val all =
            runBlocking { rm2.queryItems(RectF(-50000f, -50000f, 50000f, 50000f)) }
        assertEquals(4, all.size)
        val types = all.map { it::class.java.simpleName }.toSet()
        assertEquals(setOf("Stroke", "TextItem", "LinkItem", "CanvasImage"), types)
        rm2.clear()
    }
}
