package com.alexdremov.notate.ui.input

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.util.ColorUtils
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ShapeRecognizer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.schedule

class PenInputHandler(
    private val controller: CanvasController,
    private val view: android.view.View,
    private val scope: CoroutineScope,
    private val matrix: Matrix,
    private val inverseMatrix: Matrix,
    private val onStrokeStarted: () -> Unit = {},
    private val onStrokeFinished: () -> Unit,
) : RawInputCallback() {
    @Volatile
    private var currentTool: PenTool = PenTool.defaultPens()[0]

    @Volatile
    private var eraserTool: PenTool? = null

    @Volatile
    private var previousTool: PenTool? = null

    @Volatile
    private var isTemporaryEraserActive = false

    @Volatile
    private var isStrokeInProgress = false

    // --- Selection State ---
    private var isSelecting = false // True if drawing selection lasso/rect

    private var touchHelper: TouchHelper? = null
    private val strokeBuilder = StrokeBuilder()
    private val eraserHandler = EraserGestureHandler(controller, strokeBuilder)
    private var currentScale: Float = 1.0f
    private var cursorView: com.alexdremov.notate.ui.CursorView? = null
    private val lassoPath = Path()

    // Track if the current active stroke is intended to be an eraser
    private var isCurrentStrokeEraser = false

    // Track if hardware rendering is disabled due to size
    private var isLargeStrokeMode = false

    // Track screen bounds for partial refresh
    private val currentStrokeScreenBounds = RectF()

    @Volatile
    private var pendingPerfectShape: ShapeRecognizer.RecognitionResult? = null

    private var isIgnoringCurrentStroke = false

    private lateinit var dwellDetector: DwellDetector

    init {
        dwellDetector =
            DwellDetector(view.context, strokeBuilder) { pts ->
                // On Dwell Detected
                if (!isStrokeInProgress) return@DwellDetector

                // Shape Perfection Logic (Stylus Dwell)
                val result = ShapeRecognizer.recognize(pts)
                if (result != null && result.shape != ShapeRecognizer.RecognizedShape.NONE) {
                    pendingPerfectShape = result
                    dwellDetector.markRecognized()

                    // Transform path to Screen Coordinates for display
                    val screenPath = Path(result.path)
                    screenPath.transform(matrix)

                    cursorView?.showShapePreview(screenPath)

                    // Force EPD Refresh (UpdateMode.DU for fast feedback)
                    val bounds = RectF()
                    screenPath.computeBounds(bounds, true)
                    // Add padding for stroke width
                    val padding = (currentTool.width * currentScale) + 20f
                    bounds.inset(-padding, -padding)

                    val dirtyRect =
                        android.graphics.Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        )

                    // Toggle Raw Drawing to allow software layer update to be visible
                    val drawingBefore = touchHelper?.isRawDrawingInputEnabled() ?: false
                    if (drawingBefore) {
                        touchHelper?.setRawDrawingEnabled(false)
                    }

                    EpdController.invalidate(
                        view,
                        dirtyRect.left,
                        dirtyRect.top,
                        dirtyRect.right,
                        dirtyRect.bottom,
                        UpdateMode.DU,
                    )

                    if (drawingBefore) {
                        touchHelper?.setRawDrawingEnabled(true)
                    }
                }
            }
    }

    // Delayed Refresh Logic
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val HOVER_EXIT_REFRESH_DELAY_MS = 2000L

    private fun performRefresh(ignoreStrokeState: Boolean = false) {
        if (!ignoreStrokeState && isStrokeInProgress) return
        if (!currentStrokeScreenBounds.isEmpty) {
            val drawingBefore = touchHelper?.isRawDrawingInputEnabled() ?: false
            touchHelper?.setRawDrawingEnabled(false)

            // Expand bounds slightly for anti-aliasing safety
            val refreshPadding = (currentTool.width * currentScale) + 10f
            currentStrokeScreenBounds.inset(-refreshPadding, -refreshPadding)

            val dirtyRect =
                android.graphics.Rect(
                    currentStrokeScreenBounds.left.toInt(),
                    currentStrokeScreenBounds.top.toInt(),
                    currentStrokeScreenBounds.right.toInt(),
                    currentStrokeScreenBounds.bottom.toInt(),
                )

            val l = dirtyRect.left
            val t = dirtyRect.top
            val r = dirtyRect.right
            val b = dirtyRect.bottom

            // Perform region-specific High Quality refresh
            EpdController.invalidate(
                view,
                l,
                t,
                r,
                b,
                UpdateMode.GC,
            )

            touchHelper?.setRawDrawingEnabled(drawingBefore)
            // Restore the correct rendering state (as toggling input might reset it)
            updateTouchHelperTool()

            // Reset bounds after refresh
            currentStrokeScreenBounds.setEmpty()
        }
    }

    private val refreshRunnable = Runnable { performRefresh(false) }

    fun onHoverEnter() {
        // User started hovering, postpone any pending refresh
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    fun onHoverMove(event: android.view.MotionEvent) {
        // Keep postponing as long as we move
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    fun onHoverExit() {
        if (isStrokeInProgress) return
        // User stopped hovering. If we have pending changes, refresh soon.
        if (!currentStrokeScreenBounds.isEmpty) {
            refreshHandler.postDelayed(refreshRunnable, HOVER_EXIT_REFRESH_DELAY_MS)
        }
    }

    fun setTool(tool: PenTool) {
        if (isTemporaryEraserActive) {
            previousTool = tool
        } else {
            this.currentTool = tool
            updateTouchHelperTool()
        }
    }

    fun setEraserTool(tool: PenTool) {
        this.eraserTool = tool
    }

    fun setTouchHelper(helper: TouchHelper) {
        this.touchHelper = helper
        updateTouchHelperTool()
    }

    fun setScale(scale: Float) {
        this.currentScale = scale
        updateTouchHelperTool()
    }

    fun setCursorView(view: com.alexdremov.notate.ui.CursorView) {
        this.cursorView = view
    }

    /**
     * Activates the temporary eraser mode (e.g. side button press).
     * Ignored if a stroke is already in progress to prevent hardware glitches.
     */
    fun prepareEraser() {
        if (isStrokeInProgress) return // Ignore button press mid-stroke

        if (!isTemporaryEraserActive && eraserTool != null && currentTool.type != ToolType.ERASER) {
            previousTool = currentTool
            currentTool = eraserTool!!
            isTemporaryEraserActive = true
            updateTouchHelperTool()
        }
    }

    /**
     * Deactivates the temporary eraser mode.
     * Ignored if a stroke is already in progress.
     */
    fun finishEraser() {
        if (isStrokeInProgress) return // Ignore button release mid-stroke

        if (isTemporaryEraserActive && previousTool != null) {
            currentTool = previousTool!!
            previousTool = null
            isTemporaryEraserActive = false
            updateTouchHelperTool()
        }
    }

    fun destroy() {
    }

    private var selectionStartX: Float? = null
    private var selectionStartY: Float? = null

    /**
     * Configures the Onyx TouchHelper based on the current tool and scale.
     */
    private fun updateTouchHelperTool() {
        val helper = touchHelper ?: return
        isLargeStrokeMode =
            PenToolConfigurator.configure(
                helper,
                currentTool,
                currentScale,
                view.context,
            )
    }

    // Deprecated: Use setScale instead
    fun setStrokeWidth(width: Float) {
        touchHelper?.setStrokeWidth(width)
    }

    private fun mapPoint(
        x: Float,
        y: Float,
    ): FloatArray {
        val pts = floatArrayOf(x, y)
        matrix.invert(inverseMatrix)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    /**
     * Called when the stylus touches the screen.
     * Starts a new stroke or eraser session.
     */
    override fun onBeginRawDrawing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        // Cancel any pending refresh as the user has resumed writing
        refreshHandler.removeCallbacks(refreshRunnable)

        isStrokeInProgress = true
        isSelecting = false
        isIgnoringCurrentStroke = false

        // Always clear previous selection when starting a new stylus interaction
        scope.launch { controller.clearSelection() }
        view.post { (view as? com.alexdremov.notate.ui.OnyxCanvasView)?.dismissActionPopup() }

        if (currentTool.type == ToolType.SELECT) {
            isSelecting = true
            if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                lassoPath.reset()
                lassoPath.moveTo(touchPoint.x, touchPoint.y)
            } else {
                selectionStartX = touchPoint.x
                selectionStartY = touchPoint.y
            }
        }

        if (dwellDetector.consumeIgnoreNextStroke()) {
            isIgnoringCurrentStroke = true
            return
        }

        if (b || currentTool.type == ToolType.ERASER) {
            isCurrentStrokeEraser = true
        }

        if (b && currentTool.type != ToolType.ERASER && eraserTool != null) {
            if (!isTemporaryEraserActive) {
                previousTool = currentTool
                currentTool = eraserTool!!
                isTemporaryEraserActive = true
                isCurrentStrokeEraser = true
                view.post { updateTouchHelperTool() }
            }
        }

        // Logic for specialized modes (Eraser or Large Stroke)
        if (currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT) {
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .enterAnimationUpdate(true)

            if (currentTool.type == ToolType.ERASER) {
                scope.launch { controller.startBatchSession() }
                eraserHandler.reset()
            }
        } else {
            // Standard Pen Start: Ensure Eraser Channel is DEAD
            Device.currentDevice().setEraserRawDrawingEnabled(false)

            if (isLargeStrokeMode) {
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .enterAnimationUpdate(true)
            }
        }

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        onStrokeStarted()

        // Initialize screen bounds for refresh
        currentStrokeScreenBounds.set(touchPoint.x, touchPoint.y, touchPoint.x, touchPoint.y)

        val startPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        synchronized(strokeBuilder) {
            strokeBuilder.start(startPoint)
        }

        // Reset Dwell State
        cursorView?.hideShapePreview()
        pendingPerfectShape = null

        dwellDetector.onStart(touchPoint, currentTool)

        if (currentTool.type == ToolType.ERASER && currentTool.eraserType == EraserType.LASSO) {
            lassoPath.reset()
            lassoPath.moveTo(touchPoint.x, touchPoint.y)
        }
        if (currentTool.type == ToolType.ERASER) {
            eraserHandler.start(startPoint)
        }
        updateCursor(touchPoint)
    }

    /**
     * Called when the stylus is lifted.
     * Finalizes the stroke/erasure and commits it to the controller.
     */
    override fun onEndRawDrawing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        if (isIgnoringCurrentStroke) {
            isIgnoringCurrentStroke = false
            return
        }

        dwellDetector.onStop()
        val isSpecialMode =
            currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT ||
                (currentTool.type != ToolType.ERASER && isLargeStrokeMode)

        if (isSpecialMode) {
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .exitAnimationUpdate(true)
        }

        cursorView?.hide()

        // Handle Select Tool Finalization
        if (currentTool.type == ToolType.SELECT && isSelecting) {
            isStrokeInProgress = false
            val currentLasso = Path(lassoPath)
            val currentStartX = selectionStartX
            val currentStartY = selectionStartY
            val curX = touchPoint.x
            val curY = touchPoint.y

            scope.launch {
                if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                    currentLasso.lineTo(curX, curY)
                    currentLasso.close()
                    val worldPath = Path()
                    currentLasso.transform(inverseMatrix, worldPath)
                    val items = controller.getItemsInPath(worldPath)
                    controller.selectItems(items)
                    refreshHandler.postDelayed(refreshRunnable, 50)
                } else {
                    val left = minOf(currentStartX ?: curX, curX)
                    val top = minOf(currentStartY ?: curY, curY)
                    val right = maxOf(currentStartX ?: curX, curX)
                    val bottom = maxOf(currentStartY ?: curY, curY)
                    val screenRect = RectF(left, top, right, bottom)
                    val worldRect = RectF()
                    inverseMatrix.mapRect(worldRect, screenRect)
                    val items = controller.getItemsInRect(worldRect)
                    controller.selectItems(items)
                }

                view.post {
                    if (view is com.alexdremov.notate.ui.OnyxCanvasView) {
                        view.showActionPopup()
                    }
                }
            }

            selectionStartX = null
            selectionStartY = null
            cursorView?.hideSelectionRect()
            lassoPath.reset()
            isSelecting = false
            return
        }

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        currentStrokeScreenBounds.union(touchPoint.x, touchPoint.y)

        val endPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        val toolSnapshot = currentTool // Snapshot tool for coroutine
        val eraserToolSnapshot = eraserTool
        val tempShape = pendingPerfectShape
        val isEraser = isCurrentStrokeEraser || toolSnapshot.type == ToolType.ERASER || b

        scope.launch {
            var builtEraserStroke: com.alexdremov.notate.model.Stroke? = null
            var builtOriginalStroke: com.alexdremov.notate.model.Stroke? = null
            var hasPoints = false

            synchronized(strokeBuilder) {
                if (!dwellDetector.isShapeRecognized) {
                    strokeBuilder.addPoint(endPoint)
                }

                hasPoints = strokeBuilder.hasPoints()
                if (hasPoints) {
                    if (isEraser) {
                        builtEraserStroke = strokeBuilder.build(android.graphics.Color.BLACK, toolSnapshot.width, StrokeType.FINELINER)
                    } else {
                        builtOriginalStroke = strokeBuilder.build(toolSnapshot.color, toolSnapshot.width, toolSnapshot.strokeType)
                    }
                }
            }

            if (hasPoints) {
                if (isEraser) {
                    val effectiveEraserType =
                        if (toolSnapshot.type == ToolType.ERASER) {
                            toolSnapshot.eraserType
                        } else {
                            eraserToolSnapshot?.eraserType ?: EraserType.STANDARD
                        }

                    builtEraserStroke?.let { s ->
                        controller.commitEraser(s, effectiveEraserType)
                        if (effectiveEraserType == EraserType.LASSO) {
                            refreshHandler.post { performRefresh(true) }
                        }
                    }
                } else {
                    val originalStroke = builtOriginalStroke
                    val isScribble =
                        originalStroke != null &&
                            toolSnapshot.strokeType != StrokeType.HIGHLIGHTER &&
                            com.alexdremov.notate.data.PreferencesManager
                                .isScribbleToEraseEnabled(view.context) &&
                            com.alexdremov.notate.util.ScribbleDetector
                                .isScribble(originalStroke.points)

                    if (isScribble) {
                        controller.commitEraser(originalStroke!!, EraserType.STROKE)
                    } else {
                        val shapeEnabled =
                            com.alexdremov.notate.data.PreferencesManager
                                .isShapePerfectionEnabled(view.context)
                        if (shapeEnabled && originalStroke != null && tempShape != null &&
                            tempShape.shape != ShapeRecognizer.RecognizedShape.NONE
                        ) {
                            controller.startBatchSession()
                            val avgPressure =
                                originalStroke.points
                                    .map { it.pressure }
                                    .average()
                                    .toFloat()
                            val avgSize =
                                originalStroke.points
                                    .map { it.size }
                                    .average()
                                    .toFloat()
                            val avgTiltX =
                                originalStroke.points
                                    .map { it.tiltX }
                                    .average()
                                    .toFloat()
                            val avgTiltY =
                                originalStroke.points
                                    .map { it.tiltY }
                                    .average()
                                    .toFloat()

                            for (segmentPoints in tempShape.segments) {
                                val newTouchPoints =
                                    segmentPoints.map { p ->
                                        TouchPoint(
                                            p.x,
                                            p.y,
                                            avgPressure,
                                            avgSize,
                                            avgTiltX.toInt(),
                                            avgTiltY.toInt(),
                                            System.currentTimeMillis(),
                                        )
                                    }
                                val segmentPath = Path()
                                if (newTouchPoints.isNotEmpty()) {
                                    segmentPath.moveTo(newTouchPoints[0].x, newTouchPoints[0].y)
                                    for (i in 1 until newTouchPoints.size) segmentPath.lineTo(newTouchPoints[i].x, newTouchPoints[i].y)
                                }
                                val perfectedStroke =
                                    com.alexdremov.notate.model.Stroke(
                                        path = segmentPath,
                                        points = newTouchPoints,
                                        color = toolSnapshot.color,
                                        width = toolSnapshot.width,
                                        style = toolSnapshot.strokeType,
                                        bounds =
                                            com.alexdremov.notate.util.StrokeGeometry.computeStrokeBounds(
                                                segmentPath,
                                                toolSnapshot.width,
                                                toolSnapshot.strokeType,
                                            ),
                                    )
                                controller.commitStroke(perfectedStroke)
                            }
                            controller.endBatchSession()
                        } else {
                            originalStroke?.let { s -> controller.commitStroke(s) }
                        }
                    }
                }
            }

            if (toolSnapshot.type == ToolType.ERASER) {
                controller.endBatchSession()
            }

            synchronized(strokeBuilder) {
                strokeBuilder.clear()
            }

            eraserHandler.reset()
            isCurrentStrokeEraser = false
            isStrokeInProgress = false
            onStrokeFinished()
        }
    }

    /**
     * Called when the stylus moves.
     * Updates the current stroke path and handles real-time eraser feedback.
     */
    override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
        if (isIgnoringCurrentStroke) return

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        val newPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        if (dwellDetector.isShapeRecognized) return

        synchronized(strokeBuilder) {
            strokeBuilder.addPoint(newPoint)
        }

        dwellDetector.onMove(touchPoint, currentTool)

        currentStrokeScreenBounds.union(touchPoint.x, touchPoint.y)

        if (currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT) {
            if (currentTool.type == ToolType.SELECT) {
                if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                    lassoPath.lineTo(touchPoint.x, touchPoint.y)
                } else {
                    val startX = selectionStartX
                    val startY = selectionStartY
                    if (startX != null && startY != null) {
                        val left = minOf(startX, touchPoint.x)
                        val top = minOf(startY, touchPoint.y)
                        val right = maxOf(startX, touchPoint.x)
                        val bottom = maxOf(startY, touchPoint.y)
                        cursorView?.showSelectionRect(RectF(left, top, right, bottom))
                    }
                }
            } else if (currentTool.eraserType == EraserType.LASSO) {
                lassoPath.lineTo(touchPoint.x, touchPoint.y)
            } else {
                val toolWidth = currentTool.width
                val eraserType = currentTool.eraserType
                scope.launch { eraserHandler.processMove(newPoint, toolWidth, eraserType) }
            }
        }

        updateCursor(touchPoint)
    }

    private fun updateCursor(touchPoint: TouchPoint) {
        if (currentTool.type == ToolType.ERASER) {
            if (currentTool.eraserType == EraserType.LASSO) {
                cursorView?.hide()
            } else {
                val radius = (currentTool.width * currentScale) / 2f
                cursorView?.update(touchPoint.x, touchPoint.y, radius)
            }
        } else if (isLargeStrokeMode) {
            val radius = (currentTool.width * currentScale) / 2f
            cursorView?.update(touchPoint.x, touchPoint.y, radius)
        }
    }

    override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {}

    override fun onBeginRawErasing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        if (!isTemporaryEraserActive && eraserTool != null && currentTool.type != ToolType.ERASER) {
            previousTool = currentTool
            currentTool = eraserTool!!
            isTemporaryEraserActive = true
            updateTouchHelperTool()
        }
        onBeginRawDrawing(b, touchPoint)
    }

    override fun onEndRawErasing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        onEndRawDrawing(b, touchPoint)
        view.post { finishEraser() }
    }

    override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
        onRawDrawingTouchPointMoveReceived(touchPoint)
    }

    override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {}
}
