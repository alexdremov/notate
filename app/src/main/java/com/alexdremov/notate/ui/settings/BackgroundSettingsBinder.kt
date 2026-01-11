package com.alexdremov.notate.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.alexdremov.notate.R
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.mmToPx
import com.alexdremov.notate.ui.pxToMm
import kotlin.math.roundToInt

class BackgroundSettingsBinder(
    private val context: Context,
    private val view: View,
    private val getCurrentStyle: () -> BackgroundStyle,
    private val isFixedPageMode: Boolean,
    private val onStyleUpdate: (BackgroundStyle) -> Unit,
) {
    // Views
    private val rgPatternType: RadioGroup = view.findViewById(R.id.rg_pattern_type)
    private val layoutSpacing: View = view.findViewById(R.id.layout_spacing)
    private val tvSpacingLabel: TextView = view.findViewById(R.id.tv_spacing_label)
    private val seekbarSpacing: SeekBar = view.findViewById(R.id.seekbar_spacing)
    private val layoutSize: View = view.findViewById(R.id.layout_size)
    private val tvSizeLabel: TextView = view.findViewById(R.id.tv_size_label)
    private val seekbarSize: SeekBar = view.findViewById(R.id.seekbar_size)

    private val layoutColor: View = view.findViewById(R.id.layout_color)
    private val containerColors: LinearLayout = view.findViewById(R.id.container_colors)

    private val layoutPageSettings: View = view.findViewById(R.id.layout_page_settings)
    private val cbCenterAlign: CheckBox = view.findViewById(R.id.cb_center_align)
    private val tvPaddingTopLabel: TextView = view.findViewById(R.id.tv_padding_top_label)
    private val seekbarPaddingTop: SeekBar = view.findViewById(R.id.seekbar_padding_top)
    private val tvPaddingBottomLabel: TextView = view.findViewById(R.id.tv_padding_bottom_label)
    private val seekbarPaddingBottom: SeekBar = view.findViewById(R.id.seekbar_padding_bottom)
    private val tvPaddingLeftLabel: TextView = view.findViewById(R.id.tv_padding_left_label)
    private val seekbarPaddingLeft: SeekBar = view.findViewById(R.id.seekbar_padding_left)
    private val tvPaddingRightLabel: TextView = view.findViewById(R.id.tv_padding_right_label)
    private val seekbarPaddingRight: SeekBar = view.findViewById(R.id.seekbar_padding_right)

    // Constants (Ranges in mm)
    private val MIN_SPACING_MM = 2f
    private val MAX_SPACING_MM = 15f
    private val MIN_RADIUS_MM = CanvasConfig.TOOLS_MIN_STROKE_MM
    private val MAX_RADIUS_MM = CanvasConfig.TOOLS_MAX_STROKE_MM
    private val MIN_THICKNESS_MM = 0.1f
    private val MAX_THICKNESS_MM = 1.0f
    private val MAX_PADDING_MM = 50f

    private val PRESET_COLORS =
        listOf(
            Color.LTGRAY,
            Color.GRAY,
            Color.DKGRAY,
            Color.BLACK,
            Color.parseColor("#E1F5FE"),
            Color.parseColor("#E8F5E9"),
            Color.parseColor("#FFF3E0"),
        )

    // Internal State
    private var spacingPx: Float = 50f
    private var radiusPx: Float = 2f
    private var thicknessPx: Float = 1f
    private var selectedColor: Int = Color.LTGRAY
    private var paddingTopPx: Float = 0f
    private var paddingBottomPx: Float = 0f
    private var paddingLeftPx: Float = 0f
    private var paddingRightPx: Float = 0f
    private var isCentered: Boolean = false

    init {
        bind()
    }

    private fun bind() {
        initializeState()
        setupUI()
        updateUIState(rgPatternType.checkedRadioButtonId)
    }

    private fun initializeState() {
        val currentStyle = getCurrentStyle()
        spacingPx =
            when (currentStyle) {
                is BackgroundStyle.Dots -> currentStyle.spacing
                is BackgroundStyle.Lines -> currentStyle.spacing
                is BackgroundStyle.Grid -> currentStyle.spacing
                else -> context.mmToPx(5f)
            }

        radiusPx = if (currentStyle is BackgroundStyle.Dots) currentStyle.radius else context.mmToPx(0.5f)
        thicknessPx =
            when (currentStyle) {
                is BackgroundStyle.Lines -> currentStyle.thickness
                is BackgroundStyle.Grid -> currentStyle.thickness
                else -> context.mmToPx(0.2f)
            }

        selectedColor = currentStyle.color
        paddingTopPx = currentStyle.paddingTop
        paddingBottomPx = currentStyle.paddingBottom
        paddingLeftPx = currentStyle.paddingLeft
        paddingRightPx = currentStyle.paddingRight
        isCentered = currentStyle.isCentered

        // Set Radio Button
        when (currentStyle) {
            is BackgroundStyle.Blank -> rgPatternType.check(R.id.rb_blank)
            is BackgroundStyle.Dots -> rgPatternType.check(R.id.rb_dots)
            is BackgroundStyle.Lines -> rgPatternType.check(R.id.rb_lines)
            is BackgroundStyle.Grid -> rgPatternType.check(R.id.rb_grid)
        }
    }

    private fun setupUI() {
        // Explicitly set max to ensure correct behavior
        seekbarSpacing.max = 100
        seekbarSize.max = 100
        seekbarPaddingTop.max = 100
        seekbarPaddingBottom.max = 100
        seekbarPaddingLeft.max = 100
        seekbarPaddingRight.max = 100

        setupListeners()
        setupColorPicker()

        cbCenterAlign.isChecked = isCentered
    }

    private fun setupListeners() {
        // Spacing
        seekbarSpacing.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?,
                    p: Int,
                    u: Boolean,
                ) {
                    if (u) {
                        val mm = progressToMm(p, MIN_SPACING_MM, MAX_SPACING_MM)
                        spacingPx = context.mmToPx(mm)
                        updateLabels()
                        emitUpdate()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {}
            },
        )

        // Size
        seekbarSize.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?,
                    p: Int,
                    u: Boolean,
                ) {
                    if (u) {
                        val checkedId = rgPatternType.checkedRadioButtonId
                        val mm =
                            if (checkedId == R.id.rb_dots) {
                                progressToMm(p, MIN_RADIUS_MM, MAX_RADIUS_MM)
                            } else {
                                progressToMm(p, MIN_THICKNESS_MM, MAX_THICKNESS_MM)
                            }

                        if (checkedId == R.id.rb_dots) {
                            radiusPx = context.mmToPx(mm)
                        } else {
                            thicknessPx = context.mmToPx(mm)
                        }

                        updateLabels()
                        emitUpdate()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {}
            },
        )

        // Alignment
        cbCenterAlign.setOnCheckedChangeListener { _, isChecked ->
            isCentered = isChecked
            emitUpdate()
        }

        // Padding Listeners
        val paddingListener = { isTop: Boolean, isBottom: Boolean, isLeft: Boolean, isRight: Boolean ->
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?,
                    p: Int,
                    u: Boolean,
                ) {
                    if (u) {
                        val mm = progressToMm(p, 0f, MAX_PADDING_MM)
                        val px = context.mmToPx(mm)
                        if (isTop) paddingTopPx = px
                        if (isBottom) paddingBottomPx = px
                        if (isLeft) paddingLeftPx = px
                        if (isRight) paddingRightPx = px

                        updateLabels()
                        emitUpdate()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {}
            }
        }

        seekbarPaddingTop.setOnSeekBarChangeListener(paddingListener(true, false, false, false))
        seekbarPaddingBottom.setOnSeekBarChangeListener(paddingListener(false, true, false, false))
        seekbarPaddingLeft.setOnSeekBarChangeListener(paddingListener(false, false, true, false))
        seekbarPaddingRight.setOnSeekBarChangeListener(paddingListener(false, false, false, true))

        // Pattern Type
        rgPatternType.setOnCheckedChangeListener { _, id ->
            updateUIState(id)
            emitUpdate()
        }
    }

    private fun setupColorPicker() {
        containerColors.removeAllViews()
        val size = context.resources.getDimensionPixelSize(R.dimen.palette_item_size_dp)
        val margin = context.resources.getDimensionPixelSize(R.dimen.palette_item_margin_dp)

        for (color in PRESET_COLORS) {
            val frame = android.widget.FrameLayout(context)
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            frame.layoutParams = params

            val circle = View(context)
            val circleSize = (size * 0.8f).toInt()
            val circleParams = android.widget.FrameLayout.LayoutParams(circleSize, circleSize)
            circleParams.gravity = android.view.Gravity.CENTER
            circle.layoutParams = circleParams

            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
            if (color == Color.WHITE || color == Color.parseColor("#FFF3E0") || color == Color.parseColor("#E8F5E9") ||
                color == Color.parseColor("#E1F5FE")
            ) {
                drawable.setStroke(2, Color.LTGRAY)
            }
            circle.background = drawable

            if (color == selectedColor) {
                val ring = View(context)
                val ringParams = android.widget.FrameLayout.LayoutParams(size, size)
                ring.layoutParams = ringParams
                val ringDrawable = GradientDrawable()
                ringDrawable.shape = GradientDrawable.OVAL
                ringDrawable.setStroke(5, Color.BLACK)
                ringDrawable.setColor(Color.TRANSPARENT)
                ring.background = ringDrawable
                frame.addView(ring)
            }

            frame.addView(circle)
            frame.setOnClickListener {
                selectedColor = color
                setupColorPicker()
                emitUpdate()
            }
            containerColors.addView(frame)
        }
    }

    private fun updateUIState(checkedId: Int) {
        val isBlank = checkedId == R.id.rb_blank
        val isDots = checkedId == R.id.rb_dots

        layoutSpacing.visibility = if (isBlank) View.GONE else View.VISIBLE
        layoutSize.visibility = if (isBlank) View.GONE else View.VISIBLE
        layoutColor.visibility = if (isBlank) View.GONE else View.VISIBLE
        // Logic: Show page settings if we are in Fixed Page Mode AND (Not Blank OR user wants to set padding for blank pages?
        // Previously decided: !isBlank && isFixedPageMode.
        // But if user wants to set padding before picking style, maybe allow it?
        // Let's stick to !isBlank to avoid clutter, as padding on blank is invisible anyway (unless we render page borders respecting padding, which we don't).
        layoutPageSettings.visibility = if (!isBlank && isFixedPageMode) View.VISIBLE else View.GONE

        if (isBlank) return

        seekbarSpacing.progress = mmToProgress(context.pxToMm(spacingPx), MIN_SPACING_MM, MAX_SPACING_MM)

        val sizeMm = if (isDots) context.pxToMm(radiusPx) else context.pxToMm(thicknessPx)
        val sizeMin = if (isDots) MIN_RADIUS_MM else MIN_THICKNESS_MM
        val sizeMax = if (isDots) MAX_RADIUS_MM else MAX_THICKNESS_MM

        seekbarSize.progress = mmToProgress(sizeMm, sizeMin, sizeMax)

        // Update Padding Sliders
        seekbarPaddingTop.progress = mmToProgress(context.pxToMm(paddingTopPx), 0f, MAX_PADDING_MM)
        seekbarPaddingBottom.progress = mmToProgress(context.pxToMm(paddingBottomPx), 0f, MAX_PADDING_MM)
        seekbarPaddingLeft.progress = mmToProgress(context.pxToMm(paddingLeftPx), 0f, MAX_PADDING_MM)
        seekbarPaddingRight.progress = mmToProgress(context.pxToMm(paddingRightPx), 0f, MAX_PADDING_MM)

        updateLabels()
    }

    private fun updateLabels() {
        val checkedId = rgPatternType.checkedRadioButtonId
        if (checkedId == R.id.rb_blank) return

        tvSpacingLabel.text = String.format("Spacing: %.1f mm", context.pxToMm(spacingPx))
        if (checkedId == R.id.rb_dots) {
            tvSizeLabel.text = String.format("Radius: %.1f mm", context.pxToMm(radiusPx))
        } else {
            tvSizeLabel.text = String.format("Thickness: %.1f mm", context.pxToMm(thicknessPx))
        }

        tvPaddingTopLabel.text = String.format("Top Padding: %.1f mm", context.pxToMm(paddingTopPx))
        tvPaddingBottomLabel.text = String.format("Bottom Padding: %.1f mm", context.pxToMm(paddingBottomPx))
        tvPaddingLeftLabel.text = String.format("Left Padding: %.1f mm", context.pxToMm(paddingLeftPx))
        tvPaddingRightLabel.text = String.format("Right Padding: %.1f mm", context.pxToMm(paddingRightPx))
    }

    private fun emitUpdate() {
        val newStyle =
            when (rgPatternType.checkedRadioButtonId) {
                R.id.rb_dots -> {
                    BackgroundStyle.Dots(
                        color = selectedColor,
                        spacing = spacingPx,
                        radius = radiusPx,
                        paddingTop = paddingTopPx,
                        paddingBottom = paddingBottomPx,
                        paddingLeft = paddingLeftPx,
                        paddingRight = paddingRightPx,
                        isCentered = isCentered,
                    )
                }

                R.id.rb_lines -> {
                    BackgroundStyle.Lines(
                        color = selectedColor,
                        spacing = spacingPx,
                        thickness = thicknessPx,
                        paddingTop = paddingTopPx,
                        paddingBottom = paddingBottomPx,
                        paddingLeft = paddingLeftPx,
                        paddingRight = paddingRightPx,
                        isCentered = isCentered,
                    )
                }

                R.id.rb_grid -> {
                    BackgroundStyle.Grid(
                        color = selectedColor,
                        spacing = spacingPx,
                        thickness = thicknessPx,
                        paddingTop = paddingTopPx,
                        paddingBottom = paddingBottomPx,
                        paddingLeft = paddingLeftPx,
                        paddingRight = paddingRightPx,
                        isCentered = isCentered,
                    )
                }

                else -> {
                    BackgroundStyle.Blank(
                        paddingTop = paddingTopPx,
                        paddingBottom = paddingBottomPx,
                        paddingLeft = paddingLeftPx,
                        paddingRight = paddingRightPx,
                        isCentered = isCentered,
                    )
                }
            }
        onStyleUpdate(newStyle)
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
