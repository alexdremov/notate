package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.util.Log
import com.alexdremov.notate.model.Stroke
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot
import kotlin.random.Random

/**
 * High-performance Charcoal/Pencil renderer.
 *
 * Rules:
 * - Texture "solidity" depends on Pressure (Low P = Grainy, High P = Solid).
 * - Stroke Width depends on Tilt (0 Tilt = Base, 90 Tilt = 2.5x Base). Pressure ignores Width.
 */
object CharcoalPenRenderer {
    private const val TAG = "CharcoalRenderer"
    private var textureShader: BitmapShader? = null
    private val textureLock = Any()

    private data class CharcoalMesh(
        val verts: FloatArray,
        val colors: IntArray,
        val outlinePath: Path,
    )

    fun render(
        canvas: Canvas,
        paint: Paint,
        stroke: Stroke,
        maxPressure: Float,
    ) {
        val mesh = getOrValidateMesh(stroke, stroke.width, maxPressure) ?: return

        val originalColor = paint.color
        val originalAlpha = Color.alpha(originalColor)
        val originalShader = paint.shader
        val originalFilter = paint.colorFilter
        val originalStyle = paint.style

        try {
            // --- 1. Render Solidity Layer (Vertices) ---
            paint.shader = null
            paint.colorFilter = null
            paint.style = Paint.Style.FILL

            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_STRIP,
                mesh.verts.size,
                mesh.verts,
                0,
                null, // No texture coords
                0,
                mesh.colors,
                0,
                null, // indices
                0,
                0,
                paint,
            )

            // --- 2. Render Texture Layer (Path) ---
            ensureTexture()
            paint.shader = textureShader
            paint.colorFilter = PorterDuffColorFilter(originalColor, PorterDuff.Mode.SRC_IN)
            paint.alpha = originalAlpha
            paint.style = Paint.Style.FILL

            canvas.drawPath(mesh.outlinePath, paint)
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering charcoal", e)
        } finally {
            paint.shader = originalShader
            paint.colorFilter = originalFilter
            paint.color = originalColor
            paint.style = originalStyle
            paint.alpha = originalAlpha
        }
    }

    private fun getOrValidateMesh(
        stroke: Stroke,
        baseWidth: Float,
        maxPressure: Float,
    ): CharcoalMesh? {
        val cached = stroke.renderCache
        if (cached is CharcoalMesh) {
            return cached
        }

        val points = stroke.points
        if (points.size < 2) return null

        val size = points.size
        val vertexCount = size * 2
        val verts = FloatArray(vertexCount * 2)
        val colors = IntArray(vertexCount)

        // Ensure we mask the alpha from the base color so we can control it per-vertex
        // We use the stroke color, but we only need RGB since Alpha is derived from pressure.
        val baseColorRGB = stroke.color and 0x00FFFFFF
        val originalAlpha = Color.alpha(stroke.color) // Used for max alpha scaling

        // --- Geometry & Color Generation ---
        for (i in 0 until size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // Geometry Calculation (Double precision for safety)
            var dx = (p2.x - p1.x).toDouble()
            var dy = (p2.y - p1.y).toDouble()
            var dist = hypot(dx, dy)

            if (dist < 0.001) {
                dx = 1.0
                dy = 0.0
                dist = 1.0
            }

            val nx = -dy / dist
            val ny = dx / dist

            val tiltMag = hypot(p1.tiltX.toDouble(), p1.tiltY.toDouble()).coerceAtMost(90.0)
            val tiltFraction = tiltMag / 90.0

            val tiltExpansion = 2.0
            val widthFactor = 1.0 + (tiltFraction * tiltExpansion)
            val halfWidth = (baseWidth * widthFactor) / 2.0

            val jitter = (Random.nextFloat() - 0.5f) * (halfWidth * 0.3)
            val w = halfWidth + jitter

            val vIndex = i * 4

            // Left Vertex
            verts[vIndex] = (p1.x + nx * w).toFloat()
            verts[vIndex + 1] = (p1.y + ny * w).toFloat()

            // Right Vertex
            verts[vIndex + 2] = (p1.x - nx * w).toFloat()
            verts[vIndex + 3] = (p1.y - ny * w).toFloat()

            // Color Calculation
            val rawNormPressure = if (maxPressure > 0) p1.pressure / maxPressure else 0.5f
            // Apply quadratic falloff to make light strokes lighter (gamma correction)
            val normPressure = rawNormPressure * rawNormPressure
            val maxAlpha = 200.0f
            val solidityAlpha = (normPressure * maxAlpha).toInt()
            val finalAlpha = (solidityAlpha * (originalAlpha / 255f)).toInt().coerceIn(0, 255)
            val vertexColor = (finalAlpha shl 24) or baseColorRGB

            colors[i * 2] = vertexColor
            colors[i * 2 + 1] = vertexColor

            // Handle Last Point
            if (i == size - 2) {
                val vLastIndex = (i + 1) * 4
                val tiltMag2 = hypot(p2.tiltX.toDouble(), p2.tiltY.toDouble()).coerceAtMost(90.0)
                val widthFactor2 = 1.0 + ((tiltMag2 / 90.0) * tiltExpansion)
                val halfWidth2 = (baseWidth * widthFactor2) / 2.0
                val jitter2 = (Random.nextFloat() - 0.5f) * (halfWidth2 * 0.3)
                val w2 = halfWidth2 + jitter2

                verts[vLastIndex] = (p2.x + nx * w2).toFloat()
                verts[vLastIndex + 1] = (p2.y + ny * w2).toFloat()
                verts[vLastIndex + 2] = (p2.x - nx * w2).toFloat()
                verts[vLastIndex + 3] = (p2.y - ny * w2).toFloat()

                val rawNormP2 = if (maxPressure > 0) p2.pressure / maxPressure else 0.5f
                val normP2 = rawNormP2 * rawNormP2
                val solidityAlpha2 = (normP2 * maxAlpha).toInt()
                val finalAlpha2 = (solidityAlpha2 * (originalAlpha / 255f)).toInt().coerceIn(0, 255)
                val vertexColor2 = (finalAlpha2 shl 24) or baseColorRGB

                colors[(i + 1) * 2] = vertexColor2
                colors[(i + 1) * 2 + 1] = vertexColor2
            }
        }

        val path = Path()
        path.moveTo(verts[0], verts[1])
        for (i in 1 until size) {
            path.lineTo(verts[i * 4], verts[i * 4 + 1])
        }
        for (i in size - 1 downTo 0) {
            path.lineTo(verts[i * 4 + 2], verts[i * 4 + 3])
        }
        path.close()

        val mesh = CharcoalMesh(verts, colors, path)
        stroke.renderCache = mesh
        return mesh
    }

    /**
     * Helper for legacy or external callers.
     */
    fun applyCharcoalTexture(paint: Paint) {
        ensureTexture()
        paint.shader = textureShader
        paint.colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_IN)
    }

    private fun ensureTexture() {
        if (textureShader != null) return
        synchronized(textureLock) {
            if (textureShader != null) return

            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val random = Random(12345)

            for (y in 0 until size) {
                for (x in 0 until size) {
                    val r = random.nextFloat()
                    if (r < 0.15f) {
                        bitmap.setPixel(x, y, Color.TRANSPARENT)
                    } else {
                        val alpha = random.nextInt(120, 256)
                        bitmap.setPixel(x, y, Color.argb(alpha, 0, 0, 0))
                    }
                }
            }
            textureShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}
