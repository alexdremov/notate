package com.example.notate.util

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import java.lang.reflect.Method

object EpdFastModeController {
    private var applyTransientUpdateMethod: Method? = null
    private var clearTransientUpdateMethod: Method? = null
    private var setEpdTurboMethod: Method? = null
    private var initialized = false

    private fun initReflection() {
        if (initialized) return
        try {
            // EpdController is in com.onyx.android.sdk.api.device.epd
            val clazz = EpdController::class.java

            // Look for applyTransientUpdate(UpdateMode)
            try {
                applyTransientUpdateMethod = clazz.getMethod("applyTransientUpdate", UpdateMode::class.java)
            } catch (e: Exception) {
                // Ignore
            }

            // Look for clearTransientUpdate(boolean)
            try {
                clearTransientUpdateMethod = clazz.getMethod("clearTransientUpdate", Boolean::class.javaPrimitiveType)
            } catch (e: Exception) {
                // Ignore
            }

            // Look for setEpdTurbo(boolean)
            try {
                setEpdTurboMethod = clazz.getMethod("setEpdTurbo", Boolean::class.javaPrimitiveType)
            } catch (e: Exception) {
                // Ignore
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            initialized = true
        }
    }

    fun enterFastMode() {
        initReflection()
        try {
            // Enable Turbo
            setEpdTurboMethod?.invoke(null, true)

            // Apply Transient Update (Animation Quality)
            // Using ANIMATION_QUALITY if available, or just ANIMATION
            // Note: UpdateMode is an enum.
            applyTransientUpdateMethod?.invoke(null, UpdateMode.ANIMATION)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun exitFastMode() {
        initReflection()
        try {
            // Clear Transient Update
            // Passing 'false' might prevent the GC flash on exit?
            // Native app uses 'true' on idle timeout.
            // We want to exit without flashing if possible, or maybe 'true' is cleaner.
            // Let's try 'false' first to be "less annoying".
            clearTransientUpdateMethod?.invoke(null, false)

            // Disable Turbo
            setEpdTurboMethod?.invoke(null, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
