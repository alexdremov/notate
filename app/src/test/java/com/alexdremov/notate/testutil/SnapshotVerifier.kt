package com.alexdremov.notate.testutil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.fail
import java.io.File
import java.io.FileOutputStream

object SnapshotVerifier {

    private const val REFERENCE_DIR = "src/test/resources/snapshots"
    private const val OUTPUT_DIR = "build/outputs/snapshots"
    
    // Pixel tolerance for slight rendering differences (antialiasing, etc)
    private const val TOLERANCE = 0.01f 

    fun verify(bitmap: Bitmap, snapshotName: String) {
        val fileName = "$snapshotName.png"
        val referenceFile = File(REFERENCE_DIR, fileName)
        val outputFile = File(OUTPUT_DIR, fileName)
        
        outputFile.parentFile?.mkdirs()

        // Always save the current run result for inspection
        saveBitmap(bitmap, outputFile)

        if (!referenceFile.exists()) {
            fail("Snapshot reference not found: ${referenceFile.absolutePath}. \n" +
                 "A new snapshot has been generated at: ${outputFile.absolutePath}. \n" +
                 "Please verify it visually and copy it to the reference directory if correct.")
        }

        val referenceBitmap = BitmapFactory.decodeFile(referenceFile.absolutePath)
        
        if (bitmap.width != referenceBitmap.width || bitmap.height != referenceBitmap.height) {
            fail("Snapshot dimensions mismatch. Expected: ${referenceBitmap.width}x${referenceBitmap.height}, " +
                 "Actual: ${bitmap.width}x${bitmap.height}")
        }

        var distinctPixels = 0
        val totalPixels = bitmap.width * bitmap.height
        
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val p1 = bitmap.getPixel(x, y)
                val p2 = referenceBitmap.getPixel(x, y)
                if (p1 != p2) {
                    distinctPixels++
                }
            }
        }
        
        val diffRatio = distinctPixels.toFloat() / totalPixels
        if (diffRatio > TOLERANCE) {
            fail("Snapshot mismatch! $distinctPixels pixels differ ($diffRatio%). \n" +
                 "Check generated output at: ${outputFile.absolutePath}")
        }
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("Saved snapshot to ${file.absolutePath}")
    }
}
