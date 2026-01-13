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
    private val onEditToolbar: () -> Unit
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

        mainMenuView.findViewById<View>(R.id.menu_item_edit_toolbar).setOnClickListener {
            onEditToolbar()
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

    private fun bindBackgroundSettings(view: View) {
        com.alexdremov.notate.ui.settings.BackgroundSettingsBinder(
            context,
            view,
            getCurrentStyle,
            isFixedPageMode(),
            onStyleUpdate,
        )
    }
}
