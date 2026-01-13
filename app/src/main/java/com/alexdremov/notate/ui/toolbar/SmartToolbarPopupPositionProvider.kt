package com.alexdremov.notate.ui.toolbar

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

class SmartToolbarPopupPositionProvider(
    private val isHorizontal: Boolean,
    private val gap: Int = 20,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Center of the anchor (Toolbar Box)
        val anchorCenter = anchorBounds.center

        // Safety: Ensure window size is positive
        if (windowSize.width <= 0 || windowSize.height <= 0) return IntOffset.Zero

        if (isHorizontal) {
            // Horizontal Toolbar: Prefer showing BELOW, unless at bottom of screen.

            // Calculate candidates
            val yBelow = anchorBounds.bottom + gap
            val yAbove = anchorBounds.top - popupContentSize.height - gap

            // Center horizontally relative to Toolbar center
            var x = anchorCenter.x - (popupContentSize.width / 2)

            // Constraint X to window
            if (x < gap) x = gap
            if (x + popupContentSize.width > windowSize.width - gap) {
                x = windowSize.width - popupContentSize.width - gap
            }

            // Decide Y
            // If toolbar is in top 2/3 of screen, show below. Else above.
            val isTopHeavy = anchorBounds.top < (windowSize.height * 0.66)

            val y =
                if (isTopHeavy) {
                    // Try below first
                    if (yBelow + popupContentSize.height <= windowSize.height) yBelow else yAbove
                } else {
                    // Try above first
                    if (yAbove >= 0) yAbove else yBelow
                }

            return IntOffset(x, y)
        } else {
            // Vertical Toolbar: Prefer showing RIGHT (assuming left-handed UI default), or swap based on side.

            // If toolbar is on the LEFT side of screen, show RIGHT.
            // If toolbar is on the RIGHT side of screen, show LEFT.
            val isLeftSide = anchorCenter.x < windowSize.width / 2

            val xRight = anchorBounds.right + gap
            val xLeft = anchorBounds.left - popupContentSize.width - gap

            val x =
                if (isLeftSide) {
                    if (xRight + popupContentSize.width <= windowSize.width) xRight else xLeft
                } else {
                    if (xLeft >= 0) xLeft else xRight
                }

            // Center vertically relative to Toolbar center
            var y = anchorCenter.y - (popupContentSize.height / 2)

            // Constraint Y to window
            if (y < gap) y = gap
            if (y + popupContentSize.height > windowSize.height - gap) {
                y = windowSize.height - popupContentSize.height - gap
            }

            return IntOffset(x, y)
        }
    }
}
