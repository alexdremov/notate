package com.alexdremov.notate.ui

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ErrorBannerView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val scrollView: ScrollView
        private val messagesContainer: LinearLayout
        private val uiScope = CoroutineScope(Dispatchers.Main)

        // Max height for the banner area (approx 180dp)
        private val maxHeightPx = (180 * context.resources.displayMetrics.density).toInt()

        init {
            // Container setup
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = GONE // Hidden initially

            scrollView =
                ScrollView(context).apply {
                    layoutParams =
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    isVerticalScrollBarEnabled = false
                }

            messagesContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    layoutTransition = LayoutTransition() // Animate additions/removals
                    gravity = Gravity.CENTER_HORIZONTAL
                }

            scrollView.addView(messagesContainer)
            addView(scrollView)
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            // Enforce max height
            val hSpec =
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
                    MeasureSpec.makeMeasureSpec(maxHeightPx.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                }
            super.onMeasure(widthMeasureSpec, hSpec)
        }

        fun show(
            message: String,
            duration: Long = 5000L,
        ) {
            if (visibility != VISIBLE) {
                visibility = VISIBLE
                alpha = 1f
            }

            val messageView = createMessageView(message)
            messagesContainer.addView(messageView)

            // Auto-scroll to bottom to show new message
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }

            // Auto-remove
            uiScope.launch {
                delay(duration)
                removeMessage(messageView)
            }
        }

        private fun removeMessage(view: View) {
            messagesContainer.removeView(view)
            if (messagesContainer.childCount == 0) {
                visibility = GONE
            }
        }

        private fun createMessageView(message: String): TextView =
            TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)

                // Style: Black background, White border (High Contrast)
                val bg = GradientDrawable()
                bg.setColor(Color.BLACK)
                bg.setStroke(3, Color.WHITE)
                bg.cornerRadius = 16f
                background = bg

                // Layout Params for the item (margins for spacing)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 8, 0, 8)
                        }

                elevation = 10f
            }
    }
