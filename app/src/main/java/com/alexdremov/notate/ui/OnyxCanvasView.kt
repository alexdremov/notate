package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.controller.CanvasControllerImpl
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.ui.input.PenInputHandler
import com.alexdremov.notate.ui.interaction.ViewportInteractor
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.ui.render.MinimapDrawer
import com.alexdremov.notate.ui.render.RenderQuality
import com.alexdremov.notate.ui.render.SelectionOverlayDrawer
import com.alexdremov.notate.ui.selection.SelectionInteractor
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.TouchHelper

class OnyxCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        // --- Components ---
        private var touchHelper: TouchHelper? = null
        private val canvasModel = InfiniteCanvasModel()
        private val canvasRenderer = CanvasRenderer(canvasModel) { invalidateCanvas() }
        private val canvasController = CanvasControllerImpl(canvasModel, canvasRenderer)

        // --- Drawers ---
        private val minimapDrawer = MinimapDrawer(this, canvasModel, canvasRenderer) { invalidateCanvas() }
        private val selectionOverlayDrawer = SelectionOverlayDrawer(canvasController.getSelectionManager(), canvasRenderer)

        // --- Interaction Handlers ---
        private val matrix = Matrix()
        private val inverseMatrix = Matrix()

        private val viewportInteractor =
            ViewportInteractor(
                context,
                matrix,
                invalidateCallback = {
                    minimapDrawer.show()
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

        private val selectionInteractor = SelectionInteractor(this, canvasController, matrix, inverseMatrix)

        // --- Pen Input ---
        private val penInputHandler: PenInputHandler

        // --- State ---
        private var currentTool: PenTool = PenTool.defaultPens()[0]
        private val exclusionRects = ArrayList<Rect>()
        var onStrokeStarted: (() -> Unit)? = null
        var onContentChanged: (() -> Unit)? = null

        private var actionPopup: com.alexdremov.notate.ui.dialog.SelectionActionPopup? = null
        private var pastePopup: com.alexdremov.notate.ui.dialog.PasteActionPopup? = null
        private lateinit var gestureDetector: android.view.GestureDetector

        init {
            holder.addCallback(this)
            setZOrderOnTop(false)
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)

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
                    matrix,
                    inverseMatrix,
                    onStrokeStarted = { onStrokeStarted?.invoke() },
                    onStrokeFinished = {
                        minimapDrawer.setDirty()
                        drawContent()
                        onContentChanged?.invoke()
                    },
                )

            setupGestureDetectors()

            canvasController.setOnContentChangedListener {
                minimapDrawer.setDirty()
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

                            // Compute fresh inverse to ensure hit test is accurate
                            val inv = Matrix()
                            matrix.invert(inv)
                            val pts = floatArrayOf(e.x, e.y)
                            inv.mapPoints(pts)
                            val worldX = pts[0]
                            val worldY = pts[1]

                            val stroke = canvasController.getStrokeAt(worldX, worldY)

                            if (stroke != null) {
                                canvasController.clearSelection()
                                canvasController.selectStroke(stroke)
                                // Hand off to Interactor to start drag
                                selectionInteractor.onLongPressDragStart(e.x, e.y)
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            } else if (com.alexdremov.notate.util.ClipboardManager
                                    .hasContent()
                            ) {
                                // Show Contextual Paste Bubble instead of instant paste
                                if (pastePopup == null) {
                                    pastePopup =
                                        com.alexdremov.notate.ui.dialog.PasteActionPopup(context) {
                                            canvasController.paste(worldX, worldY)
                                        }
                                }
                                pastePopup?.show(this@OnyxCanvasView, e.x, e.y)
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    },
                )
        }

        // --- Touch Routing ---
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
            if (isStylus) return false // Handled by RawInputCallback

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
        }

        private fun invalidateCanvas() {
            drawContent()
        }

        private fun drawContent() {
            val cv = holder.lockCanvas() ?: return
            try {
                val bgColor = if (canvasModel.canvasType == CanvasType.FIXED_PAGES) Color.rgb(250, 250, 250) else Color.WHITE
                cv.drawColor(bgColor)

                val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                matrix.invert(inverseMatrix)
                inverseMatrix.mapRect(visibleRect)

                canvasRenderer.render(cv, matrix, visibleRect, RenderQuality.HIGH, viewportInteractor.getCurrentScale())
                selectionOverlayDrawer.draw(cv, matrix, viewportInteractor.getCurrentScale())
                minimapDrawer.draw(cv, matrix, inverseMatrix, viewportInteractor.getCurrentScale())

                if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                    val runtime = Runtime.getRuntime()
                    val text = "RAM: ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576L}MB"
                    val debugPaint =
                        android.graphics.Paint().apply {
                            color = Color.RED
                            textSize = 40f
                        }
                    cv.drawText(text, 20f, height - 20f, debugPaint)
                }
            } finally {
                holder.unlockCanvasAndPost(cv)
            }
        }

        // --- Public API ---
        fun getController() = canvasController

        fun getModel() = canvasModel

        fun getCurrentScale() = viewportInteractor.getCurrentScale()

        fun scrollByOffset(
            dx: Float,
            dy: Float,
        ) {
            matrix.postTranslate(dx, dy)
            minimapDrawer.show()
            invalidateCanvas()
        }

        fun getCanvasData(): CanvasData {
            val values = FloatArray(9)
            matrix.getValues(values)
            canvasModel.viewportOffsetX = values[Matrix.MTRANS_X]
            canvasModel.viewportOffsetY = values[Matrix.MTRANS_Y]
            canvasModel.viewportScale = viewportInteractor.getCurrentScale()
            return canvasModel.toCanvasData()
        }

        fun loadCanvasState(state: com.alexdremov.notate.data.CanvasSerializer.LoadedCanvasState) {
            canvasModel.setLoadedState(state)
            matrix.reset()
            matrix.postScale(state.viewportScale, state.viewportScale)
            matrix.postTranslate(state.viewportOffsetX, state.viewportOffsetY)
            viewportInteractor.setScale(state.viewportScale)

            canvasRenderer.updateLayoutStrategy()
            canvasRenderer.clearTiles()
            minimapDrawer.setDirty()
            drawContent()
        }

        fun loadCanvasData(data: CanvasData) {
            canvasModel.loadFromCanvasData(data)
            matrix.reset()
            matrix.postScale(data.zoomLevel, data.zoomLevel)
            matrix.postTranslate(data.offsetX, data.offsetY)
            viewportInteractor.setScale(data.zoomLevel)

            canvasRenderer.updateLayoutStrategy()
            canvasRenderer.clearTiles()
            minimapDrawer.setDirty()
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

        fun setBackgroundStyle(style: com.alexdremov.notate.model.BackgroundStyle) {
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

        fun clear() {
            canvasModel.clear()
            canvasRenderer.clearTiles()
            minimapDrawer.setDirty()
            drawContent()
            performHardRefresh()
            onContentChanged?.invoke()
        }

        fun undo() {
            canvasModel.undo()?.let { canvasRenderer.invalidateTiles(it) }
            refreshAfterEdit()
            onContentChanged?.invoke()
        }

        fun redo() {
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
                            onCopy = { canvasController.copySelection() },
                            onDelete = { canvasController.deleteSelection() },
                            onDismiss = { /* handled by outside touch */ },
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

        private fun updateTouchHelperTool() {
            penInputHandler.setScale(viewportInteractor.getCurrentScale())
        }

        private fun performHardRefresh() {
            // Toggle Raw Drawing to ensure refresh works (mirrors Lasso Lift logic)
            val wasEnabled = touchHelper?.isRawDrawingInputEnabled() == true
            if (wasEnabled) {
                touchHelper?.setRawDrawingEnabled(false)
            }

            EpdController.invalidate(this, UpdateMode.GC)

            if (wasEnabled) {
                touchHelper?.setRawDrawingEnabled(true)
                // Re-apply tool config after toggling
                updateTouchHelperTool()
            }
        }

        private fun setupTouchHelper() {
            if (touchHelper == null) {
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
            minimapDrawer.setDirty()
            drawContent()
            performHardRefresh()
        }
    }
