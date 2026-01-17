package com.alexdremov.notate.util

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import java.util.ArrayList

/**
 * A spatial data structure for efficient 2D spatial queries on canvas items.
 *
 * The Quadtree recursively subdivides space into four quadrants, allowing
 * O(log N) queries for items intersecting a given viewport rectangle.
 * This is critical for rendering performance on infinite canvases with
 * thousands of strokes.
 *
 * ## Features
 * - **Auto-Growth**: Automatically expands bounds when items are inserted outside
 * - **Max Depth**: Limited to [MAX_LEVELS] to prevent pathological cases
 * - **Thread Safety**: External synchronization required (see [InfiniteCanvasModel])
 *
 * ## Usage
 * ```kotlin
 * val quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))
 * quadtree = quadtree.insert(stroke) // Note: may return new root
 *
 * val visible = ArrayList<CanvasItem>()
 * quadtree.retrieve(visible, viewportRect)
 * ```
 *
 * @param level The depth level of this node (0 = root)
 * @param bounds The rectangular region this node covers
 */
class Quadtree(
    private var level: Int,
    private val bounds: RectF,
) {
    companion object {
        private const val MAX_OBJECTS = 10
        private const val MAX_LEVELS = 20 // Increased for infinite expansion
    }

    private val items = ArrayList<CanvasItem>()
    private val nodes = arrayOfNulls<Quadtree>(4)

    fun getBounds(): RectF = bounds

    fun clear() {
        items.clear()
        for (i in nodes.indices) {
            nodes[i]?.clear()
            nodes[i] = null
        }
    }

    private fun split() {
        val subWidth = bounds.width() / 2
        val subHeight = bounds.height() / 2
        val x = bounds.left
        val y = bounds.top

        nodes[0] = Quadtree(level + 1, RectF(x, y, x + subWidth, y + subHeight))
        nodes[1] = Quadtree(level + 1, RectF(x + subWidth, y, x + subWidth * 2, y + subHeight))
        nodes[2] = Quadtree(level + 1, RectF(x, y + subHeight, x + subWidth, y + subHeight * 2))
        nodes[3] = Quadtree(level + 1, RectF(x + subWidth, y + subHeight, x + subWidth * 2, y + subHeight * 2))
    }

    private fun getIndex(pRect: RectF): Int {
        var index = -1
        val verticalMidpoint = bounds.left + bounds.width() / 2
        val horizontalMidpoint = bounds.top + bounds.height() / 2

        val topQuadrant = pRect.top < horizontalMidpoint && pRect.bottom < horizontalMidpoint
        val bottomQuadrant = pRect.top > horizontalMidpoint

        if (pRect.left < verticalMidpoint && pRect.right < verticalMidpoint) {
            if (topQuadrant) {
                index = 0
            } else if (bottomQuadrant) {
                index = 2
            }
        } else if (pRect.left > verticalMidpoint) {
            if (topQuadrant) {
                index = 1
            } else if (bottomQuadrant) {
                index = 3
            }
        }

        return index
    }

    /**
     * Inserts an item into the Quadtree.
     * @return The root of the tree (which might be a new parent if grown).
     */
    fun insert(item: CanvasItem): Quadtree {
        // If item is outside current root bounds, grow upwards
        if (!bounds.contains(item.bounds)) {
            return grow(item.bounds).insert(item)
        }

        insertInternal(item)
        return this
    }

    private fun grow(target: RectF): Quadtree {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        val growRight = target.centerX() > centerX
        val growBottom = target.centerY() > centerY

        val newWidth = bounds.width() * 2
        val newHeight = bounds.height() * 2

        val newX = if (growRight) bounds.left else bounds.left - bounds.width()
        val newY = if (growBottom) bounds.top else bounds.top - bounds.height()

        val newBounds = RectF(newX, newY, newX + newWidth, newY + newHeight)
        val newRoot = Quadtree(level - 1, newBounds)

        newRoot.split()

        val childIndex =
            when {
                growRight && growBottom -> 0
                !growRight && growBottom -> 1
                growRight && !growBottom -> 2
                else -> 3
            }

        newRoot.nodes[childIndex] = this
        this.level = newRoot.level + 1

        return newRoot
    }

    private fun insertInternal(item: CanvasItem) {
        if (nodes[0] != null) {
            val index = getIndex(item.bounds)
            if (index != -1) {
                nodes[index]?.insertInternal(item)
                return
            }
        }

        items.add(item)

        if (items.size > MAX_OBJECTS && level < MAX_LEVELS) {
            if (nodes[0] == null) {
                split()
            }

            var i = 0
            while (i < items.size) {
                val existingItem = items[i]
                val index = getIndex(existingItem.bounds)
                if (index != -1) {
                    items.removeAt(i)
                    nodes[index]?.insertInternal(existingItem)
                } else {
                    i++
                }
            }
        }
    }

    fun retrieve(
        returnObjects: ArrayList<CanvasItem>,
        viewport: RectF,
    ) {
        if (!RectF.intersects(bounds, viewport)) {
            return
        }

        val index = getIndex(viewport)
        if (index != -1 && nodes[0] != null) {
            nodes[index]?.retrieve(returnObjects, viewport)
        } else if (nodes[0] != null) {
            for (i in nodes.indices) {
                if (nodes[i] != null && RectF.intersects(nodes[i]!!.bounds, viewport)) {
                    nodes[i]?.retrieve(returnObjects, viewport)
                }
            }
        }

        for (item in items) {
            if (RectF.intersects(item.bounds, viewport)) {
                returnObjects.add(item)
            }
        }
    }

    fun hitTest(
        x: Float,
        y: Float,
        tolerance: Float,
    ): CanvasItem? {
        val searchRect = RectF(x - tolerance, y - tolerance, x + tolerance, y + tolerance)
        val potentialItems = ArrayList<CanvasItem>()
        retrieve(potentialItems, searchRect)

        var closestItem: CanvasItem? = null
        var minDistance = Float.MAX_VALUE

        for (item in potentialItems) {
            val dist = item.distanceToPoint(x, y)

            if (dist <= tolerance) {
                if (dist < minDistance) {
                    minDistance = dist
                    closestItem = item
                } else if (dist == minDistance) {
                    // Tie-breaker: Prefer higher Z-index, then higher order (newer/topmost)
                    if (closestItem != null) {
                        if (item.zIndex > closestItem.zIndex) {
                            closestItem = item
                        } else if (item.zIndex == closestItem.zIndex && item.order > closestItem.order) {
                            closestItem = item
                        }
                    } else {
                        closestItem = item
                    }
                }
            }
        }
        return closestItem
    }

    /**
     * Removes an item from the Quadtree.
     * @return true if the item was found and removed, false otherwise.
     */
    fun remove(item: CanvasItem): Boolean {
        if (items.remove(item)) {
            return true
        }

        if (nodes[0] != null) {
            val index = getIndex(item.bounds)
            if (index != -1) {
                return nodes[index]?.remove(item) == true
            }
        }
        return false
    }
}
