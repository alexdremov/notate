package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.util.Quadtree
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import java.util.ArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * The core data model for the infinite canvas application.
 * Manages stroke storage, spatial indexing (Quadtree), undo/redo history, and persistence.
 * All public methods are thread-safe, utilizing a ReentrantReadWriteLock to allow concurrent
 * reads (rendering) while ensuring exclusive writes (drawing/erasing).
 */
class InfiniteCanvasModel {
    // Master list for persistence
    private val allStrokes = ArrayList<Stroke>()

    // Spatial Index for Rendering
    private var quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))

    private val contentBounds = RectF()

    private val rwLock = ReentrantReadWriteLock()

    private var nextStrokeOrder: Long = 0

    // History Manager
    private val historyManager =
        HistoryManager(
            object : HistoryManager.StrokeExecutor {
                override fun execute(action: HistoryAction) = executeAction(action)

                override fun revert(action: HistoryAction) = revertAction(action)

                override fun calculateBounds(action: HistoryAction) = calculateActionBounds(action)
            },
        )

    // --- Page / Canvas Config ---
    var canvasType: CanvasType = CanvasType.INFINITE
        private set
    var pageWidth: Float = CanvasConfig.PAGE_A4_WIDTH
        private set
    var pageHeight: Float = CanvasConfig.PAGE_A4_HEIGHT
        private set
    var backgroundStyle: BackgroundStyle = BackgroundStyle.Blank()
        private set

    // --- Viewport State (Persistence Only) ---
    var viewportScale: Float = 1.0f
    var viewportOffsetX: Float = 0f
    var viewportOffsetY: Float = 0f

    /**
     * Updates the background style of the canvas.
     * Thread-safe (Write Lock).
     */
    fun setBackground(style: BackgroundStyle) {
        rwLock.write {
            backgroundStyle = style
        }
    }

    /**
     * Starts a batch session for history tracking.
     * Useful for grouping multiple small changes (e.g., continuous eraser strokes) into a single undo step.
     * Thread-safe (Write Lock).
     */
    fun startBatchSession() {
        rwLock.write {
            historyManager.startBatchSession()
        }
    }

    /**
     * Ends the current batch session.
     * Thread-safe (Write Lock).
     */
    fun endBatchSession() {
        rwLock.write {
            historyManager.endBatchSession()
        }
    }

    /**
     * Adds a new stroke to the model.
     * - Validates stroke against page bounds if in Fixed Page mode.
     * - Assigns a unique stroke order.
     * - Updates spatial index and history.
     *
     * @param stroke The stroke to add.
     * @return True if the stroke was added, false if it was rejected (e.g., out of bounds).
     */
    fun addStroke(stroke: Stroke): Stroke? {
        // Enforce Fixed Page horizontal bounds
        if (canvasType == CanvasType.FIXED_PAGES) {
            if (stroke.bounds.right < 0 || stroke.bounds.left > pageWidth) {
                return null
            }
        }

        var addedStroke: Stroke? = null
        rwLock.write {
            val orderedStroke = stroke.copy(strokeOrder = nextStrokeOrder++)
            historyManager.applyAction(HistoryAction.Add(listOf(orderedStroke)))
            addedStroke = orderedStroke
        }
        return addedStroke
    }

    /**
     * Performs erasure on the canvas based on the provided eraser stroke and type.
     *
     * Logic:
     * 1. **Phase 1 (Read Lock)**: Identifies candidate strokes using the Quadtree.
     *    Performs complex geometric intersection checks (Stroke vs Stroke, Point in Polygon).
     *    Calculates necessary changes (Remove, Split, Replace).
     * 2. **Phase 2 (Write Lock)**: Applies the calculated actions to the model and history.
     *    This two-phase approach minimizes the time the write lock is held.
     *
     * @param eraserStroke The path/shape of the eraser.
     * @param type The type of eraser (STROKE, LASSO, STANDARD).
     * @return The bounding box of the invalidated area (where changes occurred), or null if no changes.
     */
    fun erase(
        eraserStroke: Stroke,
        type: EraserType,
    ): RectF? {
        var invalidatedBounds: RectF? = null

        // Phase 1: Read-Only Calculation (Expensive geometry)
        val actionsToApply = ArrayList<HistoryAction>()
        var boundsToInvalidate = RectF()

        rwLock.read {
            val candidates = ArrayList<Stroke>()
            val searchBounds = RectF(eraserStroke.bounds)
            // Add extra padding to ensure we find all strokes that might intersect.
            // Our strokes now have expansion + 5px padding.
            searchBounds.inset(-(eraserStroke.width + 5f), -(eraserStroke.width + 5f))
            quadtree.retrieve(candidates, searchBounds)

            if (candidates.isEmpty()) return@read

            when (type) {
                EraserType.STROKE -> {
                    // "Stroke Eraser": Deletes entire strokes touched by the eraser.
                    val toRemove =
                        candidates.filter { stroke ->
                            RectF.intersects(stroke.bounds, eraserStroke.bounds) &&
                                StrokeGeometry.strokeIntersects(stroke, eraserStroke)
                        }
                    if (toRemove.isNotEmpty()) {
                        actionsToApply.add(HistoryAction.Remove(toRemove))
                        boundsToInvalidate.union(calculateBounds(toRemove))
                    }
                }

                EraserType.LASSO -> {
                    // "Lasso Eraser": Deletes strokes strictly fully contained within the lasso loop.
                    val toRemove =
                        candidates.filter { stroke ->
                            if (!eraserStroke.bounds.contains(stroke.bounds)) return@filter false
                            stroke.points.all { p ->
                                StrokeGeometry.isPointInPolygon(p.x, p.y, eraserStroke.points)
                            }
                        }
                    if (toRemove.isNotEmpty()) {
                        actionsToApply.add(HistoryAction.Remove(toRemove))
                        boundsToInvalidate.union(calculateBounds(toRemove))
                    }
                }

                EraserType.STANDARD -> {
                    // "Standard Eraser": Physically cuts strokes (Point Eraser).
                    val removed = ArrayList<Stroke>()
                    val added = ArrayList<Stroke>()

                    candidates.forEach { target ->
                        if (RectF.intersects(target.bounds, eraserStroke.bounds)) {
                            val newParts = StrokeGeometry.splitStroke(target, eraserStroke)
                            // If split occurred or points removed, replace original with parts
                            if (newParts.size != 1 || newParts[0] !== target) {
                                removed.add(target)
                                added.addAll(newParts)
                            }
                        }
                    }

                    if (removed.isNotEmpty()) {
                        actionsToApply.add(HistoryAction.Replace(removed, added))
                        boundsToInvalidate.union(calculateBounds(removed))
                        boundsToInvalidate.union(calculateBounds(added))
                    }
                }
            }
        }

        // Phase 2: Write (Fast application of pre-calculated actions)
        if (actionsToApply.isNotEmpty()) {
            rwLock.write {
                actionsToApply.forEach { action ->
                    val finalAction =
                        if (action is HistoryAction.Replace) {
                            // Assign new IDs/Order to newly created sub-strokes
                            val orderedAdded = action.added.map { it.copy(strokeOrder = nextStrokeOrder++) }
                            HistoryAction.Replace(action.removed, orderedAdded)
                        } else {
                            action
                        }

                    historyManager.applyAction(finalAction)
                }
                invalidatedBounds = boundsToInvalidate
            }
        }

        return invalidatedBounds
    }

    fun deleteStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        rwLock.write {
            historyManager.applyAction(HistoryAction.Remove(strokes))
        }
    }

    private fun executeAction(
        action: HistoryAction,
        recalculateBounds: Boolean = true,
    ) {
        when (action) {
            is HistoryAction.Add -> {
                action.strokes.forEach { s ->
                    allStrokes.add(s)
                    quadtree = quadtree.insert(s)
                    updateContentBounds(s.bounds)
                }
            }

            is HistoryAction.Remove -> {
                action.strokes.forEach { s ->
                    allStrokes.remove(s)
                    quadtree.remove(s)
                }
                if (recalculateBounds) recalculateContentBounds()
            }

            is HistoryAction.Replace -> {
                action.removed.forEach { s ->
                    allStrokes.remove(s)
                    quadtree.remove(s)
                }
                action.added.forEach { s ->
                    allStrokes.add(s)
                    quadtree = quadtree.insert(s)
                    updateContentBounds(s.bounds)
                }
                if (recalculateBounds) recalculateContentBounds()
            }

            is HistoryAction.Batch -> {
                action.actions.forEach { executeAction(it, false) }
                if (recalculateBounds) recalculateContentBounds()
            }
        }
    }

    private fun revertAction(
        action: HistoryAction,
        recalculateBounds: Boolean = true,
    ) {
        when (action) {
            is HistoryAction.Add -> {
                action.strokes.forEach { s ->
                    allStrokes.remove(s)
                    quadtree.remove(s)
                }
                if (recalculateBounds) recalculateContentBounds()
            }

            is HistoryAction.Remove -> {
                action.strokes.forEach { s ->
                    allStrokes.add(s)
                    quadtree = quadtree.insert(s)
                    updateContentBounds(s.bounds)
                }
            }

            is HistoryAction.Replace -> {
                action.added.forEach { s ->
                    allStrokes.remove(s)
                    quadtree.remove(s)
                }
                action.removed.forEach { s ->
                    allStrokes.add(s)
                    quadtree = quadtree.insert(s)
                    updateContentBounds(s.bounds)
                }
                if (recalculateBounds) recalculateContentBounds()
            }

            is HistoryAction.Batch -> {
                action.actions.asReversed().forEach { revertAction(it, false) }
                if (recalculateBounds) recalculateContentBounds()
            }
        }
    }

    /**
     * Undoes the last action.
     * @return The bounding box of the changed area, or null if nothing to undo.
     */
    fun undo(): RectF? =
        rwLock.write {
            historyManager.undo()
        }

    /**
     * Redoes the last undone action.
     * @return The bounding box of the changed area, or null if nothing to redo.
     */
    fun redo(): RectF? =
        rwLock.write {
            historyManager.redo()
        }

    private fun calculateActionBounds(action: HistoryAction): RectF =
        when (action) {
            is HistoryAction.Add -> {
                calculateBounds(action.strokes)
            }

            is HistoryAction.Remove -> {
                calculateBounds(action.strokes)
            }

            is HistoryAction.Replace -> {
                calculateBounds(action.removed).apply { union(calculateBounds(action.added)) }
            }

            is HistoryAction.Batch -> {
                val r = RectF()
                action.actions.forEach {
                    r.union(calculateActionBounds(it))
                }
                r
            }
        }

    /**
     * Clears the entire canvas model, history, and spatial index.
     * Thread-safe (Write Lock).
     */
    fun clear() {
        rwLock.write {
            allStrokes.clear()
            historyManager.clear()
            quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))
            contentBounds.setEmpty()
            nextStrokeOrder = 0
        }
    }

    fun getContentBounds(): RectF = RectF(contentBounds)

    /**
     * Executes a read-only operation on the full stroke list.
     * Thread-safe (Read Lock).
     */
    fun performRead(block: (List<Stroke>) -> Unit) {
        rwLock.read {
            block(allStrokes)
        }
    }

    /**
     * Queries the spatial index for strokes intersecting the given rectangle.
     * Thread-safe (Read Lock).
     * @param rect The query bounds.
     * @return A list of matching strokes.
     */
    fun queryStrokes(rect: RectF): ArrayList<Stroke> {
        val result = ArrayList<Stroke>()
        rwLock.read {
            quadtree.retrieve(result, rect)
        }
        return result
    }

    // --- Helpers ---
    private fun updateContentBounds(bounds: RectF) {
        if (contentBounds.isEmpty) {
            contentBounds.set(bounds)
        } else {
            contentBounds.union(bounds)
        }
    }

    private fun recalculateContentBounds() {
        contentBounds.setEmpty()
        allStrokes.forEach { stroke ->
            if (contentBounds.isEmpty) {
                contentBounds.set(stroke.bounds)
            } else {
                contentBounds.union(stroke.bounds)
            }
        }
    }

    private fun calculateBounds(strokes: List<Stroke>): RectF {
        val r = RectF()
        if (strokes.isNotEmpty()) {
            r.set(strokes[0].bounds)
            for (i in 1 until strokes.size) {
                r.union(strokes[i].bounds)
            }
        }
        return r
    }

    /**
     * Serializes the current model state into a CanvasData object.
     * Thread-safe (Read Lock).
     */
    fun toCanvasData(): CanvasData =
        rwLock.read {
            val strokeDataList =
                allStrokes.map { stroke ->
                    val count = stroke.points.size
                    val floats = FloatArray(count * 4)
                    val longs = LongArray(count)

                    for (i in 0 until count) {
                        val p = stroke.points[i]
                        floats[i * 4] = p.x
                        floats[i * 4 + 1] = p.y
                        floats[i * 4 + 2] = p.pressure
                        floats[i * 4 + 3] = p.size
                        longs[i] = p.timestamp
                    }

                    StrokeData(
                        points = emptyList(),
                        pointsPacked = floats,
                        timestampsPacked = longs,
                        color = stroke.color,
                        width = stroke.width,
                        style = stroke.style,
                        strokeOrder = stroke.strokeOrder,
                        zIndex = stroke.zIndex,
                    )
                }
            CanvasData(
                version = 2,
                strokes = strokeDataList,
                canvasType = canvasType,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                backgroundStyle = backgroundStyle,
                zoomLevel = viewportScale,
                offsetX = viewportOffsetX,
                offsetY = viewportOffsetY,
            )
        }

    /**
     * Loads the model state from a pre-calculated LoadedCanvasState object.
     * This method is fast and only updates the references.
     * Thread-safe (Write Lock).
     */
    fun setLoadedState(state: com.alexdremov.notate.data.CanvasSerializer.LoadedCanvasState) {
        rwLock.write {
            allStrokes.clear()
            historyManager.clear()

            allStrokes.addAll(state.strokes)
            quadtree = state.quadtree
            contentBounds.set(state.contentBounds)
            nextStrokeOrder = state.nextStrokeOrder

            canvasType = state.canvasType
            pageWidth = state.pageWidth
            pageHeight = state.pageHeight
            backgroundStyle = state.backgroundStyle
            viewportScale = state.viewportScale
            viewportOffsetX = state.viewportOffsetX
            viewportOffsetY = state.viewportOffsetY
        }
    }

    /**
     * Loads the model state from a CanvasData object.
     * Reconstructs paths, bounds, and spatial index.
     * Thread-safe (Write Lock).
     */
    fun loadFromCanvasData(data: CanvasData) {
        val sysPressure = EpdController.getMaxTouchPressure()
        val defaultMaxPressure = if (sysPressure > 0f) sysPressure else 4096f

        rwLock.write {
            allStrokes.clear()
            historyManager.clear()
            quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))
            contentBounds.setEmpty()
            nextStrokeOrder = 0

            canvasType = data.canvasType
            pageWidth = data.pageWidth
            pageHeight = data.pageHeight
            backgroundStyle = data.backgroundStyle
            viewportScale = data.zoomLevel
            viewportOffsetX = data.offsetX
            viewportOffsetY = data.offsetY

            try {
                data.strokes.forEach { sData ->
                    val points = ArrayList<TouchPoint>()

                    if (sData.pointsPacked != null && sData.timestampsPacked != null) {
                        val floats = sData.pointsPacked
                        val longs = sData.timestampsPacked
                        val count = longs.size

                        for (i in 0 until count) {
                            val x = floats[i * 4]
                            val y = floats[i * 4 + 1]
                            val rawP = floats[i * 4 + 2]
                            val s = floats[i * 4 + 3]
                            val t = longs[i]

                            val pressure = if (rawP.isNaN() || rawP <= 0f) defaultMaxPressure else rawP
                            points.add(TouchPoint(x, y, pressure, s, t))
                        }
                    } else {
                        sData.points.forEach { pData ->
                            val pressure =
                                if (pData.pressure.isNaN() || pData.pressure <= 0f) {
                                    defaultMaxPressure
                                } else {
                                    pData.pressure
                                }
                            points.add(TouchPoint(pData.x, pData.y, pressure, pData.size, pData.timestamp))
                        }
                    }

                    // Reconstruct Path
                    val path = android.graphics.Path()
                    if (points.isNotEmpty()) {
                        path.moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) {
                            val p1 = points[i - 1]
                            val p2 = points[i]
                            val cx = (p1.x + p2.x) / 2
                            val cy = (p1.y + p2.y) / 2
                            path.quadTo(p1.x, p1.y, cx, cy)
                        }
                        path.lineTo(points.last().x, points.last().y)
                    }

                    val bounds = StrokeGeometry.computeStrokeBounds(path, sData.width, sData.style)

                    val stroke =
                        Stroke(
                            path = path,
                            points = points,
                            color = sData.color,
                            width = sData.width,
                            style = sData.style,
                            bounds = bounds,
                            strokeOrder = sData.strokeOrder,
                            zIndex = sData.zIndex,
                        )

                    if (stroke.strokeOrder >= nextStrokeOrder) {
                        nextStrokeOrder = stroke.strokeOrder + 1
                    }

                    allStrokes.add(stroke)
                    quadtree = quadtree.insert(stroke)
                    updateContentBounds(bounds)
                }
            } catch (e: Exception) {
                Log.e("InfiniteCanvasModel", "Error loading canvas data", e)
            }
        }
    }

    // --- Page Calculation Helpers ---

    /**
     * Calculates the total number of pages based on the content bounds.
     * Always returns at least 1.
     * Thread-safe (Read Lock).
     */
    fun getTotalPages(): Int {
        if (canvasType != CanvasType.FIXED_PAGES) return 1
        return rwLock.read {
            if (contentBounds.isEmpty) return@read 1
            val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
            val maxPage = floor(contentBounds.bottom / pageFullHeight).toInt()
            (maxPage + 1).coerceAtLeast(1)
        }
    }

    /**
     * returns the RectF for a specific page index in world coordinates.
     */
    fun getPageBounds(pageIndex: Int): RectF {
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        val top = pageIndex * pageFullHeight
        return RectF(0f, top, pageWidth, top + pageHeight)
    }

    fun hitTest(
        x: Float,
        y: Float,
        tolerance: Float = 10f,
    ): Stroke? =
        rwLock.read {
            quadtree.hitTest(x, y, tolerance)
        }
}
