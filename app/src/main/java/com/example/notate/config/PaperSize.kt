package com.example.notate.config

enum class Orientation {
    PORTRAIT,
    LANDSCAPE,
}

enum class PaperSize(
    val displayName: String,
    val width: Float,
    val height: Float,
) {
    A3("A3", 3508f, 4961f),
    A4("A4", 2480f, 3508f),
    A5("A5", 1748f, 2480f),
    SCREEN("Screen", 0f, 0f), // Dynamic
    ;

    fun getDimensions(
        orientation: Orientation,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f,
    ): Pair<Float, Float> =
        if (this == SCREEN) {
            if (orientation == Orientation.PORTRAIT) {
                // Assuming device is held in portrait
                minOf(screenWidth, screenHeight) to maxOf(screenWidth, screenHeight)
            } else {
                maxOf(screenWidth, screenHeight) to minOf(screenWidth, screenHeight)
            }
        } else {
            if (orientation == Orientation.PORTRAIT) {
                minOf(width, height) to maxOf(width, height)
            } else {
                maxOf(width, height) to minOf(width, height)
            }
        }

    private fun minOf(
        a: Float,
        b: Float,
    ) = if (a < b) a else b

    private fun maxOf(
        a: Float,
        b: Float,
    ) = if (a > b) a else b
}
