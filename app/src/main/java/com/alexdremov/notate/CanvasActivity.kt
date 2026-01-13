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
    private val isToolbarHorizontal = mutableStateOf(true)

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
            isToolbarHorizontal.value = toolbarCoordinator.getOrientation() == LinearLayout.HORIZONTAL
        }
        
        // Disable drawing when interacting with toolbar (touch down)
        // This prevents accidental strokes and improves UI responsiveness
        binding.toolbarContainer.onDown = {
            viewModel.setDrawingEnabled(false)
            com.onyx.android.sdk.api.device.EpdDeviceManager.enterAnimationUpdate(true)
        }
        
        binding.toolbarContainer.onUp = {
             // Re-enable drawing only if not in edit mode
            if (!viewModel.isEditMode.value) {
                viewModel.setDrawingEnabled(true)
            }
            com.onyx.android.sdk.api.device.EpdDeviceManager.exitAnimationUpdate(true)
        }
        
        toolbarCoordinator.setup()

        // Initialize Toolbar UI (Compose)
        binding.toolbarContainer.removeAllViews()
        val composeToolbar = androidx.compose.ui.platform.ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val horizontal by remember { isToolbarHorizontal }
                com.alexdremov.notate.ui.toolbar.ToolbarView(
                    viewModel = viewModel,
                    isHorizontal = horizontal,
                    canvasController = binding.canvasView.getController(),
                    canvasModel = binding.canvasView.getModel(),
                    onToolClick = { item, rect -> 
                        // Convert Compose Rect to Android Graphics Rect
                        val androidRect = android.graphics.Rect(
                            rect.left.toInt(),
                            rect.top.toInt(),
                            rect.right.toInt(),
                            rect.bottom.toInt()
                        )
                        handleToolClick(item.id, androidRect) 
                    },
                    onActionClick = { action ->
                        when(action) {
                            com.alexdremov.notate.model.ActionType.UNDO -> binding.canvasView.undo()
                            com.alexdremov.notate.model.ActionType.REDO -> binding.canvasView.redo()
                        }
                    },
                    onOpenSidebar = { 
                        sidebarCoordinator.open()
                        sidebarController.showMainMenu()
                    }
                )
            }
        }
        binding.toolbarContainer.addView(composeToolbar)

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
                isFixedPageMode = { binding.canvasView.getModel().canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES },
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
                onEditToolbar = {
                    sidebarCoordinator.close()
                    viewModel.setEditMode(true)
                }
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
                // Legacy support for Eraser cursor update
                viewModel.activeTool.collect { tool ->
                    binding.canvasView.setTool(tool)
                    if (tool.type == ToolType.ERASER) {
                        binding.canvasView.setEraser(tool)
                    }
                }
            }
            launch {
                viewModel.isDrawingEnabled.collect { enabled ->
                    binding.canvasView.setDrawingEnabled(enabled)
                }
            }
            launch {
                viewModel.isEditMode.collect { isEdit ->
                    android.util.Log.d("BooxVibesDebug", "CanvasActivity: isEditMode=$isEdit, setting isDragEnabled=${!isEdit}")
                    binding.toolbarContainer.isDragEnabled = !isEdit
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
                    // Parse heavy geometry on background thread
                    val loadedState = com.alexdremov.notate.data.CanvasSerializer.parseCanvasData(data)
                    
                    withContext(Dispatchers.Main) {
                        binding.canvasView.loadCanvasState(loadedState)
                        val isFixed = loadedState.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                        isFixedPageState?.value = isFixed
                        viewModel.setFixedPageMode(isFixed)
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

    private fun handleToolClick(
        toolId: String,
        targetRect: android.graphics.Rect,
    ) {
        android.util.Log.d("BooxVibesDebug", "CanvasActivity: handleToolClick ID=$toolId, Rect=$targetRect, ActiveID=${viewModel.activeToolId.value}")
        binding.canvasView.getController().clearSelection()
        binding.canvasView.dismissActionPopup()
        if (viewModel.activeToolId.value == toolId) {
            val item = viewModel.toolbarItems.value.find { it.id == toolId }
            val tool = when (item) {
                is com.alexdremov.notate.model.ToolbarItem.Pen -> item.penTool
                is com.alexdremov.notate.model.ToolbarItem.Eraser -> {
                    // Logic to get the current eraser config
                    viewModel.activeTool.value
                }
                is com.alexdremov.notate.model.ToolbarItem.Select -> {
                    viewModel.activeTool.value
                }
                else -> null
            } ?: return

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

            android.util.Log.d("BooxVibesDebug", "CanvasActivity: Showing Popup!")
            activePenPopup?.show(binding.root, targetRect)
        } else {
            viewModel.selectTool(toolId)
        }
    }
}
