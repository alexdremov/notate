package com.alexdremov.notate.ui.render.background

import android.graphics.RectF
import com.alexdremov.notate.model.BackgroundStyle

/**
 * Helper class to calculate the padded area and alignment offsets for background patterns.
 */
object PatternLayoutHelper {
    /**
     * Calculates the area where the pattern should be drawn, respecting paddings.
     */
    fun calculatePatternArea(
        pageRect: RectF,
        style: BackgroundStyle,
    ): RectF =
        RectF(
            pageRect.left + style.paddingLeft,
            pageRect.top + style.paddingTop,
            pageRect.right - style.paddingRight,
            pageRect.bottom - style.paddingBottom,
        )

    /**
     * Calculates the X and Y offsets to align the pattern correctly.
     * Handles horizontal centering if requested.
     */
    fun calculateOffsets(
        patternArea: RectF,
        style: BackgroundStyle,
        isInfinite: Boolean = false,
    ): Pair<Float, Float> {
        var offsetX = patternArea.left
        val offsetY = patternArea.top // Pattern always starts at top padding edge

        // Center only on fixed pages to maintain a stable grid on infinite canvases
        if (style.isCentered && !isInfinite) {
            val spacing = getSpacing(style)
            if (spacing > 0) {
                val availableWidth = patternArea.width()
                val remainder = availableWidth % spacing
                offsetX += remainder / 2f
            }
        }

        return Pair(offsetX, offsetY)
    }

    private fun getSpacing(style: BackgroundStyle): Float =
        when (style) {
            is BackgroundStyle.Dots -> style.spacing
            is BackgroundStyle.Lines -> style.spacing
            is BackgroundStyle.Grid -> style.spacing
            else -> 0f
        }
}
