package com.alexdremov.notate.testutil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.fail
import java.io.File
import java.io.FileOutputStream

object SnapshotVerifier {
    private const val REFERENCE_DIR = "src/test/resources/snapshots"
    private const val OUTPUT_DIR = "build/outputs/snapshots"

    // Pixel tolerance for slight rendering differences (antialiasing, etc)
    private const val TOLERANCE = 0.01f

    fun verify(
        bitmap: Bitmap,
        snapshotName: String,
    ) {
        val fileName = "$snapshotName.png"
        val referenceFile = File(REFERENCE_DIR, fileName)
        val outputFile = File(OUTPUT_DIR, fileName)
        val actualFile = File(OUTPUT_DIR, "${snapshotName}_actual.png")
        val expectedFile = File(OUTPUT_DIR, "${snapshotName}_expected.png")
        val diffFile = File(OUTPUT_DIR, "${snapshotName}_diff.png")

        outputFile.parentFile?.mkdirs()

        // Always save the current run result for inspection
        saveBitmap(bitmap, actualFile)

        if (!referenceFile.exists()) {
            fail(
                "Snapshot reference not found: ${referenceFile.absolutePath}. \n" +
                    "A new snapshot has been generated at: ${actualFile.absolutePath}. \n" +
                    "Please verify it visually and copy it to the reference directory if correct.",
            )
        }

        val referenceBitmap = BitmapFactory.decodeFile(referenceFile.absolutePath)
        saveBitmap(referenceBitmap, expectedFile)

        if (bitmap.width != referenceBitmap.width || bitmap.height != referenceBitmap.height) {
            fail(
                "Snapshot dimensions mismatch. Expected: ${referenceBitmap.width}x${referenceBitmap.height}, " +
                    "Actual: ${bitmap.width}x${bitmap.height}",
            )
        }

        val totalPixels = bitmap.width * bitmap.height
        val pixels1 = IntArray(totalPixels)
        val pixels2 = IntArray(totalPixels)
        val diffPixels = IntArray(totalPixels)

        bitmap.getPixels(pixels1, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        referenceBitmap.getPixels(pixels2, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var distinctPixels = 0
        val colorTolerance = 2 // Allow up to 2 units of difference per channel

        for (i in 0 until totalPixels) {
            val c1 = pixels1[i]
            val c2 = pixels2[i]

            if (c1 != c2) {
                val r1 = (c1 shr 16) and 0xff
                val g1 = (c1 shr 8) and 0xff
                val b1 = c1 and 0xff
                val a1 = (c1 shr 24) and 0xff

                val r2 = (c2 shr 16) and 0xff
                val g2 = (c2 shr 8) and 0xff
                val b2 = c2 and 0xff
                val a2 = (c2 shr 24) and 0xff

                val isClose =
                    Math.abs(r1 - r2) <= colorTolerance &&
                        Math.abs(g1 - g2) <= colorTolerance &&
                        Math.abs(b1 - b2) <= colorTolerance &&
                        Math.abs(a1 - a2) <= colorTolerance

                if (!isClose) {
                    if (distinctPixels < 10) {
                        println("Pixel $i mismatch: Expected ARGB($a2,$r2,$g2,$b2), Actual ARGB($a1,$r1,$g1,$b1)")
                    }
                    distinctPixels++
                    diffPixels[i] = Color.RED
                } else {
                    // Pixels are slightly different but within tolerance
                    diffPixels[i] = c1
                }
            } else {
                diffPixels[i] = c1
            }
        }

        val diffRatio = distinctPixels.toFloat() / totalPixels
        if (diffRatio > TOLERANCE) {
            val diffBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            diffBitmap.setPixels(diffPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            saveBitmap(diffBitmap, diffFile)

            fail(
                "Snapshot mismatch! $distinctPixels pixels differ significantly ($diffRatio%). \n" +
                    "Check generated outputs at: \n" +
                    "  Actual:   ${actualFile.absolutePath}\n" +
                    "  Expected: ${expectedFile.absolutePath}\n" +
                    "  Diff:     ${diffFile.absolutePath}",
            )
        }
    }

    private fun saveBitmap(
        bitmap: Bitmap,
        file: File,
    ) {
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("Saved snapshot to ${file.absolutePath}")
    }
}
