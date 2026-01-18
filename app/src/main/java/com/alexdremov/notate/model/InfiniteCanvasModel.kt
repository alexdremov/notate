package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.util.Quadtree
import com.alexdremov.notate.util.StrokeGeometry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * The core data model for the infinite canvas application.
 * Manages item storage, spatial indexing (Quadtree), undo/redo history, and persistence.
 * All public methods are thread-safe, utilizing a ReentrantReadWriteLock to allow concurrent
 * reads (rendering) while ensuring exclusive writes (drawing/erasing).
 */
class InfiniteCanvasModel {
    // Master list for persistence
    private val allItems = ArrayList<CanvasItem>()

    // Spatial Index for Rendering
    private var quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))

    private val contentBounds = RectF()

    private val rwLock = ReentrantReadWriteLock()

    private var nextOrder: Long = 0

    // Reactive Updates
    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ModelEvent> = _events.asSharedFlow()

    // History Manager
    private val historyManager =
        HistoryManager(
            object : HistoryManager.StrokeExecutor {
                override fun execute(action: HistoryAction) = executeAction(action)

                override fun revert(action: HistoryAction) = revertAction(action)

                override fun calculateBounds(action: HistoryAction) = calculateActionBounds(action)
            },
        )

    sealed class ModelEvent {
        data class ItemsAdded(
            val items: List<CanvasItem>,
        ) : ModelEvent()

        data class ItemsRemoved(
            val items: List<CanvasItem>,
        ) : ModelEvent()

        data class ItemsUpdated(
            val items: List<CanvasItem>,
        ) : ModelEvent() // For specialized updates if needed

        object ContentCleared : ModelEvent()
    }

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
    var toolbarItems: List<ToolbarItem> = emptyList()

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
     * Adds a new item to the model.
     * - Validates item against page bounds if in Fixed Page mode.
     * - Assigns a unique order.
     * - Updates spatial index and history.
     *
     * @param item The item to add.
     * @return The added item with assigned order, or null if rejected.
     */
    fun addItem(item: CanvasItem): CanvasItem? {
        // Enforce Fixed Page horizontal bounds
        if (canvasType == CanvasType.FIXED_PAGES) {
            if (item.bounds.right < 0 || item.bounds.left > pageWidth) {
                return null
            }
        }

        var addedItem: CanvasItem? = null
        rwLock.write {
            val orderedItem =
                when (item) {
                    is Stroke -> item.copy(strokeOrder = nextOrder++)
                    is CanvasImage -> item.copy(order = nextOrder++)
                }
            historyManager.applyAction(HistoryAction.Add(listOf(orderedItem)))
            addedItem = orderedItem
        }
        return addedItem
    }

    // Backwards compatibility for strokes
    fun addStroke(stroke: Stroke): Stroke? = addItem(stroke) as? Stroke

    /**
     * Performs erasure on the canvas based on the provided eraser stroke and type.
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
            val candidates = ArrayList<CanvasItem>()
            val searchBounds = RectF(eraserStroke.bounds)
            searchBounds.inset(-(eraserStroke.width + 5f), -(eraserStroke.width + 5f))
            quadtree.retrieve(candidates, searchBounds)

            if (candidates.isEmpty()) return@read

            val toRemove = ArrayList<CanvasItem>()
            val toReplaceRemoved = ArrayList<CanvasItem>()
            val toReplaceAdded = ArrayList<CanvasItem>()

            when (type) {
                EraserType.STROKE -> {
                    // "Stroke Eraser": Deletes entire strokes touched by the eraser.
                    candidates.forEach { item ->
                        if (item is Stroke && RectF.intersects(item.bounds, eraserStroke.bounds) &&
                            StrokeGeometry.strokeIntersects(item, eraserStroke)
                        ) {
                            toRemove.add(item)
                        } else if (item is CanvasImage && RectF.intersects(item.bounds, eraserStroke.bounds) &&
                            item.bounds.contains(eraserStroke.bounds.centerX(), eraserStroke.bounds.centerY())
                        ) {
                            // Simple hit test for images with stroke eraser
                            toRemove.add(item)
                        }
                    }
                }

                EraserType.LASSO -> {
                    // "Lasso Eraser": Deletes items strictly fully contained within the lasso loop.
                    candidates.forEach { item ->
                        if (!eraserStroke.bounds.contains(item.bounds)) return@forEach

                        val isContained =
                            if (item is Stroke) {
                                item.points.all { p ->
                                    StrokeGeometry.isPointInPolygon(p.x, p.y, eraserStroke.points)
                                }
                            } else {
                                // For images, check all 4 corners
                                val b = item.bounds
                                StrokeGeometry.isPointInPolygon(b.left, b.top, eraserStroke.points) &&
                                    StrokeGeometry.isPointInPolygon(b.right, b.top, eraserStroke.points) &&
                                    StrokeGeometry.isPointInPolygon(b.right, b.bottom, eraserStroke.points) &&
                                    StrokeGeometry.isPointInPolygon(b.left, b.bottom, eraserStroke.points)
                            }

                        if (isContained) {
                            toRemove.add(item)
                        }
                    }
                }

                EraserType.STANDARD -> {
                    // "Standard Eraser": Physically cuts strokes (Point Eraser).
                    // Does NOT affect images currently.
                    candidates.filterIsInstance<Stroke>().forEach { target ->
                        if (RectF.intersects(target.bounds, eraserStroke.bounds)) {
                            val newParts = StrokeGeometry.splitStroke(target, eraserStroke)
                            if (newParts.size != 1 || newParts[0] !== target) {
                                toReplaceRemoved.add(target)
                                toReplaceAdded.addAll(newParts)
                            }
                        }
                    }
                }
            }

            if (toRemove.isNotEmpty()) {
                actionsToApply.add(HistoryAction.Remove(toRemove))
                boundsToInvalidate.union(calculateBounds(toRemove))
            }
            if (toReplaceRemoved.isNotEmpty()) {
                actionsToApply.add(HistoryAction.Replace(toReplaceRemoved, toReplaceAdded))
                boundsToInvalidate.union(calculateBounds(toReplaceRemoved))
                boundsToInvalidate.union(calculateBounds(toReplaceAdded))
            }
        }

        // Phase 2: Write
        if (actionsToApply.isNotEmpty()) {
            rwLock.write {
                actionsToApply.forEach { action ->
                    val finalAction =
                        if (action is HistoryAction.Replace) {
                            val orderedAdded =
                                action.added.map { item ->
                                    if (item is Stroke) item.copy(strokeOrder = nextOrder++) else item
                                }
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

    fun deleteItems(items: List<CanvasItem>) {
        if (items.isEmpty()) return
        rwLock.write {
            historyManager.applyAction(HistoryAction.Remove(items))
        }
    }

    // Backwards compatibility
    fun deleteStrokes(strokes: List<Stroke>) {
        deleteItems(strokes)
    }

    private fun executeAction(
        action: HistoryAction,
        recalculateBounds: Boolean = true,
    ) {
        when (action) {
            is HistoryAction.Add -> {
                action.items.forEach { item ->
                    allItems.add(item)
                    quadtree = quadtree.insert(item)
                    updateContentBounds(item.bounds)
                }
                _events.tryEmit(ModelEvent.ItemsAdded(action.items))
            }

            is HistoryAction.Remove -> {
                action.items.forEach { item ->
                    allItems.remove(item)
                    quadtree.remove(item)
                }
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.items))
            }

            is HistoryAction.Replace -> {
                action.removed.forEach { item ->
                    allItems.remove(item)
                    quadtree.remove(item)
                }
                action.added.forEach { item ->
                    allItems.add(item)
                    quadtree = quadtree.insert(item)
                    updateContentBounds(item.bounds)
                }
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.removed))
                _events.tryEmit(ModelEvent.ItemsAdded(action.added))
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
                action.items.forEach { item ->
                    allItems.remove(item)
                    quadtree.remove(item)
                }
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.items))
            }

            is HistoryAction.Remove -> {
                action.items.forEach { item ->
                    allItems.add(item)
                    quadtree = quadtree.insert(item)
                    updateContentBounds(item.bounds)
                }
                _events.tryEmit(ModelEvent.ItemsAdded(action.items))
            }

            is HistoryAction.Replace -> {
                action.added.forEach { item ->
                    allItems.remove(item)
                    quadtree.remove(item)
                }
                action.removed.forEach { item ->
                    allItems.add(item)
                    quadtree = quadtree.insert(item)
                    updateContentBounds(item.bounds)
                }
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.added))
                _events.tryEmit(ModelEvent.ItemsAdded(action.removed))
            }

            is HistoryAction.Batch -> {
                action.actions.asReversed().forEach { revertAction(it, false) }
                if (recalculateBounds) recalculateContentBounds()
            }
        }
    }

    fun undo(): RectF? = rwLock.write { historyManager.undo() }

    fun redo(): RectF? = rwLock.write { historyManager.redo() }

    private fun calculateActionBounds(action: HistoryAction): RectF =
        when (action) {
            is HistoryAction.Add -> {
                calculateBounds(action.items)
            }

            is HistoryAction.Remove -> {
                calculateBounds(action.items)
            }

            is HistoryAction.Replace -> {
                calculateBounds(action.removed).apply { union(calculateBounds(action.added)) }
            }

            is HistoryAction.Batch -> {
                val r = RectF()
                action.actions.forEach { r.union(calculateActionBounds(it)) }
                r
            }
        }

    fun clear() {
        rwLock.write {
            allItems.clear()
            historyManager.clear()
            quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))
            contentBounds.setEmpty()
            nextOrder = 0
            _events.tryEmit(ModelEvent.ContentCleared)
        }
    }

    fun getContentBounds(): RectF = RectF(contentBounds)

    fun performRead(block: (List<CanvasItem>) -> Unit) {
        rwLock.read {
            block(allItems)
        }
    }

    fun queryItems(rect: RectF): ArrayList<CanvasItem> {
        val result = ArrayList<CanvasItem>()
        rwLock.read {
            quadtree.retrieve(result, rect)
        }
        return result
    }

    // Backwards compatibility
    fun queryStrokes(rect: RectF): ArrayList<Stroke> {
        val items = queryItems(rect)
        val strokes = ArrayList<Stroke>()
        for (item in items) {
            if (item is Stroke) strokes.add(item)
        }
        return strokes
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
        allItems.forEach { item ->
            if (contentBounds.isEmpty) {
                contentBounds.set(item.bounds)
            } else {
                contentBounds.union(item.bounds)
            }
        }
    }

    private fun calculateBounds(items: List<CanvasItem>): RectF {
        val r = RectF()
        if (items.isNotEmpty()) {
            r.set(items[0].bounds)
            for (i in 1 until items.size) {
                r.union(items[i].bounds)
            }
        }
        return r
    }

    fun toCanvasData(): CanvasData =
        rwLock.read {
            CanvasSerializer.toData(
                allItems,
                canvasType,
                pageWidth,
                pageHeight,
                backgroundStyle,
                viewportScale,
                viewportOffsetX,
                viewportOffsetY,
                toolbarItems,
            )
        }

    fun setLoadedState(state: CanvasSerializer.LoadedCanvasState) {
        rwLock.write {
            allItems.clear()
            historyManager.clear()

            allItems.addAll(state.items)
            quadtree = state.quadtree
            contentBounds.set(state.contentBounds)
            nextOrder = state.nextStrokeOrder

            canvasType = state.canvasType
            pageWidth = state.pageWidth
            pageHeight = state.pageHeight
            backgroundStyle = state.backgroundStyle
            viewportScale = state.viewportScale
            viewportOffsetX = state.viewportOffsetX
            viewportOffsetY = state.viewportOffsetY
            toolbarItems = state.toolbarItems
        }
    }

    fun loadFromCanvasData(data: CanvasData) {
        rwLock.write {
            // ... existing complicated load logic but delegated to serializer ideally,
            // but here we are doing it in-place.
            // For safety and DRY, let's rely on Serializer's parsing logic which we already improved
            // But parseCanvasData is suspend...
            // We can just use the Serializer's synchronous helper if we expose it, or duplicate logic.
            // Given I refactored the serializer to handle everything, it's better to use it.
            // However, loadFromCanvasData in Model was doing synchronous path reconstruction (bad).
            // The calling code (CanvasActivity) uses CanvasRepository which uses CanvasSerializer.parseCanvasData (suspend).
            // So this method might not be used anymore or only for legacy?
            // Let's implement it using Serializer's fromData which is synchronous callback based.

            allItems.clear()
            historyManager.clear()
            quadtree = Quadtree(0, RectF(-50000f, -50000f, 50000f, 50000f))
            contentBounds.setEmpty()
            nextOrder = 0

            canvasType = data.canvasType
            pageWidth = data.pageWidth
            pageHeight = data.pageHeight
            backgroundStyle = data.backgroundStyle
            viewportScale = data.zoomLevel
            viewportOffsetX = data.offsetX
            viewportOffsetY = data.offsetY
            toolbarItems = data.toolbarItems

            CanvasSerializer.fromData(data) { item ->
                if (item.order >= nextOrder) nextOrder = item.order + 1
                allItems.add(item)
                quadtree = quadtree.insert(item)
                updateContentBounds(item.bounds)
            }
        }
    }

    fun getTotalPages(): Int {
        if (canvasType != CanvasType.FIXED_PAGES) return 1
        return rwLock.read {
            if (contentBounds.isEmpty) return@read 1
            val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
            val maxPage = floor(contentBounds.bottom / pageFullHeight).toInt()
            (maxPage + 1).coerceAtLeast(1)
        }
    }

    fun getPageBounds(pageIndex: Int): RectF {
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        val top = pageIndex * pageFullHeight
        return RectF(0f, top, pageWidth, top + pageHeight)
    }

    fun hitTest(
        x: Float,
        y: Float,
        tolerance: Float = 10f,
    ): CanvasItem? =
        rwLock.read {
            quadtree.hitTest(x, y, tolerance)
        }
}
