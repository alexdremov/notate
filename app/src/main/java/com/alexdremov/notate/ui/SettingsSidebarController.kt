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
        switchAxis.setOnCheckedChangeListener { _, isChecked ->
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

        // Constants for Ranges (in mm)
        val MIN_SPACING_MM = 2f
        val MAX_SPACING_MM = 15f
        val MIN_RADIUS_MM = CanvasConfig.TOOLS_MIN_STROKE_MM
        val MAX_RADIUS_MM = CanvasConfig.TOOLS_MAX_STROKE_MM
        val MIN_THICKNESS_MM = 0.1f
        val MAX_THICKNESS_MM = 1.0f

        // Internal State
        var spacingPx: Float = 50f
        var radiusPx: Float = 2f
        var thicknessPx: Float = 1f

        // Unit Helpers
        fun mmToProgress(
            mm: Float,
            minMm: Float,
            maxMm: Float,
        ): Int {
            val ratio = (mm - minMm) / (maxMm - minMm)
            return (ratio.coerceIn(0f, 1f) * 100).roundToInt()
        }

        fun progressToMm(
            progress: Int,
            minMm: Float,
            maxMm: Float,
        ): Float {
            val ratio = progress / 100f
            return minMm + (ratio * (maxMm - minMm))
        }

        // Emit Update
        fun emitUpdate() {
            val newStyle =
                when (rgPatternType.checkedRadioButtonId) {
                    R.id.rb_dots -> BackgroundStyle.Dots(spacing = spacingPx, radius = radiusPx)
                    R.id.rb_lines -> BackgroundStyle.Lines(spacing = spacingPx, thickness = thicknessPx)
                    R.id.rb_grid -> BackgroundStyle.Grid(spacing = spacingPx, thickness = thicknessPx)
                    else -> BackgroundStyle.Blank()
                }
            onStyleUpdate(newStyle)
        }

        // Update Labels/Sliders
        fun updateLabels() {
            val checkedId = rgPatternType.checkedRadioButtonId
            if (checkedId == R.id.rb_blank) return

            val spacingMm = context.pxToMm(spacingPx)
            tvSpacingLabel.text = String.format("Spacing: %.1f mm", spacingMm)

            if (checkedId == R.id.rb_dots) {
                val radiusMm = context.pxToMm(radiusPx)
                tvSizeLabel.text = String.format("Radius: %.1f mm", radiusMm)
            } else {
                val thicknessMm = context.pxToMm(thicknessPx)
                tvSizeLabel.text = String.format("Thickness: %.1f mm", thicknessMm)
            }
        }

        fun updateUIState(checkedId: Int) {
            val isBlank = checkedId == R.id.rb_blank
            val isDots = checkedId == R.id.rb_dots

            layoutSpacing.visibility = if (isBlank) View.GONE else View.VISIBLE
            layoutSize.visibility = if (isBlank) View.GONE else View.VISIBLE

            if (isBlank) return

            val spacingMm = context.pxToMm(spacingPx)
            seekbarSpacing.progress = mmToProgress(spacingMm, MIN_SPACING_MM, MAX_SPACING_MM)

            if (isDots) {
                val radiusMm = context.pxToMm(radiusPx)
                seekbarSize.progress = mmToProgress(radiusMm, MIN_RADIUS_MM, MAX_RADIUS_MM)
            } else {
                val thicknessMm = context.pxToMm(thicknessPx)
                seekbarSize.progress = mmToProgress(thicknessMm, MIN_THICKNESS_MM, MAX_THICKNESS_MM)
            }
            updateLabels()
        }

        // Initialize from current style
        val currentStyle = getCurrentStyle()
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

        // Setup Seekbars
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

        rgPatternType.setOnCheckedChangeListener { _, checkedId ->
            updateUIState(checkedId)
            emitUpdate()
        }

        // Initial Update
        updateUIState(rgPatternType.checkedRadioButtonId)
    }
}
