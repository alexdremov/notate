package com.alexdremov.notate.model

import android.graphics.Path

sealed interface RenderCache

data class BallpointCache(
    val segments: List<BallpointSegment>,
) : RenderCache {
    data class BallpointSegment(
        val path: Path,
        val width: Float,
        val alpha: Int,
    )
}

data class CharcoalCache(
    val verts: FloatArray,
    val colors: IntArray,
    val outlinePath: Path,
) : RenderCache {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CharcoalCache

        if (!verts.contentEquals(other.verts)) return false
        if (!colors.contentEquals(other.colors)) return false
        // Path does not implement equals/hashCode, so we can't reliably compare it.
        // Identity check might be enough or we skip it for data equality.
        // For caching purposes, if the object is the same, it's fine.
        return true
    }

    override fun hashCode(): Int {
        var result = verts.contentHashCode()
        result = 31 * result + colors.contentHashCode()
        // Path skipped
        return result
    }
}

data class FountainCache(
    val path: Path,
) : RenderCache
