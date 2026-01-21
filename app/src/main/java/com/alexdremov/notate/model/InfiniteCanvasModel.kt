package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.util.StrokeGeometry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * The core data model for the infinite canvas application.
 * Manages item storage via RegionManager, undo/redo history, and persistence.
 */
class InfiniteCanvasModel {
    private var regionManager: RegionManager? = null

    // Track global bounds for navigation/zoom-to-fit
    private val contentBounds = RectF()

    private val rwLock = ReentrantReadWriteLock()

    private var nextOrder: Long = 0

    // Reactive Updates
    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 1024)
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
        ) : ModelEvent()

        data class RegionLoaded(
            val bounds: RectF,
        ) : ModelEvent()

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
    var tagIds: List<String> = emptyList()
    var tagDefinitions: List<Tag> = emptyList()

    fun getRegionManager(): RegionManager? = regionManager

    fun initializeSession(manager: RegionManager) {
        rwLock.write {
            regionManager = manager
            // Recalculate bounds from loaded session
            val bounds = manager.getContentBounds()
            contentBounds.set(bounds)

            manager.onRegionLoaded = { region ->
                // Calculate bounds for refresh
                val size = manager.regionSize
                val rLeft = region.id.x * size
                val rTop = region.id.y * size
                val regionBounds = RectF(rLeft, rTop, rLeft + size, rTop + size)
                _events.tryEmit(ModelEvent.RegionLoaded(regionBounds))
            }
        }
    }

    fun setBackground(style: BackgroundStyle) {
        rwLock.write {
            backgroundStyle = style
        }
    }

    fun startBatchSession() {
        rwLock.write {
            historyManager.startBatchSession()
        }
    }

    fun endBatchSession() {
        rwLock.write {
            historyManager.endBatchSession()
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? = regionManager?.importImage(uri, context)

    fun addItem(item: CanvasItem): CanvasItem? {
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
                    else -> throw IllegalArgumentException("Unsupported CanvasItem type: ${item::class.java.name}")
                }
            historyManager.applyAction(HistoryAction.Add(listOf(orderedItem)))
            addedItem = orderedItem
        }
        return addedItem
    }

    fun addStroke(stroke: Stroke): Stroke? = addItem(stroke) as? Stroke

    fun erase(
        eraserStroke: Stroke,
        type: EraserType,
    ): RectF? {
        var invalidatedBounds: RectF? = null
        val actionsToApply = ArrayList<HistoryAction>()
        var boundsToInvalidate = RectF()
        val pendingReplacements = ArrayList<Pair<CanvasItem, List<CanvasItem>>>()
        val toRemove = ArrayList<CanvasItem>()

        rwLock.read {
            val rm = regionManager ?: return@read
            // Query regions slightly larger than eraser
            val searchBounds = RectF(eraserStroke.bounds)
            searchBounds.inset(-(eraserStroke.width + 5f), -(eraserStroke.width + 5f))

            // Retrieve candidates from RegionManager
            val regions = rm.getRegionsInRect(searchBounds)
            val candidates = ArrayList<CanvasItem>()
            regions.forEach { region ->
                region.quadtree?.retrieve(candidates, searchBounds)
            }

            if (candidates.isEmpty()) return@read

            when (type) {
                EraserType.STROKE -> {
                    candidates.forEach { item ->
                        if (item is Stroke && RectF.intersects(item.bounds, eraserStroke.bounds) &&
                            StrokeGeometry.strokeIntersects(item, eraserStroke)
                        ) {
                            toRemove.add(item)
                        } else if (item is CanvasImage && RectF.intersects(item.bounds, eraserStroke.bounds) &&
                            item.bounds.contains(eraserStroke.bounds.centerX(), eraserStroke.bounds.centerY())
                        ) {
                            toRemove.add(item)
                        }
                    }
                }

                EraserType.LASSO -> {
                    candidates.forEach { item ->
                        if (!eraserStroke.bounds.contains(item.bounds)) return@forEach
                        val isContained =
                            if (item is Stroke) {
                                item.points.all { p ->
                                    StrokeGeometry.isPointInPolygon(p.x, p.y, eraserStroke.points)
                                }
                            } else {
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
                    candidates.filterIsInstance<Stroke>().forEach { target ->
                        if (RectF.intersects(target.bounds, eraserStroke.bounds)) {
                            val newParts = StrokeGeometry.splitStroke(target, eraserStroke)
                            if (newParts.size != 1 || newParts[0] !== target) {
                                pendingReplacements.add(target to newParts)
                            }
                        }
                    }
                }
            }
        }

        if (toRemove.isNotEmpty() || pendingReplacements.isNotEmpty()) {
            rwLock.write {
                if (toRemove.isNotEmpty()) {
                    val action = HistoryAction.Remove(toRemove)
                    actionsToApply.add(action)
                    boundsToInvalidate.union(calculateBounds(toRemove))
                    historyManager.applyAction(action)
                }

                if (pendingReplacements.isNotEmpty()) {
                    val finalRemoved = pendingReplacements.map { it.first }
                    val finalAdded = pendingReplacements.flatMap { it.second }

                    val orderedAdded =
                        finalAdded.map { item ->
                            if (item is Stroke) item.copy(strokeOrder = nextOrder++) else item
                        }

                    val action = HistoryAction.Replace(finalRemoved, orderedAdded)
                    actionsToApply.add(action)
                    boundsToInvalidate.union(calculateBounds(finalRemoved))
                    boundsToInvalidate.union(calculateBounds(orderedAdded))
                    historyManager.applyAction(action)
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

    fun deleteStrokes(strokes: List<Stroke>) {
        deleteItems(strokes)
    }

    private fun executeAction(
        action: HistoryAction,
        recalculateBounds: Boolean = true,
    ) {
        val rm = regionManager ?: return
        when (action) {
            is HistoryAction.Add -> {
                action.items.forEach { item ->
                    rm.addItem(item)
                    updateContentBounds(item.bounds)
                }
                _events.tryEmit(ModelEvent.ItemsAdded(action.items))
            }

            is HistoryAction.Remove -> {
                rm.removeItems(action.items)
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.items))
            }

            is HistoryAction.Replace -> {
                rm.removeItems(action.removed)
                action.added.forEach { item ->
                    rm.addItem(item)
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
        val rm = regionManager ?: return
        when (action) {
            is HistoryAction.Add -> {
                rm.removeItems(action.items)
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.ItemsRemoved(action.items))
            }

            is HistoryAction.Remove -> {
                action.items.forEach { item ->
                    rm.addItem(item)
                    updateContentBounds(item.bounds)
                }
                _events.tryEmit(ModelEvent.ItemsAdded(action.items))
            }

            is HistoryAction.Replace -> {
                rm.removeItems(action.added)
                action.removed.forEach { item ->
                    rm.addItem(item)
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
            regionManager?.clear()
            historyManager.clear()
            contentBounds.setEmpty()
            nextOrder = 0
            _events.tryEmit(ModelEvent.ContentCleared)
        }
    }

    fun getContentBounds(): RectF = RectF(contentBounds)

    fun queryItems(rect: RectF): ArrayList<CanvasItem> {
        val result = ArrayList<CanvasItem>()
        rwLock.read {
            val rm = regionManager ?: return@read
            val regions = rm.getRegionsInRect(rect)
            regions.forEach { region ->
                region.quadtree?.retrieve(result, rect)
            }
        }
        return result
    }

    fun queryStrokes(rect: RectF): ArrayList<Stroke> {
        val items = queryItems(rect)
        val strokes = ArrayList<Stroke>()
        for (item in items) {
            if (item is Stroke) strokes.add(item)
        }
        return strokes
    }

    private fun updateContentBounds(bounds: RectF) {
        if (contentBounds.isEmpty) {
            contentBounds.set(bounds)
        } else {
            contentBounds.union(bounds)
        }
    }

    private fun recalculateContentBounds() {
        val bounds = regionManager?.getContentBounds() ?: RectF()
        contentBounds.set(bounds)
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
            val rm = regionManager
            val size = rm?.regionSize ?: CanvasConfig.DEFAULT_REGION_SIZE

            CanvasSerializer.toData(
                canvasType,
                pageWidth,
                pageHeight,
                backgroundStyle,
                viewportScale,
                viewportOffsetX,
                viewportOffsetY,
                toolbarItems,
                tagIds,
                tagDefinitions,
                regionSize = size,
                nextStrokeOrder = nextOrder,
            )
        }

    fun setLoadedState(state: CanvasSerializer.LoadedCanvasState) {
        rwLock.write {
            clear()

            regionManager?.let {
                // No items in state anymore
            }

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
            tagIds = state.tagIds
            tagDefinitions = state.tagDefinitions
        }
    }

    fun loadFromCanvasData(data: CanvasData) {
        rwLock.write {
            canvasType = data.canvasType
            pageWidth = data.pageWidth
            pageHeight = data.pageHeight
            backgroundStyle = data.backgroundStyle
            viewportScale = data.zoomLevel
            viewportOffsetX = data.offsetX
            viewportOffsetY = data.offsetY
            toolbarItems = data.toolbarItems
            tagIds = data.tagIds
            tagDefinitions = data.tagDefinitions
            // No content loading
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
            val rm = regionManager ?: return@read null
            val searchRect = RectF(x - tolerance, y - tolerance, x + tolerance, y + tolerance)
            val regions = rm.getRegionsInRect(searchRect)

            var hit: CanvasItem? = null
            val candidates = ArrayList<CanvasItem>()
            regions.forEach { it.quadtree?.retrieve(candidates, searchRect) }

            candidates.sortByDescending { it.order }

            for (item in candidates) {
                if (item.distanceToPoint(x, y) < tolerance) {
                    hit = item
                    break
                }
            }
            hit
        }

    // Save Flush
    fun flush() {
        regionManager?.saveAll()
    }
}
