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
import java.util.Timer
import kotlin.concurrent.schedule

class PenInputHandler(
    private val controller: CanvasController,
    private val view: android.view.View,
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
        // This ensures "Tap anywhere outside" deselects, and prevents multiple selections confusion.
        controller.clearSelection()
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
        // If we are erasing (but not lasso) or drawing a large stroke, we need High Speed Mode (Animation)
        if (currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT) {
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .enterAnimationUpdate(true)

            if (currentTool.type == ToolType.ERASER) {
                controller.startBatchSession()
                eraserHandler.reset()
            }
        } else {
            // Standard Pen Start: Ensure Eraser Channel is DEAD (User Fix)
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

        dwellDetector.onStart(touchPoint, currentTool) // Note: using raw touchPoint for screen coords logic

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
            if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                lassoPath.lineTo(touchPoint.x, touchPoint.y)
                lassoPath.close()
                // Transform path to World
                val worldPath = Path()
                lassoPath.transform(inverseMatrix, worldPath)
                val items = controller.getItemsInPath(worldPath)
                controller.selectItems(items)

                // Trigger refresh to clear the HW drawn dashed line
                refreshHandler.postDelayed(refreshRunnable, 50)
            } else {
                // Rectangle Select Finalize
                val startX = selectionStartX ?: touchPoint.x
                val startY = selectionStartY ?: touchPoint.y

                val left = minOf(startX, touchPoint.x)
                val top = minOf(startY, touchPoint.y)
                val right = maxOf(startX, touchPoint.x)
                val bottom = maxOf(startY, touchPoint.y)

                val screenRect = RectF(left, top, right, bottom)
                // Map to World
                val worldRect = RectF()
                val pts = floatArrayOf(screenRect.left, screenRect.top, screenRect.right, screenRect.bottom)
                inverseMatrix.mapPoints(pts)

                // Note: mapPoints maps [x0,y0, x1,y1].
                // Since matrix might have rotation (though unlikely in this app), simple mapping might be unsafe if rotated.
                // But we only support scale/pan.
                // However, mapRect is safer.
                val m = Matrix()
                inverseMatrix.invert(m) // Wait, inverseMatrix IS Screen->World. No, matrix is World->Screen.
                // matrix: World -> Screen.
                // inverseMatrix: Screen -> World.
                inverseMatrix.mapRect(worldRect, screenRect)

                val items = controller.getItemsInRect(worldRect)
                controller.selectItems(items)

                selectionStartX = null
                selectionStartY = null
                cursorView?.hideSelectionRect()
            }

            lassoPath.reset()
            isSelecting = false

            // Show Actions for the new selection
            view.post {
                if (view is com.alexdremov.notate.ui.OnyxCanvasView) {
                    view.showActionPopup()
                }
            }
            return
        }

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        // Track bounds
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

        synchronized(strokeBuilder) {
            if (!dwellDetector.isShapeRecognized) {
                strokeBuilder.addPoint(endPoint)
            }

            if (strokeBuilder.hasPoints()) {
                if (isCurrentStrokeEraser || currentTool.type == ToolType.ERASER || b) {
                    // Determine eraser type
                    val effectiveEraserType =
                        if (currentTool.type == ToolType.ERASER) {
                            currentTool.eraserType
                        } else {
                            eraserTool?.eraserType ?: EraserType.STANDARD
                        }

                    // Construct stroke for erasure
                    val stroke =
                        strokeBuilder.build(
                            android.graphics.Color.BLACK,
                            currentTool.width,
                            StrokeType.FINELINER,
                        )

                    stroke?.let { s ->
                        controller.commitEraser(s, effectiveEraserType)
                        if (effectiveEraserType == EraserType.LASSO) {
                            refreshHandler.post { performRefresh(true) }
                        }
                    }
                } else {
                    // Ink Stroke (Potential Scribble or Shape)
                    val originalStroke =
                        strokeBuilder.build(
                            currentTool.color,
                            currentTool.width,
                            currentTool.strokeType,
                        )

                    val isScribble =
                        originalStroke != null &&
                            currentTool.strokeType != StrokeType.HIGHLIGHTER &&
                            com.alexdremov.notate.data.PreferencesManager
                                .isScribbleToEraseEnabled(view.context) &&
                            com.alexdremov.notate.util.ScribbleDetector
                                .isScribble(originalStroke.points)

                    if (isScribble) {
                        // Treat as Object Eraser (delete crossed strokes)
                        // originalStroke is non-null here because isScribble checks it
                        controller.commitEraser(originalStroke!!, EraserType.STROKE)
                    } else {
                        // --- Shape Perfection Logic ---
                        val shapeEnabled =
                            com.alexdremov.notate.data.PreferencesManager
                                .isShapePerfectionEnabled(view.context)

                        val result = pendingPerfectShape
                        Logger.d("PenInputHandler", "onEndRawDrawing: pendingPerfectShape=$result")

                        if (shapeEnabled && originalStroke != null && result != null &&
                            result.shape != ShapeRecognizer.RecognizedShape.NONE
                        ) {
                            // Start a batch session for the entire shape creation
                            controller.startBatchSession()

                            // Calculate average properties from original stroke
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
                            // Tilt is Float for our internal logic, cast to Int for SDK
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

                            for (segmentPoints in result.segments) {
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

                                // Build Path for this segment
                                val segmentPath = Path()
                                if (newTouchPoints.isNotEmpty()) {
                                    segmentPath.moveTo(newTouchPoints[0].x, newTouchPoints[0].y)
                                    for (i in 1 until newTouchPoints.size) {
                                        segmentPath.lineTo(newTouchPoints[i].x, newTouchPoints[i].y)
                                    }
                                }

                                val perfectedStroke =
                                    com.alexdremov.notate.model.Stroke(
                                        path = segmentPath,
                                        points = newTouchPoints,
                                        color = currentTool.color,
                                        width = currentTool.width,
                                        style = currentTool.strokeType,
                                        bounds =
                                            com.alexdremov.notate.util.StrokeGeometry.computeStrokeBounds(
                                                segmentPath,
                                                currentTool.width,
                                                currentTool.strokeType,
                                            ),
                                    )
                                controller.commitStroke(perfectedStroke)
                            }

                            // End the batch session
                            controller.endBatchSession()
                        } else {
                            originalStroke?.let { s ->
                                controller.commitStroke(s)
                            }
                        }
                    }
                }
            }
            if (currentTool.type == ToolType.ERASER) {
                controller.endBatchSession()
            }

            strokeBuilder.clear()
        }
        eraserHandler.reset()
        isCurrentStrokeEraser = false
        isStrokeInProgress = false // Reset here

        onStrokeFinished()
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

        if (dwellDetector.isShapeRecognized) {
            // Ignore points after recognition to prevent trailing dots
            return
        }

        synchronized(strokeBuilder) {
            strokeBuilder.addPoint(newPoint)
        }

        // Dwell Logic (Using Screen Coordinates)
        dwellDetector.onMove(touchPoint, currentTool)

        if (pendingPerfectShape != null && !dwellDetector.isShapeRecognized) {
            // If we moved and broke dwell (DwellDetector handles reset, but we need to clear UI preview)
            // DwellDetector sets isShapeRecognized=false on move unless it was already recognized?
            // Actually, DwellDetector::onMove resets if threshold exceeded.
            // If DwellDetector resets, pendingPerfectShape should be cleared.
            // We can check if dwellDetector cancelled logic?
            // Simpler: DwellDetector resets lastDwellPoint.
            // Let's rely on the fact that if we move significantly, we clear preview.
            // Since DwellDetector manages the timer, we just need to know if we should hide preview.
            // If user moves pen far, DwellDetector resets timer.
            // Ideally DwellDetector has a callback for "Dwell Broken".
            // For now, let's keep it simple: if movement occurs, clear preview if not recognized.

            // Wait, if isShapeRecognized is true, we returned above. So we are here only if NOT recognized.
            // So if we have a pending shape but haven't recognized (wait, pending comes FROM recognition?)
            // Ah, pendingPerfectShape is set inside the callback.
            // If we move after recognition, we return early.
            // If we move BEFORE recognition, timer resets.
            // So pendingPerfectShape is only non-null IF recognized.
            // So this check is redundant?
            // Actually, if we had a previous recognition from a previous stroke? No, onStart clears it.
            // If we dwell, recognize, then move?
            // If we dwell -> callback -> pending=Set, isRecognized=True.
            // Next move -> isRecognized=True -> Return.
            // So we never reach here if recognized.

            // What if we want to cancel the shape by moving?
            // The user requirement was "hold to recognize".
            // If they hold, it recognizes. If they then continue writing?
            // If isShapeRecognized is true, we ignore input.
            // This means they MUST lift pen to commit.
            // This seems to be the desired behavior to prevent "trailing dots".
        }

        // Track bounds
        currentStrokeScreenBounds.union(touchPoint.x, touchPoint.y)

        // Realtime Eraser Logic / Lasso Path Update
        if (currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT) {
            if (currentTool.type == ToolType.SELECT) {
                if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                    lassoPath.lineTo(touchPoint.x, touchPoint.y)
                } else {
                    // Update Rectangle Preview
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
                eraserHandler.processMove(newPoint, currentTool.width, currentTool.eraserType)
            }
        }

        updateCursor(touchPoint)
    }

    private fun updateCursor(touchPoint: TouchPoint) {
        if (currentTool.type == ToolType.ERASER) {
            if (currentTool.eraserType == EraserType.LASSO) {
                // Hardware handles the dash, so clear/hide software cursor
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
