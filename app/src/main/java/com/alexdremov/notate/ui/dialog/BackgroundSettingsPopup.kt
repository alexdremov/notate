package com.alexdremov.notate.ui.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.alexdremov.notate.R
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.mmToPx
import kotlin.math.roundToInt

class BackgroundSettingsPopup(
    private val context: Context,
    private val currentStyle: BackgroundStyle,
    private val isFixedPageMode: Boolean,
    private val onUpdate: (BackgroundStyle) -> Unit,
    private val onDismiss: () -> Unit,
) : PopupWindow(context) {
    private val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_background_settings, null)
    private val rgPatternType: RadioGroup = view.findViewById(R.id.rg_pattern_type)

    // Spacing
    private val layoutSpacing: View = view.findViewById(R.id.layout_spacing)
    private val tvSpacingLabel: TextView = view.findViewById(R.id.tv_spacing_label)
    private val seekbarSpacing: SeekBar = view.findViewById(R.id.seekbar_spacing)

    // Size (Radius/Thickness)
    private val layoutSize: View = view.findViewById(R.id.layout_size)
    private val tvSizeLabel: TextView = view.findViewById(R.id.tv_size_label)
    private val seekbarSize: SeekBar = view.findViewById(R.id.seekbar_size)

    // Color
    private val layoutColor: View = view.findViewById(R.id.layout_color)
    private val containerColors: android.widget.LinearLayout = view.findViewById(R.id.container_colors)

    // Page Layout
    private val layoutPageSettings: View = view.findViewById(R.id.layout_page_settings)
    private val cbCenterAlign: android.widget.CheckBox = view.findViewById(R.id.cb_center_align)
    private val tvPaddingTopLabel: TextView = view.findViewById(R.id.tv_padding_top_label)
    private val seekbarPaddingTop: SeekBar = view.findViewById(R.id.seekbar_padding_top)
    private val tvPaddingBottomLabel: TextView = view.findViewById(R.id.tv_padding_bottom_label)
    private val seekbarPaddingBottom: SeekBar = view.findViewById(R.id.seekbar_padding_bottom)
    private val tvPaddingLeftLabel: TextView = view.findViewById(R.id.tv_padding_left_label)
    private val seekbarPaddingLeft: SeekBar = view.findViewById(R.id.seekbar_padding_left)
    private val tvPaddingRightLabel: TextView = view.findViewById(R.id.tv_padding_right_label)
    private val seekbarPaddingRight: SeekBar = view.findViewById(R.id.seekbar_padding_right)

    // Internal State (World Units / Pixels)
    private var spacingPx: Float = 50f
    private var radiusPx: Float = 2f
    private var thicknessPx: Float = 1f
    private var selectedColor: Int = Color.LTGRAY
    private var paddingTopPx: Float = 0f
    private var paddingBottomPx: Float = 0f
    private var paddingLeftPx: Float = 0f
    private var paddingRightPx: Float = 0f
    private var isCentered: Boolean = false

    // Constants for Ranges (in mm)
    private val MIN_SPACING_MM = 2f
    private val MAX_SPACING_MM = 15f

    private val MIN_RADIUS_MM = 0.1f
    private val MAX_RADIUS_MM = 1.5f

    private val MIN_THICKNESS_MM = 0.1f
    private val MAX_THICKNESS_MM = 1.0f

    private val MAX_PADDING_MM = 50f

    private val PRESET_COLORS = listOf(
        Color.LTGRAY,
        Color.GRAY,
        Color.DKGRAY,
        Color.BLACK,
        Color.parseColor("#E1F5FE"), // Light Blue
        Color.parseColor("#E8F5E9"), // Light Green
        Color.parseColor("#FFF3E0")  // Light Orange
    )

    init {
        contentView = view
        isFocusable = true
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = 16f

        layoutPageSettings.visibility = if (isFixedPageMode) View.VISIBLE else View.GONE

        // Initialize state from current style
        selectedColor = currentStyle.color
        paddingTopPx = currentStyle.paddingTop
        paddingBottomPx = currentStyle.paddingBottom
        paddingLeftPx = currentStyle.paddingLeft
        paddingRightPx = currentStyle.paddingRight
        isCentered = currentStyle.isCentered

        when (currentStyle) {
            is BackgroundStyle.Blank -> {
                rgPatternType.check(R.id.rb_blank)
                spacingPx = context.mmToPx(5f)
            }

            is BackgroundStyle.Dots -> {
                rgPatternType.check(R.id.rb_dots)
                spacingPx = currentStyle.spacing
                radiusPx = currentStyle.radius
            }

            is BackgroundStyle.Lines -> {
                rgPatternType.check(R.id.rb_lines)
                spacingPx = currentStyle.spacing
                thicknessPx = currentStyle.thickness
            }

            is BackgroundStyle.Grid -> {
                rgPatternType.check(R.id.rb_grid)
                spacingPx = currentStyle.spacing
                thicknessPx = currentStyle.thickness
            }
        }

        setupSeekBars()
        setupColorPicker()
        setupPageLayoutControls()
        updateUIState(rgPatternType.checkedRadioButtonId)

        rgPatternType.setOnCheckedChangeListener { _, checkedId ->
            updateUIState(checkedId)
            emitUpdate()
        }

        setOnDismissListener {
            onDismiss()
        }
    }

    private fun setupPageLayoutControls() {
        // Alignment
        cbCenterAlign.isChecked = isCentered
        cbCenterAlign.setOnCheckedChangeListener { _, isChecked ->
            isCentered = isChecked
            emitUpdate()
        }

        // Padding Top
        seekbarPaddingTop.max = 100
        seekbarPaddingTop.progress = mmToProgress(pxToMm(paddingTopPx), 0f, MAX_PADDING_MM)
        seekbarPaddingTop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mm = progressToMm(progress, 0f, MAX_PADDING_MM)
                    paddingTopPx = context.mmToPx(mm)
                    updateLabels()
                    emitUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Padding Bottom
        seekbarPaddingBottom.max = 100
        seekbarPaddingBottom.progress = mmToProgress(pxToMm(paddingBottomPx), 0f, MAX_PADDING_MM)
        seekbarPaddingBottom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mm = progressToMm(progress, 0f, MAX_PADDING_MM)
                    paddingBottomPx = context.mmToPx(mm)
                    updateLabels()
                    emitUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Padding Left
        seekbarPaddingLeft.max = 100
        seekbarPaddingLeft.progress = mmToProgress(pxToMm(paddingLeftPx), 0f, MAX_PADDING_MM)
        seekbarPaddingLeft.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mm = progressToMm(progress, 0f, MAX_PADDING_MM)
                    paddingLeftPx = context.mmToPx(mm)
                    updateLabels()
                    emitUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Padding Right
        seekbarPaddingRight.max = 100
        seekbarPaddingRight.progress = mmToProgress(pxToMm(paddingRightPx), 0f, MAX_PADDING_MM)
        seekbarPaddingRight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mm = progressToMm(progress, 0f, MAX_PADDING_MM)
                    paddingRightPx = context.mmToPx(mm)
                    updateLabels()
                    emitUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupColorPicker() {
        containerColors.removeAllViews()
        val size = context.resources.getDimensionPixelSize(R.dimen.palette_item_size_dp)
        val margin = context.resources.getDimensionPixelSize(R.dimen.palette_item_margin_dp)

        for (color in PRESET_COLORS) {
            val frame = android.widget.FrameLayout(context)
            val params = android.widget.LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            frame.layoutParams = params

            val circle = View(context)
            val circleSize = (size * 0.8f).toInt()
            val circleParams = android.widget.FrameLayout.LayoutParams(circleSize, circleSize)
            circleParams.gravity = android.view.Gravity.CENTER
            circle.layoutParams = circleParams
            
            // Simple oval shape
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
            drawable.setColor(color)
            if (color == Color.WHITE || color == Color.parseColor("#FFF3E0") || color == Color.parseColor("#E8F5E9") || color == Color.parseColor("#E1F5FE")) {
                drawable.setStroke(2, Color.LTGRAY)
            }
            circle.background = drawable
            
            // Selection indicator
            if (color == selectedColor) {
                 val ring = View(context)
                 val ringParams = android.widget.FrameLayout.LayoutParams(size, size)
                 ring.layoutParams = ringParams
                 val ringDrawable = android.graphics.drawable.GradientDrawable()
                 ringDrawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                 ringDrawable.setStroke(5, Color.BLACK)
                 ringDrawable.setColor(Color.TRANSPARENT)
                 ring.background = ringDrawable
                 frame.addView(ring)
            }

            frame.addView(circle)
            frame.setOnClickListener {
                selectedColor = color
                setupColorPicker() // Refresh UI
                emitUpdate()
            }
            containerColors.addView(frame)
        }
    }

    private fun setupSeekBars() {
        // --- Spacing Slider ---
        // Map 0..100 to MIN..MAX
        seekbarSpacing.max = 100
        seekbarSpacing.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val mm = progressToMm(progress, MIN_SPACING_MM, MAX_SPACING_MM)
                        spacingPx = context.mmToPx(mm)
                        updateLabels()
                        emitUpdate()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            },
        )

        // --- Size Slider ---
        seekbarSize.max = 100
        seekbarSize.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val checkedId = rgPatternType.checkedRadioButtonId
                        if (checkedId == R.id.rb_dots) {
                            val mm = progressToMm(progress, MIN_RADIUS_MM, MAX_RADIUS_MM)
                            radiusPx = context.mmToPx(mm)
                        } else {
                            val mm = progressToMm(progress, MIN_THICKNESS_MM, MAX_THICKNESS_MM)
                            thicknessPx = context.mmToPx(mm)
                        }
                        updateLabels()
                        emitUpdate()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            },
        )
    }

    private fun updateUIState(checkedId: Int) {
        val isBlank = checkedId == R.id.rb_blank
        val isDots = checkedId == R.id.rb_dots

        layoutSpacing.visibility = if (isBlank) View.GONE else View.VISIBLE
        layoutSize.visibility = if (isBlank) View.GONE else View.VISIBLE
        layoutColor.visibility = if (isBlank) View.GONE else View.VISIBLE
        layoutPageSettings.visibility = if (isBlank) View.GONE else View.VISIBLE

        if (isBlank) return

        // Update Slider Progress based on current values
        val spacingMm = pxToMm(spacingPx)
        seekbarSpacing.progress = mmToProgress(spacingMm, MIN_SPACING_MM, MAX_SPACING_MM)

        if (isDots) {
            val radiusMm = pxToMm(radiusPx)
            seekbarSize.progress = mmToProgress(radiusMm, MIN_RADIUS_MM, MAX_RADIUS_MM)
        } else {
            val thicknessMm = pxToMm(thicknessPx)
            seekbarSize.progress = mmToProgress(thicknessMm, MIN_THICKNESS_MM, MAX_THICKNESS_MM)
        }

        updateLabels()
    }

    private fun updateLabels() {
        val checkedId = rgPatternType.checkedRadioButtonId
        if (checkedId == R.id.rb_blank) return

        val spacingMm = pxToMm(spacingPx)
        tvSpacingLabel.text = String.format("Spacing: %.1f mm", spacingMm)

        if (checkedId == R.id.rb_dots) {
            val radiusMm = pxToMm(radiusPx)
            tvSizeLabel.text = String.format("Radius: %.1f mm", radiusMm)
        } else {
            val thicknessMm = pxToMm(thicknessPx)
            tvSizeLabel.text = String.format("Thickness: %.1f mm", thicknessMm)
        }

        tvPaddingTopLabel.text = String.format("Top Padding: %.0f mm", pxToMm(paddingTopPx))
        tvPaddingBottomLabel.text = String.format("Bottom Padding: %.0f mm", pxToMm(paddingBottomPx))
        tvPaddingLeftLabel.text = String.format("Left Padding: %.0f mm", pxToMm(paddingLeftPx))
        tvPaddingRightLabel.text = String.format("Right Padding: %.0f mm", pxToMm(paddingRightPx))
    }

    private fun emitUpdate() {
        val newStyle =
            when (rgPatternType.checkedRadioButtonId) {
                R.id.rb_dots -> BackgroundStyle.Dots(
                    color = selectedColor,
                    spacing = spacingPx,
                    radius = radiusPx,
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx,
                    paddingLeft = paddingLeftPx,
                    paddingRight = paddingRightPx,
                    isCentered = isCentered
                )
                R.id.rb_lines -> BackgroundStyle.Lines(
                    color = selectedColor,
                    spacing = spacingPx,
                    thickness = thicknessPx,
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx,
                    paddingLeft = paddingLeftPx,
                    paddingRight = paddingRightPx,
                    isCentered = isCentered
                )
                R.id.rb_grid -> BackgroundStyle.Grid(
                    color = selectedColor,
                    spacing = spacingPx,
                    thickness = thicknessPx,
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx,
                    paddingLeft = paddingLeftPx,
                    paddingRight = paddingRightPx,
                    isCentered = isCentered
                )
                else -> BackgroundStyle.Blank(
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx,
                    paddingLeft = paddingLeftPx,
                    paddingRight = paddingRightPx,
                    isCentered = isCentered
                )
            }
        onUpdate(newStyle)
    }

    // --- Unit Helpers ---

    private fun pxToMm(px: Float): Float {
        val dm = context.resources.displayMetrics
        return px * 25.4f / dm.xdpi
    }

    private fun mmToProgress(
        mm: Float,
        minMm: Float,
        maxMm: Float,
    ): Int {
        val ratio = (mm - minMm) / (maxMm - minMm)
        return (ratio.coerceIn(0f, 1f) * 100).roundToInt()
    }

    private fun progressToMm(
        progress: Int,
        minMm: Float,
        maxMm: Float,
    ): Float {
        val ratio = progress / 100f
        return minMm + (ratio * (maxMm - minMm))
    }
}
