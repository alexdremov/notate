package com.alexdremov.notate.data

import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.math.abs
import kotlin.math.max

/**
 * Handles grouping of individual strokes into clusters (paragraphs/blocks)
 * and further segmenting those clusters into lines.
 */
object StrokeClusteringManager {
    /**
     * Groups strokes into high-level clusters based on spatial proximity.
     * Uses Connected Component Analysis (CCA) on a proximity graph.
     */
    fun clusterStrokes(
        strokes: List<Stroke>,
        thresholdMultiplier: Float = 2.0f,
    ): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()

        // 1. Build adjacency list for proximity graph
        val adjacency = Array(strokes.size) { mutableListOf<Int>() }

        for (i in strokes.indices) {
            val s1 = strokes[i]
            val s1BoundsExpanded = RectF(s1.bounds)
            // Adaptive threshold based on stroke height
            val threshold = s1.bounds.height() * thresholdMultiplier
            s1BoundsExpanded.inset(-threshold, -threshold)

            for (j in i + 1 until strokes.size) {
                val s2 = strokes[j]
                if (RectF.intersects(s1BoundsExpanded, s2.bounds)) {
                    adjacency[i].add(j)
                    adjacency[j].add(i)
                }
            }
        }

        // 2. Find Connected Components using BFS
        val clusters = ArrayList<List<Stroke>>()
        val visited = BooleanArray(strokes.size)

        for (i in strokes.indices) {
            if (!visited[i]) {
                val component = mutableListOf<Stroke>()
                val queue: Queue<Int> = LinkedList()
                queue.add(i)
                visited[i] = true

                while (queue.isNotEmpty()) {
                    val current = queue.poll()!!
                    component.add(strokes[current])
                    for (neighbor in adjacency[current]) {
                        if (!visited[neighbor]) {
                            visited[neighbor] = true
                            queue.add(neighbor)
                        }
                    }
                }
                clusters.add(component)
            }
        }

        return clusters
    }

    /**
     * Splits a cluster of strokes into individual horizontal lines.
     * Uses a left-to-right sweep with an Exponential Moving Average (EMA)
     * to track sloped baselines, and restores original temporal order at the end.
     */
    fun segmentIntoLines(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()

        class IndexedStroke(
            val stroke: Stroke,
            val originalIndex: Int,
        )
        val indexedStrokes = strokes.mapIndexed { index, stroke -> IndexedStroke(stroke, index) }

        val heights = strokes.map { it.bounds.height() }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(5f)

        val sweepSorted = indexedStrokes.sortedBy { it.stroke.bounds.left }

        class TextLine(
            firstItem: IndexedStroke,
        ) {
            val items = mutableListOf(firstItem)
            var localCenterY: Float = firstItem.stroke.bounds.centerY()
            var localHeight: Float = max(firstItem.stroke.bounds.height(), medianHeight * 0.5f)

            fun add(item: IndexedStroke) {
                items.add(item)
                val h = item.stroke.bounds.height()
                if (h > medianHeight * 0.3f) {
                    localCenterY = (localCenterY * 0.7f) + (item.stroke.bounds.centerY() * 0.3f)
                    localHeight = (localHeight * 0.8f) + (h * 0.2f)
                }
            }
        }

        val lines = mutableListOf<TextLine>()

        // 1. Initial Left-to-Right Sweep
        for (item in sweepSorted) {
            val strokeCenterY = item.stroke.bounds.centerY()
            var bestLine: TextLine? = null
            var minDistance = Float.MAX_VALUE

            for (line in lines) {
                val distance = abs(strokeCenterY - line.localCenterY)
                // Tighten threshold: 0.5x line height or median height
                // This prevents merging a line with its neighbor below.
                val threshold = max(line.localHeight * 0.8f, medianHeight)

                if (distance < threshold && distance < minDistance) {
                    bestLine = line
                    minDistance = distance
                }
            }

            if (bestLine != null) {
                bestLine.add(item)
            } else {
                lines.add(TextLine(item))
            }
        }

        // 2. NEW: Orphan Absorption Pass (Diacritics, dots, commas)
        val mainLines = mutableListOf<TextLine>()
        val orphanLines = mutableListOf<TextLine>()

        // Separate tiny isolated strokes from actual text lines
        for (line in lines) {
            val firstBounds =
                line.items
                    .first()
                    .stroke.bounds
            val isOrphan =
                line.items.size == 1 &&
                    firstBounds.height() < medianHeight * 0.6f &&
                    firstBounds.width() < medianHeight * 0.6f

            if (isOrphan) {
                orphanLines.add(line)
            } else {
                mainLines.add(line)
            }
        }

        // Attach orphans to the main line that encompasses their X-coordinates
        for (orphan in orphanLines) {
            val orphanItem = orphan.items.first()
            val orphanCenterY = orphanItem.stroke.bounds.centerY()
            val orphanCenterX = orphanItem.stroke.bounds.centerX()

            var bestMainLine: TextLine? = null
            var minDistanceY = Float.MAX_VALUE

            for (mainLine in mainLines) {
                val lineLeft = mainLine.items.minOf { it.stroke.bounds.left }
                val lineRight = mainLine.items.maxOf { it.stroke.bounds.right }

                // Check if the dot is horizontally positioned over/under this line
                // (allowing a small padding margin)
                val padding = medianHeight * 0.5f
                if (orphanCenterX in (lineLeft - padding)..(lineRight + padding)) {
                    val distanceY = abs(orphanCenterY - mainLine.localCenterY)
                    if (distanceY < minDistanceY) {
                        minDistanceY = distanceY
                        bestMainLine = mainLine
                    }
                }
            }

            if (bestMainLine != null) {
                bestMainLine.add(orphanItem)
            } else {
                // If it truly floats nowhere near text, keep it as an isolated line
                mainLines.add(orphan)
            }
        }

        // 3. Refinement Pass (Merge heavily overlapping main lines)
        val mergedLines = mutableListOf<MutableList<IndexedStroke>>()
        val sortedLines = mainLines.sortedBy { it.items.map { s -> s.stroke.bounds.centerY() }.average() }

        for (line in sortedLines) {
            val currentLineItems = line.items
            val currentAvgY = currentLineItems.map { it.stroke.bounds.centerY() }.average()

            val lastMerged = mergedLines.lastOrNull()
            if (lastMerged != null) {
                val lastAvgY = lastMerged.map { it.stroke.bounds.centerY() }.average()
                // Only merge if they are very close vertically (likely fragments of the same line)
                if (abs(currentAvgY - lastAvgY) < medianHeight * 0.6f) {
                    lastMerged.addAll(currentLineItems)
                    continue
                }
            }
            mergedLines.add(currentLineItems.toMutableList())
        }

        // 4. Final Pass: Horizontal Splitting
        // Prevents extremely long mega-lines on infinite canvas by splitting at large horizontal gaps.
        val finalResultLines = mutableListOf<List<Stroke>>()

        for (lineIndexedStrokes in mergedLines) {
            val sortedStrokes = lineIndexedStrokes.sortedBy { it.stroke.bounds.left }
            if (sortedStrokes.isEmpty()) continue

            // Refined sub-line logic
            val subLines = mutableListOf<MutableList<IndexedStroke>>()
            var activeSubLine = mutableListOf<IndexedStroke>()
            subLines.add(activeSubLine)

            for (i in sortedStrokes.indices) {
                val current = sortedStrokes[i]
                if (i == 0) {
                    activeSubLine.add(current)
                    continue
                }

                val prev = sortedStrokes[i - 1]
                val gap = current.stroke.bounds.left - prev.stroke.bounds.right

                // Threshold: If the horizontal gap is > 4.5x the median line height,
                // it's likely a separate logical block on the same vertical plane.
                val horizontalGapThreshold = medianHeight * 4.5f

                if (gap > horizontalGapThreshold) {
                    activeSubLine = mutableListOf<IndexedStroke>()
                    subLines.add(activeSubLine)
                }
                activeSubLine.add(current)
            }

            for (subLine in subLines) {
                if (subLine.isNotEmpty()) {
                    finalResultLines.add(
                        restoreNaturalOrder(
                            subLine.sortedBy { it.originalIndex }.map { it.stroke },
                        ),
                    )
                }
            }
        }

        // 5. Final Sort: Restore to Top-to-Bottom sequence
        return finalResultLines
            .sortedBy { lineStrokes -> lineStrokes.map { it.bounds.centerY() }.average() }
    }

    /**
     * Restores the most probable natural reading order for a line of strokes.
     * Overrides corrupted temporal order (due to erasures or late corrections)
     * with a spatial left-to-right sequence.
     */
    fun restoreNaturalOrder(lineStrokes: List<Stroke>): List<Stroke> {
        if (lineStrokes.size <= 1) return lineStrokes

        // Primary sort: Left-to-Right progression.
        // Secondary sort: Top-to-Bottom to handle vertically stacked strokes logically
        // (e.g., the top and bottom bars of an '=' sign, or an 'i' dot and its stem
        // if they happen to share the exact same left coordinate).
        return lineStrokes.sortedWith(
            compareBy(
                { it.bounds.left },
                { it.bounds.top },
            ),
        )
    }
}
