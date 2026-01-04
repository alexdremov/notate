package com.example.notate.ui.dialog

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
import com.example.notate.R
import com.example.notate.model.BackgroundStyle
import com.example.notate.ui.mmToPx
import kotlin.math.roundToInt

class BackgroundSettingsPopup(
    private val context: Context,
    private val currentStyle: BackgroundStyle,
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

    // Internal State (World Units / Pixels)
    private var spacingPx: Float = 50f
    private var radiusPx: Float = 2f
    private var thicknessPx: Float = 1f

    // Constants for Ranges (in mm)
    private val MIN_SPACING_MM = 2f
    private val MAX_SPACING_MM = 15f

    private val MIN_RADIUS_MM = 0.1f
    private val MAX_RADIUS_MM = 1.5f

    private val MIN_THICKNESS_MM = 0.1f
    private val MAX_THICKNESS_MM = 1.0f

    init {
        contentView = view
        isFocusable = true
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = 16f

        // Initialize state from current style
        when (currentStyle) {
            is BackgroundStyle.Blank -> {
                rgPatternType.check(R.id.rb_blank)
                // Use defaults for others if switching back
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
        updateUIState(rgPatternType.checkedRadioButtonId)

        rgPatternType.setOnCheckedChangeListener { _, checkedId ->
            updateUIState(checkedId)
            emitUpdate()
        }

        setOnDismissListener {
            onDismiss()
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
    }

    private fun emitUpdate() {
        val newStyle =
            when (rgPatternType.checkedRadioButtonId) {
                R.id.rb_dots -> BackgroundStyle.Dots(spacing = spacingPx, radius = radiusPx)
                R.id.rb_lines -> BackgroundStyle.Lines(spacing = spacingPx, thickness = thicknessPx)
                R.id.rb_grid -> BackgroundStyle.Grid(spacing = spacingPx, thickness = thicknessPx)
                else -> BackgroundStyle.Blank()
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
