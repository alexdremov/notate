package com.alexdremov.notate.util

import android.view.View
import java.lang.reflect.Method

/**
 * Access to hidden Onyx system methods via reflection.
 * Based on reverse-engineering findings (Notable/GitHub).
 */
object OnyxSystemHelper {
    private const val TAG = "OnyxSystemHelper"

    /**
     * Disables the system's default handling of the stylus side button (e.g., selection menu).
     * This ensures the app receives the raw button events (MotionEvent.BUTTON_STYLUS_PRIMARY)
     * without interference or "Lasso" popups from the OS.
     */
    fun ignoreSystemSideButton(view: View) {
        try {
            // Method signature: public void setEnablePenSideButton(boolean enable)
            // Found on android.view.View in Onyx frameworks.
            val method: Method = View::class.java.getMethod("setEnablePenSideButton", Boolean::class.javaPrimitiveType)
            method.invoke(view, false)
            Logger.d(TAG, "Successfully disabled system side-button handling for view.")
        } catch (e: NoSuchMethodException) {
            Logger.w(TAG, "Device does not support setEnablePenSideButton (Not an Onyx device or API changed).")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to invoke setEnablePenSideButton", e)
        }
    }
}
