package com.alexdremov.notate.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.R
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.pxToMm
import com.alexdremov.notate.ui.settings.InputSettingsPanel
import com.alexdremov.notate.ui.settings.InputSettingsState
import com.alexdremov.notate.ui.settings.InterfaceSettingsPanel
import com.alexdremov.notate.ui.settings.InterfaceSettingsState
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.vm.DrawingViewModel
import kotlin.math.roundToInt

class SettingsSidebarController(
    private val context: Context,
    private val container: ViewGroup,
    private val viewModel: DrawingViewModel,
    private val getCurrentStyle: () -> BackgroundStyle,
    private val isFixedPageMode: () -> Boolean,
    private val onStyleUpdate: (BackgroundStyle) -> Unit,
    private val onExportRequest: (ExportAction) -> Unit,
    private val onEditToolbar: () -> Unit,
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

        mainMenuView.findViewById<View>(R.id.menu_item_debug).setOnClickListener {
            showDebugMenu()
        }
    }

    private fun showWritingMenu() {
        contentFrame.removeAllViews()

        tvTitle.text = "Writing"
        btnBack.visibility = View.VISIBLE

        val composeView =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    NotateTheme {
                        Surface(color = Color.White) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                            ) {
                                val collapsible by viewModel.isCollapsibleToolbar.collectAsState()
                                val timeout by viewModel.toolbarCollapseTimeout.collectAsState()

                                val scribbleEnabled =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .isScribbleToEraseEnabled(context)
                                val shapeEnabled =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .isShapePerfectionEnabled(context)
                                val angleSnapping =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .isAngleSnappingEnabled(context)
                                val axisLocking =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .isAxisLockingEnabled(context)
                                val shapeDelay =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .getShapePerfectionDelay(context)
                                        .toFloat()

                                val (localScribble, setScribble) =
                                    androidx.compose.runtime.remember {
                                        androidx.compose.runtime.mutableStateOf(
                                            scribbleEnabled,
                                        )
                                    }
                                val (localShape, setShape) =
                                    androidx.compose.runtime.remember {
                                        androidx.compose.runtime.mutableStateOf(
                                            shapeEnabled,
                                        )
                                    }
                                val (localAngle, setAngle) =
                                    androidx.compose.runtime.remember {
                                        androidx.compose.runtime.mutableStateOf(
                                            angleSnapping,
                                        )
                                    }
                                val (localAxis, setAxis) =
                                    androidx.compose.runtime.remember {
                                        androidx.compose.runtime.mutableStateOf(
                                            axisLocking,
                                        )
                                    }
                                val (localShapeDelay, setShapeDelay) =
                                    androidx.compose.runtime.remember {
                                        androidx.compose.runtime
                                            .mutableFloatStateOf(
                                                shapeDelay,
                                            )
                                    }

                                InputSettingsPanel(
                                    state = InputSettingsState(localScribble, localShape, localAngle, localAxis, localShapeDelay),
                                    onScribbleChange = {
                                        setScribble(it)
                                        com.alexdremov.notate.data.PreferencesManager
                                            .setScribbleToEraseEnabled(context, it)
                                    },
                                    onShapeChange = {
                                        setShape(it)
                                        com.alexdremov.notate.data.PreferencesManager
                                            .setShapePerfectionEnabled(context, it)
                                    },
                                    onAngleChange = {
                                        setAngle(it)
                                        com.alexdremov.notate.data.PreferencesManager
                                            .setAngleSnappingEnabled(context, it)
                                    },
                                    onAxisChange = {
                                        setAxis(it)
                                        com.alexdremov.notate.data.PreferencesManager
                                            .setAxisLockingEnabled(context, it)
                                    },
                                    onShapeDelayChange = { setShapeDelay(it) },
                                    onShapeDelayFinished = {
                                        com.alexdremov.notate.data.PreferencesManager.setShapePerfectionDelay(
                                            context,
                                            localShapeDelay.toLong(),
                                        )
                                    },
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )

                                val (localTimeout, setLocalTimeout) =
                                    androidx.compose.runtime.remember(timeout) {
                                        androidx.compose.runtime.mutableFloatStateOf(timeout.toFloat())
                                    }

                                InterfaceSettingsPanel(
                                    state = InterfaceSettingsState(collapsible, localTimeout),
                                    onCollapsibleChange = { viewModel.setCollapsibleToolbar(it) },
                                    onTimeoutChange = { setLocalTimeout(it) },
                                    onTimeoutFinished = { viewModel.setToolbarCollapseTimeout(localTimeout.toLong()) },
                                )
                            }
                        }
                    }
                }
            }

        contentFrame.addView(composeView)
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

    private fun showDebugMenu() {
        contentFrame.removeAllViews()
        val debugView = LayoutInflater.from(context).inflate(R.layout.sidebar_debug_menu, contentFrame, false)
        contentFrame.addView(debugView)

        tvTitle.text = "Debug"
        btnBack.visibility = View.VISIBLE

        debugView.findViewById<Switch>(R.id.switch_debug_simple_renderer).apply {
            isChecked = CanvasConfig.DEBUG_USE_SIMPLE_RENDERER
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_USE_SIMPLE_RENDERER = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugSimpleRendererEnabled(context, isChecked)
            }
        }

        debugView.findViewById<Switch>(R.id.switch_debug_ram_usage).apply {
            isChecked = CanvasConfig.DEBUG_SHOW_RAM_USAGE
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_SHOW_RAM_USAGE = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugRamUsageEnabled(context, isChecked)
            }
        }

        debugView.findViewById<Switch>(R.id.switch_debug_show_tiles).apply {
            isChecked = CanvasConfig.DEBUG_SHOW_TILES
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_SHOW_TILES = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugShowTilesEnabled(context, isChecked)
            }
        }

        debugView.findViewById<Switch>(R.id.switch_debug_show_regions).apply {
            isChecked = CanvasConfig.DEBUG_SHOW_REGIONS
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_SHOW_REGIONS = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugShowRegionsEnabled(context, isChecked)
            }
        }

        debugView.findViewById<Switch>(R.id.switch_debug_bounding_box).apply {
            isChecked = CanvasConfig.DEBUG_SHOW_BOUNDING_BOX
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_SHOW_BOUNDING_BOX = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugBoundingBoxEnabled(context, isChecked)
            }
        }

        debugView.findViewById<Switch>(R.id.switch_debug_profiling).apply {
            isChecked = CanvasConfig.DEBUG_ENABLE_PROFILING
            setOnCheckedChangeListener { _, isChecked ->
                CanvasConfig.DEBUG_ENABLE_PROFILING = isChecked
                com.alexdremov.notate.data.PreferencesManager
                    .setDebugProfilingEnabled(context, isChecked)
            }
        }

        val spinnerLogLevel: Spinner = debugView.findViewById(R.id.spinner_debug_log_level)
        val levels =
            com.alexdremov.notate.util.Logger.Level
                .values()
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, levels.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLogLevel.adapter = adapter

        val currentLevel =
            com.alexdremov.notate.util.Logger
                .getMinLogLevelToShow()
        spinnerLogLevel.setSelection(levels.indexOf(currentLevel))

        spinnerLogLevel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val level = levels[position]
                    com.alexdremov.notate.util.Logger
                        .setMinLogLevelToShow(level)
                    com.alexdremov.notate.data.PreferencesManager
                        .setMinLogLevel(context, level.priority)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
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
