package com.example.notate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import com.example.notate.ui.dpToPx
import com.onyx.android.sdk.api.device.EpdDeviceManager

/**
 * Manages the toolbar's position, orientation, and EPD optimizations.
 * Simple Strategy: Always resets to top-left on screen rotation.
 */
class ToolbarCoordinator(
    private val context: Context,
    private val toolbarContainer: DraggableLinearLayout,
    private val rootView: View,
    private val onExclusionRectChanged: (List<Rect>) -> Unit,
) {
    private var currentOrientation = LinearLayout.HORIZONTAL
    private val verticalThresholdDp = 100
    private val horizontalThresholdDp = 160
    private val marginDp = 24

    private var lastParentWidth = 0
    private var lastParentHeight = 0

    var onOrientationChanged: (() -> Unit)? = null

    fun setup() {
        // 1. Monitor layout changes to detect rotation
        rootView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = rootView.width
                    val h = rootView.height

                    if (w > 0 && h > 0) {
                        if (w != lastParentWidth || h != lastParentHeight) {
                            lastParentWidth = w
                            lastParentHeight = h
                            resetToTopLeft()
                        }
                    }
                    updateExclusionRect()
                }
            },
        )

        // 2. Drag Logic
        toolbarContainer.onPositionChanged = { rawX, _ ->
            handleOrientationLogic(rawX)
        }

        toolbarContainer.onDragStart = {
            EpdDeviceManager.enterAnimationUpdate(true)
        }

        toolbarContainer.onDragEnd = {
            EpdDeviceManager.exitAnimationUpdate(true)
        }

        resetToTopLeft()
    }

    private fun resetToTopLeft() {
        val lp = toolbarContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val offset = context.dpToPx(marginDp)

        lp.leftMargin = offset
        lp.topMargin = offset
        toolbarContainer.layoutParams = lp
    }

    private fun handleOrientationLogic(rawX: Float) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val verticalThreshold = screenWidth - context.dpToPx(verticalThresholdDp)
        val horizontalThreshold = screenWidth - context.dpToPx(horizontalThresholdDp)

        if (currentOrientation == LinearLayout.HORIZONTAL) {
            if (rawX > verticalThreshold) {
                setOrientation(LinearLayout.VERTICAL)
            }
        } else {
            if (rawX < horizontalThreshold) {
                setOrientation(LinearLayout.HORIZONTAL)
            }
        }
        updateExclusionRect()
    }

    fun setOrientation(
        orientation: Int,
        force: Boolean = false,
    ) {
        if (currentOrientation != orientation || force) {
            val oldRight = toolbarContainer.right
            currentOrientation = orientation
            toolbarContainer.orientation = orientation
            onOrientationChanged?.invoke()

            // Adjust position based on target orientation
            toolbarContainer.post {
                val newWidth = toolbarContainer.width
                val lp = toolbarContainer.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    // If switching to VERTICAL (at right edge), pin the Right side.
                    // If switching to HORIZONTAL (at left edge), pin the Left side (default behavior).
                    if (orientation == LinearLayout.VERTICAL) {
                        lp.leftMargin = (oldRight - newWidth).coerceAtLeast(0)
                        toolbarContainer.layoutParams = lp
                    }
                }
            }
        }
    }

    fun getOrientation() = currentOrientation

    private fun updateExclusionRect() {
        onExclusionRectChanged(getRects())
    }

    fun getRects(): List<Rect> {
        val rect = Rect()
        toolbarContainer.getGlobalVisibleRect(rect)
        return listOf(rect)
    }
}
