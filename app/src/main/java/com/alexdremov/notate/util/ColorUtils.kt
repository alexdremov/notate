package com.alexdremov.notate.util

import android.graphics.Color
import android.util.Log
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.device.Device

object ColorUtils {
    /**
     * Adjusts a color to be visible on E-ink screens for hardware rendering.
     * E-ink hardware layers often treat light colors as white/transparent.
     * This function darkens colors that exceed a luminance threshold.
     *
     * @param color The original color.
     * @return The adjusted color safe for hardware rendering.
     */
    fun adjustColorForHardware(color: Int): Int {
        var red = Color.red(color)
        var green = Color.green(color)
        var blue = Color.blue(color)

        // Force full opacity for hardware rendering
        // Hardware layers often ignore alpha or treat it unpredictably.

        // Calculate luminance
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue)

        // If it's very light (like yellow highlighter), map it to a Dark Gray or Black
        // to ensure visibility on the E-ink hardware layer.

        // Threshold of 200 (out of 255) catches white, light yellow, etc.
        if (luminance > 200) {
            return color
        }

        if (luminance > 150) {
            return Color.BLACK
        }

        return Color.rgb(red, green, blue)
    }

    /**
     * Lightens dark colors for better visibility in UI menus on E-ink devices.
     * Ensures that different dark shades are distinguishable and don't all look like solid black.
     */
    fun adjustColorForMenuDisplay(color: Int): Int {
        // Optimization: In high-quality modes (Regal), E-Ink renders colors accurately enough
        // so we don't need to artificially boost dark shades.
        try {
            val currentMode =
                com.onyx.android.sdk.device.Device
                    .currentDevice()
                    .appScopeRefreshMode
            Log.d("ColorUtils", "Current refresh mode: $currentMode")
            if (currentMode.toString() != "FAST") {
                return color
            }
        } catch (e: Exception) {
            // API might not be available on all devices/firmwares, fallback to adjustment
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // If the color is too dark, boost its value (brightness)
        // This ensures the preview is visible and distinguishable on E-ink
        val minBrightness = 0.5f
        if (hsv[2] < minBrightness) {
            hsv[2] = minBrightness + (hsv[2] * minBrightness) // Map [0, 0.4] to [0.4, 0.56]
            // Also slightly reduce saturation to make it look more like a "soft" version of the color
            hsv[1] = hsv[1] * 0.9f
        }

        return Color.HSVToColor(Color.alpha(color), hsv)
    }
}
