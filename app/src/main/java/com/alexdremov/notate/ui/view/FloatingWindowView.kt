package com.alexdremov.notate.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.alexdremov.notate.R

class FloatingWindowView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val header: View
        private val contentFrame: FrameLayout
        private val closeBtn: ImageView
        private val resizeHandle: View
        private val titleView: TextView

        var onClose: (() -> Unit)? = null

        private var initialX = 0f
        private var initialY = 0f
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialWidth = 0
        private var initialHeight = 0

        init {
            LayoutInflater.from(context).inflate(R.layout.view_floating_window, this, true)

            header = findViewById(R.id.window_header)
            contentFrame = findViewById(R.id.window_content)
            closeBtn = findViewById(R.id.btn_close)
            resizeHandle = findViewById(R.id.resize_handle)
            titleView = findViewById(R.id.window_title)

            closeBtn.setOnClickListener { onClose?.invoke() }

            setupDrag()
            setupResize()

            // Ensure we are top-left aligned so resizing doesn't shift us due to gravity
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        val params = layoutParams
                        if (params is FrameLayout.LayoutParams) {
                            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            layoutParams = params
                        }
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                },
            )
        }

        fun setTitle(title: String) {
            titleView.text = title
        }

        fun setContentView(view: View) {
            contentFrame.removeAllViews()
            contentFrame.addView(view)
        }

        private fun setupDrag() {
            header.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = this.x
                        initialY = this.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        this.x = initialX + (event.rawX - initialTouchX)
                        this.y = initialY + (event.rawY - initialTouchY)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }

        private fun setupResize() {
            resizeHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = this.width
                        initialHeight = this.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val newWidth = (initialWidth + (event.rawX - initialTouchX)).toInt().coerceAtLeast(300)
                        val newHeight = (initialHeight + (event.rawY - initialTouchY)).toInt().coerceAtLeast(300)

                        val params = this.layoutParams
                        if (params != null) {
                            params.width = newWidth
                            params.height = newHeight
                            this.layoutParams = params
                        }
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }
    }
