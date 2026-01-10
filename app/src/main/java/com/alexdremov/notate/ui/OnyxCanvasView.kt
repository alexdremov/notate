package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.controller.CanvasControllerImpl
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.ui.input.PenInputHandler
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.ui.render.MinimapDrawer
import com.alexdremov.notate.ui.render.RenderQuality
import com.alexdremov.notate.util.ColorUtils
import com.onyx.android.sdk.api.device.EpdDeviceManager
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
        // --- Core Components ---
        private var touchHelper: TouchHelper? = null
        private val canvasModel = InfiniteCanvasModel()
        private val canvasRenderer: CanvasRenderer
        private val canvasController: CanvasControllerImpl
        private val minimapDrawer: MinimapDrawer
        private val penInputHandler: PenInputHandler

        // --- Transformation State ---
        private val matrix = Matrix()
        private val inverseMatrix = Matrix()
        private val scaleDetector: ScaleGestureDetector
        private var currentScale = 1.0f
        private var hasPerformedScale = false

        // --- Interaction State ---
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var isInteracting = false

        // --- Two Finger Tap State ---
        private var isPotentialTwoFingerTap = false
        private var twoFingerTapStartTime = 0L
        private var totalPanDistance = 0f
        private val TWO_FINGER_TAP_TIMEOUT = 300L
        private val TWO_FINGER_TAP_SLOP = 50f

        // --- Drawing Configuration ---
        private var currentTool: PenTool = PenTool.defaultPens()[0]
        private val exclusionRects = ArrayList<Rect>()
        var onStrokeStarted: (() -> Unit)? = null
        var onContentChanged: (() -> Unit)? = null

        init {
            holder.addCallback(this)
            setZOrderOnTop(false)
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)

            // Initialize Renderer & Drawer
            canvasRenderer =
                CanvasRenderer(canvasModel) {
                    invalidateCanvas()
                }

            // Initialize Controller
            canvasController = CanvasControllerImpl(canvasModel, canvasRenderer)
            canvasController.setViewportController(
                object : com.alexdremov.notate.controller.ViewportController {
                    override fun scrollTo(
                        x: Float,
                        y: Float,
                    ) {
                        // Reset matrix translation while keeping scale
                        matrix.reset()
                        matrix.postScale(currentScale, currentScale)
                        // Translate to negative coordinates to move viewport to (x, y)
                        matrix.postTranslate(-x * currentScale, -y * currentScale)
                        invalidateCanvas()
                        updateTouchHelperTool()
                    }

                    override fun getViewportOffset(): Pair<Float, Float> {
                        val values = FloatArray(9)
                        matrix.getValues(values)
                        // Invert translation to get world coordinate of top-left corner
                        // tx = -x * scale  =>  x = -tx / scale
                        val tx = values[Matrix.MTRANS_X]
                        val ty = values[Matrix.MTRANS_Y]
                        return Pair(-tx / currentScale, -ty / currentScale)
                    }
                },
            )

            minimapDrawer =
                MinimapDrawer(this, canvasModel, canvasRenderer) {
                    invalidateCanvas()
                }

            // Initialize Pen Handler
            penInputHandler =
                PenInputHandler(
                    canvasController,
                    this,
                    matrix,
                    inverseMatrix,
                    onStrokeStarted = {
                        onStrokeStarted?.invoke()
                    },
                ) {
                    // On Stroke Finished
                    minimapDrawer.setDirty()
                    drawContent()
                    onContentChanged?.invoke()
                }

            // Initialize Gesture Detector
            scaleDetector =
                ScaleGestureDetector(
                    context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            hasPerformedScale = true
                            return super.onScaleBegin(detector)
                        }

                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            handleScale(detector)
                            return true
                        }
                    },
                )
        }

        // --- Public API ---

        fun getController(): com.alexdremov.notate.controller.CanvasController = canvasController

        fun getModel(): InfiniteCanvasModel = canvasModel

        fun getCanvasData(): CanvasData {
            updateModelViewport()
            return canvasModel.toCanvasData()
        }

        fun loadCanvasData(data: CanvasData) {
            canvasModel.loadFromCanvasData(data)
            restoreViewportFromModel()
            canvasRenderer.updateLayoutStrategy() // Type might have changed
            canvasRenderer.clearTiles()
            minimapDrawer.setDirty()
            drawContent()
        }

        fun setTool(tool: PenTool) {
            this.currentTool = tool
            penInputHandler.setTool(tool)
            updateTouchHelperTool()
        }

        fun setEraser(tool: PenTool) {
            penInputHandler.setEraserTool(tool)
        }

        fun setCursorView(view: CursorView) {
            penInputHandler.setCursorView(view)
        }

        fun setBackgroundStyle(style: com.alexdremov.notate.model.BackgroundStyle) {
            canvasModel.setBackground(style)
            // Background is drawn per frame in render() behind tiles, so just invalidate view
            invalidateCanvas()
            onContentChanged?.invoke() // Trigger autosave
        }

        fun getBackgroundStyle(): com.alexdremov.notate.model.BackgroundStyle = canvasModel.backgroundStyle

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
                this.getLocalVisibleRect(limit)
                it.setLimitRect(limit, exclusionRects)
            }
        }

        fun clear() {
            canvasModel.clear()
            canvasRenderer.clearTiles()
            minimapDrawer.setDirty()
            drawContent()
            // Toggle raw drawing to clear HW buffer
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
            onContentChanged?.invoke()
        }

        fun undo() {
            val bounds = canvasModel.undo()
            bounds?.let { canvasRenderer.invalidateTiles(it) }
            refreshAfterEdit()
            onContentChanged?.invoke()
        }

        fun redo() {
            val bounds = canvasModel.redo()
            bounds?.let { canvasRenderer.invalidateTiles(it) }
            refreshAfterEdit()
            onContentChanged?.invoke()
        }

        // --- Internal Logic ---

        private fun handleScale(detector: ScaleGestureDetector) {
            var scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor

            // Clamp scale
            if (newScale < CanvasConfig.MIN_SCALE) {
                scaleFactor = CanvasConfig.MIN_SCALE / currentScale
                currentScale = CanvasConfig.MIN_SCALE
            } else if (newScale > CanvasConfig.MAX_SCALE) {
                scaleFactor = CanvasConfig.MAX_SCALE / currentScale
                currentScale = CanvasConfig.MAX_SCALE
            } else {
                currentScale = newScale
            }

            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            minimapDrawer.show()
            invalidateCanvas()
            updateTouchHelperTool()
        }

        private fun startTouchInteraction(
            event: MotionEvent,
            focusX: Float,
            focusY: Float,
        ) {
            lastTouchX = focusX
            lastTouchY = focusY
            activePointerId = event.getPointerId(0)
            isInteracting = true

            // Disable drawing to prevent accidental marks while panning
            touchHelper?.setRawDrawingEnabled(false)

            // Pause HW Pen driver to prevent stray strokes during multi-touch
            EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)

            canvasRenderer.setInteracting(true)

            // Enter Fast Mode (Private API) for smooth panning
            com.alexdremov.notate.util.EpdFastModeController
                .enterFastMode()
        }

        private fun endTouchInteraction() {
            // Exit Fast Mode
            com.alexdremov.notate.util.EpdFastModeController
                .exitFastMode()

            activePointerId = MotionEvent.INVALID_POINTER_ID
            isInteracting = false
            canvasRenderer.setInteracting(false)

            // Restore Drawing State
            touchHelper?.setRawDrawingEnabled(true)
            EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_DRAWING)

            if (hasPerformedScale) {
                hasPerformedScale = false
            }
        }

        private fun refreshAfterEdit() {
            // Force synchronous refresh of visible tiles to prevent white flicker
            val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            matrix.invert(inverseMatrix)
            inverseMatrix.mapRect(visibleRect)
            canvasRenderer.refreshTiles(currentScale, visibleRect)

            minimapDrawer.setDirty()
            drawContent()
            EpdController.invalidate(this, UpdateMode.DU_QUALITY)
        }

        // --- SurfaceHolder.Callback ---

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

        // --- Touch & Gesture ---

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                val toolType = event.getToolType(0)
                val isEraserTail = toolType == MotionEvent.TOOL_TYPE_ERASER
                val isStylusButton = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

                if (isEraserTail || isStylusButton) {
                    penInputHandler.prepareEraser()
                } else {
                    penInputHandler.finishEraser()
                }
            }
            return super.onGenericMotionEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

            if (isStylus) {
                return false // Let PenInputHandler handle stylus via RawInputCallback
            }

            val action = event.actionMasked

            // Calculate Multitouch Focus Point
            val pointerUp = action == MotionEvent.ACTION_POINTER_UP
            val skipIndex = if (pointerUp) event.actionIndex else -1
            var sumX = 0f
            var sumY = 0f
            val count = event.pointerCount
            var div = 0
            for (i in 0 until count) {
                if (skipIndex == i) continue
                div++
                sumX += event.getX(i)
                sumY += event.getY(i)
            }
            val focusX = sumX / div
            val focusY = sumY / div

            // Pass to ScaleDetector
            scaleDetector.onTouchEvent(event)

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchInteraction(event, focusX, focusY)
                    // Reset Tap State
                    isPotentialTwoFingerTap = false
                    totalPanDistance = 0f
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastTouchX = focusX
                    lastTouchY = focusY

                    if (event.pointerCount == 2) {
                        isPotentialTwoFingerTap = true
                        twoFingerTapStartTime = System.currentTimeMillis()
                        totalPanDistance = 0f
                    } else {
                        isPotentialTwoFingerTap = false
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (isPotentialTwoFingerTap && event.pointerCount == 2) {
                        val duration = System.currentTimeMillis() - twoFingerTapStartTime
                        if (duration < TWO_FINGER_TAP_TIMEOUT && !hasPerformedScale && totalPanDistance < TWO_FINGER_TAP_SLOP) {
                            undo()
                            isPotentialTwoFingerTap = false
                        }
                    }

                    lastTouchX = focusX
                    lastTouchY = focusY
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isInteracting) {
                        val dx = focusX - lastTouchX
                        val dy = focusY - lastTouchY

                        if (isPotentialTwoFingerTap) {
                            totalPanDistance += kotlin.math.hypot(dx, dy)
                            if (totalPanDistance > TWO_FINGER_TAP_SLOP) {
                                isPotentialTwoFingerTap = false
                            }
                        }

                        matrix.postTranslate(dx, dy)
                        minimapDrawer.show()
                        invalidateCanvas()
                        lastTouchX = focusX
                        lastTouchY = focusY
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endTouchInteraction()
                }
            }
            return true
        }

        // --- Helpers ---

        private fun updateModelViewport() {
            val values = FloatArray(9)
            matrix.getValues(values)
            canvasModel.viewportOffsetX = values[Matrix.MTRANS_X]
            canvasModel.viewportOffsetY = values[Matrix.MTRANS_Y]
            canvasModel.viewportScale = currentScale
        }

        private fun restoreViewportFromModel() {
            matrix.reset()
            matrix.postScale(canvasModel.viewportScale, canvasModel.viewportScale)
            matrix.postTranslate(canvasModel.viewportOffsetX, canvasModel.viewportOffsetY)
            currentScale = canvasModel.viewportScale
        }

        private fun updateTouchHelperTool() {
            penInputHandler.setScale(currentScale)
        }

        private fun setupTouchHelper() {
            if (touchHelper == null) {
                touchHelper = TouchHelper.create(this, true, penInputHandler)
                penInputHandler.setTouchHelper(touchHelper!!)
            }

            // prevent system-level button interception
            com.alexdremov.notate.util.OnyxSystemHelper
                .ignoreSystemSideButton(this)

            val limit = Rect()
            this.getLocalVisibleRect(limit)

            touchHelper?.apply {
                setLimitRect(limit, exclusionRects)
                openRawDrawing()
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)

                // Prioritize Stylus Input
                EpdController.enterScribbleMode(this@OnyxCanvasView)

                EpdController.setScreenHandWritingPenState(this@OnyxCanvasView, EpdPenManager.PEN_DRAWING)
            }
            updateTouchHelperTool()
        }

        private fun invalidateCanvas() {
            drawContent()
        }

        private fun drawContent() {
            val cv = holder.lockCanvas() ?: return
            try {
                // Draw background based on type
                val bgColor =
                    if (canvasModel.canvasType == CanvasType.FIXED_PAGES) {
                        Color.rgb(250, 250, 250) // Light Gray
                    } else {
                        Color.WHITE
                    }
                cv.drawColor(bgColor)

                // Calculate Visible Rect in World Space
                val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                matrix.invert(inverseMatrix)
                inverseMatrix.mapRect(visibleRect)

                val quality = RenderQuality.HIGH

                // Render Tiles
                canvasRenderer.render(cv, matrix, visibleRect, quality, currentScale)

                // Draw Minimap
                minimapDrawer.draw(cv, matrix, inverseMatrix, currentScale)

                // --- DEBUG RAM OVERLAY ---
                if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                    val runtime = Runtime.getRuntime()
                    val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
                    val maxMem = runtime.maxMemory() / 1048576L
                    val text = "RAM: ${usedMem}MB / ${maxMem}MB"

                    val debugPaint =
                        android.graphics.Paint().apply {
                            color = Color.RED
                            textSize = 40f
                            style = android.graphics.Paint.Style.FILL
                        }
                    cv.drawText(text, 20f, height - 20f, debugPaint)
                }
            } finally {
                holder.unlockCanvasAndPost(cv)
            }
        }
    }
