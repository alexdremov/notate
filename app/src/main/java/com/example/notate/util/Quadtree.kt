package com.example.notate.util

import android.graphics.RectF
import com.example.notate.model.Stroke
import java.util.ArrayList

class Quadtree(
    private var level: Int,
    private val bounds: RectF,
) {
    companion object {
        private const val MAX_OBJECTS = 10
        private const val MAX_LEVELS = 20 // Increased for infinite expansion
    }

    private val strokes = ArrayList<Stroke>()
    private val nodes = arrayOfNulls<Quadtree>(4)

    fun getBounds(): RectF = bounds

    fun clear() {
        strokes.clear()
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
     * Inserts a stroke into the Quadtree.
     * @return The root of the tree (which might be a new parent if grown).
     */
    fun insert(stroke: Stroke): Quadtree {
        // If stroke is outside current root bounds, grow upwards
        if (!bounds.contains(stroke.bounds)) {
            return grow(stroke.bounds).insert(stroke)
        }

        insertInternal(stroke)
        return this
    }

    private fun grow(target: RectF): Quadtree {
        // Determine direction to grow based on where the target is
        // We double the size in the direction of the target
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        // Simple heuristic: If mostly Left/Top, grow Left/Top.
        val growRight = target.centerX() > centerX
        val growBottom = target.centerY() > centerY

        val newWidth = bounds.width() * 2
        val newHeight = bounds.height() * 2

        val newX = if (growRight) bounds.left else bounds.left - bounds.width()
        val newY = if (growBottom) bounds.top else bounds.top - bounds.height()

        val newBounds = RectF(newX, newY, newX + newWidth, newY + newHeight)
        val newRoot = Quadtree(level - 1, newBounds) // New root is one level higher (lower number)

        // Force split to create children
        newRoot.split()

        // Determine which child 'this' becomes
        // If we grew Right (expanded East), 'this' was West (Left).
        // If we grew Bottom (expanded South), 'this' was North (Top).
        // 0: NW, 1: NE, 2: SW, 3: SE

        // If we grew Right, we kept Left. 'this' is Left.
        // If we grew Bottom, we kept Top. 'this' is Top.
        // Left-Top = 0

        val childIndex =
            when {
                growRight && growBottom -> 0

                // We expanded East and South. 'this' is NW.
                !growRight && growBottom -> 1

                // We expanded West and South. 'this' is NE.
                growRight && !growBottom -> 2

                // We expanded East and North. 'this' is SW.
                else -> 3 // We expanded West and North. 'this' is SE.
            }

        // Replace the empty child with 'this'
        // We must update 'this' level to match the new depth relative to root?
        // Actually, if we use relative depth, 'this' level is fine if we just increment it?
        // But 'this' is immutable mostly.
        // Let's just slot it in. The 'level' param is mostly for split depth limit.
        // If we construct 'newRoot' with level-1, then 'this' (at level) is consistent.

        newRoot.nodes[childIndex] = this
        this.level = newRoot.level + 1 // Maintain level consistency

        return newRoot
    }

    private fun insertInternal(stroke: Stroke) {
        if (nodes[0] != null) {
            val index = getIndex(stroke.bounds)
            if (index != -1) {
                nodes[index]?.insertInternal(stroke)
                return
            }
        }

        strokes.add(stroke)

        if (strokes.size > MAX_OBJECTS && level < MAX_LEVELS) {
            if (nodes[0] == null) {
                split()
            }

            var i = 0
            while (i < strokes.size) {
                val existingStroke = strokes[i]
                val index = getIndex(existingStroke.bounds)
                if (index != -1) {
                    strokes.removeAt(i)
                    nodes[index]?.insertInternal(existingStroke)
                } else {
                    i++
                }
            }
        }
    }

    fun retrieve(
        returnObjects: ArrayList<Stroke>,
        viewport: RectF,
    ) {
        // Optimization: If viewport doesn't intersect this node, abort
        if (!RectF.intersects(bounds, viewport)) {
            return
        }

        val index = getIndex(viewport)
        if (index != -1 && nodes[0] != null) {
            nodes[index]?.retrieve(returnObjects, viewport)
        } else if (nodes[0] != null) {
            // Viewport overlaps multiple quadrants, search all intersecting nodes
            for (i in nodes.indices) {
                if (nodes[i] != null && RectF.intersects(nodes[i]!!.bounds, viewport)) {
                    nodes[i]?.retrieve(returnObjects, viewport)
                }
            }
        }

        for (stroke in strokes) {
            if (RectF.intersects(stroke.bounds, viewport)) {
                returnObjects.add(stroke)
            }
        }
    }

    /**
     * Removes a stroke from the Quadtree.
     * @return true if the stroke was found and removed, false otherwise.
     */
    fun remove(stroke: Stroke): Boolean {
        // 1. Check if it's in this node's local list
        // We use the exact object reference for removal
        if (strokes.remove(stroke)) {
            return true
        }

        // 2. If not in local list, check appropriate child node
        if (nodes[0] != null) {
            val index = getIndex(stroke.bounds)
            if (index != -1) {
                return nodes[index]?.remove(stroke) == true
            }
        }

        // If index is -1, it means the stroke doesn't fit into any quadrant
        // completely. In that case, it MUST be in 'strokes' list (per insertion logic).
        // Since we already checked 'strokes' and didn't find it, it's not in this branch.
        return false
    }
}
