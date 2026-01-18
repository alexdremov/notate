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
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        private var uiScope: CoroutineScope? = null

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

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            uiScope?.cancel()
            uiScope = null
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
            event: Logger.UserEvent,
            duration: Long = 5000L,
        ) {
            if (uiScope == null) return

            if (visibility != VISIBLE) {
                visibility = VISIBLE
                alpha = 1f
            }

            val messageView = createMessageView(event)
            messagesContainer.addView(messageView)

            // Auto-scroll to bottom to show new message
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }

            // Auto-remove
            uiScope?.launch {
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

        private fun createMessageView(event: Logger.UserEvent): TextView =
            TextView(context).apply {
                var textToShow = event.message
                if (event.throwable != null) {
                    textToShow += "\n[${event.throwable.javaClass.simpleName}]"
                }
                text = textToShow
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)

                val bg = GradientDrawable()
                bg.cornerRadius = 16f
                bg.setStroke(3, Color.BLACK) // Default border

                when (event.level) {
                    Logger.Level.ERROR -> {
                        setTextColor(Color.WHITE)
                        bg.setColor(Color.BLACK)
                        bg.setStroke(3, Color.WHITE)
                    }

                    Logger.Level.WARNING -> {
                        setTextColor(Color.WHITE)
                        bg.setColor(Color.parseColor("#333333"))
                        bg.setStroke(3, Color.WHITE)
                    }

                    Logger.Level.INFO -> {
                        setTextColor(Color.BLACK)
                        bg.setColor(Color.WHITE)
                        bg.setStroke(3, Color.BLACK)
                    }

                    Logger.Level.DEBUG -> {
                        setTextColor(Color.BLACK)
                        bg.setColor(Color.parseColor("#EEEEEE"))
                        bg.setStroke(3, Color.BLACK)
                    }

                    else -> {
                        setTextColor(Color.WHITE)
                        bg.setColor(Color.BLACK)
                        bg.setStroke(3, Color.WHITE)
                    }
                }
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
