package com.alexdremov.notate.ui.selection

import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.ui.OnyxCanvasView
import com.alexdremov.notate.util.EpdFastModeController
import kotlin.math.hypot

/**
 * Handles touch interactions for the Selection Tool.
 * Responsibilities:
 * - Hit Testing (Body vs Handles).
 * - State Management (Dragging, Transforming).
 * - Interaction Logic (Move, Scale, Rotate).
 * - Auto-scrolling.
 */
class SelectionInteractor(
    private val view: OnyxCanvasView,
    private val controller: CanvasController,
    private val matrix: Matrix, // View Matrix (World -> Screen)
    private val inverseMatrix: Matrix // Screen -> World
) {
    enum class HandleType { NONE, BODY, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, ROTATE }

    // --- State ---
    private var activeHandle = HandleType.NONE
    private var isDragging = false
    private var isTransformingMultiTouch = false
    
    // Tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragDistanceAccumulator = 0f
    
    // Multi-touch Tracking
    private var prevSpan = 0f
    private var prevAngle = 0f
    private var prevCentroidX = 0f
    private var prevCentroidY = 0f

    // Constants
    private val HANDLE_HIT_RADIUS = 60f // Screen pixels
    private val SCROLL_EDGE_ZONE = 200f
    private val MAX_SCROLL_STEP = 150f
    private val BASE_SCROLL_STEP = 15f

    // --- Auto Scroll ---
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var scrollDirX = 0f
    private var scrollDirY = 0f
    
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isDragging || (scrollDirX == 0f && scrollDirY == 0f)) return
            
            // Apply Scroll to View
            val stepX = scrollDirX * BASE_SCROLL_STEP
            val stepY = scrollDirY * BASE_SCROLL_STEP
            
            // Move Canvas
            view.scrollByOffset(-stepX, -stepY)
            
            // Adjust Selection to keep up with finger (if moving body)
            if (activeHandle == HandleType.BODY) {
                val scale = view.getCurrentScale()
                controller.moveSelection(stepX / scale, stepY / scale)
            }
            
            autoScrollHandler.postDelayed(this, 16)
        }
    }

    fun onDown(x: Float, y: Float): Boolean {
        val sm = controller.getSelectionManager()
        if (!sm.hasSelection()) return false

        activeHandle = hitTest(x, y)
        
        if (activeHandle != HandleType.NONE) {
            isDragging = true
            lastTouchX = x
            lastTouchY = y
            dragDistanceAccumulator = 0f
            
            // Start Interaction
            EpdFastModeController.enterFastMode()
            controller.startMoveSelection()
            view.dismissActionPopup()
            return true
        }
        
        // Tap outside -> Clear selection
        controller.clearSelection()
        view.dismissActionPopup()
        return false
    }
    
    fun onLongPressDragStart(x: Float, y: Float) {
        // Called when long-press initiates a drag directly
        isDragging = true
        activeHandle = HandleType.BODY
        lastTouchX = x
        lastTouchY = y
        dragDistanceAccumulator = 100f // Fake distance to prevent "tap" logic
        
        EpdFastModeController.enterFastMode()
        controller.startMoveSelection()
        view.dismissActionPopup() // Hide popup during drag
    }

    fun onPointerDown(event: MotionEvent) {
        if (isDragging && event.pointerCount == 2) {
            // Transition to Multi-touch Transform
            isTransformingMultiTouch = true
            
            val x1 = event.getX(0); val y1 = event.getY(0)
            val x2 = event.getX(1); val y2 = event.getY(1)
            
            prevSpan = hypot(x2 - x1, y2 - y1)
            prevAngle = kotlin.math.atan2(y2 - y1, x2 - x1)
            prevCentroidX = (x1 + x2) / 2f
            prevCentroidY = (y1 + y2) / 2f
        }
    }

    fun onMove(event: MotionEvent): Boolean {
        if (isTransformingMultiTouch && event.pointerCount >= 2) {
            handleMultiTouchTransform(event)
            return true
        } else if (isDragging) {
            handleSingleTouchDrag(event.x, event.y)
            return true
        }
        return false
    }

    fun onUp() {
        stopAutoScroll()
        
        val wasInteracting = isDragging || isTransformingMultiTouch
        val wasBodyTap = activeHandle == HandleType.BODY && dragDistanceAccumulator < 10f && !isTransformingMultiTouch
        
        if (wasInteracting) {
            controller.commitMoveSelection()
            EpdFastModeController.exitFastMode()
        }
        
        // Reset state BEFORE showing popup, so isInteracting() returns false
        isDragging = false
        isTransformingMultiTouch = false
        activeHandle = HandleType.NONE
        
        if (wasInteracting) {
            if (wasBodyTap) {
                controller.clearSelection()
                view.dismissActionPopup()
            } else {
                view.showActionPopup()
            }
        }
    }

    private fun handleSingleTouchDrag(x: Float, y: Float) {
        val dx = x - lastTouchX
        val dy = y - lastTouchY
        dragDistanceAccumulator += hypot(dx, dy)
        
        when (activeHandle) {
            HandleType.BODY -> {
                val scale = view.getCurrentScale()
                controller.moveSelection(dx / scale, dy / scale)
                updateAutoScroll(x, y)
            }
            HandleType.ROTATE -> handleRotate(x, y)
            HandleType.TOP_LEFT, HandleType.TOP_RIGHT, 
            HandleType.BOTTOM_LEFT, HandleType.BOTTOM_RIGHT -> handleScale(x, y)
            else -> {}
        }
        
        lastTouchX = x
        lastTouchY = y
    }

    private fun handleRotate(x: Float, y: Float) {
        val sm = controller.getSelectionManager()
        val center = sm.getSelectionCenter() // World
        val screenCenter = FloatArray(2)
        matrix.mapPoints(screenCenter, center)
        
        val prevAngle = kotlin.math.atan2(lastTouchY - screenCenter[1], lastTouchX - screenCenter[0])
        val currAngle = kotlin.math.atan2(y - screenCenter[1], x - screenCenter[0])
        
        val deltaDeg = Math.toDegrees((currAngle - prevAngle).toDouble()).toFloat()
        
        val m = Matrix()
        m.postRotate(deltaDeg, center[0], center[1])
        controller.transformSelection(m)
    }

    private fun handleScale(x: Float, y: Float) {
        val sm = controller.getSelectionManager()
        val corners = sm.getTransformedCorners() // World
        
        // Map corners to indices [TL, TR, BR, BL]
        val pivotIdx = when(activeHandle) {
            HandleType.TOP_LEFT -> 2 // BR
            HandleType.TOP_RIGHT -> 3 // BL
            HandleType.BOTTOM_RIGHT -> 0 // TL
            HandleType.BOTTOM_LEFT -> 1 // TR
            else -> 0
        }
        
        val px = corners[pivotIdx * 2]
        val py = corners[pivotIdx * 2 + 1]
        
        // Pivot in Screen Space
        val screenPivot = floatArrayOf(px, py)
        matrix.mapPoints(screenPivot)
        
        val prevDist = hypot(lastTouchX - screenPivot[0], lastTouchY - screenPivot[1])
        val currDist = hypot(x - screenPivot[0], y - screenPivot[1])
        
        if (prevDist > 1f) {
            val scale = currDist / prevDist
            val m = Matrix()
            m.postScale(scale, scale, px, py)
            controller.transformSelection(m)
        }
    }

    private fun handleMultiTouchTransform(event: MotionEvent) {
        val x1 = event.getX(0); val y1 = event.getY(0)
        val x2 = event.getX(1); val y2 = event.getY(1)
        
        val currSpan = hypot(x2 - x1, y2 - y1)
        val currAngle = kotlin.math.atan2(y2 - y1, x2 - x1)
        val cx = (x1 + x2) / 2f
        val cy = (y1 + y2) / 2f
        
        if (prevSpan > 0f) {
            val scale = currSpan / prevSpan
            val rotateDeg = Math.toDegrees((currAngle - prevAngle).toDouble()).toFloat()
            val dx = cx - prevCentroidX
            val dy = cy - prevCentroidY
            
            // Construct Transform in World Space
            // 1. Pivot for Scale/Rotate is prevCentroid (mapped to world)
            val worldPivot = floatArrayOf(prevCentroidX, prevCentroidY)
            inverseMatrix.mapPoints(worldPivot)
            
            val m = Matrix()
            m.postTranslate(-worldPivot[0], -worldPivot[1])
            m.postScale(scale, scale)
            m.postRotate(rotateDeg)
            m.postTranslate(worldPivot[0], worldPivot[1])
            
            // 2. Translation
            val worldDelta = floatArrayOf(dx, dy)
            inverseMatrix.mapVectors(worldDelta)
            m.postTranslate(worldDelta[0], worldDelta[1])
            
            controller.transformSelection(m)
        }
        
        prevSpan = currSpan
        prevAngle = currAngle
        prevCentroidX = cx
        prevCentroidY = cy
    }

    private fun hitTest(x: Float, y: Float): HandleType {
        val sm = controller.getSelectionManager()
        val corners = sm.getTransformedCorners()
        val screenCorners = FloatArray(8)
        matrix.mapPoints(screenCorners, corners)
        
        // Check Corners
        fun dist(i: Int) = hypot(x - screenCorners[i*2], y - screenCorners[i*2+1])
        
        if (dist(0) < HANDLE_HIT_RADIUS) return HandleType.TOP_LEFT
        if (dist(1) < HANDLE_HIT_RADIUS) return HandleType.TOP_RIGHT
        if (dist(2) < HANDLE_HIT_RADIUS) return HandleType.BOTTOM_RIGHT
        if (dist(3) < HANDLE_HIT_RADIUS) return HandleType.BOTTOM_LEFT
        
        // Check Rotate Handle
        val mx = (screenCorners[0] + screenCorners[2]) / 2f
        val my = (screenCorners[1] + screenCorners[3]) / 2f
        val dx = screenCorners[2] - screenCorners[0]
        val dy = screenCorners[3] - screenCorners[1]
        val len = hypot(dx, dy)
        if (len > 0.1f) {
            val ux = dy / len
            val uy = -dx / len
            val rhx = mx + ux * 50f
            val rhy = my + uy * 50f
            if (hypot(x - rhx, y - rhy) < HANDLE_HIT_RADIUS) return HandleType.ROTATE
        }
        
        // Check Body
        val bounds = sm.getTransformedBounds()
        val worldPt = floatArrayOf(x, y)
        inverseMatrix.mapPoints(worldPt)
        
        val hitRect = RectF(bounds)
        // Expand slightly for touch
        hitRect.inset(-20f / view.getCurrentScale(), -20f / view.getCurrentScale())
        
        if (hitRect.contains(worldPt[0], worldPt[1])) return HandleType.BODY
        
        return HandleType.NONE
    }

    private fun updateAutoScroll(focusX: Float, focusY: Float) {
        val w = view.width
        val h = view.height
        
        scrollDirX = 0f
        scrollDirY = 0f
        
        if (focusX < SCROLL_EDGE_ZONE) {
            val factor = (SCROLL_EDGE_ZONE - focusX) / SCROLL_EDGE_ZONE
            val accel = factor * factor
            scrollDirX = -(1f + accel * (MAX_SCROLL_STEP/BASE_SCROLL_STEP - 1))
        } else if (focusX > w - SCROLL_EDGE_ZONE) {
            val factor = (focusX - (w - SCROLL_EDGE_ZONE)) / SCROLL_EDGE_ZONE
            val accel = factor * factor
            scrollDirX = (1f + accel * (MAX_SCROLL_STEP/BASE_SCROLL_STEP - 1))
        }
        
        if (focusY < SCROLL_EDGE_ZONE) {
            val factor = (SCROLL_EDGE_ZONE - focusY) / SCROLL_EDGE_ZONE
            val accel = factor * factor
            scrollDirY = -(1f + accel * (MAX_SCROLL_STEP/BASE_SCROLL_STEP - 1))
        } else if (focusY > h - SCROLL_EDGE_ZONE) {
            val factor = (focusY - (h - SCROLL_EDGE_ZONE)) / SCROLL_EDGE_ZONE
            val accel = factor * factor
            scrollDirY = (1f + accel * (MAX_SCROLL_STEP/BASE_SCROLL_STEP - 1))
        }
        
        if (scrollDirX != 0f || scrollDirY != 0f) {
            if (!autoScrollHandler.hasCallbacks(autoScrollRunnable)) {
                autoScrollHandler.post(autoScrollRunnable)
            }
        } else {
            stopAutoScroll()
        }
    }
    
    private fun stopAutoScroll() {
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        scrollDirX = 0f
        scrollDirY = 0f
    }
    
    fun isInteracting() = isDragging || isTransformingMultiTouch
}
