package com.alexdremov.notate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A LinearLayout that captures touch events to drag itself within its parent's bounds.
 */
class DraggableLinearLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 4 // Increased for E-Ink stability

        init {
            clipChildren = false
            clipToPadding = false
        }

        /**
         * Callback invoked when the view is dragged.
         * Reports raw screen coordinates for orientation logic.
         */
        var onPositionChanged: ((rawX: Float, rawY: Float) -> Unit)? = null

        /**
         * Callback invoked when dragging starts.
         */
        var onDragStart: (() -> Unit)? = null

        /**
         * Callback invoked when dragging ends.
         */
        var onDragEnd: (() -> Unit)? = null

        var onDown: (() -> Unit)? = null
        var onUp: (() -> Unit)? = null
        var onLongPress: (() -> Unit)? = null

        var isDragEnabled: Boolean = true

        private val longPressRunnable =
            Runnable {
                if (!isDragging) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onLongPress?.invoke()
                }
            }

        private fun scheduleLongPress() {
            cancelLongPressCheck()
            postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        }

        private fun cancelLongPressCheck() {
            removeCallbacks(longPressRunnable)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                android.util.Log.d("BooxVibesDebug", "DLL.onIntercept: DOWN, isDragEnabled=$isDragEnabled")
            }
            if (!isDragEnabled) return false

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.rawX
                    lastTouchY = ev.rawY
                    isDragging = false
                    onDown?.invoke()
                    scheduleLongPress()
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(ev.rawX - lastTouchX)
                    val dy = abs(ev.rawY - lastTouchY)
                    if (dx > touchSlop || dy > touchSlop) {
                        android.util.Log.d("BooxVibesDebug", "DLL.onIntercept: MOVE > Slop, Intercepting!")
                        cancelLongPressCheck()
                        isDragging = true
                        onDragStart?.invoke()
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("BooxVibesDebug", "DLL.onIntercept: UP/CANCEL")
                    cancelLongPressCheck()
                    onUp?.invoke()
                    if (isDragging) {
                        isDragging = false
                        onDragEnd?.invoke()
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                android.util.Log.d("BooxVibesDebug", "DLL.onTouch: DOWN")
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    onDown?.invoke()
                    scheduleLongPress()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val dx = abs(event.rawX - lastTouchX)
                        val dy = abs(event.rawY - lastTouchY)
                        if (dx > touchSlop || dy > touchSlop) {
                            cancelLongPressCheck()
                            isDragging = true
                            onDragStart?.invoke()
                        }
                    }

                    if (isDragging) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY

                        val lp = layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            val parentView = parent as? View
                            val parentWidth = parentView?.width ?: 0
                            val parentHeight = parentView?.height ?: 0

                            var newLeft = lp.leftMargin + dx.toInt()
                            var newTop = lp.topMargin + dy.toInt()

                            if (parentWidth > 0 && parentHeight > 0) {
                                val maxLeft = (parentWidth - width).coerceAtLeast(0)
                                val maxTop = (parentHeight - height).coerceAtLeast(0)
                                newLeft = newLeft.coerceIn(0, maxLeft)
                                newTop = newTop.coerceIn(0, maxTop)
                            }

                            lp.leftMargin = newLeft
                            lp.topMargin = newTop
                            layoutParams = lp
                        }

                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        onPositionChanged?.invoke(event.rawX, event.rawY)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPressCheck()
                    onUp?.invoke()
                    if (isDragging) {
                        isDragging = false
                        onDragEnd?.invoke()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
