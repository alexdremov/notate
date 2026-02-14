package com.alexdremov.notate.ui.selection

import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import com.alexdremov.notate.ui.OnyxCanvasView
import com.alexdremov.notate.ui.controller.CanvasController
import com.alexdremov.notate.util.EpdFastModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Handles touch interactions for the Selection Tool.
 */
class SelectionInteractor(
    private val view: OnyxCanvasView,
    private val controller: CanvasController,
    private val scope: CoroutineScope,
    private val matrix: Matrix, // View Matrix (World -> Screen)
    private val inverseMatrix: Matrix, // Screen -> World
) {
    enum class HandleType {
        NONE,
        BODY,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_RIGHT,
        BOTTOM_LEFT,
        ROTATE,
        MID_TOP,
        MID_BOTTOM,
        MID_LEFT,
        MID_RIGHT,
    }

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
    private var autoScrollJob: Job? = null
    private var scrollDirX = 0f
    private var scrollDirY = 0f

    private fun startAutoScroll() {
        if (autoScrollJob?.isActive == true) return
        autoScrollJob =
            scope.launch {
                while (isActive && isDragging && (scrollDirX != 0f || scrollDirY != 0f)) {
                    val stepX = scrollDirX * BASE_SCROLL_STEP
                    val stepY = scrollDirY * BASE_SCROLL_STEP

                    view.scrollByOffset(-stepX, -stepY)

                    if (activeHandle == HandleType.BODY) {
                        val scale = view.getCurrentScale()
                        controller.moveSelection(stepX / scale, stepY / scale)
                    }
                    delay(16)
                }
            }
    }

    // Snapping State
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var initialObjectRotation = 0f
    private var accumulatedFingerRotation = 0f

    private val SNAP_ANGLE_THRESHOLD = 5.0 // Degrees
    private val AXIS_LOCK_THRESHOLD = 20f // Pixels moved before locking kicks in
    private val AXIS_LOCK_GAP = 20.0 // Dominant axis must be 4x the other

    // State for managing commit concurrency
    private var isCommitting = false

    fun onDown(
        x: Float,
        y: Float,
    ): Boolean {
        // Prevent interaction if a commit is still processing to avoid state race/freeze
        if (isCommitting) return true

        val sm = controller.getSelectionManager()
        if (!sm.hasSelection()) return false

        activeHandle = hitTest(x, y)

        if (activeHandle != HandleType.NONE) {
            isDragging = true
            lastTouchX = x
            lastTouchY = y
            dragOriginX = x
            dragOriginY = y
            dragDistanceAccumulator = 0f

            if (activeHandle == HandleType.ROTATE) {
                accumulatedFingerRotation = 0f
                val values = FloatArray(9)
                sm.getTransform().getValues(values)
                val rotRad =
                    kotlin.math.atan2(
                        values[android.graphics.Matrix.MSKEW_Y].toDouble(),
                        values[android.graphics.Matrix.MSCALE_X].toDouble(),
                    )
                initialObjectRotation = Math.toDegrees(rotRad).toFloat()
            }

            EpdFastModeController.enterFastMode()
            scope.launch { controller.startMoveSelection() }
            view.dismissActionPopup()
            return true
        }

        scope.launch { controller.clearSelection() }
        view.dismissActionPopup()
        return false
    }

    fun onLongPressDragStart(
        x: Float,
        y: Float,
    ) {
        if (isCommitting) return

        isDragging = true
        activeHandle = HandleType.BODY
        lastTouchX = x
        lastTouchY = y
        dragOriginX = x
        dragOriginY = y
        dragDistanceAccumulator = 100f

        EpdFastModeController.enterFastMode()
        scope.launch { controller.startMoveSelection() }
        view.dismissActionPopup()
    }

    fun onPointerDown(event: MotionEvent) {
        if (isDragging && event.pointerCount == 2) {
            isTransformingMultiTouch = true
            val x1 = event.getX(0)
            val y1 = event.getY(0)
            val x2 = event.getX(1)
            val y2 = event.getY(1)

            prevSpan = hypot(x2 - x1, y2 - y1)
            prevAngle = kotlin.math.atan2(y2 - y1, x2 - x1)
            prevCentroidX = (x1 + x2) / 2f
            prevCentroidY = (y1 + y2) / 2f
        }
    }

    fun onMove(event: MotionEvent): Boolean {
        if (isCommitting) return true
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
        // Increased threshold to 40f to accommodate finger jitter
        val wasBodyTap = activeHandle == HandleType.BODY && dragDistanceAccumulator < 40f && !isTransformingMultiTouch

        isDragging = false
        isTransformingMultiTouch = false
        activeHandle = HandleType.NONE

        if (wasInteracting) {
            EpdFastModeController.exitFastMode()

            if (wasBodyTap) {
                // Guard against multiple commits
                if (isCommitting) return
                isCommitting = true

                val job =
                    scope.launch {
                        controller.clearSelection() // This now triggers commit(reselect=false)
                        view.dismissActionPopup()
                    }
                job.invokeOnCompletion {
                    isCommitting = false
                }
            } else {
                // Drag ended. Keep selection floating (no commit).
                view.showActionPopup()
            }
        }
    }

    private fun handleSingleTouchDrag(
        x: Float,
        y: Float,
    ) {
        var targetX = x
        var targetY = y

        when (activeHandle) {
            HandleType.BODY -> {
                val isAxisLocking =
                    com.alexdremov.notate.data.PreferencesManager
                        .isAxisLockingEnabled(view.context)
                if (isAxisLocking) {
                    val totalDx = x - dragOriginX
                    val totalDy = y - dragOriginY
                    if (hypot(totalDx, totalDy) > AXIS_LOCK_THRESHOLD) {
                        if (kotlin.math.abs(totalDy) <= AXIS_LOCK_GAP) {
                            targetY = dragOriginY
                        } else if (kotlin.math.abs(totalDx) <= AXIS_LOCK_GAP) {
                            targetX = dragOriginX
                        }
                    }
                }

                val dx = targetX - lastTouchX
                val dy = targetY - lastTouchY
                dragDistanceAccumulator += hypot(dx, dy)

                val scale = view.getCurrentScale()
                scope.launch { controller.moveSelection(dx / scale, dy / scale) }
                updateAutoScroll(targetX, targetY)
            }

            HandleType.ROTATE -> {
                dragDistanceAccumulator += hypot(x - lastTouchX, y - lastTouchY)
                handleRotate(x, y)
            }

            HandleType.TOP_LEFT, HandleType.TOP_RIGHT,
            HandleType.BOTTOM_LEFT, HandleType.BOTTOM_RIGHT,
            -> {
                dragDistanceAccumulator += hypot(x - lastTouchX, y - lastTouchY)
                handleScale(x, y)
            }

            HandleType.MID_TOP, HandleType.MID_BOTTOM, HandleType.MID_LEFT, HandleType.MID_RIGHT -> {
                dragDistanceAccumulator += hypot(x - lastTouchX, y - lastTouchY)
                handleNonUniformScale(x, y)
            }

            else -> {}
        }
        lastTouchX = targetX
        lastTouchY = targetY
    }

    private fun handleNonUniformScale(
        x: Float,
        y: Float,
    ) {
        val sm = controller.getSelectionManager()
        // World Space Corners
        val worldCorners = sm.getTransformedCorners()

        // Determine Pivot Edge (Opposite to active handle)
        // Indices: 0,1=TL, 2,3=TR, 4,5=BR, 6,7=BL

        // Pivot Point P (Center of opposite edge) in World Space
        var px = 0f
        var py = 0f
        var scaleAxisX = 0f
        var scaleAxisY = 0f

        when (activeHandle) {
            HandleType.MID_TOP -> {
                // Pulling Top. Pivot is Bottom Edge (BR-BL: 4,5 - 6,7)
                px = (worldCorners[4] + worldCorners[6]) / 2f
                py = (worldCorners[5] + worldCorners[7]) / 2f
                // Handle Center (Top Edge): (TL+TR)/2
                val hx = (worldCorners[0] + worldCorners[2]) / 2f
                val hy = (worldCorners[1] + worldCorners[3]) / 2f
                scaleAxisX = hx - px
                scaleAxisY = hy - py
            }

            HandleType.MID_BOTTOM -> {
                // Pulling Bottom. Pivot is Top Edge (TL-TR: 0,1 - 2,3)
                px = (worldCorners[0] + worldCorners[2]) / 2f
                py = (worldCorners[1] + worldCorners[3]) / 2f
                // Handle Center (Bottom Edge)
                val hx = (worldCorners[4] + worldCorners[6]) / 2f
                val hy = (worldCorners[5] + worldCorners[7]) / 2f
                scaleAxisX = hx - px
                scaleAxisY = hy - py
            }

            HandleType.MID_LEFT -> {
                // Pulling Left. Pivot is Right Edge (TR-BR: 2,3 - 4,5)
                px = (worldCorners[2] + worldCorners[4]) / 2f
                py = (worldCorners[3] + worldCorners[5]) / 2f
                // Handle Center (Left Edge)
                val hx = (worldCorners[6] + worldCorners[0]) / 2f
                val hy = (worldCorners[7] + worldCorners[1]) / 2f
                scaleAxisX = hx - px
                scaleAxisY = hy - py
            }

            HandleType.MID_RIGHT -> {
                // Pulling Right. Pivot is Left Edge (BL-TL: 6,7 - 0,1)
                px = (worldCorners[6] + worldCorners[0]) / 2f
                py = (worldCorners[7] + worldCorners[1]) / 2f
                // Handle Center (Right Edge)
                val hx = (worldCorners[2] + worldCorners[4]) / 2f
                val hy = (worldCorners[3] + worldCorners[5]) / 2f
                scaleAxisX = hx - px
                scaleAxisY = hy - py
            }

            else -> {
                return
            }
        }

        // Normalize Axis Vector
        val axisLen = hypot(scaleAxisX, scaleAxisY)
        if (axisLen < 0.1f) return
        val ux = scaleAxisX / axisLen
        val uy = scaleAxisY / axisLen

        // Convert Screen Touches to World Space
        val screenLast = floatArrayOf(lastTouchX, lastTouchY)
        val worldLast = FloatArray(2)
        inverseMatrix.mapPoints(worldLast, screenLast)

        val screenCurr = floatArrayOf(x, y)
        val worldCurr = FloatArray(2)
        inverseMatrix.mapPoints(worldCurr, screenCurr)

        // Project World Touches onto Axis relative to Pivot
        val vLastX = worldLast[0] - px
        val vLastY = worldLast[1] - py
        val distLast = vLastX * ux + vLastY * uy

        val vCurrX = worldCurr[0] - px
        val vCurrY = worldCurr[1] - py
        val distCurr = vCurrX * ux + vCurrY * uy

        // Guard against division by zero or flipping (if crossing the pivot)
        if (kotlin.math.abs(distLast) > 1f && (distLast * distCurr) > 0) {
            val scaleFactor = distCurr / distLast

            // Construct Matrix to scale along Local Axis in World Space
            // 1. Get current rotation
            val values = FloatArray(9)
            sm.getTransform().getValues(values)
            val rotRad =
                kotlin.math.atan2(
                    values[android.graphics.Matrix.MSKEW_Y].toDouble(),
                    values[android.graphics.Matrix.MSCALE_X].toDouble(),
                )
            val rotationDeg = Math.toDegrees(rotRad).toFloat()

            // 2. Apply Transform: Rotate -> Scale -> Rotate Back
            val m = Matrix()
            m.setRotate(-rotationDeg, px, py)

            // Determine which local axis to scale
            // Assuming 0 rotation means:
            // Top/Bottom aligns with Y axis
            // Left/Right aligns with X axis
            if (activeHandle == HandleType.MID_TOP || activeHandle == HandleType.MID_BOTTOM) {
                m.postScale(1f, scaleFactor, px, py)
            } else {
                m.postScale(scaleFactor, 1f, px, py)
            }

            m.postRotate(rotationDeg, px, py)

            scope.launch { controller.transformSelection(m) }
        }
    }

    private fun handleRotate(
        x: Float,
        y: Float,
    ) {
        val sm = controller.getSelectionManager()
        val center = sm.getSelectionCenter()
        val screenCenter = FloatArray(2)
        matrix.mapPoints(screenCenter, center)

        val prevAngleRad = kotlin.math.atan2(lastTouchY - screenCenter[1], lastTouchX - screenCenter[0])
        val currAngleRad = kotlin.math.atan2(y - screenCenter[1], x - screenCenter[0])
        var deltaDeg = Math.toDegrees((currAngleRad - prevAngleRad).toDouble()).toFloat()

        accumulatedFingerRotation += deltaDeg
        var naturalTargetDeg = initialObjectRotation + accumulatedFingerRotation
        while (naturalTargetDeg > 180) naturalTargetDeg -= 360
        while (naturalTargetDeg < -180) naturalTargetDeg += 360

        val isAngleSnapping =
            com.alexdremov.notate.data.PreferencesManager
                .isAngleSnappingEnabled(view.context)
        var finalTargetDeg = naturalTargetDeg
        if (isAngleSnapping) {
            val snapTargets = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, -45.0, -90.0, -135.0, -180.0)
            for (target in snapTargets) {
                if (kotlin.math.abs(naturalTargetDeg - target) < SNAP_ANGLE_THRESHOLD) {
                    finalTargetDeg = target.toFloat()
                    break
                }
            }
        }

        val values = FloatArray(9)
        sm.getTransform().getValues(values)
        val currentObjRotRad =
            kotlin.math.atan2(
                values[android.graphics.Matrix.MSKEW_Y].toDouble(),
                values[android.graphics.Matrix.MSCALE_X].toDouble(),
            )
        var currentObjRotDeg = Math.toDegrees(currentObjRotRad).toFloat()
        val rotationStep = finalTargetDeg - currentObjRotDeg
        var stepNormalized = rotationStep
        while (stepNormalized > 180) stepNormalized -= 360
        while (stepNormalized < -180) stepNormalized += 360

        val m = Matrix()
        m.postRotate(stepNormalized, center[0], center[1])
        scope.launch { controller.transformSelection(m) }
    }

    private fun handleScale(
        x: Float,
        y: Float,
    ) {
        val sm = controller.getSelectionManager()
        val corners = sm.getTransformedCorners()

        val pivotIdx =
            when (activeHandle) {
                HandleType.TOP_LEFT -> 2
                HandleType.TOP_RIGHT -> 3
                HandleType.BOTTOM_RIGHT -> 0
                HandleType.BOTTOM_LEFT -> 1
                else -> 0
            }

        val px = corners[pivotIdx * 2]
        val py = corners[pivotIdx * 2 + 1]
        val screenPivot = floatArrayOf(px, py)
        matrix.mapPoints(screenPivot)

        val prevDist = hypot(lastTouchX - screenPivot[0], lastTouchY - screenPivot[1])
        val currDist = hypot(x - screenPivot[0], y - screenPivot[1])

        if (prevDist > 1f) {
            val scale = currDist / prevDist
            val m = Matrix()
            m.postScale(scale, scale, px, py)
            scope.launch { controller.transformSelection(m) }
        }
    }

    private fun handleMultiTouchTransform(event: MotionEvent) {
        val x1 = event.getX(0)
        val y1 = event.getY(0)
        val x2 = event.getX(1)
        val y2 = event.getY(1)

        val currSpan = hypot(x2 - x1, y2 - y1)
        val currAngle = kotlin.math.atan2(y2 - y1, x2 - x1)
        val cx = (x1 + x2) / 2f
        val cy = (y1 + y2) / 2f

        if (prevSpan > 0f) {
            val scale = currSpan / prevSpan
            val rotateDeg = Math.toDegrees((currAngle - prevAngle).toDouble()).toFloat()
            val dx = cx - prevCentroidX
            val dy = cy - prevCentroidY

            val worldPivot = floatArrayOf(prevCentroidX, prevCentroidY)
            inverseMatrix.mapPoints(worldPivot)

            val m = Matrix()
            m.postTranslate(-worldPivot[0], -worldPivot[1])
            m.postScale(scale, scale)
            m.postRotate(rotateDeg)
            m.postTranslate(worldPivot[0], worldPivot[1])

            val worldDelta = floatArrayOf(dx, dy)
            inverseMatrix.mapVectors(worldDelta)
            m.postTranslate(worldDelta[0], worldDelta[1])

            scope.launch { controller.transformSelection(m) }
        }

        prevSpan = currSpan
        prevAngle = currAngle
        prevCentroidX = cx
        prevCentroidY = cy
    }

    private fun hitTest(
        x: Float,
        y: Float,
    ): HandleType {
        val sm = controller.getSelectionManager()
        val corners = sm.getTransformedCorners()
        val screenCorners = FloatArray(8)
        matrix.mapPoints(screenCorners, corners)

        fun dist(i: Int) = hypot(x - screenCorners[i * 2], y - screenCorners[i * 2 + 1])

        if (dist(0) < HANDLE_HIT_RADIUS) return HandleType.TOP_LEFT
        if (dist(1) < HANDLE_HIT_RADIUS) return HandleType.TOP_RIGHT
        if (dist(2) < HANDLE_HIT_RADIUS) return HandleType.BOTTOM_RIGHT
        if (dist(3) < HANDLE_HIT_RADIUS) return HandleType.BOTTOM_LEFT

        // Rotate Handle (High Priority, Further Out)
        val tmx = (screenCorners[0] + screenCorners[2]) / 2f
        val tmy = (screenCorners[1] + screenCorners[3]) / 2f
        val dx = screenCorners[2] - screenCorners[0]
        val dy = screenCorners[3] - screenCorners[1]
        val len = hypot(dx, dy)
        if (len > 0.1f) {
            val ux = dy / len
            val uy = -dx / len
            val rhx = tmx + ux * 80f
            val rhy = tmy + uy * 80f
            if (hypot(x - rhx, y - rhy) < HANDLE_HIT_RADIUS) return HandleType.ROTATE
        }

        // Mid-Handle Detection
        fun distToMid(
            idx1: Int,
            idx2: Int,
        ): Float {
            val mx = (screenCorners[idx1 * 2] + screenCorners[idx2 * 2]) / 2f
            val my = (screenCorners[idx1 * 2 + 1] + screenCorners[idx2 * 2 + 1]) / 2f
            return hypot(x - mx, y - my)
        }

        if (distToMid(0, 1) < HANDLE_HIT_RADIUS) return HandleType.MID_TOP
        if (distToMid(1, 2) < HANDLE_HIT_RADIUS) return HandleType.MID_RIGHT
        if (distToMid(2, 3) < HANDLE_HIT_RADIUS) return HandleType.MID_BOTTOM
        if (distToMid(3, 0) < HANDLE_HIT_RADIUS) return HandleType.MID_LEFT

        val bounds = sm.getTransformedBounds()
        val worldPt = floatArrayOf(x, y)
        inverseMatrix.mapPoints(worldPt)
        val hitRect = RectF(bounds)
        hitRect.inset(-20f / view.getCurrentScale(), -20f / view.getCurrentScale())
        if (hitRect.contains(worldPt[0], worldPt[1])) return HandleType.BODY
        return HandleType.NONE
    }

    private fun updateAutoScroll(
        focusX: Float,
        focusY: Float,
    ) {
        val w = view.width
        val h = view.height
        scrollDirX = 0f
        scrollDirY = 0f

        if (focusX < SCROLL_EDGE_ZONE) {
            val factor = (SCROLL_EDGE_ZONE - focusX) / SCROLL_EDGE_ZONE
            scrollDirX = -(1f + factor * factor * (MAX_SCROLL_STEP / BASE_SCROLL_STEP - 1))
        } else if (focusX > w - SCROLL_EDGE_ZONE) {
            val factor = (focusX - (w - SCROLL_EDGE_ZONE)) / SCROLL_EDGE_ZONE
            scrollDirX = (1f + factor * factor * (MAX_SCROLL_STEP / BASE_SCROLL_STEP - 1))
        }

        if (focusY < SCROLL_EDGE_ZONE) {
            val factor = (SCROLL_EDGE_ZONE - focusY) / SCROLL_EDGE_ZONE
            scrollDirY = -(1f + factor * factor * (MAX_SCROLL_STEP / BASE_SCROLL_STEP - 1))
        } else if (focusY > h - SCROLL_EDGE_ZONE) {
            val factor = (focusY - (h - SCROLL_EDGE_ZONE)) / SCROLL_EDGE_ZONE
            scrollDirY = (1f + factor * factor * (MAX_SCROLL_STEP / BASE_SCROLL_STEP - 1))
        }

        if (scrollDirX != 0f || scrollDirY != 0f) {
            startAutoScroll()
        } else {
            stopAutoScroll()
        }
    }

    private fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        scrollDirX = 0f
        scrollDirY = 0f
    }

    fun isInteracting() = isDragging || isTransformingMultiTouch || isCommitting
}
