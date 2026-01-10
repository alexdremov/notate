package com.alexdremov.notate.model

import android.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
sealed class BackgroundStyle {
    abstract val color: Int // The color of the pattern (dots/lines)

    @Serializable
    data class Blank(
        override val color: Int = Color.TRANSPARENT, // Not used
    ) : BackgroundStyle()

    @Serializable
    data class Dots(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f, // World units
        val radius: Float = 2f,
    ) : BackgroundStyle()

    @Serializable
    data class Lines(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
    ) : BackgroundStyle()

    @Serializable
    data class Grid(
        override val color: Int = Color.LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
    ) : BackgroundStyle()
}
