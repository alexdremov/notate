package com.alexdremov.notate

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.alexdremov.notate.R
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.databinding.ActivityMainBinding
import com.alexdremov.notate.export.CanvasExportCoordinator
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.ui.SettingsSidebarController
import com.alexdremov.notate.ui.SidebarCoordinator
import com.alexdremov.notate.ui.ToolbarCoordinator
import com.alexdremov.notate.ui.dpToPx
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.util.ThumbnailGenerator
import com.alexdremov.notate.vm.DrawingViewModel
import com.onyx.android.sdk.api.device.EpdDeviceManager
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class CanvasActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: DrawingViewModel by viewModels()
    private var activePenPopup: com.alexdremov.notate.ui.dialog.PenSettingsPopup? = null
    private var isGridOpen = false

    private lateinit var sidebarCoordinator: SidebarCoordinator
    private lateinit var sidebarController: SettingsSidebarController
    private lateinit var toolbarCoordinator: ToolbarCoordinator
    private lateinit var exportCoordinator: CanvasExportCoordinator

    private var autoSaveJob: Job? = null
    private val saveMutex = Mutex()

    private var currentCanvasPath: String? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            exportCoordinator.onFilePickerResult(uri)
            if (uri != null) {
                sidebarCoordinator.close()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize State Holder
        isFixedPageState = mutableStateOf(false)

        // Use DU (Direct Update) as the baseline for high responsiveness and no flashing.
        EpdController.setViewDefaultUpdateMode(window.decorView, UpdateMode.DU)
        EpdController.setDisplayScheme(EpdController.SCHEME_SCRIBBLE)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentCanvasPath = intent.getStringExtra("CANVAS_PATH")

        enableImmersiveMode()

        // Initialize Coordinators
        exportCoordinator =
            CanvasExportCoordinator(
                this,
                lifecycleScope,
                { binding.canvasView.getModel() },
                exportLauncher,
            )

        sidebarCoordinator = SidebarCoordinator(this, binding.settingsSidebarContainer, binding.sidebarScrim)
        sidebarCoordinator.onStateChanged = {
            updateDrawingEnabledState()
            updateExclusionRects()
        }

        toolbarCoordinator =
            ToolbarCoordinator(this, binding.toolbarContainer, binding.root) { _ ->
                updateExclusionRects()
            }
        toolbarCoordinator.onOrientationChanged = {
            renderToolbar(viewModel.tools.value, viewModel.activeToolId.value)
        }
        toolbarCoordinator.setup()

        // Ensure clicking/tapping anywhere on the toolbar clears selection
        binding.toolbarContainer.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                binding.canvasView.getController().clearSelection()
                binding.canvasView.dismissActionPopup()
            }
            false // Don't consume, allow DraggableLinearLayout to handle dragging
        }

        // Initialize Sidebar Content Controller
        sidebarController =
            SettingsSidebarController(
                this,
                binding.settingsSidebarContainer,
                getCurrentStyle = { binding.canvasView.getBackgroundStyle() },
                onStyleUpdate = { newStyle -> binding.canvasView.setBackgroundStyle(newStyle) },
                onExportRequest = { action ->
                    when (action) {
                        is ExportAction.Export -> {
                            exportCoordinator.requestExport(action.isVector)
                        }

                        is ExportAction.Share -> {
                            exportCoordinator.requestShare(action.isVector)
                            sidebarCoordinator.close()
                        }
                    }
                },
            )

        binding.canvasView.onStrokeStarted = {
            activePenPopup?.dismiss()
            activePenPopup = null
            if (sidebarCoordinator.isOpen) {
                sidebarCoordinator.close()
            }
        }

        binding.canvasView.setCursorView(binding.cursorView)

        // ViewModel observation
        lifecycleScope.launch {
            launch {
                viewModel.tools.collect { tools ->
                    renderToolbar(tools, viewModel.activeToolId.value)
                    tools.find { it.type == ToolType.ERASER }?.let { eraser ->
                        binding.canvasView.setEraser(eraser)
                    }
                }
            }
            launch {
                viewModel.activeToolId.collect { id ->
                    renderToolbar(viewModel.tools.value, id)
                }
            }
            launch {
                viewModel.activeTool.collect { tool ->
                    binding.canvasView.setTool(tool)
                }
            }
            launch {
                viewModel.isDrawingEnabled.collect { enabled ->
                    binding.canvasView.setDrawingEnabled(enabled)
                }
            }
        }

        loadCanvas()
        setupAutoSave()
    }

    // State holder for Compose visibility
    private var isFixedPageState: androidx.compose.runtime.MutableState<Boolean>? = null

    private fun setupAutoSave() {
        binding.canvasView.onContentChanged = {
            scheduleAutoSave()
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob =
            lifecycleScope.launch {
                delay(500)
                if (isActive) {
                    saveCanvas()
                }
            }
    }

    private fun loadCanvas() {
        val path = currentCanvasPath ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonString =
                    if (path.startsWith("content://")) {
                        contentResolver.openInputStream(Uri.parse(path))?.bufferedReader()?.use { it.readText() }
                    } else {
                        val file = File(path)
                        if (file.exists() && file.length() > 0) file.readText() else null
                    }

                if (!jsonString.isNullOrEmpty()) {
                    val data = Json.decodeFromString<CanvasData>(jsonString)
                    withContext(Dispatchers.Main) {
                        binding.canvasView.loadCanvasData(data)
                        isFixedPageState?.value = data.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                        // Trigger toolbar update to show/hide navigation
                        renderToolbar(viewModel.tools.value, viewModel.activeToolId.value)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCanvas()
    }

    private fun saveCanvas() {
        val path = currentCanvasPath ?: return
        val rawData = binding.canvasView.getCanvasData()

        lifecycleScope.launch(Dispatchers.Default) {
            // Generate thumbnail off the UI thread but before serialization
            val thumbBase64 = ThumbnailGenerator.generateBase64(rawData)
            val dataWithThumb = rawData.copy(thumbnail = thumbBase64)

            withContext(NonCancellable) {
                saveMutex.withLock {
                    try {
                        val jsonString = Json.encodeToString(dataWithThumb)

                        if (path.startsWith("content://")) {
                            contentResolver.openOutputStream(Uri.parse(path), "wt")?.use {
                                it.write(jsonString.toByteArray())
                            }
                        } else {
                            // Atomic write for local files
                            val targetFile = File(path)
                            val tmpFile = File(targetFile.parent, "${targetFile.name}.tmp")
                            tmpFile.writeText(jsonString)
                            if (targetFile.exists()) {
                                targetFile.delete()
                            }
                            tmpFile.renameTo(targetFile)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateDrawingEnabledState() {
        val shouldDisable = sidebarCoordinator.isOpen || isGridOpen || activePenPopup != null
        viewModel.setDrawingEnabled(!shouldDisable)
    }

    private fun updateExclusionRects() {
        val rects = mutableListOf<android.graphics.Rect>()
        rects.addAll(toolbarCoordinator.getRects())

        if (sidebarCoordinator.isOpen) {
            val sidebarRect = android.graphics.Rect()
            binding.settingsSidebarContainer.getGlobalVisibleRect(sidebarRect)
            rects.add(sidebarRect)
        }

        binding.canvasView.setExclusionRects(rects)
    }

    private fun renderToolbar(
        tools: List<PenTool>,
        activeId: String,
    ) {
        binding.toolbarContainer.removeAllViews()

        val isHorizontal = toolbarCoordinator.getOrientation() == LinearLayout.HORIZONTAL
        val spacerSize = dpToPx(8)
        val sectionSpacerSize = dpToPx(16)

        fun addSpacer(size: Int) {
            binding.toolbarContainer.addView(
                Space(this).apply {
                    layoutParams =
                        if (isHorizontal) {
                            LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
                        } else {
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                        }
                },
            )
        }

        tools.forEachIndexed { index, tool ->
            val container =
                android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))

                    if (tool.id == activeId) {
                        background = ContextCompat.getDrawable(context, R.drawable.bg_tool_active)
                    } else {
                        background = null
                    }

                    setOnClickListener { handleToolClick(tool.id, it) }
                }

            val iconView =
                android.widget.ImageView(this).apply {
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                            }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

                    val iconRes =
                        when (tool.type) {
                            ToolType.ERASER -> {
                                R.drawable.ink_eraser_24
                            }

                            ToolType.PEN -> {
                                when (tool.strokeType) {
                                    StrokeType.PENCIL -> R.drawable.stylus_pencil_24
                                    StrokeType.HIGHLIGHTER -> R.drawable.stylus_highlighter_24
                                    StrokeType.BRUSH -> R.drawable.stylus_brush_24
                                    StrokeType.CHARCOAL -> R.drawable.stylus_pen_24
                                    StrokeType.DASH -> R.drawable.stylus_dash_24
                                    else -> R.drawable.stylus_fountain_pen_24
                                }
                            }

                            ToolType.SELECT -> {
                                R.drawable.ic_tool_select
                            } // Placeholder or existing icon
                        }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(Color.BLACK)
                }
            container.addView(iconView)

            if (tool.type == ToolType.PEN) {
                val badgeSize = dpToPx(14)
                val badgeView =
                    View(this).apply {
                        layoutParams =
                            android.widget.FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                                setMargins(0, 0, dpToPx(8), dpToPx(8))
                            }

                        background =
                            android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(tool.color)
                                setStroke(dpToPx(1), Color.LTGRAY)
                            }
                    }
                container.addView(badgeView)
            }

            binding.toolbarContainer.addView(container)

            if (index < tools.size - 1 || tools.count { it.type == ToolType.PEN } < 7) {
                addSpacer(spacerSize)
            }
        }

        if (tools.count { it.type == ToolType.PEN } < 7) {
            val addBtn =
                ImageButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setImageResource(R.drawable.ic_add)
                    background = null
                    imageTintList = ColorStateList.valueOf(Color.GRAY)
                    setOnClickListener {
                        binding.canvasView.getController().clearSelection()
                        binding.canvasView.dismissActionPopup()
                        viewModel.addPen()
                    }
                }
            binding.toolbarContainer.addView(addBtn)
        }

        addSpacer(sectionSpacerSize)

        val undoBtn =
            ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setImageResource(R.drawable.ic_undo)
                background = null
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                setOnClickListener {
                    binding.canvasView.getController().clearSelection()
                    binding.canvasView.dismissActionPopup()
                    binding.canvasView.undo()
                }
            }
        binding.toolbarContainer.addView(undoBtn)

        val redoBtn =
            ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setImageResource(R.drawable.ic_redo)
                background = null
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                setOnClickListener {
                    binding.canvasView.getController().clearSelection()
                    binding.canvasView.dismissActionPopup()
                    binding.canvasView.redo()
                }
            }
        binding.toolbarContainer.addView(redoBtn)

        // Add Navigation controls if in Fixed Page mode
        if (isFixedPageState?.value == true) {
            addSpacer(sectionSpacerSize)
            val composeView =
                androidx.compose.ui.platform.ComposeView(this).apply {
                    // Important: View Composition Strategy to avoid memory leaks if views are detached/reattached frequently
                    setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        com.alexdremov.notate.ui.navigation.CompactPageNavigation(
                            controller = binding.canvasView.getController(),
                            model = binding.canvasView.getModel(),
                            isVertical = !isHorizontal,
                            onGridOpenChanged = { isOpen ->
                                isGridOpen = isOpen
                                binding.canvasView.getController().clearSelection()
                                binding.canvasView.dismissActionPopup()
                                updateDrawingEnabledState()
                            },
                        )
                    }
                }
            binding.toolbarContainer.addView(composeView)
        }

        addSpacer(spacerSize)
        val settingsBtn =
            ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setImageResource(R.drawable.ic_more_vert)
                background = null
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                setOnClickListener {
                    binding.canvasView.getController().clearSelection()
                    binding.canvasView.dismissActionPopup()
                    sidebarCoordinator.open()
                    sidebarController.showMainMenu()
                }
            }
        binding.toolbarContainer.addView(settingsBtn)

        binding.toolbarContainer.requestLayout()
    }

    private fun handleToolClick(
        toolId: String,
        anchor: View,
    ) {
        binding.canvasView.getController().clearSelection()
        binding.canvasView.dismissActionPopup()
        if (viewModel.activeToolId.value == toolId) {
            val tool = viewModel.tools.value.find { it.id == toolId } ?: return

            val popup =
                com.alexdremov.notate.ui.dialog.PenSettingsPopup(
                    this,
                    tool,
                    onUpdate = { updatedTool ->
                        viewModel.updateTool(updatedTool)
                    },
                    onRemove = { toolToRemove ->
                        viewModel.removePen(toolToRemove.id)
                    },
                    onDismiss = {
                        com.alexdremov.notate.util.EpdFastModeController
                            .exitFastMode()
                        activePenPopup = null
                        updateDrawingEnabledState()
                    },
                )

            activePenPopup = popup
            updateDrawingEnabledState()

            com.alexdremov.notate.util.EpdFastModeController
                .enterFastMode()

            activePenPopup?.show(anchor)
        } else {
            viewModel.selectTool(toolId)
        }
    }
}
