package com.alexdremov.notate.controller

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    ): com.alexdremov.notate.model.CanvasItem? = model.hitTest(x, y, 20f)

    override fun getItemAtSync(
        x: Float,
        y: Float,
    ): com.alexdremov.notate.model.CanvasItem? = model.hitTestSync(x, y, 20f)

    override suspend fun getItemsInRect(rect: android.graphics.RectF): List<com.alexdremov.notate.model.CanvasItem> {
        // Optimized Precise Rect Selection using Visitor (No intermediate list)
        return withContext(Dispatchers.Default) {
            val result = ArrayList<com.alexdremov.notate.model.CanvasItem>()

            model.visitItemsInRect(rect) { item ->
                val matches =
                    if (rect.contains(item.bounds)) {
                        true
                    } else if (item is Stroke) {
                        com.alexdremov.notate.util.StrokeGeometry
                            .strokeIntersectsRect(item, rect)
                    } else {
                        android.graphics.RectF.intersects(rect, item.bounds)
                    }

                if (matches) {
                    result.add(item)
                }
            }
            result
        }
    }

    override suspend fun getItemsInPath(path: android.graphics.Path): List<com.alexdremov.notate.model.CanvasItem> {
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)

        return withContext(Dispatchers.Default) {
            var pathPoints =
                com.alexdremov.notate.util.StrokeGeometry
                    .flattenPath(path, 15f)
            pathPoints =
                com.alexdremov.notate.util.StrokeGeometry
                    .simplifyPoints(pathPoints, 5.0f)

            val result = ArrayList<com.alexdremov.notate.model.CanvasItem>()

            model.visitItemsInRect(bounds) { item ->
                if (!bounds.contains(item.bounds)) return@visitItemsInRect

                val matches =
                    if (com.alexdremov.notate.util.StrokeGeometry
                            .isRectFullyInPolygon(item.bounds, pathPoints)
                    ) {
                        true
                    } else if (item is Stroke) {
                        item.points.all { p ->
                            com.alexdremov.notate.util.StrokeGeometry
                                .isPointInPolygon(p.x, p.y, pathPoints)
                        }
                    } else {
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

                if (matches) {
                    result.add(item)
                }
            }
            result
        }
    }

    override suspend fun selectItem(item: com.alexdremov.notate.model.CanvasItem) {
        selectionManager.select(item)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun selectItems(items: List<com.alexdremov.notate.model.CanvasItem>) {
        selectionManager.selectAll(items)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun clearSelection() {
        if (selectionManager.hasSelection()) {
            selectionManager.clearSelection()
            withContext(Dispatchers.Main) { renderer.invalidate() }
        }
    }

    override suspend fun deleteSelection() {
        if (selectionManager.hasSelection()) {
            val toRemove = selectionManager.selectedItems.toList()
            selectionManager.clearSelection()
            deleteItemsFromModel(toRemove)
            withContext(Dispatchers.Main) { onContentChangedListener?.invoke() }
        }
    }

    override suspend fun copySelection() {
        if (selectionManager.hasSelection()) {
            com.alexdremov.notate.util.ClipboardManager
                .copy(selectionManager.selectedItems)
        }
    }

    override suspend fun paste(
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
                            android.util.Log.w(
                                "CanvasControllerImpl",
                                "Unsupported CanvasItem type during paste: ${item::class.java.name}",
                            )
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
                renderer.invalidate()
                onContentChangedListener?.invoke()
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
            withContext(Dispatchers.Main) {
                renderer.updateTilesWithItem(added)
                selectionManager.clearSelection()
                selectionManager.select(added)
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return
        startBatchSession()

        // 1. Immediate Visual Lift (On Caller Thread/Main)
        // Visually remove items from the canvas tiles.
        // We need to do this first so the user sees the "lift" even if the bitmap isn't ready.
        val itemsToList = selectionManager.selectedItems.toList()
        deleteItemsFromModel(itemsToList)

        selectionManager.isGeneratingImposter = true
        withContext(Dispatchers.Main) { renderer.invalidate() }

        // 2. Async Imposter Generation (Background)
        withContext(Dispatchers.Default) {
            try {
                val bounds = selectionManager.getTransformedBounds()
                val padding = 5
                val width = bounds.width().toInt() + (padding * 2)
                val height = bounds.height().toInt() + (padding * 2)

                // Limit size to prevent OOM
                if (width in 1..4096 && height in 1..4096) {
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)

                    canvas.translate(-(bounds.left - padding), -(bounds.top - padding))

                    model.visitItemsInRect(bounds) { item ->
                        if (selectionManager.isSelected(item)) {
                            renderer.drawItemToCanvas(canvas, item)
                        }
                    }

                    val matrix = android.graphics.Matrix()
                    matrix.setTranslate(bounds.left - padding, bounds.top - padding)

                    withContext(Dispatchers.Main) {
                        if (selectionManager.hasSelection()) {
                            selectionManager.setImposter(bitmap, matrix)
                        } else {
                            bitmap.recycle() // Cancelled
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("CanvasController", "Async imposter generation failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    selectionManager.isGeneratingImposter = false
                    renderer.invalidate()
                }
            }
        }
    }

    override suspend fun moveSelection(
        dx: Float,
        dy: Float,
    ) {
        selectionManager.translate(dx, dy)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun transformSelection(matrix: android.graphics.Matrix) {
        selectionManager.applyTransform(matrix)
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun commitMoveSelection() {
        if (!selectionManager.hasSelection()) return

        val originals = selectionManager.selectedItems.toList()
        val transform = selectionManager.getTransform()

        val values = FloatArray(9)
        transform.getValues(values)
        val scaleX = values[android.graphics.Matrix.MSCALE_X]
        val skewY = values[android.graphics.Matrix.MSKEW_Y]
        val scale = kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
        val rotation = kotlin.math.atan2(skewY.toDouble(), scaleX.toDouble()).toFloat()

        val newSelected = ArrayList<com.alexdremov.notate.model.CanvasItem>()

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
                        item.copy(bounds = newBounds, rotation = item.rotation + Math.toDegrees(rotation.toDouble()).toFloat())
                    }

                    else -> {
                        android.util.Log.w(
                            "CanvasControllerImpl",
                            "Unsupported CanvasItem type: ${item::class.java.name}",
                        )
                        return@forEach
                    }
                }

            val added = model.addItem(newItem)
            if (added != null) {
                newSelected.add(added)
                withContext(Dispatchers.Main) { renderer.updateTilesWithItem(added) }
            }
        }

        endBatchSession()

        selectionManager.clearSelection()
        selectionManager.selectAll(newSelected)
        selectionManager.resetTransform()

        withContext(Dispatchers.Main) {
            renderer.invalidate()
            onContentChangedListener?.invoke()
        }
    }

    private suspend fun deleteItemsFromModel(items: List<com.alexdremov.notate.model.CanvasItem>) {
        model.deleteItems(items)
        if (items.isNotEmpty()) {
            val bounds = android.graphics.RectF(items[0].bounds)
            items.forEach { bounds.union(it.bounds) }
            withContext(Dispatchers.Main) { renderer.invalidateTiles(bounds) }
        }
    }
}
