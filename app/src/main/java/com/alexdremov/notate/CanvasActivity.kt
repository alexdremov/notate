@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alexdremov.notate.databinding.ActivityMainBinding
import com.alexdremov.notate.export.CanvasExportCoordinator
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.ui.SettingsSidebarController
import com.alexdremov.notate.ui.SidebarCoordinator
import com.alexdremov.notate.ui.ToolbarCoordinator
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.toolbar.MainToolbar
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.DrawingViewModel
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
import java.util.concurrent.atomic.AtomicReference
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

    // Thread-safe session management
    // The mutex protects session transitions and save operations
    private val sessionMutex = Mutex()
    private val currentSessionRef = AtomicReference<com.alexdremov.notate.data.CanvasSession?>(null)

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
                lifecycleScope.launch {
                    val (finalUriStr, w, h) =
                        withContext(Dispatchers.IO) {
                            val importedPath =
                                com.alexdremov.notate.util.ImageImportHelper
                                    .importImage(this@CanvasActivity, uri)

                            val uriToUse =
                                if (importedPath != null) {
                                    android.net.Uri.parse(importedPath)
                                } else {
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

                    val matrix = android.graphics.Matrix()
                    binding.canvasView.getViewportMatrix(matrix)
                    val screenCenterX = binding.canvasView.width / 2f
                    val screenCenterY = binding.canvasView.height / 2f

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

        // Prevent sync while canvas is opening
        com.alexdremov.notate.data.SyncManager
            .cancelAllSyncs()
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = true

        // Intercept Back Press - Save in background and close immediately
        onBackPressedDispatcher.addCallback(this) {
            val session = currentSessionRef.getAndSet(null)
            val path = currentCanvasPath
            if (session != null && path != null && !session.isClosed()) {
                // Update metadata from UI one last time to capture final viewport for thumbnail
                try {
                    val finalMetadata =
                        binding.canvasView.getCanvasData().copy(
                            toolbarItems = viewModel.toolbarItems.value,
                        )
                    session.updateMetadata(finalMetadata)
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Failed to capture final metadata on exit", e)
                }

                // Launch background save and close on Process Scope to survive Activity destruction
                ProcessLifecycleOwner.get().lifecycleScope.launch {
                    canvasRepository.saveAndCloseSession(path, session)
                }
            }
            // Close UI immediately
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

        binding.toolbarContainer.onUp = { finishToolbarInteraction() }

        binding.toolbarContainer.onLongPress = {
            finishToolbarInteraction()
            if (!viewModel.isToolbarCollapsed.value) {
                viewModel.setEditMode(true)
            }
        }

        toolbarCoordinator.setup()

        toolbarCoordinator.onRequestCollapse = {
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
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    val horizontal by remember { isToolbarHorizontal }
                    MainToolbar(
                        viewModel = viewModel,
                        isHorizontal = horizontal,
                        canvasController = binding.canvasView.getController(),
                        canvasModel = binding.canvasView.getModel(),
                        onToolClick = { item: ToolbarItem, rect: ComposeRect ->
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
                                    lifecycleScope.launch { binding.canvasView.undo() }
                                }

                                com.alexdremov.notate.model.ActionType.REDO -> {
                                    lifecycleScope.launch { binding.canvasView.redo() }
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
                        onToolbarExpandStart = { toolbarCoordinator.savePosition() },
                        onToolbarExpanded = {
                            toolbarCoordinator.ensureOnScreen()
                            binding.canvasView.refreshScreen()
                        },
                        onToolbarCollapsed = { toolbarCoordinator.restorePosition() },
                    )
                }
            }
        binding.toolbarContainer.addView(composeToolbar)

        binding.toolbarContainer.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                lifecycleScope.launch { binding.canvasView.getController().clearSelection() }
                binding.canvasView.dismissActionPopup()
            }
            false
        }

        sidebarController =
            SettingsSidebarController(
                this,
                binding.settingsSidebarContainer,
                viewModel,
                getCurrentStyle = { binding.canvasView.getBackgroundStyle() },
                isFixedPageMode = {
                    binding.canvasView.getModel().canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                },
                onStyleUpdate = { newStyle ->
                    lifecycleScope.launch { binding.canvasView.setBackgroundStyle(newStyle) }
                },
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
                    viewModel.activeTool.collect { tool ->
                        binding.canvasView.setTool(tool)
                        if (tool.type == ToolType.ERASER) {
                            binding.canvasView.setEraser(tool)
                        }
                    }
                }
                launch {
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
                        Logger.d("NotateDebug", "CanvasActivity:  isEditMode=$isEdit")
                        binding.toolbarContainer.isDragEnabled = !isEdit
                    }
                }
            }
        }

        loadCanvas()
        setupAutoSave()
    }

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
                    saveCanvas(commit = false)
                }
            }
    }

    private fun loadCanvas() {
        val path = currentCanvasPath ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            // Close previous session safely via Repository
            val oldSession = currentSessionRef.getAndSet(null)
            if (oldSession != null) {
                canvasRepository.releaseCanvasSession(oldSession)
            }

            val session = canvasRepository.openCanvasSession(path)

            if (session != null) {
                currentSessionRef.set(session)
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

                    Logger.d("CanvasActivity", "UI Load:  ${System.currentTimeMillis() - tUiStart}ms")
                }
            } else {
                Logger.e("CanvasActivity", "Failed to load session for $path", showToUser = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure no sync runs while canvas is active
        com.alexdremov.notate.data.SyncManager
            .cancelAllSyncs()
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = true
    }

    override fun onPause() {
        super.onPause()
        // Allow sync to proceed in background
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = false

        val path = currentCanvasPath
        if (path != null) {
            // Sequential Save -> Sync to prevent race condition
            ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 1. Full Save (Blocking in this coroutine)
                    saveCanvasSuspend(commit = true)

                    // 2. Trigger Sync (only after save completes)
                    val projectId = syncManager.findProjectForFile(path)
                    if (projectId != null) {
                        Logger.d("CanvasActivity", "Triggering background sync for project $projectId")
                        syncManager.syncProject(projectId)
                    }
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Background save/sync failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSaveJob?.cancel()

        // Schedule session cleanup on process scope (after any pending saves)
        val session = currentSessionRef.getAndSet(null)
        if (session != null) {
            ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                // Release via Repository
                canvasRepository.releaseCanvasSession(session)
            }
        }
    }

    /**
     * Triggers an async save.  Does not wait for completion.
     */
    private fun saveCanvas(commit: Boolean = false) {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            try {
                saveCanvasSuspend(commit)
            } catch (e: Exception) {
                // Already logged in saveCanvasSuspend
            }
        }
    }

    /**
     * Performs the actual save.  Can be awaited for synchronous save (e.g., on back press).
     */
    private suspend fun saveCanvasSuspend(commit: Boolean = true) {
        val path = currentCanvasPath ?: return

        // Fast path: If session is already cleared (e.g. via Back Press), skip save.
        // This prevents redundant operations and potential crashes when accessing UI from background.
        if (currentSessionRef.get() == null) return

        com.alexdremov.notate.data.SaveStatusManager
            .startSaving(path)

        try {
            // Capture metadata from UI thread BEFORE acquiring mutex
            // This is safe because we're just reading view state
            val updatedMetadata =
                withContext(Dispatchers.Main) {
                    try {
                        binding.canvasView.getCanvasData().copy(
                            toolbarItems = viewModel.toolbarItems.value,
                        )
                    } catch (e: Exception) {
                        Logger.e("CanvasActivity", "Failed to get canvas data", e)
                        null
                    }
                }

            if (updatedMetadata == null) {
                Logger.w("CanvasActivity", "Skipping save: failed to capture metadata")
                return
            }

            withContext(NonCancellable) {
                // We keep sessionMutex locally to prevent race between load/save within Activity
                sessionMutex.withLock {
                    // Get session INSIDE the lock to prevent races with loadCanvas
                    val session = currentSessionRef.get()

                    // Guard:  No session yet (load still in progress or failed)
                    if (session == null) {
                        Logger.w("CanvasActivity", "Skipping save: no active session")
                        return@withLock
                    }

                    // Guard: Session already closed
                    if (session.isClosed()) {
                        Logger.w("CanvasActivity", "Skipping save: session is closed")
                        return@withLock
                    }

                    // Update metadata in the session (in-place, no copy)
                    session.updateMetadata(updatedMetadata)

                    Logger.d("CanvasActivity", "Saving canvas session to $path (Commit=$commit)")

                    try {
                        val result = canvasRepository.saveCanvasSession(path, session, commitToZip = commit)

                        // Update origin timestamps for conflict detection (in-place)
                        session.updateOrigin(result.newLastModified, result.newSize)

                        if (commit) {
                            Logger.i("CanvasActivity", "Full Save complete: ${result.savedPath}")
                        } else {
                            // Verbose only for flush
                            // Logger.v("CanvasActivity", "Session flushed.")
                        }
                    } catch (e: Exception) {
                        Logger.e("CanvasActivity", "Failed to save canvas", e, showToUser = true)
                        // Don't rethrow - allow app to continue
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
        Logger.d("NotateDebug", "handleToolClick ID=$toolId")
        lifecycleScope.launch { binding.canvasView.getController().clearSelection() }
        binding.canvasView.dismissActionPopup()

        if (viewModel.activeToolId.value == toolId) {
            val item = viewModel.toolbarItems.value.find { it.id == toolId }
            val tool =
                when (item) {
                    is ToolbarItem.Pen -> item.penTool
                    is ToolbarItem.Eraser -> viewModel.activeTool.value
                    is ToolbarItem.Select -> viewModel.activeTool.value
                    else -> null
                } ?: return

            val popup =
                com.alexdremov.notate.ui.dialog.PenSettingsPopup(
                    this,
                    tool,
                    onUpdate = { updatedTool -> viewModel.updateTool(updatedTool) },
                    onRemove = { toolToRemove -> viewModel.removePen(toolToRemove.id) },
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
            activePenPopup?.show(binding.root, targetRect)
        } else {
            viewModel.selectTool(toolId)
        }
    }
}
