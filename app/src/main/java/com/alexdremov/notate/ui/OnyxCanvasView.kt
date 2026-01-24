package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.controller.CanvasControllerImpl
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ui.input.PenInputHandler
import com.alexdremov.notate.ui.interaction.ViewportInteractor
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.ui.render.RenderQuality
import com.alexdremov.notate.ui.render.SelectionOverlayDrawer
import com.alexdremov.notate.ui.selection.SelectionInteractor
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.LinkedList

class OnyxCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        // --- Components ---
        private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val debugRamHistory = LinkedList<Float>()
        private val MAX_RAM_HISTORY_POINTS = 60
        private var touchHelper: TouchHelper? = null
        private val canvasModel = InfiniteCanvasModel()
        private val canvasRenderer = CanvasRenderer(canvasModel, context.applicationContext, viewScope) { invalidateCanvas() }
        private val canvasController = CanvasControllerImpl(context.applicationContext, canvasModel, canvasRenderer)

        // --- Drawers ---
        private val selectionOverlayDrawer = SelectionOverlayDrawer(canvasController.getSelectionManager(), canvasRenderer)

        // --- Interaction Handlers ---
        private val matrix = Matrix()
        private val inverseMatrix = Matrix()
        var onViewportChanged: (() -> Unit)? = null

        private var drawScheduled = false
        private val frameCallback =
            Choreographer.FrameCallback {
                drawScheduled = false
                drawContent()
            }

        private val viewportInteractor =
            ViewportInteractor(
                context,
                matrix,
                invalidateCallback = {
                    onViewportChanged?.invoke()
                    invalidateCanvas()
                },
                onScaleChanged = { updateTouchHelperTool() },
                onInteractionStart = {
                    touchHelper?.setRawDrawingEnabled(false)
                    EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
                    canvasRenderer.setInteracting(true)
                },
                onInteractionEnd = {
                    canvasRenderer.setInteracting(false)
                    touchHelper?.setRawDrawingEnabled(true)
                    EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_DRAWING)
                    updateTouchHelperTool()
                },
            )

        private val selectionInteractor = SelectionInteractor(this, canvasController, viewScope, matrix, inverseMatrix)

        // --- Pen Input ---
        private val penInputHandler: PenInputHandler

        // --- State ---

        // Two-Finger Tap Detection (Undo)
        private var twoFingerTapDownTime = 0L
        private var lastTwoFingerTapTime = 0L
        private var isTwoFingerTapCheck = false
        private var twoFingerStartPt1 = floatArrayOf(0f, 0f)
        private var twoFingerStartPt2 = floatArrayOf(0f, 0f)
        private var twoFingerPointerId1 = -1
        private var twoFingerPointerId2 = -1

        private var currentTool: PenTool = PenTool.defaultPens()[0]

        private val exclusionRects = ArrayList<Rect>()

        var onStrokeStarted: (() -> Unit)? = null

        var onContentChanged: (() -> Unit)? = null

        var minimapDrawer: com.alexdremov.notate.ui.render.MinimapDrawer? = null

        var onRequestInsertImage: (() -> Unit)? = null

        private var actionPopup: com.alexdremov.notate.ui.dialog.SelectionActionPopup? = null

        private var pastePopup: com.alexdremov.notate.ui.dialog.PasteActionPopup? = null
        private lateinit var gestureDetector: android.view.GestureDetector

        init {
            holder.addCallback(this)
            setZOrderOnTop(false)
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)

            viewScope.launch {
                while (isActive) {
                    if (CanvasConfig.DEBUG_ENABLE_PROFILING) {
                        com.alexdremov.notate.util.PerformanceProfiler
                            .printReport()
                    }
                    delay(CanvasConfig.PROFILING_INTERVAL_MS)
                }
            }

            viewScope.launch {
                while (isActive) {
                    if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                        val runtime = Runtime.getRuntime()
                        val used = (runtime.totalMemory() - runtime.freeMemory()).toFloat()
                        val max = runtime.maxMemory().toFloat()
                        val percent = if (max > 0) used / max else 0f

                        debugRamHistory.add(percent)
                        if (debugRamHistory.size > MAX_RAM_HISTORY_POINTS) {
                            debugRamHistory.removeFirst()
                        }

                        invalidateCanvas()
                    }
                    delay(500)
                }
            }

            // Setup Viewport Controller to bridge Controller -> Interactor
            canvasController.setViewportController(
                object : com.alexdremov.notate.controller.ViewportController {
                    override fun scrollTo(
                        x: Float,
                        y: Float,
                    ) {
                        matrix.reset()
                        val scale = viewportInteractor.getCurrentScale()
                        matrix.postScale(scale, scale)
                        matrix.postTranslate(-x * scale, -y * scale)
                        invalidateCanvas()
                        updateTouchHelperTool()
                    }

                    override fun getViewportOffset(): Pair<Float, Float> {
                        val values = FloatArray(9)
                        matrix.getValues(values)
                        val tx = values[Matrix.MTRANS_X]
                        val ty = values[Matrix.MTRANS_Y]
                        val scale = viewportInteractor.getCurrentScale()
                        return Pair(-tx / scale, -ty / scale)
                    }
                },
            )

            penInputHandler =
                PenInputHandler(
                    canvasController,
                    this,
                    viewScope,
                    matrix,
                    inverseMatrix,
                    onStrokeStarted = { onStrokeStarted?.invoke() },
                    onStrokeFinished = {
                        minimapDrawer?.setDirty()
                        drawContent()
                        onContentChanged?.invoke()
                    },
                )

            setupGestureDetectors()

            canvasController.setOnContentChangedListener {
                onContentChanged?.invoke()
            }
        }

        private fun setupGestureDetectors() {
            gestureDetector =
                android.view.GestureDetector(
                    context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            if (viewportInteractor.isBusy()) return

                            viewScope.launch {
                                // Compute fresh inverse to ensure hit test is accurate
                                val inv = Matrix()
                                matrix.invert(inv)
                                val pts = floatArrayOf(e.x, e.y)
                                inv.mapPoints(pts)
                                val worldX = pts[0]
                                val worldY = pts[1]

                                val item = canvasController.getItemAt(worldX, worldY)

                                if (item != null) {
                                    canvasController.clearSelection()
                                    canvasController.selectItem(item)
                                    // Hand off to Interactor to start drag
                                    selectionInteractor.onLongPressDragStart(e.x, e.y)
                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                } else {
                                    // Show Contextual Menu (Paste / Insert Image)
                                    pastePopup =
                                        com.alexdremov.notate.ui.dialog.PasteActionPopup(
                                            context,
                                            onPaste = {
                                                viewScope.launch { canvasController.paste(worldX, worldY) }
                                            },
                                            onPasteImage = { onRequestInsertImage?.invoke() },
                                        )
                                    pastePopup?.show(this@OnyxCanvasView, e.x, e.y)
                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                        }
                    },
                )
        }

        // --- Touch Routing ---
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
            if (isStylus) return false // Handled by RawInputCallback

            detectTwoFingerTap(event)

            // 1. Gesture Detector (Long Press)
            gestureDetector.onTouchEvent(event)

            // 2. Selection Interaction (High Priority)
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                if (selectionInteractor.onDown(event.x, event.y)) {
                    return true
                }
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                selectionInteractor.onPointerDown(event)
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (selectionInteractor.onMove(event)) {
                    return true
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                selectionInteractor.onUp()
            }

            // 3. Viewport Interaction (Pan/Zoom) - Only if selection didn't consume
            if (!selectionInteractor.isInteracting()) {
                if (viewportInteractor.onTouchEvent(event)) {
                    return true
                }
            }

            return true
        }

        private fun detectTwoFingerTap(event: MotionEvent) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
                twoFingerTapDownTime = System.currentTimeMillis()
                isTwoFingerTapCheck = true
                twoFingerPointerId1 = event.getPointerId(0)
                twoFingerPointerId2 = event.getPointerId(1)
                twoFingerStartPt1[0] = event.getX(0)
                twoFingerStartPt1[1] = event.getY(0)
                twoFingerStartPt2[0] = event.getX(1)
                twoFingerStartPt2[1] = event.getY(1)
            } else if (action == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
                if (isTwoFingerTapCheck) {
                    val now = System.currentTimeMillis()
                    val duration = now - twoFingerTapDownTime
                    if (duration < CanvasConfig.TWO_FINGER_TAP_MAX_DELAY) {
                        if (!isTapSlopExceeded(event)) {
                            if (now - lastTwoFingerTapTime < CanvasConfig.TWO_FINGER_TAP_DOUBLE_TIMEOUT) {
                                viewScope.launch { undo() }
                                lastTwoFingerTapTime = 0L
                            } else {
                                lastTwoFingerTapTime = now
                            }
                        }
                    }
                    isTwoFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_MOVE && isTwoFingerTapCheck) {
                if (event.pointerCount == 2) {
                    if (isTapSlopExceeded(event)) {
                        isTwoFingerTapCheck = false
                    }
                } else {
                    isTwoFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isTwoFingerTapCheck = false
            }
        }

        private fun isTapSlopExceeded(event: MotionEvent): Boolean {
            val index1 = event.findPointerIndex(twoFingerPointerId1)
            val index2 = event.findPointerIndex(twoFingerPointerId2)

            if (index1 == -1 || index2 == -1) return true

            val d1 = distSq(event.getX(index1), event.getY(index1), twoFingerStartPt1[0], twoFingerStartPt1[1])
            val d2 = distSq(event.getX(index2), event.getY(index2), twoFingerStartPt2[0], twoFingerStartPt2[1])
            return d1 > CanvasConfig.TWO_FINGER_TAP_SLOP_SQ || d2 > CanvasConfig.TWO_FINGER_TAP_SLOP_SQ
        }

        private fun distSq(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ) = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val toolType = event.getToolType(0)
                    val isEraserTail = toolType == MotionEvent.TOOL_TYPE_ERASER
                    val isStylusButton = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    if (isEraserTail || isStylusButton) penInputHandler.prepareEraser() else penInputHandler.finishEraser()
                    penInputHandler.onHoverMove(event)
                }

                MotionEvent.ACTION_HOVER_ENTER -> {
                    penInputHandler.onHoverEnter()
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    penInputHandler.onHoverExit()
                }
            }
            return super.onGenericMotionEvent(event)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            viewScope.cancel()
            minimapDrawer?.detach()
            canvasRenderer.destroy()
        }

        // --- Lifecycle & Drawing ---
        override fun surfaceCreated(holder: SurfaceHolder) {
            drawContent()
            setupTouchHelper()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            setupTouchHelper()
            drawContent()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            EpdController.leaveScribbleMode(this)
            touchHelper?.closeRawDrawing()
            penInputHandler.destroy()
        }

        private fun invalidateCanvas() {
            if (!drawScheduled) {
                drawScheduled = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        private fun drawContent() {
            com.alexdremov.notate.util.PerformanceProfiler.trace("OnyxCanvasView.drawContent") {
                val cv =
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            holder.lockHardwareCanvas()
                        } else {
                            holder.lockCanvas()
                        }
                    } catch (e: Exception) {
                        com.alexdremov.notate.util.Logger
                            .e("OnyxCanvasView", "Failed to lock canvas", e)
                        null
                    } ?: return

                try {
                    val bgColor =
                        if (canvasModel.canvasType == CanvasType.FIXED_PAGES) {
                            CanvasConfig.FIXED_PAGE_CANVAS_BG_COLOR
                        } else {
                            Color.WHITE
                        }
                    cv.drawColor(bgColor)

                    val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    matrix.invert(inverseMatrix)
                    inverseMatrix.mapRect(visibleRect)

                    val values = FloatArray(9)
                    matrix.getValues(values)
                    val currentScale = values[Matrix.MSCALE_X]

                    canvasRenderer.render(cv, matrix, visibleRect, RenderQuality.HIGH, currentScale)
                    selectionOverlayDrawer.draw(cv, matrix, currentScale)

                    if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                        val runtime = Runtime.getRuntime()
                        val usedMem = (runtime.totalMemory() - runtime.freeMemory())
                        val text = "RAM: ${usedMem / 1048576L}MB"
                        val debugPaint =
                            Paint().apply {
                                color = Color.RED
                                textSize = 40f
                                style = Paint.Style.FILL
                            }
                        cv.drawText(text, 20f, height - 130f, debugPaint)

                        val graphWidth = 300f
                        val graphHeight = 100f
                        val graphX = 20f
                        val graphY = height - 20f - graphHeight

                        val bgPaint =
                            Paint().apply {
                                color = Color.parseColor("#ffffffc7")
                                style = Paint.Style.FILL
                            }
                        cv.drawRect(graphX, graphY, graphX + graphWidth, graphY + graphHeight, bgPaint)

                        if (debugRamHistory.isNotEmpty()) {
                            val path = Path()
                            val stepX = graphWidth / MAX_RAM_HISTORY_POINTS
                            debugPaint.style = Paint.Style.STROKE
                            debugPaint.strokeWidth = 3f
                            var first = true
                            val historySnapshot = ArrayList(debugRamHistory)
                            historySnapshot.forEachIndexed { index, percent ->
                                val x = graphX + index * stepX
                                val y = graphY + graphHeight - (percent * graphHeight)
                                if (first) {
                                    path.moveTo(x, y)
                                    first = false
                                } else {
                                    path.lineTo(x, y)
                                }
                            }
                            cv.drawPath(path, debugPaint)
                        }
                    }
                } finally {
                    holder.unlockCanvasAndPost(cv)
                }
            }
        }

        // --- Public API ---
        fun getController() = canvasController

        fun getModel() = canvasModel

        fun getRenderer() = canvasRenderer

        fun getViewportMatrix(outMatrix: Matrix) {
            outMatrix.set(matrix)
        }

        fun getCurrentScale() = viewportInteractor.getCurrentScale()

        fun scrollByOffset(
            dx: Float,
            dy: Float,
        ) {
            matrix.postTranslate(dx, dy)
            onViewportChanged?.invoke()
            invalidateCanvas()
        }

        fun getCanvasData(): CanvasData {
            val values = FloatArray(9)
            matrix.getValues(values)
            canvasModel.viewportOffsetX = values[Matrix.MTRANS_X]
            canvasModel.viewportOffsetY = values[Matrix.MTRANS_Y]
            canvasModel.viewportScale = viewportInteractor.getCurrentScale()
            // CRITICAL: Since we can't make this suspend without breaking Activity callers,
            // and getCanvasData is mostly used for saving, we'll use runBlocking for this leaf call.
            // This is ONLY okay because toCanvasData is now mutex-protected and very fast
            // (it just captures state, doesn't do IO).
            return runBlocking { canvasModel.toCanvasData() }
        }

        suspend fun loadMetadata(data: CanvasData) {
            canvasModel.loadFromCanvasData(data)
            matrix.reset()
            matrix.postScale(data.zoomLevel, data.zoomLevel)
            matrix.postTranslate(data.offsetX, data.offsetY)
            viewportInteractor.setScale(data.zoomLevel)

            canvasRenderer.updateLayoutStrategy()
            canvasRenderer.clearTiles()
            drawContent()
        }

        fun setTool(tool: PenTool) {
            this.currentTool = tool
            penInputHandler.setTool(tool)
            performHardRefresh()
        }

        fun setEraser(tool: PenTool) {
            penInputHandler.setEraserTool(tool)
        }

        fun setCursorView(view: CursorView) {
            penInputHandler.setCursorView(view)
        }

        suspend fun setBackgroundStyle(style: com.alexdremov.notate.model.BackgroundStyle) {
            canvasModel.setBackground(style)
            invalidateCanvas()
            performHardRefresh()
            onContentChanged?.invoke()
        }

        fun getBackgroundStyle() = canvasModel.backgroundStyle

        fun setDrawingEnabled(enabled: Boolean) {
            if (enabled) {
                setupTouchHelper()
            } else {
                touchHelper?.setRawDrawingEnabled(false)
                touchHelper?.closeRawDrawing()
                EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
            }
        }

        fun setExclusionRects(rects: List<Rect>) {
            exclusionRects.clear()
            exclusionRects.addAll(rects)
            touchHelper?.let {
                val limit = Rect()
                getLocalVisibleRect(limit)
                it.setLimitRect(limit, exclusionRects)
            }
        }

        suspend fun clear() {
            canvasModel.clear()
            canvasRenderer.clearTiles()
            minimapDrawer?.setDirty()
            drawContent()
            performHardRefresh()
            onContentChanged?.invoke()
        }

        suspend fun undo() {
            canvasModel.undo()?.let { canvasRenderer.invalidateTiles(it) }
            refreshAfterEdit()
            onContentChanged?.invoke()
        }

        suspend fun redo() {
            canvasModel.redo()?.let { canvasRenderer.invalidateTiles(it) }
            refreshAfterEdit()
            onContentChanged?.invoke()
        }

        fun showActionPopup() {
            val sm = canvasController.getSelectionManager()
            if (sm.hasSelection()) {
                if (actionPopup == null) {
                    actionPopup =
                        com.alexdremov.notate.ui.dialog.SelectionActionPopup(
                            context,
                            onCopy = { viewScope.launch { canvasController.copySelection() } },
                            onDelete = { viewScope.launch { canvasController.deleteSelection() } },
                            onDismiss = { },
                        )
                }
                if (!selectionInteractor.isInteracting()) {
                    val bounds = sm.getTransformedBounds()
                    if (!bounds.isEmpty && bounds.width() > 1f && bounds.height() > 1f) {
                        actionPopup?.show(this, bounds, matrix)
                    } else {
                        actionPopup?.dismiss()
                    }
                }
            } else {
                actionPopup?.dismiss()
            }
        }

        fun dismissActionPopup() {
            actionPopup?.dismiss()
            pastePopup?.dismiss()
        }

        fun refreshScreen() {
            performHardRefresh()
        }

        private fun updateTouchHelperTool() {
            penInputHandler.setScale(viewportInteractor.getCurrentScale())
        }

        private fun performHardRefresh() {
            val wasEnabled = touchHelper?.isRawDrawingInputEnabled() == true
            if (wasEnabled) {
                touchHelper?.setRawDrawingEnabled(false)
            }
            EpdController.invalidate(this, UpdateMode.GC)
            if (wasEnabled) {
                touchHelper?.setRawDrawingEnabled(true)
                updateTouchHelperTool()
            }
        }

        private fun setupTouchHelper() {
            if (touchHelper == null) {
                // Revert to Mode 2 (true) which handles surface interactions better for panning
                // Use SFTouchRender (Mode 2) for better ink quality and panning support
                touchHelper = TouchHelper.create(this, true, penInputHandler)
                penInputHandler.setTouchHelper(touchHelper!!)
            }
            com.alexdremov.notate.util.OnyxSystemHelper
                .ignoreSystemSideButton(this)
            val limit = Rect()
            getLocalVisibleRect(limit)
            touchHelper?.apply {
                setLimitRect(limit, exclusionRects)
                openRawDrawing()
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)

                // Mode 2 ignores enableFingerTouch(), so we don't need to call it.
                // Instead, we use setSingleRegionMode() which is crucial for fast stroke capture in SFTouchRender.
                setSingleRegionMode()

                EpdController.enterScribbleMode(this@OnyxCanvasView)
                EpdController.setScreenHandWritingPenState(this@OnyxCanvasView, EpdPenManager.PEN_DRAWING)
            }
            updateTouchHelperTool()
        }

        private fun refreshAfterEdit() {
            val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            matrix.invert(inverseMatrix)
            inverseMatrix.mapRect(visibleRect)
            canvasRenderer.refreshTiles(viewportInteractor.getCurrentScale(), visibleRect)
            minimapDrawer?.setDirty()
            drawContent()
            performHardRefresh()
        }
    }
