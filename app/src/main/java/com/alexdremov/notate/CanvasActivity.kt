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
import android.widget.Toast
import androidx.activity.addCallback
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
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.alexdremov.notate.util.Logger
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
    private var currentSession: com.alexdremov.notate.data.CanvasSession? = null

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
                lifecycleScope.launch {
                    // 1. Import & Measure (IO Thread)
                    val (finalUriStr, w, h) =
                        withContext(Dispatchers.IO) {
                            // Import
                            val importedPath =
                                com.alexdremov.notate.util.ImageImportHelper
                                    .importImage(this@CanvasActivity, uri)

                            val uriToUse =
                                if (importedPath != null) {
                                    android.net.Uri.parse(importedPath)
                                } else {
                                    // Fallback to legacy behavior
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri,
                                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    } catch (e: Exception) {
                                        Logger.e("ImageImport", "Failed to take permission", e)
                                    }
                                    uri
                                }

                            // Measure
                            var width = 400f
                            var height = 400f
                            try {
                                val options = android.graphics.BitmapFactory.Options()
                                options.inJustDecodeBounds = true

                                val scheme = uriToUse.scheme
                                if (scheme == "file") {
                                    android.graphics.BitmapFactory.decodeFile(uriToUse.path, options)
                                } else {
                                    contentResolver.openInputStream(uriToUse)?.use {
                                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                                    }
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
                                Logger.e("ImageImport", "Failed to decode dimensions", e, showToUser = true)
                            }
                            Triple(uriToUse.toString(), width, height)
                        }

                    // 2. Capture View State (UI Thread) - Recapture after IO to avoid race condition
                    val matrix = android.graphics.Matrix()
                    binding.canvasView.getViewportMatrix(matrix)
                    val screenCenterX = binding.canvasView.width / 2f
                    val screenCenterY = binding.canvasView.height / 2f

                    // 3. Paste (UI Thread)
                    val values = FloatArray(9)
                    matrix.getValues(values)
                    val tx = values[android.graphics.Matrix.MTRANS_X]
                    val ty = values[android.graphics.Matrix.MTRANS_Y]
                    val scale = values[android.graphics.Matrix.MSCALE_X]

                    val worldX = (screenCenterX - tx) / scale
                    val worldY = (screenCenterY - ty) / scale

                    binding.canvasView.getController().pasteImage(finalUriStr, worldX, worldY, w, h)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intercept Back Press for non-blocking save
        onBackPressedDispatcher.addCallback(this) {
            saveCanvas()
            finish()
        }

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
        toolbarCoordinator.onDragStateChanged = { isDragging ->
            viewModel.setToolbarDragging(isDragging)
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
            // Bug Fix: Do not allow edit mode if toolbar is collapsed
            if (!viewModel.isToolbarCollapsed.value) {
                viewModel.setEditMode(true)
            }
        }

        toolbarCoordinator.setup()

        // Wire up Auto-Collapse
        toolbarCoordinator.onRequestCollapse = {
            // Take a consistent snapshot of relevant state before deciding to collapse
            val shouldCollapse =
                with(viewModel) {
                    !isToolbarCollapsed.value &&
                        !isToolbarDragging.value &&
                        !isEditMode.value &&
                        !isPenPopupOpen.value
                }
            if (shouldCollapse && !sidebarCoordinator.isOpen) {
                viewModel.setToolbarCollapsed(true)
            }
        }

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
                        onToolbarExpandStart = {
                            toolbarCoordinator.savePosition()
                        },
                        onToolbarExpanded = {
                            toolbarCoordinator.ensureOnScreen()
                            binding.canvasView.refreshScreen()
                        },
                        onToolbarCollapsed = {
                            toolbarCoordinator.restorePosition()
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
                viewModel,
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
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    Logger.userEvents.collect { event ->
                        binding.errorBanner.show(event)
                    }
                }
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
                        Logger.d("NotateDebug", "CanvasActivity: isEditMode=$isEdit, setting isDragEnabled=${!isEdit}")
                        binding.toolbarContainer.isDragEnabled = !isEdit
                    }
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
            // Close previous session if any, ensuring we don't leak disk space on reload
            // Note: If saving is in progress, this might be risky, but loadCanvas implies new state.
            // Ideally we'd wait for save. But for now, we rely on unique dirs.
            // currentSession?.close() // Disabled to prevent race with background save of previous session.

            val session = canvasRepository.openCanvasSession(path)
// ...

            if (session != null) {
                currentSession = session
                withContext(Dispatchers.Main) {
                    val tUiStart = System.currentTimeMillis()

                    binding.canvasView.getModel().initializeSession(session.regionManager)
                    binding.canvasView.loadMetadata(session.metadata)

                    val isFixed = session.metadata.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                    isFixedPageState?.value = isFixed
                    viewModel.setFixedPageMode(isFixed)

                    if (session.metadata.toolbarItems.isNotEmpty()) {
                        viewModel.setToolbarItems(session.metadata.toolbarItems)
                    }

                    val tUiEnd = System.currentTimeMillis()
                    Logger.d("CanvasActivity", "  UI Load: ${tUiEnd - tUiStart}ms")
                }
            } else {
                Logger.e("CanvasActivity", "Failed to load session for $path", showToUser = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // We still save on pause to handle Home/Recents, but mutex ensures serialization
        saveCanvas()
        currentCanvasPath?.let { path ->
            Logger.d("CanvasActivity", "onPause: Attempting to sync for file $path")
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val projectId = syncManager.findProjectForFile(path)
                    if (projectId != null) {
                        Logger.d("CanvasActivity", "Triggering background sync for project $projectId")
                        syncManager.syncProject(projectId)
                    } else {
                        Logger.w("CanvasActivity", "Could not find project for file $path, sync skipped.")
                    }
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Background sync failed", e)
                }
            }
        }
    }

    private fun saveCanvas() {
        // Use Process scope so save survives activity finish
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            saveCanvasSuspend()
        }
    }

    private suspend fun saveCanvasSuspend() {
        val path = currentCanvasPath ?: return
        val session = currentSession ?: return

        com.alexdremov.notate.data.SaveStatusManager
            .startSaving(path)

        try {
            // Update session metadata from UI
            val updatedMetadata =
                withContext(Dispatchers.Main) {
                    binding.canvasView.getCanvasData().copy(
                        toolbarItems = viewModel.toolbarItems.value,
                    )
                }

            val updatedSession = session.copy(metadata = updatedMetadata)
            currentSession = updatedSession

            Logger.d("CanvasActivity", "Saving canvas session to $path")

            withContext(NonCancellable) {
                saveMutex.withLock {
                    try {
                        val result = canvasRepository.saveCanvasSession(path, updatedSession)

                        // Update session origin to prevent false conflicts on subsequent saves
                        if (currentSession == updatedSession) {
                            currentSession =
                                updatedSession.copy(
                                    originLastModified = result.newLastModified,
                                    originSize = result.newSize,
                                )
                        }
                    } catch (e: Exception) {
                        Logger.e("CanvasActivity", "Failed to save canvas", e, showToUser = true)
                    }
                }
            }
        } finally {
            com.alexdremov.notate.data.SaveStatusManager
                .finishSaving(path)
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
        Logger.d(
            "NotateDebug",
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
                        viewModel.setPenPopupOpen(false)
                        updateDrawingEnabledState()
                    },
                )

            activePenPopup = popup
            viewModel.setPenPopupOpen(true)
            updateDrawingEnabledState()

            com.alexdremov.notate.util.EpdFastModeController
                .enterFastMode()

            Logger.d("NotateDebug", "CanvasActivity: Showing Popup!")
            activePenPopup?.show(binding.root, targetRect)
        } else {
            viewModel.selectTool(toolId)
        }
    }
}
