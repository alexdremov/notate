package com.alexdremov.notate.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.alexdremov.notate.R
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.pxToMm
import kotlin.math.roundToInt

class SettingsSidebarController(
    private val context: Context,
    private val container: ViewGroup,
    private val getCurrentStyle: () -> BackgroundStyle,
    private val isFixedPageMode: () -> Boolean,
    private val onStyleUpdate: (BackgroundStyle) -> Unit,
    private val onExportRequest: (ExportAction) -> Unit,
) {
    private val wrapperView: View = LayoutInflater.from(context).inflate(R.layout.sidebar_layout_wrapper, container, false)
    private val contentFrame: FrameLayout = wrapperView.findViewById(R.id.sidebar_content)
    private val tvTitle: TextView = wrapperView.findViewById(R.id.tv_sidebar_title)
    private val btnBack: ImageButton = wrapperView.findViewById(R.id.btn_sidebar_back)

    init {
        container.addView(wrapperView)
        showMainMenu()

        btnBack.setOnClickListener {
            showMainMenu()
        }
    }

    fun showMainMenu() {
        contentFrame.removeAllViews()
        val mainMenuView = LayoutInflater.from(context).inflate(R.layout.sidebar_main_menu, contentFrame, false)
        contentFrame.addView(mainMenuView)

        tvTitle.text = "Settings"
        btnBack.visibility = View.GONE

        mainMenuView.findViewById<View>(R.id.menu_item_background).setOnClickListener {
            showBackgroundSettings()
        }

        mainMenuView.findViewById<View>(R.id.menu_item_writing).setOnClickListener {
            showWritingMenu()
        }

        mainMenuView.findViewById<View>(R.id.menu_item_export).setOnClickListener {
            showExportMenu()
        }
    }

    private fun showWritingMenu() {
        contentFrame.removeAllViews()
        val writingView = LayoutInflater.from(context).inflate(R.layout.sidebar_writing_menu, contentFrame, false)
        contentFrame.addView(writingView)

        tvTitle.text = "Writing"
        btnBack.visibility = View.VISIBLE

        val switchScribble: Switch = writingView.findViewById(R.id.switch_scribble_erase)
        switchScribble.isChecked =
            com.alexdremov.notate.data.PreferencesManager
                .isScribbleToEraseEnabled(context)
        switchScribble.setOnCheckedChangeListener { _, isChecked ->
            com.alexdremov.notate.data.PreferencesManager
                .setScribbleToEraseEnabled(context, isChecked)
        }

        // --- Snapping & Locking ---
        val switchAngle: Switch = writingView.findViewById(R.id.switch_angle_snapping)
        switchAngle.isChecked =
            com.alexdremov.notate.data.PreferencesManager
                .isAngleSnappingEnabled(context)
        switchAngle.setOnCheckedChangeListener { _, isChecked ->
            com.alexdremov.notate.data.PreferencesManager
                .setAngleSnappingEnabled(context, isChecked)
        }

        val switchAxis: Switch = writingView.findViewById(R.id.switch_axis_locking)
        switchAxis.isChecked =
            com.alexdremov.notate.data.PreferencesManager
                .isAxisLockingEnabled(context)
        switchAngle.setOnCheckedChangeListener { _, isChecked ->
            com.alexdremov.notate.data.PreferencesManager
                .setAxisLockingEnabled(context, isChecked)
        }

        // --- Shape Perfection ---
        val switchShape: Switch = writingView.findViewById(R.id.switch_shape_perfection)
        val tvShapeDelay: TextView = writingView.findViewById(R.id.tv_shape_delay_label)
        val seekbarShapeDelay: SeekBar = writingView.findViewById(R.id.seekbar_shape_delay)

        val minDelay = 100L
        val maxDelay = 1500L

        val updateShapeUI = {
            val isEnabled =
                com.alexdremov.notate.data.PreferencesManager
                    .isShapePerfectionEnabled(context)
            switchShape.isChecked = isEnabled

            val delay =
                com.alexdremov.notate.data.PreferencesManager
                    .getShapePerfectionDelay(context)

            tvShapeDelay.text = "Hold stylus ($delay ms) to convert drawing to shape."
            tvShapeDelay.alpha = if (isEnabled) 1.0f else 0.5f

            seekbarShapeDelay.visibility = if (isEnabled) View.VISIBLE else View.GONE
            // Map delay to progress 0-100
            val progress = ((delay - minDelay).toFloat() / (maxDelay - minDelay) * 100).toInt()
            seekbarShapeDelay.progress = progress
        }

        updateShapeUI()

        switchShape.setOnCheckedChangeListener { _, isChecked ->
            com.alexdremov.notate.data.PreferencesManager
                .setShapePerfectionEnabled(context, isChecked)
            updateShapeUI()
        }

        seekbarShapeDelay.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val delay = minDelay + (progress / 100f * (maxDelay - minDelay)).toLong()
                        tvShapeDelay.text = "Hold stylus ($delay ms) to convert drawing to shape."
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 0
                    val delay = minDelay + (progress / 100f * (maxDelay - minDelay)).toLong()
                    com.alexdremov.notate.data.PreferencesManager
                        .setShapePerfectionDelay(context, delay)
                    updateShapeUI() // Refresh text/state
                }
            },
        )
    }

    private fun showExportMenu() {
        contentFrame.removeAllViews()
        val exportView = LayoutInflater.from(context).inflate(R.layout.sidebar_export_menu, contentFrame, false)
        contentFrame.addView(exportView)

        tvTitle.text = "Export"
        btnBack.visibility = View.VISIBLE

        val rgMode: RadioGroup = exportView.findViewById(R.id.rg_export_mode)
        val btnExport: Button = exportView.findViewById(R.id.btn_export_action)
        val btnShare: Button = exportView.findViewById(R.id.btn_share_action)

        btnExport.setOnClickListener {
            val isVector = rgMode.checkedRadioButtonId == R.id.rb_vector
            onExportRequest(ExportAction.Export(isVector))
        }

        btnShare.setOnClickListener {
            val isVector = rgMode.checkedRadioButtonId == R.id.rb_vector
            onExportRequest(ExportAction.Share(isVector))
        }
    }

    private fun showBackgroundSettings() {
        contentFrame.removeAllViews()
        val bgView = LayoutInflater.from(context).inflate(R.layout.dialog_background_settings, contentFrame, false)
        contentFrame.addView(bgView)

        tvTitle.text = "Background"
        btnBack.visibility = View.VISIBLE

        bindBackgroundSettings(bgView)
    }

    // --- Background Logic (Adapted from BackgroundSettingsPopup) ---
    private fun bindBackgroundSettings(view: View) {
        val rgPatternType: RadioGroup = view.findViewById(R.id.rg_pattern_type)
        val layoutSpacing: View = view.findViewById(R.id.layout_spacing)
        val tvSpacingLabel: TextView = view.findViewById(R.id.tv_spacing_label)
        val seekbarSpacing: SeekBar = view.findViewById(R.id.seekbar_spacing)
        val layoutSize: View = view.findViewById(R.id.layout_size)
        val tvSizeLabel: TextView = view.findViewById(R.id.tv_size_label)
        val seekbarSize: SeekBar = view.findViewById(R.id.seekbar_size)

        val layoutColor: View = view.findViewById(R.id.layout_color)
        val containerColors: LinearLayout = view.findViewById(R.id.container_colors)

        val layoutPageSettings: View = view.findViewById(R.id.layout_page_settings)
        val cbCenterAlign: CheckBox = view.findViewById(R.id.cb_center_align)
        val tvPaddingTopLabel: TextView = view.findViewById(R.id.tv_padding_top_label)
        val seekbarPaddingTop: SeekBar = view.findViewById(R.id.seekbar_padding_top)
        val tvPaddingBottomLabel: TextView = view.findViewById(R.id.tv_padding_bottom_label)
        val seekbarPaddingBottom: SeekBar = view.findViewById(R.id.seekbar_padding_bottom)
        val tvPaddingLeftLabel: TextView = view.findViewById(R.id.tv_padding_left_label)
        val seekbarPaddingLeft: SeekBar = view.findViewById(R.id.seekbar_padding_left)
        val tvPaddingRightLabel: TextView = view.findViewById(R.id.tv_padding_right_label)
        val seekbarPaddingRight: SeekBar = view.findViewById(R.id.seekbar_padding_right)

        // Hide page settings in infinite mode
        layoutPageSettings.visibility = if (isFixedPageMode()) View.VISIBLE else View.GONE

        // Constants for Ranges (in mm)
        val MIN_SPACING_MM = 2f
        val MAX_SPACING_MM = 15f
        val MIN_RADIUS_MM = CanvasConfig.TOOLS_MIN_STROKE_MM
        val MAX_RADIUS_MM = CanvasConfig.TOOLS_MAX_STROKE_MM
        val MIN_THICKNESS_MM = 0.1f
        val MAX_THICKNESS_MM = 1.0f
        val MAX_PADDING_MM = 50f

        val PRESET_COLORS = listOf(
            android.graphics.Color.LTGRAY,
            android.graphics.Color.GRAY,
            android.graphics.Color.DKGRAY,
            android.graphics.Color.BLACK,
            android.graphics.Color.parseColor("#E1F5FE"),
            android.graphics.Color.parseColor("#E8F5E9"),
            android.graphics.Color.parseColor("#FFF3E0")
        )

        val currentStyle = getCurrentStyle()
        var spacingPx: Float = currentStyle.let { if (it is BackgroundStyle.Dots) it.spacing else if (it is BackgroundStyle.Lines) it.spacing else if (it is BackgroundStyle.Grid) it.spacing else context.mmToPx(5f) }
        var radiusPx: Float = if (currentStyle is BackgroundStyle.Dots) currentStyle.radius else context.mmToPx(0.5f)
        var thicknessPx: Float = if (currentStyle is BackgroundStyle.Lines) currentStyle.thickness else if (currentStyle is BackgroundStyle.Grid) currentStyle.thickness else context.mmToPx(0.2f)
        var selectedColor: Int = currentStyle.color
        var paddingTopPx: Float = currentStyle.paddingTop
        var paddingBottomPx: Float = currentStyle.paddingBottom
        var paddingLeftPx: Float = currentStyle.paddingLeft
        var paddingRightPx: Float = currentStyle.paddingRight
        var isCentered: Boolean = currentStyle.isCentered

        // Unit Helpers
        fun mmToProgress(mm: Float, minMm: Float, maxMm: Float): Int {
            val ratio = (mm - minMm) / (maxMm - minMm)
            return (ratio.coerceIn(0f, 1f) * 100).roundToInt()
        }

        fun progressToMm(progress: Int, minMm: Float, maxMm: Float): Float {
            val ratio = progress / 100f
            return minMm + (ratio * (maxMm - minMm))
        }

        // Emit Update
        fun emitUpdate() {
            val newStyle = when (rgPatternType.checkedRadioButtonId) {
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
            onStyleUpdate(newStyle)
        }

        // Update Labels/Sliders
        fun updateLabels() {
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

        fun setupColorPicker() {
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
                
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                drawable.setColor(color)
                if (color == android.graphics.Color.WHITE || color == android.graphics.Color.parseColor("#FFF3E0") || color == android.graphics.Color.parseColor("#E8F5E9") || color == android.graphics.Color.parseColor("#E1F5FE")) {
                    drawable.setStroke(2, android.graphics.Color.LTGRAY)
                }
                circle.background = drawable
                
                if (color == selectedColor) {
                     val ring = View(context)
                     val ringParams = android.widget.FrameLayout.LayoutParams(size, size)
                     ring.layoutParams = ringParams
                     val ringDrawable = android.graphics.drawable.GradientDrawable()
                     ringDrawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                     ringDrawable.setStroke(5, android.graphics.Color.BLACK)
                     ringDrawable.setColor(android.graphics.Color.TRANSPARENT)
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

        fun updateUIState(checkedId: Int) {
            val isBlank = checkedId == R.id.rb_blank
            val isDots = checkedId == R.id.rb_dots

            layoutSpacing.visibility = if (isBlank) View.GONE else View.VISIBLE
            layoutSize.visibility = if (isBlank) View.GONE else View.VISIBLE
            layoutColor.visibility = if (isBlank) View.GONE else View.VISIBLE
            layoutPageSettings.visibility = if (!isBlank && isFixedPageMode()) View.VISIBLE else View.GONE

            if (isBlank) return

            seekbarSpacing.progress = mmToProgress(context.pxToMm(spacingPx), MIN_SPACING_MM, MAX_SPACING_MM)
            if (isDots) {
                seekbarSize.progress = mmToProgress(context.pxToMm(radiusPx), MIN_RADIUS_MM, MAX_RADIUS_MM)
            } else {
                seekbarSize.progress = mmToProgress(context.pxToMm(thicknessPx), MIN_THICKNESS_MM, MAX_THICKNESS_MM)
            }
            updateLabels()
        }

        // Initialize UI
        when (currentStyle) {
            is BackgroundStyle.Blank -> rgPatternType.check(R.id.rb_blank)
            is BackgroundStyle.Dots -> rgPatternType.check(R.id.rb_dots)
            is BackgroundStyle.Lines -> rgPatternType.check(R.id.rb_lines)
            is BackgroundStyle.Grid -> rgPatternType.check(R.id.rb_grid)
        }
        cbCenterAlign.isChecked = isCentered
        seekbarPaddingTop.progress = mmToProgress(context.pxToMm(paddingTopPx), 0f, MAX_PADDING_MM)
        seekbarPaddingBottom.progress = mmToProgress(context.pxToMm(paddingBottomPx), 0f, MAX_PADDING_MM)
        seekbarPaddingLeft.progress = mmToProgress(context.pxToMm(paddingLeftPx), 0f, MAX_PADDING_MM)
        seekbarPaddingRight.progress = mmToProgress(context.pxToMm(paddingRightPx), 0f, MAX_PADDING_MM)

        // Setup Seekbars
        seekbarSpacing.max = 100
        seekbarSize.max = 100
        seekbarPaddingTop.max = 100
        seekbarPaddingBottom.max = 100
        seekbarPaddingLeft.max = 100
        seekbarPaddingRight.max = 100

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
        seekbarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) {
                val checkedId = rgPatternType.checkedRadioButtonId
                if (checkedId == R.id.rb_dots) radiusPx = context.mmToPx(progressToMm(p, MIN_RADIUS_MM, MAX_RADIUS_MM))
                else thicknessPx = context.mmToPx(progressToMm(p, MIN_THICKNESS_MM, MAX_THICKNESS_MM))
                updateLabels(); emitUpdate()
            } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        cbCenterAlign.setOnCheckedChangeListener { _, isChecked -> isCentered = isChecked; emitUpdate() }
        seekbarPaddingTop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { paddingTopPx = context.mmToPx(progressToMm(p, 0f, MAX_PADDING_MM)); updateLabels(); emitUpdate() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekbarPaddingBottom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { paddingBottomPx = context.mmToPx(progressToMm(p, 0f, MAX_PADDING_MM)); updateLabels(); emitUpdate() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekbarPaddingLeft.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { paddingLeftPx = context.mmToPx(progressToMm(p, 0f, MAX_PADDING_MM)); updateLabels(); emitUpdate() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekbarPaddingRight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { paddingRightPx = context.mmToPx(progressToMm(p, 0f, MAX_PADDING_MM)); updateLabels(); emitUpdate() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        rgPatternType.setOnCheckedChangeListener { _, id -> updateUIState(id); emitUpdate() }

        setupColorPicker()
        updateUIState(rgPatternType.checkedRadioButtonId)
    }
}
