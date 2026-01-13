package com.alexdremov.notate.controller

import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.render.CanvasRenderer
import java.util.ArrayList

class CanvasControllerImpl(
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

    override fun getStrokeAt(
        x: Float,
        y: Float,
    ): Stroke? = model.hitTest(x, y, 20f)

    override fun getStrokesInRect(rect: android.graphics.RectF): List<Stroke> {
        val strokes = model.queryStrokes(rect)
        return strokes.filter { android.graphics.RectF.intersects(rect, it.bounds) }
    }

    override fun getStrokesInPath(path: android.graphics.Path): List<Stroke> {
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        val strokes = model.queryStrokes(bounds)

        // Strict Lasso Logic: Convert path to polygon points
        val pathPoints =
            com.alexdremov.notate.util.StrokeGeometry
                .flattenPath(path)

        return strokes.filter { stroke ->
            if (!bounds.contains(stroke.bounds)) return@filter false
            stroke.points.all { p ->
                com.alexdremov.notate.util.StrokeGeometry
                    .isPointInPolygon(p.x, p.y, pathPoints)
            }
        }
    }

    override fun selectStroke(stroke: Stroke) {
        selectionManager.select(stroke)
        runOnUi { renderer.invalidate() }
    }

    override fun selectStrokes(strokes: List<Stroke>) {
        selectionManager.selectAll(strokes)
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
            val toRemove = selectionManager.selectedStrokes.toList()
            selectionManager.clearSelection()
            deleteStrokesFromModel(toRemove)
            runOnUi { onContentChangedListener?.invoke() }
        }
    }

    private fun deleteStrokesFromModel(strokes: List<Stroke>) {
        model.deleteStrokes(strokes)
        if (strokes.isNotEmpty()) {
            val bounds = android.graphics.RectF(strokes[0].bounds)
            strokes.forEach { bounds.union(it.bounds) }
            runOnUi { renderer.invalidateTiles(bounds) }
        }
    }

    override fun copySelection() {
        if (selectionManager.hasSelection()) {
            com.alexdremov.notate.util.ClipboardManager
                .copy(selectionManager.selectedStrokes)
        }
    }

    override fun paste(
        x: Float,
        y: Float,
    ) {
        if (com.alexdremov.notate.util.ClipboardManager
                .hasContent()
        ) {
            val strokes =
                com.alexdremov.notate.util.ClipboardManager
                    .getStrokes()
            if (strokes.isEmpty()) return

            val bounds = android.graphics.RectF()
            bounds.set(strokes[0].bounds)
            for (s in strokes) bounds.union(s.bounds)

            val dx = x - bounds.centerX()
            val dy = y - bounds.centerY()

            val matrix = android.graphics.Matrix()
            matrix.setTranslate(dx, dy)

            val pastedStrokes = ArrayList<Stroke>()

            startBatchSession()
            strokes.forEach { s ->
                val newPath = android.graphics.Path(s.path)
                newPath.transform(matrix)
                val newPoints =
                    s.points.map { p ->
                        com.onyx.android.sdk.data.note
                            .TouchPoint(p.x + dx, p.y + dy, p.pressure, p.size, p.timestamp)
                    }
                val newBounds = android.graphics.RectF(s.bounds)
                matrix.mapRect(newBounds)

                val newStroke = s.copy(path = newPath, points = newPoints, bounds = newBounds, strokeOrder = 0)
                val added = model.addStroke(newStroke)
                if (added != null) {
                    pastedStrokes.add(added)
                }
            }
            endBatchSession()

            runOnUi {
                pastedStrokes.forEach { renderer.updateTilesWithStroke(it) }
                selectionManager.clearSelection()
                selectionManager.selectAll(pastedStrokes)
                renderer.invalidate()
                onContentChangedListener?.invoke()
            }
        }
    }

    override fun startMoveSelection() {
        if (!selectionManager.hasSelection()) return
        startBatchSession()
        // Visual Lift
        deleteStrokesFromModel(selectionManager.selectedStrokes.toList())
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

        val originals = selectionManager.selectedStrokes.toList()
        val transform = selectionManager.transformMatrix

        val values = FloatArray(9)
        transform.getValues(values)
        val scaleX = values[android.graphics.Matrix.MSCALE_X]
        val skewY = values[android.graphics.Matrix.MSKEW_Y]
        val scale = kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)

        val newSelected = ArrayList<Stroke>()

        // Note: startBatchSession called in startMoveSelection

        originals.forEach { s ->
            val newPath = android.graphics.Path(s.path)
            newPath.transform(transform)

            val newPoints =
                s.points.map { p ->
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

            val newBounds = android.graphics.RectF(s.bounds)
            transform.mapRect(newBounds)

            val newStroke = s.copy(path = newPath, points = newPoints, bounds = newBounds, width = s.width * scale)
            val added = model.addStroke(newStroke)
            if (added != null) {
                newSelected.add(added)
                runOnUi { renderer.updateTilesWithStroke(added) }
            }
        }

        endBatchSession()

        selectionManager.clearSelection()
        selectionManager.selectAll(newSelected)
        selectionManager.transformMatrix.reset()

        runOnUi {
            renderer.invalidate()
            onContentChangedListener?.invoke()
        }
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else uiHandler.post(block)
    }
}
