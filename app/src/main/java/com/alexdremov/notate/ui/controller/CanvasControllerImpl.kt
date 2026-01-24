package com.alexdremov.notate.controller

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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayList

class CanvasControllerImpl(
    private val context: Context,
    private val model: InfiniteCanvasModel,
    private val renderer: CanvasRenderer,
) : CanvasController {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var viewportController: com.alexdremov.notate.controller.ViewportController? = null
    private val selectionManager = SelectionManager()
    private var onContentChangedListener: (() -> Unit)? = null

    private var stashedFile: File? = null

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
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun selectItems(items: List<CanvasItem>) {
        selectionManager.selectAll(items)
        generateSelectionImposter()
        withContext(Dispatchers.Main) { renderer.invalidate() }
    }

    override suspend fun clearSelection() {
        if (selectionManager.hasSelection()) {
            selectionManager.clearSelection()
            stashedFile?.delete()
            stashedFile = null
            withContext(Dispatchers.Main) { renderer.invalidate() }
        }
    }

    override suspend fun deleteSelection() {
        if (selectionManager.hasSelection()) {
            val bounds = selectionManager.getTransformedBounds()
            val ids = selectionManager.getSelectedIds()
            selectionManager.clearSelection()
            stashedFile?.delete()
            stashedFile = null
            model.deleteItemsByIds(bounds, ids)
            withContext(Dispatchers.Main) {
                renderer.invalidateTiles(bounds)
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun copySelection() {
        if (selectionManager.hasSelection()) {
            val bounds = selectionManager.getTransformedBounds()
            val ids = selectionManager.getSelectedIds()
            val items = ArrayList<CanvasItem>()
            model.visitItemsInRect(bounds) { item ->
                if (ids.contains(item.order)) items.add(item)
            }
            ClipboardManager.copy(items)
        }
    }

    override suspend fun paste(
        x: Float,
        y: Float,
    ) {
        if (ClipboardManager.hasContent()) {
            val items = ClipboardManager.getItems()
            if (items.isEmpty()) return

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
                generateSelectionImposter()
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
                generateSelectionImposter()
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override suspend fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return
        startBatchSession()

        val bounds = selectionManager.getTransformedBounds()
        val ids = selectionManager.getSelectedIds()

        // 1. Ensure Imposter is ready (or wait for it)
        if (selectionManager.getImposter() == null) {
            generateSelectionImposter()
        }

        // 2. Stash Selection to Temp File
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "selection_stash_${System.currentTimeMillis()}.bin")
            stashedFile = file
            model.stashItems(bounds, ids, file)
        }

        // 3. Inform renderer to invalidate the "lifted" area
        withContext(Dispatchers.Main) {
            renderer.invalidateTiles(bounds)
        }
    }

    private suspend fun generateSelectionImposter() {
        if (!selectionManager.hasSelection()) return
        val bounds = selectionManager.getTransformedBounds()
        val ids = selectionManager.getSelectedIds()

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

    override suspend fun commitMoveSelection() {
        if (!selectionManager.hasSelection()) return
        val file = stashedFile ?: return

        val transform = selectionManager.getTransform()

        withContext(Dispatchers.IO) {
            model.unstashItems(file, transform)
            file.delete()
        }
        stashedFile = null

        endBatchSession()

        // For now, clear selection on commit since we don't have the new IDs easily
        // Or we could re-query the new area, but let's keep it simple.
        selectionManager.clearSelection()
        selectionManager.resetTransform()

        withContext(Dispatchers.Main) {
            renderer.invalidate()
            onContentChangedListener?.invoke()
        }
    }
}
