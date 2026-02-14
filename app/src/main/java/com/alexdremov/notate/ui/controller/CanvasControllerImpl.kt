package com.alexdremov.notate.ui.controller

import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alexdremov.notate.model.CanvasImage
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.util.ClipboardManager
import com.alexdremov.notate.util.StrokeGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayList
import kotlin.coroutines.coroutineContext

class CanvasControllerImpl(
    private val context: Context,
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
) : CanvasController {
    companion object {
        // Threshold for switching to "Disk Stash" move strategy to avoid OOM.
        // Value 512 chosen based on memory profiling of 500+ items selection.
        private const val LARGE_SELECTION_THRESHOLD = 512
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var viewportController: ViewportController? = null
    private val selectionManager = SelectionManager()
    private var onContentChangedListener: (() -> Unit)? = null
    private var progressCallback: ((Boolean, String?, Int) -> Unit)? = null

    // Mutex to prevent concurrent destructive operations (commit, paste, delete)
    private val operationMutex = Mutex()

    override fun setOnContentChangedListener(listener: () -> Unit) {
        this.onContentChangedListener = listener
    }

    override fun setProgressCallback(callback: (isVisible: Boolean, message: String?, progress: Int) -> Unit) {
        this.progressCallback = callback
    }

    override fun setViewportController(controller: ViewportController) {
        this.viewportController = controller
    }

    override suspend fun startBatchSession() = model.startBatchSession()

    override suspend fun endBatchSession() = model.endBatchSession()

    override suspend fun commitStroke(stroke: Stroke) {
        val added = model.addStroke(stroke)
        if (added != null) {
            withContext(Dispatchers.Main) {
                renderer.updateTilesWithStroke(added)
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun addStrokes(strokes: Sequence<Stroke>) {
        val batchSize = 500 // Update renderer every 500 strokes to avoid UI freeze or OOM
        val batch = ArrayList<Stroke>(batchSize)

        for (stroke in strokes) {
            if (!coroutineContext.isActive) break

            val added = model.addStroke(stroke)
            if (added != null) {
                batch.add(added)
            }

            if (batch.size >= batchSize) {
                val batchCopy = ArrayList(batch)
                withContext(Dispatchers.Main) {
                    renderer.updateTilesWithItems(batchCopy)
                }
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                renderer.updateTilesWithItems(batch)
            }
        }

        withContext(Dispatchers.Main) {
            onContentChangedListener?.invoke()
        }
    }

    override suspend fun previewEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        val invalidated = model.erase(stroke, type)
        withContext(Dispatchers.Main) {
            if (type == EraserType.STANDARD) {
                renderer.updateTilesWithErasure(stroke)
            } else if (invalidated != null) {
                renderer.refreshTiles(invalidated)
            }
        }
    }

    override suspend fun commitEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        val invalidated = model.erase(stroke, type)
        withContext(Dispatchers.Main) {
            if (type == EraserType.STANDARD) {
                renderer.updateTilesWithErasure(stroke)
            } else if (invalidated != null) {
                renderer.refreshTiles(invalidated)
            }
            onContentChangedListener?.invoke()
        }
    }

    // --- Page Navigation ---
    override suspend fun getCurrentPageIndex(): Int {
        val offsetY = viewportController?.getViewportOffset()?.second ?: return 0
        val pageFullHeight = model.pageHeight + com.alexdremov.notate.config.CanvasConfig.PAGE_SPACING
        return kotlin.math
            .floor(offsetY / pageFullHeight)
            .toInt()
            .coerceAtLeast(0)
    }

    override suspend fun getTotalPages(): Int {
        val contentPages = model.getTotalPages()
        val current = getCurrentPageIndex() + 1
        return kotlin.math.max(contentPages, current)
    }

    override suspend fun jumpToPage(index: Int) {
        if (index < 0) return
        val bounds = model.getPageBounds(index)
        withContext(Dispatchers.Main) {
            viewportController?.scrollTo(0f, bounds.top)
        }
    }

    override suspend fun nextPage() = jumpToPage(getCurrentPageIndex() + 1)

    override suspend fun prevPage() = if (getCurrentPageIndex() > 0) jumpToPage(getCurrentPageIndex() - 1) else Unit

    // --- Selection & Queries ---
    override fun getSelectionManager(): SelectionManager = selectionManager

    override suspend fun getItemAt(
        x: Float,
        y: Float,
    ): CanvasItem? = model.hitTest(x, y, 20f)

    override fun getItemAtSync(
        x: Float,
        y: Float,
    ): CanvasItem? = model.hitTestSync(x, y, 20f)

    override suspend fun getItemsInRect(rect: RectF): List<CanvasItem> =
        withContext(Dispatchers.Default) {
            val result = ArrayList<CanvasItem>()

            model.visitItemsInRect(rect) { item ->
                val matches =
                    if (rect.contains(item.bounds)) {
                        true
                    } else if (item is Stroke) {
                        StrokeGeometry.strokeIntersectsRect(item, rect)
                    } else {
                        RectF.intersects(rect, item.bounds)
                    }

                if (matches) {
                    result.add(item)
                }
            }
            result
        }

    override suspend fun getItemsInPath(path: Path): List<CanvasItem> {
        val bounds = RectF()
        path.computeBounds(bounds, true)

        return withContext(Dispatchers.Default) {
            var pathPoints = StrokeGeometry.flattenPath(path, 15f)
            pathPoints = StrokeGeometry.simplifyPoints(pathPoints, 5.0f)

            val result = ArrayList<CanvasItem>()

            model.visitItemsInRect(bounds) { item ->
                if (!bounds.contains(item.bounds)) return@visitItemsInRect

                val matches =
                    if (StrokeGeometry.isRectFullyInPolygon(item.bounds, pathPoints)) {
                        true
                    } else if (item is Stroke) {
                        item.points.all { p ->
                            StrokeGeometry.isPointInPolygon(p.x, p.y, pathPoints)
                        }
                    } else {
                        val b = item.bounds
                        StrokeGeometry.isPointInPolygon(b.left, b.top, pathPoints) &&
                            StrokeGeometry.isPointInPolygon(b.right, b.top, pathPoints) &&
                            StrokeGeometry.isPointInPolygon(b.right, b.bottom, pathPoints) &&
                            StrokeGeometry.isPointInPolygon(b.left, b.bottom, pathPoints)
                    }

                if (matches) {
                    result.add(item)
                }
            }
            result
        }
    }

    override suspend fun selectItem(item: CanvasItem) {
        selectionManager.select(item)
        generateSelectionImposter()
        withContext(Dispatchers.Main) {
            renderer.setHiddenItems(selectionManager.getSelectedIds())
            renderer.hideItemsInCache(listOf(item))
            renderer.invalidate()
        }
    }

    override suspend fun selectItems(items: List<CanvasItem>) {
        selectionManager.selectAll(items)
        generateSelectionImposter()
        withContext(Dispatchers.Main) {
            renderer.setHiddenItems(selectionManager.getSelectedIds())
            renderer.hideItemsInCache(items)
            renderer.invalidate()
        }
    }

    override suspend fun clearSelection() {
        // Ensure pending transforms are committed before clearing
        if (selectionManager.hasSelection()) {
            // We use commitMoveSelection(false) to finalize the move and clear the selection in one go.
            // commitMoveSelection checks for identity transform, so it's safe to call even if no move occurred.
            commitMoveSelection(false)
        }

        // Standard clear logic (cleanup if commit didn't run or to ensure UI reset)
        if (selectionManager.hasSelection()) {
            val bounds = selectionManager.getTransformedBounds()
            val ids = selectionManager.getSelectedIds()

            // BAKING: Only query items for very small selections to provide instant feedback.
            // For large selections, we rely on TileManager's background refresh to avoid UI freeze.
            val items =
                if (ids.size < 50) {
                    getItemsInRect(bounds).filter { ids.contains(it.order) }
                } else {
                    emptyList()
                }

            selectionManager.clearSelection()
            updatePinnedRegions()

            withContext(Dispatchers.Main) {
                renderer.setHiddenItems(emptySet())
                if (items.isNotEmpty()) {
                    renderer.updateTilesWithItems(items)
                }
                renderer.refreshTiles(bounds)
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun deleteSelection() {
        operationMutex.withLock {
            if (selectionManager.hasSelection()) {
                val bounds = selectionManager.getTransformedBounds()
                val ids = selectionManager.getSelectedIds()
                selectionManager.clearSelection()
                updatePinnedRegions()

                withContext(Dispatchers.Default) {
                    model.deleteItemsByIds(bounds, ids, context.cacheDir)
                }

                withContext(Dispatchers.Main) {
                    renderer.setHiddenItems(emptySet())
                    renderer.invalidateTiles(bounds)
                    onContentChangedListener?.invoke()
                }
            }
        }
    }

    override suspend fun copySelection() {
        if (selectionManager.hasSelection()) {
            val ids = selectionManager.getSelectedIds()
            if (ids.size > 1000) {
                // Prevent OOM/Freeze on massive copy
                withContext(Dispatchers.Main) {
                    android.widget.Toast
                        .makeText(context, "Selection too large to copy", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                return
            }

            val items = fetchSelectedItems()
            ClipboardManager.copy(items)
        }
    }

    override suspend fun paste(
        x: Float,
        y: Float,
    ) {
        operationMutex.withLock {
            if (ClipboardManager.hasContent()) {
                val items = ClipboardManager.getItems()
                if (items.isEmpty()) return@withLock

                val bounds = RectF()
                bounds.set(items[0].bounds)
                for (item in items) bounds.union(item.bounds)

                val dx = x - bounds.centerX()
                val dy = y - bounds.centerY()

                val matrix = Matrix()
                matrix.setTranslate(dx, dy)

                val pastedItems = ArrayList<CanvasItem>()

                startBatchSession()
                items.forEach { item ->
                    val newItem =
                        when (item) {
                            is Stroke -> {
                                val newPath = Path(item.path)
                                newPath.transform(matrix)
                                val newPoints =
                                    item.points.map { p ->
                                        com.onyx.android.sdk.data.note.TouchPoint(
                                            p.x + dx,
                                            p.y + dy,
                                            p.pressure,
                                            p.size,
                                            p.timestamp,
                                        )
                                    }
                                val newBounds = RectF(item.bounds)
                                matrix.mapRect(newBounds)
                                item.copy(path = newPath, points = newPoints, bounds = newBounds, strokeOrder = 0)
                            }

                            is CanvasImage -> {
                                val newBounds = RectF(item.bounds)
                                matrix.mapRect(newBounds)
                                item.copy(bounds = newBounds, order = 0)
                            }

                            else -> {
                                Log.w("CanvasControllerImpl", "Unsupported CanvasItem during paste")
                                return@forEach
                            }
                        }

                    val added = model.addItem(newItem)
                    if (added != null) {
                        pastedItems.add(added)
                    }
                }
                endBatchSession()

                withContext(Dispatchers.Main) {
                    renderer.updateTilesWithItems(pastedItems)
                    selectionManager.clearSelection()
                    selectionManager.selectAll(pastedItems)
                    renderer.setHiddenItems(selectionManager.getSelectedIds())
                    renderer.hideItemsInCache(pastedItems)
                    generateSelectionImposter()
                    renderer.invalidate()
                    onContentChangedListener?.invoke()
                }
            }
        }
    }

    override suspend fun pasteImage(
        uri: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        operationMutex.withLock {
            val importedPath = model.importImage(Uri.parse(uri), context) ?: uri
            val bounds = RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
            val image =
                CanvasImage(
                    uri = importedPath,
                    bounds = bounds,
                    zIndex = 0f,
                    order = 0,
                )

            val added = model.addItem(image)
            if (added != null) {
                withContext(Dispatchers.Main) {
                    renderer.updateTilesWithItem(added)
                    selectionManager.clearSelection()
                    selectionManager.select(added)
                    renderer.setHiddenItems(selectionManager.getSelectedIds())
                    renderer.hideItemsInCache(listOf(added))
                    generateSelectionImposter()
                    renderer.invalidate()
                    onContentChangedListener?.invoke()
                }
            }
        }
    }

    override suspend fun addText(
        text: String,
        x: Float,
        y: Float,
        fontSize: Float,
        color: Int,
    ) {
        if (text.isBlank()) return

        operationMutex.withLock {
            // Default width for new text box
            val defaultWidth = 500f
            // Measure actual height immediately
            val measuredHeight =
                com.alexdremov.notate.util.TextRenderer.measureHeight(
                    context,
                    text,
                    defaultWidth,
                    fontSize,
                )

            val bounds = RectF(x, y, x + defaultWidth, y + measuredHeight)

            val textItem =
                com.alexdremov.notate.model.TextItem(
                    text = text,
                    fontSize = fontSize,
                    color = color,
                    bounds = bounds,
                )

            val added = model.addItem(textItem)
            if (added != null) {
                withContext(Dispatchers.Main) {
                    renderer.updateTilesWithItem(added)
                    // Select the new text item to allow immediate moving/resizing
                    selectionManager.clearSelection()
                    selectionManager.select(added)
                    renderer.setHiddenItems(selectionManager.getSelectedIds())
                    renderer.hideItemsInCache(listOf(added))
                    generateSelectionImposter()
                    renderer.invalidate()
                    onContentChangedListener?.invoke()
                }
            }
        }
    }

    override suspend fun updateText(
        oldItem: com.alexdremov.notate.model.TextItem,
        newText: String,
    ) {
        if (newText.isBlank()) {
            operationMutex.withLock {
                val bounds = selectionManager.getItemWorldAABB(oldItem)
                val ids = setOf(oldItem.order)
                selectionManager.clearSelection()
                updatePinnedRegions()

                withContext(Dispatchers.Default) {
                    model.deleteItemsByIds(bounds, ids, context.cacheDir)
                }

                withContext(Dispatchers.Main) {
                    renderer.setHiddenItems(emptySet())
                    renderer.invalidateTiles(bounds)
                    onContentChangedListener?.invoke()
                }
            }
            return
        }

        operationMutex.withLock {
            // Measure new height
            val newHeight =
                com.alexdremov.notate.util.TextRenderer.measureHeight(
                    context,
                    newText,
                    oldItem.bounds.width(),
                    oldItem.fontSize,
                )

            // Create new item with updated text and bounds
            val newBounds =
                RectF(
                    oldItem.bounds.left,
                    oldItem.bounds.top,
                    oldItem.bounds.right,
                    oldItem.bounds.top + newHeight,
                )

            val newItem = oldItem.copy(text = newText, bounds = newBounds)

            // Apply to Model and get the committed item with new ID
            val committedItems = model.replaceItems(listOf(oldItem), listOf(newItem))
            val committedItem = committedItems.firstOrNull() as? com.alexdremov.notate.model.TextItem ?: return@withLock

            withContext(Dispatchers.Main) {
                // Calculate invalidation area (Union of old and new)
                val unionBounds = RectF(oldItem.bounds)
                unionBounds.union(newBounds)
                unionBounds.inset(-10f, -10f) // Inflate for safety (shadows, anti-aliasing)

                // Force refresh
                renderer.refreshTiles(unionBounds)

                // Update renderer cache with new item
                renderer.updateTilesWithItem(committedItem)

                // Re-select to allow immediate further editing/moving
                selectionManager.clearSelection()
                selectionManager.select(committedItem)
                renderer.setHiddenItems(selectionManager.getSelectedIds())
                renderer.hideItemsInCache(listOf(committedItem))
                generateSelectionImposter()

                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun updateSelectedTextStyle(
        fontSize: Float?,
        color: Int?,
    ) {
        if (!selectionManager.hasSelection()) return

        operationMutex.withLock {
            val selectedItems = fetchSelectedItems()
            val textItems = selectedItems.filterIsInstance<com.alexdremov.notate.model.TextItem>()

            if (textItems.isEmpty()) return@withLock

            val updatedItems =
                textItems.map { item ->
                    val newFontSize = fontSize ?: item.fontSize
                    val newColor = color ?: item.color

                    // Re-measure height if font size changed
                    val newHeight =
                        if (newFontSize != item.fontSize) {
                            com.alexdremov.notate.util.TextRenderer.measureHeight(
                                context,
                                item.text,
                                item.bounds.width(),
                                newFontSize,
                            )
                        } else {
                            item.bounds.height()
                        }

                    val newBounds =
                        RectF(
                            item.bounds.left,
                            item.bounds.top,
                            item.bounds.right,
                            item.bounds.top + newHeight,
                        )

                    item.copy(fontSize = newFontSize, color = newColor, bounds = newBounds)
                }

            val committedItems = model.replaceItems(textItems, updatedItems)

            withContext(Dispatchers.Main) {
                val totalBounds = RectF()
                textItems.forEach { totalBounds.union(selectionManager.getItemWorldAABB(it)) }
                committedItems.forEach { totalBounds.union(selectionManager.getItemWorldAABB(it)) }
                totalBounds.inset(-30f, -30f) // Slightly larger padding for safety

                renderer.refreshTiles(totalBounds)
                renderer.updateTilesWithItems(committedItems)

                // Re-select updated items to keep handles correct and allow further updates
                selectionManager.clearSelection()
                selectionManager.selectAll(committedItems)

                // Sync renderer hidden state with new selection
                renderer.setHiddenItems(selectionManager.getSelectedIds())
                renderer.hideItemsInCache(committedItems)
                generateSelectionImposter()

                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return

        val bounds = selectionManager.getTransformedBounds()
        val ids = selectionManager.getSelectedIds()

        startBatchSession()

        // 3. Inform renderer to hide items
        withContext(Dispatchers.Main) {
            renderer.setHiddenItems(ids)
            // Instant hide optimization for small selections
            if (ids.size < 500) {
                val items = withContext(Dispatchers.Default) { fetchSelectedItems() }
                renderer.hideItemsInCache(items)
            }
            renderer.invalidateTiles(bounds)
        }
    }

    private suspend fun generateSelectionImposter() {
        if (!selectionManager.hasSelection()) return
        val bounds = selectionManager.getTransformedBounds()
        val ids = selectionManager.getSelectedIds()

        // Skip for massive selections to prevent O(N) scan or massive bitmap
        if (ids.size > 2000) {
            withContext(Dispatchers.Main) {
                selectionManager.clearImposter() // Ensure no stale imposter
                renderer.invalidate() // Will fallback to vector/overlay
            }
            return
        }

        selectionManager.isGeneratingImposter = true
        withContext(Dispatchers.Main) { renderer.invalidate() }

        withContext(Dispatchers.Default) {
            try {
                val padding = 5
                val width = bounds.width().toInt() + (padding * 2)
                val height = bounds.height().toInt() + (padding * 2)

                if (width in 1..4096 && height in 1..4096) {
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(-(bounds.left - padding), -(bounds.top - padding))

                    model.visitItemsInRect(bounds) { item ->
                        if (ids.contains(item.order)) {
                            renderer.drawItemToCanvas(canvas, item)
                        }
                    }

                    val matrix = Matrix()
                    matrix.setTranslate(bounds.left - padding, bounds.top - padding)

                    withContext(Dispatchers.Main) {
                        if (selectionManager.hasSelection()) {
                            selectionManager.setImposter(bitmap, matrix)
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("CanvasController", "Async imposter generation failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    selectionManager.isGeneratingImposter = false
                    renderer.invalidate()
                }
            }
        }
    }

    private fun transformItem(
        item: CanvasItem,
        transform: Matrix,
    ): CanvasItem =
        when (item) {
            is Stroke -> {
                val newPath = Path(item.path)
                newPath.transform(transform)

                // Recalculate width if scale changed
                val values = FloatArray(9)
                transform.getValues(values)
                val scale =
                    kotlin.math.sqrt(
                        values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] +
                            values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y],
                    )
                val newWidth = item.width * scale

                // Use consistent bounds calculation logic
                val newBounds = StrokeGeometry.computeStrokeBounds(newPath, newWidth, item.style)

                val newPoints =
                    item.points.map { p ->
                        val pts = floatArrayOf(p.x, p.y)
                        transform.mapPoints(pts)
                        com.onyx.android.sdk.data.note
                            .TouchPoint(pts[0], pts[1], p.pressure, p.size, p.timestamp)
                    }

                item.copy(path = newPath, points = newPoints, bounds = newBounds, width = newWidth)
            }

            is CanvasImage -> {
                // 1. Calculate new center in World Space
                val center = floatArrayOf(item.bounds.centerX(), item.bounds.centerY())
                transform.mapPoints(center)

                // 2. Calculate new width and height (decomposing scaling from the matrix)
                val vW = floatArrayOf(item.bounds.width(), 0f)
                transform.mapVectors(vW)
                val newWidth = kotlin.math.hypot(vW[0], vW[1])

                val vH = floatArrayOf(0f, item.bounds.height())
                transform.mapVectors(vH)
                val newHeight = kotlin.math.hypot(vH[0], vH[1])

                // 3. Calculate rotation change
                val vRot = floatArrayOf(1f, 0f)
                transform.mapVectors(vRot)
                val deltaRot = Math.toDegrees(kotlin.math.atan2(vRot[1].toDouble(), vRot[0].toDouble())).toFloat()

                val newBounds =
                    RectF(
                        center[0] - newWidth / 2f,
                        center[1] - newHeight / 2f,
                        center[0] + newWidth / 2f,
                        center[1] + newHeight / 2f,
                    )

                item.copy(bounds = newBounds, rotation = item.rotation + deltaRot)
            }

            is com.alexdremov.notate.model.TextItem -> {
                // 1. Calculate new center in World Space
                val center = floatArrayOf(item.bounds.centerX(), item.bounds.centerY())
                transform.mapPoints(center)

                // 2. Calculate new width (scaling along the local horizontal axis)
                val vW = floatArrayOf(item.bounds.width(), 0f)
                transform.mapVectors(vW)
                val newWidth = kotlin.math.hypot(vW[0], vW[1])

                // 3. Calculate rotation change
                val vRot = floatArrayOf(1f, 0f)
                transform.mapVectors(vRot)
                val deltaRot = Math.toDegrees(kotlin.math.atan2(vRot[1].toDouble(), vRot[0].toDouble())).toFloat()

                // 4. USER REQUEST: Keep font size constant.
                // Text reflows into the new width.
                val fontSize = item.fontSize

                // 5. Re-measure height based on new width
                val newHeight =
                    com.alexdremov.notate.util.TextRenderer.measureHeight(
                        context,
                        item.text,
                        newWidth,
                        fontSize,
                    )

                // 6. Reconstruct logical bounds (axis-aligned in logical space)
                val newBounds =
                    RectF(
                        center[0] - newWidth / 2f,
                        center[1] - newHeight / 2f,
                        center[0] + newWidth / 2f,
                        center[1] + newHeight / 2f,
                    )

                item.copy(
                    bounds = newBounds,
                    fontSize = fontSize,
                    rotation = item.rotation + deltaRot,
                )
            }

            else -> {
                item
            }
        }

    override suspend fun moveSelection(
        dx: Float,
        dy: Float,
    ) {
        selectionManager.translate(dx, dy)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun transformSelection(matrix: Matrix) {
        selectionManager.applyTransform(matrix)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun commitMoveSelection(shouldReselect: Boolean) {
        operationMutex.withLock {
            if (!selectionManager.hasSelection()) return@withLock

            val originalBounds = selectionManager.getOriginalBounds()
            val transform = selectionManager.getTransform()
            val ids = selectionManager.getSelectedIds()

            if (transform.isIdentity && shouldReselect) {
                // No move, just return
                return@withLock
            }

            // Remove isIdentity optimization for !shouldReselect to ensure we clear/finalize correctly.

            // Large Selection Strategy: Disk Stash
            // Prevents OOM by streaming items to disk instead of holding them all in a List<CanvasItem>.
            if (ids.size > LARGE_SELECTION_THRESHOLD) {
                commitLargeSelectionMove(ids, originalBounds, transform, shouldReselect)
            } else {
                commitStandardSelectionMove(ids, transform, shouldReselect)
            }
        }
    }

    private suspend fun commitLargeSelectionMove(
        ids: Set<Long>,
        originalBounds: RectF,
        transform: Matrix,
        shouldReselect: Boolean,
    ) {
        val stashFile = File(context.cacheDir, "move_stash_${System.currentTimeMillis()}.bin")

        try {
            withContext(Dispatchers.Main) {
                progressCallback?.invoke(true, "Optimizing Selection...", 10)
            }

            startBatchSession()

            // 1. Stash & Remove (Writes to disk, removes from RAM/Index)
            withContext(Dispatchers.IO) {
                model.stashItems(originalBounds, ids, stashFile)
            }

            // 2. Clear Selection (IDs are now invalid/removed)
            selectionManager.clearSelection()

            // 3. Unstash & Transform (Reads from disk, adds to RAM/Index in batches)
            withContext(Dispatchers.Main) {
                progressCallback?.invoke(true, "Committing...", 60)
            }

            // Callback populates SelectionManager efficiently without accumulating a heavy List<CanvasItem>.
            val (_, newBounds) =
                withContext(Dispatchers.IO) {
                    model.unstashItems(stashFile, transform) { item ->
                        // Thread-safe call to populate metadata (ID + Bounds)
                        // The heavy item object is then discarded/GC'd.
                        if (shouldReselect) {
                            selectionManager.select(item)
                        }
                    }
                }

            endBatchSession()

            updatePinnedRegions()
            selectionManager.clearImposter()

            withContext(Dispatchers.Main) {
                if (shouldReselect) {
                    renderer.setHiddenItems(selectionManager.getSelectedIds())

                    // Invalidate old and new areas
                    renderer.invalidateTiles(originalBounds)
                    renderer.invalidateTiles(newBounds)
                    renderer.invalidate()
                    generateSelectionImposter()
                } else {
                    renderer.setHiddenItems(emptySet())
                    renderer.invalidateTiles(originalBounds)
                    renderer.invalidateTiles(newBounds)
                    renderer.invalidate()
                }
                onContentChangedListener?.invoke()
            }
        } catch (e: Exception) {
            Log.e("CanvasController", "Large move failed", e)
            endBatchSession()
        } finally {
            stashFile.delete()
            withContext(Dispatchers.Main) {
                progressCallback?.invoke(false, null, 0)
            }
        }
    }

    private suspend fun commitStandardSelectionMove(
        ids: Set<Long>,
        transform: Matrix,
        shouldReselect: Boolean,
    ) {
        // Standard Path (In-Memory)
        try {
            if (ids.size > 50) {
                withContext(Dispatchers.Main) {
                    progressCallback?.invoke(true, "Saving Move...", 30)
                }
            }

            val originalItems = fetchSelectedItems()

            if (originalItems.isEmpty()) {
                // Critical Failure: Could not find items to move.
                // Just clear selection to reset state and avoid sticking in floating mode.
                selectionManager.clearSelection()

                withContext(Dispatchers.Main) {
                    renderer.setHiddenItems(emptySet())
                    renderer.invalidate()
                    onContentChangedListener?.invoke()
                }
                return
            }

            // Transform items in memory
            val newItems = originalItems.map { transformItem(it, transform) }

            // Calculate new bounds
            val newBounds = RectF()
            if (newItems.isNotEmpty()) {
                newBounds.set(newItems[0].bounds)
                for (i in 1 until newItems.size) newBounds.union(newItems[i].bounds)
            }
            newBounds.inset(-5f, -5f) // Safety padding

            // Also calculate original bounds for invalidation
            val originalBounds = RectF()
            if (originalItems.isNotEmpty()) {
                originalBounds.set(originalItems[0].bounds)
                for (i in 1 until originalItems.size) originalBounds.union(originalItems[i].bounds)
            }
            originalBounds.inset(-5f, -5f) // Safety padding

            // Apply to Model
            if (ids.size > 50) {
                withContext(Dispatchers.Main) {
                    progressCallback?.invoke(true, "Updating Database...", 70)
                }
            }

            val committedItems =
                withContext(Dispatchers.IO) {
                    model.replaceItems(originalItems, newItems)
                }

            endBatchSession()

            // ALWAYS LIFTED STRATEGY:
            // Clear old selection state and re-select new items.
            // This keeps them "lifted" visually.
            selectionManager.clearSelection()

            if (shouldReselect) {
                selectionManager.selectAll(committedItems) // Re-select transformed items WITH CORRECT IDs
                updatePinnedRegions()
                selectionManager.clearImposter()

                withContext(Dispatchers.Main) {
                    // Atomic transition for re-selected move:
                    if (originalItems.isNotEmpty()) {
                        renderer.hideItemsInCache(originalItems)
                    }
                    if (committedItems.isNotEmpty()) {
                        renderer.hideItemsInCache(committedItems)
                    }
                    renderer.setHiddenItems(selectionManager.getSelectedIds())

                    renderer.invalidateTiles(originalBounds)
                    renderer.invalidateTiles(newBounds)

                    renderer.invalidate()
                    generateSelectionImposter()
                    onContentChangedListener?.invoke()
                }
            } else {
                withContext(Dispatchers.Main) {
                    // Atomic transition for finalizing move:
                    // 1. Unhide everything (so standard rendering logic takes over)
                    renderer.setHiddenItems(emptySet())

                    // 2. Explicitly remove old items from cache if the renderer supports it
                    // (This might be redundant if invalidateTiles forces a full refresh, but safe)
                    renderer.hideItemsInCache(originalItems)

                    // 3. Update cache with new items immediately
                    if (committedItems.isNotEmpty()) {
                        renderer.updateTilesWithItems(committedItems)
                    }

                    // 4. Force refresh of the dirty areas (Old position -> Clear, New Position -> Draw)
                    renderer.refreshTiles(originalBounds) // Use refreshTiles for stronger invalidation
                    renderer.refreshTiles(newBounds)

                    renderer.invalidate()
                    onContentChangedListener?.invoke()
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                progressCallback?.invoke(false, null, 0)
            }
        }
    }

    private suspend fun fetchSelectedItems(): List<CanvasItem> {
        val items = ArrayList<CanvasItem>()
        val missingIds = ArrayList<Long>()

        // Use SelectionManager's internal iterator to avoid allocating lists of IDs/Bounds
        // This is memory efficient (O(1) allocation) and allows pinpoint item retrieval.
        val snapshotItems = ArrayList<Pair<Long, RectF>>()

        selectionManager.forEachSelected { id, bounds ->
            snapshotItems.add(Pair(id, RectF(bounds)))
        }

        // We must query outside the lock/iterator to avoid blocking SelectionManager
        // while performing IO (RegionManager loads).
        for ((id, bounds) in snapshotItems) {
            // 1. Fast Path: Direct Lookup (Center + Overlaps)
            var item = model.getItem(id, bounds)

            // 2. Slow Path: Broad Spatial Query
            // If findItem failed (e.g. due to precision errors causing center region mismatch),
            // we perform a broader query of all items in the area and filter by ID.
            if (item == null) {
                val inflated = RectF(bounds)
                inflated.inset(-50f, -50f) // Generous margin to catch boundary items
                val candidates = model.queryItems(inflated)
                item = candidates.find { it.order == id }
            }

            // 3. Paranoid Path: Global Cache Scan
            // If spatial lookup fails entirely (e.g. item moved significantly without index update,
            // or bounds in SelectionManager are stale/wrong), scan all loaded regions.
            if (item == null) {
                val rm = model.getRegionManager()
                if (rm != null) {
                    val activeIds = rm.getActiveRegionIds()
                    for (rId in activeIds) {
                        val region = rm.getRegion(rId)
                        val found = region.items.find { it.order == id }
                        if (found != null) {
                            item = found
                            break
                        }
                    }
                }
            }

            if (item != null) {
                items.add(item)
            } else {
                missingIds.add(id)
            }
        }

        if (missingIds.isNotEmpty()) {
            Log.e(
                "CanvasController",
                "CRITICAL: Failed to fetch ${missingIds.size} selected items. IDs: ${missingIds.take(
                    10,
                )}... This will cause duplication or data loss.",
            )
        }

        return items
    }

    private fun updatePinnedRegions() {
        // Pinning disabled per requirements to avoid OOM on large selections.
        // SelectionManager holds items in memory for overlay rendering.
    }
}
