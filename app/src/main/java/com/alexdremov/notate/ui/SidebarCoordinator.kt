package com.alexdremov.notate.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Manages the animation, visibility, and back-press handling for the side drawer.
 */
class SidebarCoordinator(
    private val activity: ComponentActivity,
    private val container: ViewGroup,
    private val scrim: View,
) {
    private var _isOpen = false
    val isOpen: Boolean get() = _isOpen

    var onStateChanged: (() -> Unit)? = null

    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                close()
            }
        }

    init {
        // Register back callback. It is enabled/disabled based on sidebar state.
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        // Initial Layout Setup: Move sidebar off-screen to the right
        container.post {
            container.translationX = container.width.toFloat()
        }

        scrim.setOnClickListener {
            close()
        }
    }

    fun open() {
        if (_isOpen) return
        _isOpen = true
        backCallback.isEnabled = true

        com.alexdremov.notate.util.EpdFastModeController
            .enterFastMode()

        scrim.visibility = View.VISIBLE
        container
            .animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction {
                onStateChanged?.invoke()
            }.start()
        onStateChanged?.invoke()
    }

    fun close() {
        if (!_isOpen) return
        _isOpen = false
        backCallback.isEnabled = false

        com.alexdremov.notate.util.EpdFastModeController
            .enterFastMode()

        val width = container.width.toFloat()
        container
            .animate()
            .translationX(width)
            .setDuration(300)
            .withEndAction {
                scrim.visibility = View.GONE
                com.alexdremov.notate.util.EpdFastModeController
                    .exitFastMode()
                onStateChanged?.invoke()
            }.start()
    }
}
