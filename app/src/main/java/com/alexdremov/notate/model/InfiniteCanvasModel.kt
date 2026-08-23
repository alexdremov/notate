package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.util.StrokeGeometry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.floor

/**
 * The core data model for the infinite canvas application.
 * Manages item storage via RegionManager, undo/redo history, and persistence.
 *
 * Refactored to be fully Suspendable/Async to prevent deadlocks.
 */
class InfiniteCanvasModel {
    private var regionManager: RegionManager? = null

    // Track global bounds for navigation/zoom-to-fit
    private val contentBounds = RectF()
    private val _contentBoundsFlow = MutableStateFlow(RectF())
    val contentBoundsFlow: StateFlow<RectF> = _contentBoundsFlow.asStateFlow()

    private val mutex = Mutex()

    private var nextOrder: Long = 0

    // Reactive Updates
    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<ModelEvent> = _events.asSharedFlow()

    // History Manager
    private val historyManager = HistoryManager()

    init {
        // Release external resources when history entries become unreachable.
        // RemoveStashed actions reference a stash file on disk; without this
        // hook every deleted selection would leak a `del_*.bin` file forever,
        // even after the entry fell off the 100-deep undo stack or the canvas
        // was cleared. Runs under the model mutex; File.delete() is cheap.
        historyManager.onActionDiscarded = { action ->
            if (action is HistoryAction.RemoveStashed) {
                action.stashFile.delete()
            }
        }
    }

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

        data class BulkItemsAdded(
            val bounds: RectF,
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
    // Written from the UI layer (viewport gestures / load) and read during
    // save/export on background threads. @Volatile guarantees visibility of
    // the last completed write across threads.
    @Volatile
    var viewportScale: Float = 1.0f

    @Volatile
    var viewportOffsetX: Float = 0f

    @Volatile
    var viewportOffsetY: Float = 0f
    var toolbarItems: List<ToolbarItem> = emptyList()
    var tagIds: List<String> = emptyList()
    var tagDefinitions: List<Tag> = emptyList()

    // UUID for linking
    private var uuid: String? = null

    fun getRegionManager(): RegionManager? = regionManager

    suspend fun initializeSession(manager: RegionManager) {
        mutex.withLock {
            regionManager = manager
            val bounds = manager.getContentBounds()
            contentBounds.set(bounds)
            _contentBoundsFlow.value = RectF(bounds)

            // Resume order assignment: restarting at 0 after a reopen made
            // new strokes collide with persisted ones (found by
            // RealWorldSessionTest — new content shared identity with old).
            nextOrder = manager.maxItemOrder() + 1

            manager.onRegionLoaded = { region ->
                val size = manager.regionSize
                val rLeft = region.id.x * size
                val rTop = region.id.y * size
                val regionBounds = RectF(rLeft, rTop, rLeft + size, rTop + size)
                _events.tryEmit(ModelEvent.RegionLoaded(regionBounds))
            }
        }
    }

    suspend fun setBackground(style: BackgroundStyle) {
        mutex.withLock {
            backgroundStyle = style
        }
    }

    suspend fun startBatchSession() {
        mutex.withLock {
            historyManager.startBatchSession()
        }
    }

    suspend fun endBatchSession() {
        mutex.withLock {
            historyManager.endBatchSession()
        }
    }

    fun importImage(
        uri: android.net.Uri,
        context: android.content.Context,
    ): String? = regionManager?.importImage(uri, context)

    suspend fun getItem(
        id: Long,
        bounds: RectF,
    ): CanvasItem? = regionManager?.findItem(id, bounds)

    suspend fun addItem(item: CanvasItem): CanvasItem? {
        if (canvasType == CanvasType.FIXED_PAGES) {
            if (item.bounds.right < 0 || item.bounds.left > pageWidth) {
                return null
            }
        }

        var addedItem: CanvasItem? = null
        mutex.withLock {
            val orderedItem =
                when (item) {
                    is Stroke -> item.copy(strokeOrder = nextOrder++)
                    is CanvasImage -> item.copy(order = nextOrder++)
                    is TextItem -> item.copy(order = nextOrder++)
                    is LinkItem -> item.copy(order = nextOrder++)
                    else -> throw IllegalArgumentException("Unsupported CanvasItem type: ${item::class.java.name}")
                }

            executeAction(HistoryAction.Add(listOf(orderedItem)))
            historyManager.addToStack(HistoryAction.Add(listOf(orderedItem)))

            addedItem = orderedItem
        }
        return addedItem
    }

    suspend fun addStroke(stroke: Stroke): Stroke? = addItem(stroke) as? Stroke

    suspend fun erase(
        eraserStroke: Stroke,
        type: EraserType,
    ): RectF? {
        var invalidatedBounds: RectF? = null
        val boundsToInvalidate = RectF()
        val toRemove = ArrayList<CanvasItem>()
        val pendingReplacements = ArrayList<Pair<CanvasItem, List<CanvasItem>>>()

        mutex.withLock {
            val rm = regionManager ?: return@withLock null
            val searchBounds = RectF(eraserStroke.bounds)
            searchBounds.inset(-(eraserStroke.width + 5f), -(eraserStroke.width + 5f))

            val regions = rm.getRegionsInRect(searchBounds)
            try {
                val candidates = ArrayList<CanvasItem>()
                regions.forEach { region ->
                    region.quadtree?.retrieve(candidates, searchBounds)
                }

                // Deduplicate candidates as items spanning multiple regions will appear multiple times
                val uniqueCandidates = candidates.distinctBy { it.order }

                if (uniqueCandidates.isEmpty()) return@withLock null

                // Pre-simplify eraser stroke to drastically reduce O(N*M) geometry checks
                val optimizedEraser =
                    if (eraserStroke.points.size > 20 && type != EraserType.LASSO) {
                        val simplified = StrokeGeometry.simplifyPoints(eraserStroke.points, 2.0f)
                        eraserStroke.copy(points = simplified)
                    } else {
                        eraserStroke
                    }

                when (type) {
                    EraserType.STROKE -> {
                        uniqueCandidates.forEach { item ->
                            if (item is Stroke && RectF.intersects(item.bounds, optimizedEraser.bounds) &&
                                StrokeGeometry.strokeIntersects(item, optimizedEraser)
                            ) {
                                toRemove.add(item)
                            } else if (item is CanvasImage && RectF.intersects(item.bounds, optimizedEraser.bounds) &&
                                item.bounds.contains(optimizedEraser.bounds.centerX(), optimizedEraser.bounds.centerY())
                            ) {
                                toRemove.add(item)
                            } else if (item is TextItem && RectF.intersects(item.bounds, optimizedEraser.bounds) &&
                                item.bounds.contains(optimizedEraser.bounds.centerX(), optimizedEraser.bounds.centerY())
                            ) {
                                toRemove.add(item)
                            } else if (item is LinkItem && RectF.intersects(item.bounds, optimizedEraser.bounds) &&
                                item.bounds.contains(optimizedEraser.bounds.centerX(), optimizedEraser.bounds.centerY())
                            ) {
                                toRemove.add(item)
                            }
                        }
                    }

                    EraserType.LASSO -> {
                        val simplifiedLasso = StrokeGeometry.simplifyPoints(eraserStroke.points, 3.0f)
                        uniqueCandidates.forEach { item ->
                            if (!eraserStroke.bounds.contains(item.bounds)) return@forEach

                            val isContained =
                                if (StrokeGeometry.isRectFullyInPolygon(item.bounds, simplifiedLasso)) {
                                    true
                                } else if (item is Stroke) {
                                    item.points.all { p ->
                                        StrokeGeometry.isPointInPolygon(p.x, p.y, simplifiedLasso)
                                    }
                                } else {
                                    val b = item.bounds
                                    StrokeGeometry.isPointInPolygon(b.left, b.top, simplifiedLasso) &&
                                        StrokeGeometry.isPointInPolygon(b.right, b.top, simplifiedLasso) &&
                                        StrokeGeometry.isPointInPolygon(b.right, b.bottom, simplifiedLasso) &&
                                        StrokeGeometry.isPointInPolygon(b.left, b.bottom, simplifiedLasso)
                                }

                            if (isContained) {
                                toRemove.add(item)
                            }
                        }
                    }

                    EraserType.STANDARD -> {
                        uniqueCandidates.filterIsInstance<Stroke>().forEach { target ->
                            if (RectF.intersects(target.bounds, optimizedEraser.bounds)) {
                                val newParts = StrokeGeometry.splitStroke(target, optimizedEraser)
                                if (newParts.size != 1 || newParts[0] !== target) {
                                    pendingReplacements.add(target to newParts)
                                }
                            }
                        }
                    }
                }

                if (toRemove.isNotEmpty() || pendingReplacements.isNotEmpty()) {
                    if (toRemove.isNotEmpty()) {
                        val action = HistoryAction.Remove(toRemove)
                        executeAction(action)
                        historyManager.addToStack(action)
                        boundsToInvalidate.union(calculateBounds(toRemove))
                    }

                    if (pendingReplacements.isNotEmpty()) {
                        val finalRemoved = pendingReplacements.map { it.first }
                        val finalAdded = pendingReplacements.flatMap { it.second }

                        val orderedAdded =
                            finalAdded.map { item ->
                                if (item is Stroke) item.copy(strokeOrder = nextOrder++) else item
                            }

                        val action = HistoryAction.Replace(finalRemoved, orderedAdded)
                        executeAction(action)
                        historyManager.addToStack(action)

                        boundsToInvalidate.union(calculateBounds(finalRemoved))
                        boundsToInvalidate.union(calculateBounds(orderedAdded))
                    }

                    invalidatedBounds = boundsToInvalidate
                }
            } finally {
                // getRegionsInRect returns reader-retained regions (safe under
                // concurrent eviction); drop the references once candidate
                // extraction and mutation planning are done. The item objects
                // themselves stay valid: history actions re-load by id on undo.
                rm.releaseRegions(regions)
            }
        }
        return invalidatedBounds
    }

    suspend fun deleteItems(items: List<CanvasItem>) {
        if (items.isEmpty()) return
        mutex.withLock {
            val action = HistoryAction.Remove(items)
            executeAction(action)
            historyManager.addToStack(action)
        }
    }

    suspend fun deleteStrokes(strokes: List<Stroke>) {
        deleteItems(strokes)
    }

    suspend fun replaceItems(
        oldItems: List<CanvasItem>,
        newItems: List<CanvasItem>,
    ): List<CanvasItem> {
        if (oldItems.isEmpty() && newItems.isEmpty()) return emptyList()
        return mutex.withLock {
            val orderedNewItems =
                newItems.map { item ->
                    when (item) {
                        is Stroke -> item.copy(strokeOrder = nextOrder++)
                        is CanvasImage -> item.copy(order = nextOrder++)
                        is TextItem -> item.copy(order = nextOrder++)
                        is LinkItem -> item.copy(order = nextOrder++)
                        else -> item
                    }
                }

            val action = HistoryAction.Replace(oldItems, orderedNewItems)
            executeAction(action)
            historyManager.addToStack(action)
            orderedNewItems
        }
    }

    private suspend fun executeAction(
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

            is HistoryAction.RemoveStashed -> {
                val rm = regionManager ?: return
                rm.removeItemsByIds(action.bounds, action.ids)
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.BulkItemsAdded(action.bounds))
            }
        }
    }

    private suspend fun revertAction(
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

            is HistoryAction.RemoveStashed -> {
                val rm = regionManager ?: return
                val (_, _) = rm.unstashItems(action.stashFile, android.graphics.Matrix())
                if (recalculateBounds) recalculateContentBounds()
                _events.tryEmit(ModelEvent.BulkItemsAdded(action.bounds))

                // The stash file MUST stay alive: a later redo→undo cycle calls
                // this again and needs it to restore the items (found by
                // HistorySemanticsTest-style review; deleting here silently
                // dropped strokes on the second undo). Cleanup happens when the
                // action leaves history entirely, via onActionDiscarded above.
            }
        }
    }

    suspend fun undo(): RectF? =
        mutex.withLock {
            val action = historyManager.undoActionOnly() ?: return@withLock null
            revertAction(action)
            return@withLock calculateActionBounds(action)
        }

    suspend fun redo(): RectF? =
        mutex.withLock {
            val action = historyManager.redoActionOnly() ?: return@withLock null
            executeAction(action)
            return@withLock calculateActionBounds(action)
        }

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

            is HistoryAction.RemoveStashed -> {
                action.bounds
            }
        }

    suspend fun clear() {
        mutex.withLock {
            // USER-initiated wipe: persisted strokes must not resurrect on
            // reopen (rebuildIndex scans region files). Plain memory-only
            // clear stays reserved for internal flows like setLoadedState.
            regionManager?.clearAndWipeStorage()
            historyManager.clear()
            contentBounds.setEmpty()
            _contentBoundsFlow.value = RectF()
            nextOrder = 0
            _events.tryEmit(ModelEvent.ContentCleared)
        }
    }

    fun getContentBounds(): RectF {
        // Safe read from StateFlow or cached RectF
        // Note: contentBounds RectF is NOT thread safe, so we return a copy from StateFlow
        return RectF(_contentBoundsFlow.value)
    }

    /**
     * Queries items intersecting [rect].
     *
     * Deliberately does NOT hold the model mutex: this is on the tile-generation
     * hot path and must not serialize behind long mutations (erase geometry,
     * undo/redo, stash). Consistency is delegated to [RegionManager.queryItems],
     * which gathers candidates under its read lock — quadtrees are persistent,
     * so each region yields a consistent snapshot. Callers needing an atomic
     * view across a multi-step mutation (eraser split, replace) must keep those
     * steps inside the model mutex themselves, as [erase] does.
     */
    suspend fun queryItems(rect: RectF): ArrayList<CanvasItem> {
        val rm = regionManager ?: return ArrayList()
        return rm.queryItems(rect)
    }

    suspend fun visitItemsInRect(
        rect: RectF,
        visitor: (CanvasItem) -> Unit,
    ) {
        mutex.withLock {
            regionManager?.visitItemsInRect(rect, visitor)
        }
    }

    suspend fun queryStrokes(rect: RectF): ArrayList<Stroke> {
        val items = queryItems(rect)
        val strokes = ArrayList<Stroke>()
        for (item in items) {
            if (item is Stroke) strokes.add(item)
        }
        return strokes
    }

    suspend fun stashItems(
        rect: RectF,
        ids: Set<Long>,
        file: java.io.File,
    ): Int =
        mutex.withLock {
            regionManager?.stashSelectedItems(rect, ids, file) ?: 0
        }

    suspend fun unstashItems(
        file: java.io.File,
        transform: android.graphics.Matrix,
        onItemUnstashed: ((CanvasItem) -> Unit)? = null,
    ): Pair<Set<Long>, RectF> =
        mutex.withLock {
            val result = regionManager?.unstashItems(file, transform, onItemUnstashed) ?: Pair(emptySet(), RectF())
            if (!result.second.isEmpty) {
                _events.tryEmit(ModelEvent.BulkItemsAdded(result.second))
            }
            recalculateContentBounds()
            result
        }

    suspend fun deleteItemsByIds(
        rect: RectF,
        ids: Set<Long>,
        stashDir: java.io.File,
    ) {
        mutex.withLock {
            val file = java.io.File(stashDir, "del_${System.currentTimeMillis()}_${ids.hashCode()}.bin")
            regionManager?.stashSelectedItems(rect, ids, file)
            val action = HistoryAction.RemoveStashed(file, rect, ids)
            historyManager.addToStack(action)

            recalculateContentBounds()
            _events.tryEmit(ModelEvent.BulkItemsAdded(rect)) // Force refresh area
        }
    }

    private fun updateContentBounds(bounds: RectF) {
        if (contentBounds.isEmpty) {
            contentBounds.set(bounds)
        } else {
            contentBounds.union(bounds)
        }
        _contentBoundsFlow.value = RectF(contentBounds)
    }

    private suspend fun recalculateContentBounds() {
        val bounds = regionManager?.getContentBounds() ?: RectF()
        contentBounds.set(bounds)
        _contentBoundsFlow.value = RectF(contentBounds)
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

    /**
     * Serializes the current canvas state to [CanvasData].
     *
     * Note: this requires an initialized session; [regionManager] must be non-null.
     */
    suspend fun toCanvasData(): CanvasData =
        mutex.withLock {
            val manager =
                regionManager
                    ?: throw IllegalStateException("toCanvasData() called before session initialization: regionManager is null")
            val size = manager.regionSize

            // Auto-generate UUID if missing
            if (uuid.isNullOrBlank()) {
                uuid = UUID.randomUUID().toString()
            }

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
                uuid = uuid,
            )
        }

    suspend fun setLoadedState(state: CanvasSerializer.LoadedCanvasState) {
        mutex.withLock {
            regionManager?.clear()
            historyManager.clear()
            contentBounds.setEmpty()
            nextOrder = 0
            _events.tryEmit(ModelEvent.ContentCleared)

            contentBounds.set(state.contentBounds)
            _contentBoundsFlow.value = RectF(state.contentBounds)
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
            uuid = state.uuid
        }
    }

    suspend fun loadFromCanvasData(data: CanvasData) {
        mutex.withLock {
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
            nextOrder = data.nextStrokeOrder
            uuid = data.uuid
        }
    }

    fun getTotalPages(): Int {
        if (canvasType != CanvasType.FIXED_PAGES) return 1
        val bounds = _contentBoundsFlow.value
        if (bounds.isEmpty) return 1
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        val maxPage = floor(bounds.bottom / pageFullHeight).toInt()
        return (maxPage + 1).coerceAtLeast(1)
    }

    fun getPageBounds(pageIndex: Int): RectF {
        val pageFullHeight = pageHeight + CanvasConfig.PAGE_SPACING
        val top = pageIndex * pageFullHeight
        return RectF(0f, top, pageWidth, top + pageHeight)
    }

    /**
     * Hit test against the canvas content.
     *
     * Delegates to [RegionManager.hitTest], which loads all intersecting
     * regions (lazily deserialized from disk) and collects candidates under the
     * region read lock — required because quadtrees are concurrently mutated by
     * background stroke commits and evicted regions are recycled. The model
     * mutex additionally serializes this against other model mutations.
     */
    suspend fun hitTest(
        x: Float,
        y: Float,
        tolerance: Float = 10f,
    ): CanvasItem? = mutex.withLock { regionManager?.hitTest(x, y, tolerance) }

    suspend fun flush() {
        regionManager?.saveAll()
    }
}
