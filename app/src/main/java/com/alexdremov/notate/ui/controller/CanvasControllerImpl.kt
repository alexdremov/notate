package com.alexdremov.notate.controller

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import java.util.ArrayList
import java.util.stream.Collectors

class CanvasControllerImpl(
    private val context: Context,
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
) : CanvasController {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var viewportController: com.alexdremov.notate.controller.ViewportController? = null
    private val selectionManager = SelectionManager()
    private var onContentChangedListener: (() -> Unit)? = null

    override fun setOnContentChangedListener(listener: () -> Unit) {
        this.onContentChangedListener = listener
    }

    override fun setViewportController(controller: com.alexdremov.notate.controller.ViewportController) {
        this.viewportController = controller
    }

    override fun startBatchSession() = model.startBatchSession()

    override fun endBatchSession() = model.endBatchSession()

    override fun commitStroke(stroke: Stroke) {
        val added = model.addStroke(stroke)
        if (added != null) {
            runOnUi {
                renderer.updateTilesWithStroke(added)
                onContentChangedListener?.invoke()
            }
        }
    }

    override fun previewEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        val invalidated = model.erase(stroke, type)
        runOnUi {
            if (type == EraserType.STANDARD) {
                renderer.updateTilesWithErasure(stroke)
            } else if (invalidated != null) {
                renderer.refreshTiles(invalidated)
            }
        }
    }

    override fun commitEraser(
        stroke: Stroke,
        type: EraserType,
    ) {
        val invalidated = model.erase(stroke, type)
        runOnUi {
            if ((type == EraserType.LASSO || type == EraserType.STROKE) && invalidated != null) {
                renderer.invalidateTiles(invalidated)
            } else if (type == EraserType.STANDARD) {
                renderer.refreshTiles(stroke.bounds)
            }
            onContentChangedListener?.invoke()
        }
    }

    override fun setStrokeWidth(width: Float) {}

    // --- Page Navigation ---
    override fun getCurrentPageIndex(): Int {
        val offsetY = viewportController?.getViewportOffset()?.second ?: return 0
        val pageFullHeight = model.pageHeight + com.alexdremov.notate.config.CanvasConfig.PAGE_SPACING
        return kotlin.math
            .floor(offsetY / pageFullHeight)
            .toInt()
            .coerceAtLeast(0)
    }

    override fun getTotalPages(): Int {
        val contentPages = model.getTotalPages()
        val current = getCurrentPageIndex() + 1
        return kotlin.math.max(contentPages, current)
    }

    override fun jumpToPage(index: Int) {
        if (index < 0) return
        val bounds = model.getPageBounds(index)
        runOnUi { viewportController?.scrollTo(0f, bounds.top) }
    }

    override fun nextPage() = jumpToPage(getCurrentPageIndex() + 1)

    override fun prevPage() = if (getCurrentPageIndex() > 0) jumpToPage(getCurrentPageIndex() - 1) else Unit

    // --- Selection & Queries ---
    override fun getSelectionManager(): SelectionManager = selectionManager

    override fun getItemAt(
        x: Float,
        y: Float,
    ): com.alexdremov.notate.model.CanvasItem? = model.hitTest(x, y, 20f)

    override fun getItemsInRect(rect: android.graphics.RectF): List<com.alexdremov.notate.model.CanvasItem> {
        val items = model.queryItems(rect)
        return items.filter { android.graphics.RectF.intersects(rect, it.bounds) }
    }

    override fun getItemsInPath(path: android.graphics.Path): List<com.alexdremov.notate.model.CanvasItem> {
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        val items = model.queryItems(bounds)

        // Strict Lasso Logic: Convert path to polygon points
        // Use a coarser step (15f) for faster polygon construction without losing much precision for selection
        val pathPoints =
            com.alexdremov.notate.util.StrokeGeometry
                .flattenPath(path, 15f)

        // Parallel processing for heavy geometric checks
        return items
            .parallelStream()
            .filter { item ->
                if (!bounds.contains(item.bounds)) return@filter false

                if (item is Stroke) {
                    item.points.all { p ->
                        com.alexdremov.notate.util.StrokeGeometry
                            .isPointInPolygon(p.x, p.y, pathPoints)
                    }
                } else {
                    // For images, check corners
                    val b = item.bounds
                    com.alexdremov.notate.util.StrokeGeometry
                        .isPointInPolygon(b.left, b.top, pathPoints) &&
                        com.alexdremov.notate.util.StrokeGeometry
                            .isPointInPolygon(b.right, b.top, pathPoints) &&
                        com.alexdremov.notate.util.StrokeGeometry
                            .isPointInPolygon(b.right, b.bottom, pathPoints) &&
                        com.alexdremov.notate.util.StrokeGeometry
                            .isPointInPolygon(b.left, b.bottom, pathPoints)
                }
            }.collect(Collectors.toList())
    }

    override fun selectItem(item: com.alexdremov.notate.model.CanvasItem) {
        selectionManager.select(item)
        runOnUi { renderer.invalidate() }
    }

    override fun selectItems(items: List<com.alexdremov.notate.model.CanvasItem>) {
        selectionManager.selectAll(items)
        runOnUi { renderer.invalidate() }
    }

    override fun clearSelection() {
        if (selectionManager.hasSelection()) {
            selectionManager.clearSelection()
            runOnUi { renderer.invalidate() }
        }
    }

    override fun deleteSelection() {
        if (selectionManager.hasSelection()) {
            val toRemove = selectionManager.selectedItems.toList()
            selectionManager.clearSelection()
            deleteItemsFromModel(toRemove)
            runOnUi { onContentChangedListener?.invoke() }
        }
    }

    override fun copySelection() {
        if (selectionManager.hasSelection()) {
            com.alexdremov.notate.util.ClipboardManager
                .copy(selectionManager.selectedItems)
        }
    }

    override fun paste(
        x: Float,
        y: Float,
    ) {
        if (com.alexdremov.notate.util.ClipboardManager
                .hasContent()
        ) {
            val items =
                com.alexdremov.notate.util.ClipboardManager
                    .getItems()
            if (items.isEmpty()) return

            val bounds = android.graphics.RectF()
            bounds.set(items[0].bounds)
            for (item in items) bounds.union(item.bounds)

            val dx = x - bounds.centerX()
            val dy = y - bounds.centerY()

            val matrix = android.graphics.Matrix()
            matrix.setTranslate(dx, dy)

            val pastedItems = ArrayList<com.alexdremov.notate.model.CanvasItem>()

            startBatchSession()
            items.forEach { item ->
                val newItem =
                    when (item) {
                        is Stroke -> {
                            val newPath = android.graphics.Path(item.path)
                            newPath.transform(matrix)
                            val newPoints =
                                item.points.map { p ->
                                    com.onyx.android.sdk.data.note
                                        .TouchPoint(p.x + dx, p.y + dy, p.pressure, p.size, p.timestamp)
                                }
                            val newBounds = android.graphics.RectF(item.bounds)
                            matrix.mapRect(newBounds)
                            item.copy(path = newPath, points = newPoints, bounds = newBounds, strokeOrder = 0)
                        }

                        is com.alexdremov.notate.model.CanvasImage -> {
                            val newBounds = android.graphics.RectF(item.bounds)
                            matrix.mapRect(newBounds)
                            item.copy(bounds = newBounds, order = 0)
                        }

                        else -> {
                            throw IllegalArgumentException("Unsupported CanvasItem type: ${item::class.java.name}")
                        }
                    }

                val added = model.addItem(newItem)
                if (added != null) {
                    pastedItems.add(added)
                }
            }
            endBatchSession()

            runOnUi {
                pastedItems.forEach { renderer.updateTilesWithItem(it) }
                selectionManager.clearSelection()
                selectionManager.selectAll(pastedItems)
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override fun pasteImage(
        uri: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        // Import image to session storage first
        val importedPath = model.importImage(Uri.parse(uri), context) ?: uri

        val bounds = android.graphics.RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
        val image =
            com.alexdremov.notate.model.CanvasImage(
                uri = importedPath,
                bounds = bounds,
                zIndex = 0f,
                order = 0,
            )

        val added = model.addItem(image)
        if (added != null) {
            runOnUi {
                renderer.updateTilesWithItem(added)
                selectionManager.clearSelection()
                selectionManager.select(added)
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return
        startBatchSession()
        // Visual Lift
        deleteItemsFromModel(selectionManager.selectedItems.toList())
        runOnUi { renderer.invalidate() }
    }

    override fun moveSelection(
        dx: Float,
        dy: Float,
    ) {
        selectionManager.translate(dx, dy)
        runOnUi { renderer.invalidate() }
    }

    override fun transformSelection(matrix: android.graphics.Matrix) {
        selectionManager.applyTransform(matrix)
        runOnUi { renderer.invalidate() }
    }

    override fun commitMoveSelection() {
        if (!selectionManager.hasSelection()) return

        val originals = selectionManager.selectedItems.toList()
        val transform = selectionManager.getTransform()

        val values = FloatArray(9)
        transform.getValues(values)
        val scaleX = values[android.graphics.Matrix.MSCALE_X]
        val skewY = values[android.graphics.Matrix.MSKEW_Y]
        val scale = kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
        val rotation = kotlin.math.atan2(skewY.toDouble(), scaleX.toDouble()).toFloat() // approximate

        val newSelected = ArrayList<com.alexdremov.notate.model.CanvasItem>()

        // Note: startBatchSession called in startMoveSelection

        originals.forEach { item ->
            val newItem =
                when (item) {
                    is Stroke -> {
                        val newPath = android.graphics.Path(item.path)
                        newPath.transform(transform)

                        val newPoints =
                            item.points.map { p ->
                                val pts = floatArrayOf(p.x, p.y)
                                transform.mapPoints(pts)
                                com.onyx.android.sdk.data.note.TouchPoint(
                                    pts[0],
                                    pts[1],
                                    p.pressure,
                                    p.size * scale,
                                    p.timestamp,
                                )
                            }

                        val newBounds = android.graphics.RectF(item.bounds)
                        transform.mapRect(newBounds)

                        item.copy(path = newPath, points = newPoints, bounds = newBounds, width = item.width * scale)
                    }

                    is com.alexdremov.notate.model.CanvasImage -> {
                        val newBounds = android.graphics.RectF(item.bounds)
                        transform.mapRect(newBounds)
                        // Simple transform of bounds might skew if rotation is involved for images.
                        // But CanvasImage just holds bounds and rotation.
                        // Ideally we should update item.rotation too.
                        // For now, let's just update bounds.
                        item.copy(bounds = newBounds, rotation = item.rotation + Math.toDegrees(rotation.toDouble()).toFloat())
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported CanvasItem type: ${item::class.java.name}")
                    }
                }

            val added = model.addItem(newItem)
            if (added != null) {
                newSelected.add(added)
                runOnUi { renderer.updateTilesWithItem(added) }
            }
        }

        endBatchSession()

        selectionManager.clearSelection()
        selectionManager.selectAll(newSelected)
        selectionManager.resetTransform()

        runOnUi {
            renderer.invalidate()
            onContentChangedListener?.invoke()
        }
    }

    private fun deleteItemsFromModel(items: List<com.alexdremov.notate.model.CanvasItem>) {
        model.deleteItems(items)
        if (items.isNotEmpty()) {
            val bounds = android.graphics.RectF(items[0].bounds)
            items.forEach { bounds.union(it.bounds) }
            runOnUi { renderer.invalidateTiles(bounds) }
        }
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else uiHandler.post(block)
    }
}
