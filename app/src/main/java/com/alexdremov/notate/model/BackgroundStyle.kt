package com.alexdremov.notate.model

import android.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
sealed class BackgroundStyle {
    abstract val color: Int // The color of the pattern (dots/lines)
    abstract val paddingTop: Float // Padding from top of the page (Fixed Page Mode)
    abstract val paddingBottom: Float // Padding from bottom of the page (Fixed Page Mode)
    abstract val paddingLeft: Float // Padding from left of the page (Fixed Page Mode)
    abstract val paddingRight: Float // Padding from right of the page (Fixed Page Mode)
    abstract val isCentered: Boolean // Whether to center the pattern horizontally (Fixed Page Mode)

    @Serializable
    data class Blank(
        override val color: Int = Color.TRANSPARENT,
        override val paddingTop: Float = 0f,
        override val paddingBottom: Float = 0f,
        override val paddingLeft: Float = 0f,
        override val paddingRight: Float = 0f,
        override val isCentered: Boolean = false,
    ) : BackgroundStyle()

    @Serializable
    data class Dots(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f, // World units
        val radius: Float = 2f,
        override val paddingTop: Float = 0f,
        override val paddingBottom: Float = 0f,
        override val paddingLeft: Float = 0f,
        override val paddingRight: Float = 0f,
        override val isCentered: Boolean = false,
    ) : BackgroundStyle()

    @Serializable
    data class Lines(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
        override val paddingTop: Float = 0f,
        override val paddingBottom: Float = 0f,
        override val paddingLeft: Float = 0f,
        override val paddingRight: Float = 0f,
        override val isCentered: Boolean = false,
    ) : BackgroundStyle()

    @Serializable
    data class Grid(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
        override val paddingTop: Float = 0f,
        override val paddingBottom: Float = 0f,
        override val paddingLeft: Float = 0f,
        override val paddingRight: Float = 0f,
        override val isCentered: Boolean = false,
    ) : BackgroundStyle()
}
