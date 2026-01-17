@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.ui.SettingsSidebarController
import com.alexdremov.notate.ui.SidebarCoordinator
import com.alexdremov.notate.ui.ToolbarCoordinator
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.navigation.CompactPageNavigation
import com.alexdremov.notate.ui.toolbar.MainToolbar
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
import java.io.File
import androidx.compose.ui.geometry.Rect as ComposeRect

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
    private lateinit var canvasRepository: com.alexdremov.notate.data.CanvasRepository
    private lateinit var syncManager: com.alexdremov.notate.data.SyncManager

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

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Persistent permission
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val matrix = android.graphics.Matrix()
                binding.canvasView.getViewportMatrix(matrix)
                val values = FloatArray(9)
                matrix.getValues(values)
                val tx = values[android.graphics.Matrix.MTRANS_X]
                val ty = values[android.graphics.Matrix.MTRANS_Y]
                val scale = values[android.graphics.Matrix.MSCALE_X] // Uniform scale assumption

                // Calculate dimensions
                var width = 400f
                var height = 400f
                try {
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                    }
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth.toFloat()
                        height = options.outHeight.toFloat()
                        val maxDim = 800f
                        if (width > maxDim || height > maxDim) {
                            val s = kotlin.math.min(maxDim / width, maxDim / height)
                            width *= s
                            height *= s
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Calculate center in World Coordinates
                // Screen Center = (Width/2, Height/2)
                // World = (Screen - Translate) / Scale
                val screenCenterX = binding.canvasView.width / 2f
                val screenCenterY = binding.canvasView.height / 2f

                val worldX = (screenCenterX - tx) / scale
                val worldY = (screenCenterY - ty) / scale

                binding.canvasView.getController().pasteImage(uri.toString(), worldX, worldY, width, height)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize State Holder
        isFixedPageState = mutableStateOf(false)
        canvasRepository =
            com.alexdremov.notate.data
                .CanvasRepository(this)
        syncManager =
            com.alexdremov.notate.data
                .SyncManager(this, canvasRepository)

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
        var isToolbarInteractionActive = false
        val finishToolbarInteraction = {
            if (isToolbarInteractionActive) {
                isToolbarInteractionActive = false
                if (!viewModel.isEditMode.value) {
                    viewModel.setDrawingEnabled(true)
                }
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .exitAnimationUpdate(true)
            }
        }

        binding.toolbarContainer.onDown = {
            if (!isToolbarInteractionActive) {
                isToolbarInteractionActive = true
                viewModel.setDrawingEnabled(false)
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .enterAnimationUpdate(true)
            }
        }

        binding.toolbarContainer.onUp = {
            finishToolbarInteraction()
        }

        binding.toolbarContainer.onLongPress = {
            finishToolbarInteraction()
            viewModel.setEditMode(true)
        }

        toolbarCoordinator.setup()

        // Initialize Toolbar UI (Compose)
        binding.toolbarContainer.removeAllViews()
        val composeToolbar =
            androidx.compose.ui.platform.ComposeView(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val horizontal by remember { isToolbarHorizontal }
                    MainToolbar(
                        viewModel = viewModel,
                        isHorizontal = horizontal,
                        canvasController = binding.canvasView.getController(),
                        canvasModel = binding.canvasView.getModel(),
                        onToolClick = { item: ToolbarItem, rect: ComposeRect ->
                            // Convert Compose Rect to Android Graphics Rect
                            val androidRect =
                                android.graphics.Rect(
                                    rect.left.toInt(),
                                    rect.top.toInt(),
                                    rect.right.toInt(),
                                    rect.bottom.toInt(),
                                )
                            handleToolClick(item.id, androidRect)
                        },
                        onActionClick = { action ->
                            when (action) {
                                com.alexdremov.notate.model.ActionType.UNDO -> {
                                    binding.canvasView.undo()
                                }

                                com.alexdremov.notate.model.ActionType.REDO -> {
                                    binding.canvasView.redo()
                                }

                                com.alexdremov.notate.model.ActionType.INSERT_IMAGE -> {
                                    imagePickerLauncher.launch(arrayOf("image/*"))
                                }
                            }
                        },
                        onOpenSidebar = {
                            sidebarCoordinator.open()
                            sidebarController.showMainMenu()
                        },
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
                },
            )

        binding.canvasView.onStrokeStarted = {
            activePenPopup?.dismiss()
            activePenPopup = null
            if (sidebarCoordinator.isOpen) {
                sidebarCoordinator.close()
            }
        }

        binding.canvasView.onRequestInsertImage = {
            imagePickerLauncher.launch(arrayOf("image/*"))
        }

        binding.canvasView.setCursorView(binding.cursorView)
        binding.minimapView.setup(binding.canvasView)

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
                // Observe current eraser for stylus button support
                viewModel.currentEraser.collect { eraser ->
                    eraser?.let { binding.canvasView.setEraser(it) }
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

    @androidx.annotation.OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun loadCanvas() {
        val path = currentCanvasPath ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = canvasRepository.loadCanvas(path)

            if (result != null) {
                withContext(Dispatchers.Main) {
                    val tUiStart = System.currentTimeMillis()
                    binding.canvasView.loadCanvasState(result.canvasState)
                    val isFixed = result.canvasState.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                    isFixedPageState?.value = isFixed
                    viewModel.setFixedPageMode(isFixed)

                    // Restore Toolbar Configuration
                    if (result.canvasState.toolbarItems.isNotEmpty()) {
                        viewModel.setToolbarItems(result.canvasState.toolbarItems)
                    }

                    val tUiEnd = System.currentTimeMillis()
                    android.util.Log.d("CanvasActivity", "  UI Load: ${tUiEnd - tUiStart}ms")

                    if (result.migrationPerformed && result.newPath != null) {
                        currentCanvasPath = result.newPath
                        android.util.Log.d("CanvasActivity", "Migration completed, new path: $currentCanvasPath")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCanvas()
        // Trigger background sync if project is configured
        currentCanvasPath?.let { path ->
            android.util.Log.d("CanvasActivity", "onPause: Attempting to sync for file $path")
            // Use GlobalScope (or ProcessLifecycleScope) to ensure sync continues after Activity pause/destroy
            // This is "fire and forget" logic for this context.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val projectId = syncManager.findProjectForFile(path)
                    if (projectId != null) {
                        android.util.Log.d("CanvasActivity", "Triggering background sync for project $projectId")
                        syncManager.syncProject(projectId)
                    } else {
                        android.util.Log.w("CanvasActivity", "Could not find project for file $path, sync skipped.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CanvasActivity", "Background sync failed", e)
                }
            }
        }
    }

    @androidx.annotation.OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun saveCanvas() {
        val path = currentCanvasPath ?: return
        android.util.Log.d("CanvasActivity", "Saving canvas to $path")
        val rawData =
            binding.canvasView.getCanvasData().copy(
                toolbarItems = viewModel.toolbarItems.value,
            )

        lifecycleScope.launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                saveMutex.withLock {
                    try {
                        canvasRepository.saveCanvas(path, rawData)
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
        android.util.Log.d(
            "BooxVibesDebug",
            "CanvasActivity: handleToolClick ID=$toolId, Rect=$targetRect, ActiveID=${viewModel.activeToolId.value}",
        )
        binding.canvasView.getController().clearSelection()
        binding.canvasView.dismissActionPopup()
        if (viewModel.activeToolId.value == toolId) {
            val item = viewModel.toolbarItems.value.find { it.id == toolId }
            val tool =
                when (item) {
                    is com.alexdremov.notate.model.ToolbarItem.Pen -> {
                        item.penTool
                    }

                    is com.alexdremov.notate.model.ToolbarItem.Eraser -> {
                        // Logic to get the current eraser config
                        viewModel.activeTool.value
                    }

                    is com.alexdremov.notate.model.ToolbarItem.Select -> {
                        viewModel.activeTool.value
                    }

                    else -> {
                        null
                    }
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
