@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import com.alexdremov.notate.data.LinkType
import com.alexdremov.notate.databinding.ActivityMainBinding
import com.alexdremov.notate.export.CanvasExportCoordinator
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.ui.OnyxCanvasView
import com.alexdremov.notate.ui.SettingsSidebarController
import com.alexdremov.notate.ui.SidebarCoordinator
import com.alexdremov.notate.ui.ToolbarCoordinator
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.toolbar.MainToolbar
import com.alexdremov.notate.ui.view.FloatingWindowView
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.DrawingViewModel
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Rect as ComposeRect

class CanvasActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: DrawingViewModel by viewModels()
    private var activePenPopup: com.alexdremov.notate.ui.dialog.PenSettingsPopup? = null
    private var isGridOpen = false
    private val isToolbarHorizontal = mutableStateOf(true)
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var sidebarCoordinator: SidebarCoordinator
    private lateinit var sidebarController: SettingsSidebarController
    private lateinit var toolbarCoordinator: ToolbarCoordinator
    private lateinit var exportCoordinator: CanvasExportCoordinator

    // Repository retained for SyncManager, but session management is delegated to ViewModel
    private lateinit var canvasRepository: com.alexdremov.notate.data.CanvasRepository
    private lateinit var projectRepository: com.alexdremov.notate.data.ProjectRepository
    private lateinit var syncManager: com.alexdremov.notate.data.SyncManager

    private var autoSaveJob: Job? = null

    private var currentCanvasPath: String? = null
    private var isFixedPageState: androidx.compose.runtime.MutableState<Boolean>? = null

    // Floating Window State
    private var floatingWindow: FloatingWindowView? = null
    private var floatingSession: com.alexdremov.notate.data.CanvasSession? = null

    private var pendingLinkCallback: ((name: String, uuid: String) -> Unit)? = null

    private val notePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val name = data?.getStringExtra("NOTE_NAME")
                val uuid = data?.getStringExtra("NOTE_UUID")

                if (name != null && uuid != null) {
                    pendingLinkCallback?.invoke(name, uuid)
                }
            }
            pendingLinkCallback = null
        }

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
                                    Uri.parse(importedPath)
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
            val path = currentCanvasPath
            if (path != null) {
                // Launch on Activity Scope to capture data safely while Activity is alive
                lifecycleScope.launch {
                    val finalMetadata =
                        try {
                            binding.canvasView.getCanvasData().copy(
                                toolbarItems = viewModel.toolbarItems.value,
                            )
                        } catch (e: Exception) {
                            Logger.e("CanvasActivity", "Failed to capture final metadata", e)
                            null
                        }

                    // Hand off to Process Scope for saving
                    ProcessLifecycleOwner.get().lifecycleScope.launch {
                        viewModel.closeSession(path, finalMetadata)
                    }

                    // Finish after data capture
                    finish()
                }
            } else {
                finish()
            }
        }

        // Initialize State Holder
        isFixedPageState = mutableStateOf(false)
        canvasRepository =
            com.alexdremov.notate.data
                .CanvasRepository(this)
        projectRepository =
            com.alexdremov.notate.data
                .ProjectRepository(this)
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
                onGeneratePatterns = { type, intensity ->
                    lifecycleScope.launch(Dispatchers.Default) {
                        // Calculate visible rect in Model coordinates
                        val matrix = android.graphics.Matrix()
                        binding.canvasView.getViewportMatrix(matrix)

                        val screenWidth = binding.canvasView.width.toFloat()
                        val screenHeight = binding.canvasView.height.toFloat()

                        val invertMatrix = android.graphics.Matrix()
                        matrix.invert(invertMatrix)

                        val visibleRect = android.graphics.RectF(0f, 0f, screenWidth, screenHeight)
                        invertMatrix.mapRect(visibleRect)

                        val strokes =
                            com.alexdremov.notate.util.PatternGenerator.generateStrokes(
                                type,
                                intensity,
                                visibleRect,
                            )
                        binding.canvasView.getController().addStrokes(strokes)
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

        binding.canvasView.onRequestInsertImage = {
            imagePickerLauncher.launch(arrayOf("image/*"))
        }

        binding.canvasView.onBrowseFiles = { callback ->
            pendingLinkCallback = callback

            lifecycleScope.launch {
                val currentPath = currentCanvasPath
                val projectId =
                    if (currentPath != null) {
                        withContext(Dispatchers.IO) {
                            syncManager.findProjectForFile(currentPath)
                        }
                    } else {
                        null
                    }

                val currentUuid =
                    viewModel.currentSession.value
                        ?.metadata
                        ?.uuid

                val intent =
                    Intent(this@CanvasActivity, NotePickerActivity::class.java).apply {
                        if (projectId != null) {
                            putExtra("LOCKED_PROJECT_ID", projectId)
                        }
                        if (currentUuid != null) {
                            putExtra("DISABLED_ITEM_UUID", currentUuid)
                        }
                    }
                notePickerLauncher.launch(intent)
            }
        }

        binding.canvasView.onLinkActivated = { link ->
            handleLinkActivation(link)
        }

        // Setup Progress Dialog
        val progressView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressDialogBuilder =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(progressView)
                .setCancelable(false)
        progressDialog = progressDialogBuilder.create()

        val progressBar = progressView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val progressMessage = progressView.findViewById<android.widget.TextView>(R.id.tv_progress_message)

        binding.canvasView.getController().setProgressCallback { isVisible, message, progress ->
            runOnUiThread {
                if (isVisible) {
                    if (!progressDialog!!.isShowing) {
                        progressDialog!!.show()
                    }
                    message?.let { progressMessage.text = it }
                    progressBar.progress = progress
                } else {
                    if (progressDialog!!.isShowing) {
                        progressDialog!!.dismiss()
                    }
                }
            }
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

                // Session Observation
                launch {
                    viewModel.currentSession.collect { session ->
                        if (session != null) {
                            withContext(Dispatchers.Main) {
                                val tUiStart = System.currentTimeMillis()

                                binding.canvasView.getModel().initializeSession(session.regionManager)
                                binding.canvasView.loadMetadata(session.metadata)

                                val isFixed = session.metadata.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                                isFixedPageState?.value = isFixed
                                viewModel.setFixedPageMode(isFixed)

                                // Toolbar init logic is now in ViewModel's loadCanvasSession

                                Logger.d("CanvasActivity", "UI Load:  ${System.currentTimeMillis() - tUiStart}ms")
                            }
                        }
                    }
                }
            }
        }

        loadCanvas()
        setupAutoSave()
    }

    private fun handleLinkActivation(link: com.alexdremov.notate.model.LinkItem) {
        // Close existing window if any
        floatingWindow?.onClose?.invoke()

        floatingWindow =
            FloatingWindowView(this).apply {
                setTitle(link.label)
                onClose = {
                    // Save state
                    com.alexdremov.notate.data.PreferencesManager.saveFloatingWindowRect(
                        this@CanvasActivity,
                        this.x.toInt(),
                        this.y.toInt(),
                        this.width,
                        this.height,
                    )

                    (this.parent as? ViewGroup)?.removeView(this)
                    floatingWindow = null
                    closeFloatingSession()
                }
            }

        // Restore State or Default
        val savedRect =
            com.alexdremov.notate.data.PreferencesManager
                .getFloatingWindowRect(this)
        val screenW = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val params: FrameLayout.LayoutParams

        if (savedRect != null) {
            var x = savedRect[0]
            var y = savedRect[1]
            var w = savedRect[2]
            var h = savedRect[3]

            // Clamp Size
            w = w.coerceIn(300, screenW)
            h = h.coerceIn(300, screenH)

            // Clamp Position (Ensure header is reachable and window is partially visible)
            // x: allow dragging off-screen but keep 50px visible
            x = x.coerceIn(-w + 100, screenW - 100)
            // y: keep top within screen (0) to screenH - 100
            y = y.coerceIn(0, screenH - 100)

            params = FrameLayout.LayoutParams(w, h)
            params.gravity = Gravity.TOP or Gravity.START
            floatingWindow?.translationX = x.toFloat()
            floatingWindow?.translationY = y.toFloat()
        } else {
            // Default Center
            val defaultW = 1000.coerceAtMost(screenW - 50)
            val defaultH = 800.coerceAtMost(screenH - 50)

            params = FrameLayout.LayoutParams(defaultW, defaultH)
            params.gravity = Gravity.TOP or Gravity.START

            val initialX = (screenW - defaultW) / 2
            val initialY = (screenH - defaultH) / 2
            floatingWindow?.translationX = initialX.toFloat()
            floatingWindow?.translationY = initialY.toFloat()
        }

        floatingWindow?.layoutParams = params

        // Add to root view (CoordinatorLayout/FrameLayout)
        (binding.root as? ViewGroup)?.addView(floatingWindow)

        when (link.type) {
            LinkType.EXTERNAL_URL -> {
                val webView = WebView(this)
                webView.webViewClient = WebViewClient()
                webView.settings.javaScriptEnabled = true
                webView.loadUrl(link.target)
                floatingWindow?.setContentView(webView)
            }

            LinkType.INTERNAL_NOTE -> {
                lifecycleScope.launch {
                    val targetUuid = link.target
                    Logger.d("LinkResolution", "Resolving UUID: $targetUuid")

                    val path =
                        withContext(Dispatchers.IO) {
                            // 1. Try default internal repository
                            var foundPath = projectRepository.getPathForUuid(targetUuid)
                            if (foundPath == null) {
                                Logger.d("LinkResolution", "Not found in default cache, refreshing index...")
                                projectRepository.refreshIndex()
                                foundPath = projectRepository.getPathForUuid(targetUuid)
                            }

                            if (foundPath == null) {
                                val projects =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .getProjects(this@CanvasActivity)
                                Logger.d("LinkResolution", "Searching ${projects.size} external projects")

                                for (project in projects) {
                                    val repo =
                                        com.alexdremov.notate.data
                                            .ProjectRepository(this@CanvasActivity, project.uri)
                                    foundPath = repo.getPathForUuid(targetUuid)

                                    if (foundPath == null) {
                                        Logger.d("LinkResolution", "Refreshing index for project: ${project.name}")
                                        repo.refreshIndex()
                                        foundPath = repo.getPathForUuid(targetUuid)
                                    }

                                    if (foundPath != null) {
                                        Logger.d("LinkResolution", "Found in project: ${project.name}")
                                        break
                                    }
                                }
                            }
                            foundPath
                        }

                    if (path != null) {
                        Logger.d("LinkResolution", "Resolved path: $path")
                        openFloatingCanvas(path)
                    } else {
                        Logger.e("LinkResolution", "Failed to resolve note with UUID: $targetUuid")
                        android.widget.Toast
                            .makeText(this@CanvasActivity, "Note not found", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        floatingWindow?.onClose?.invoke()
                    }
                }
            }
        }
    }

    private suspend fun openFloatingCanvas(path: String) {
        val session =
            withContext(Dispatchers.IO) {
                canvasRepository.openCanvasSession(path)
            }

        if (session != null) {
            floatingSession = session
            val canvasView = OnyxCanvasView(this)

            // Initialize
            canvasView.getModel().initializeSession(session.regionManager)
            canvasView.loadMetadata(session.metadata)
            canvasView.setReadOnly(true)

            floatingWindow?.setContentView(canvasView)
        } else {
            android.widget.Toast
                .makeText(this@CanvasActivity, "Failed to load note", android.widget.Toast.LENGTH_SHORT)
                .show()
            floatingWindow?.onClose?.invoke()
        }
    }

    private fun closeFloatingSession() {
        floatingSession?.let { session ->
            lifecycleScope.launch(Dispatchers.IO) {
                canvasRepository.releaseCanvasSession(session)
            }
            floatingSession = null
        }
    }

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
        lifecycleScope.launch {
            viewModel.loadCanvasSession(path)
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
        closeFloatingSession()
        // Session cleanup is handled by ViewModel or explicit close in onBackPressed
    }

    /**
     * Triggers an async save.  Does not wait for completion.
     */
    private fun saveCanvas(commit: Boolean = false) {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            try {
                saveCanvasSuspend(commit)
            } catch (e: Exception) {
                Logger.e("CanvasActivity", "Auto-save failed", e)
            }
        }
    }

    /**
     * Performs the actual save.  Can be awaited for synchronous save (e.g., on back press).
     */
    private suspend fun saveCanvasSuspend(commit: Boolean = true) {
        val path = currentCanvasPath ?: return

        try {
            // Capture metadata from UI thread
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

            viewModel.saveCanvasSession(path, updatedMetadata, commit)
        } catch (e: Exception) {
            Logger.e("CanvasActivity", "saveCanvasSuspend exception", e)
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

        val item = viewModel.toolbarItems.value.find { it.id == toolId }
        val isSelectionSafeTool =
            when (item) {
                is ToolbarItem.Pen -> item.penTool.type == ToolType.TEXT
                is ToolbarItem.Select -> true
                else -> false
            }

        // If clicking same tool (opening settings) OR switching to TEXT/SELECT, preserve selection.
        if (viewModel.activeToolId.value != toolId && !isSelectionSafeTool) {
            lifecycleScope.launch { binding.canvasView.getController().clearSelection() }
        }
        binding.canvasView.dismissActionPopup()

        if (viewModel.activeToolId.value == toolId) {
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
                    onUpdate = { updatedTool ->
                        viewModel.updateTool(updatedTool)
                        if (updatedTool.type == ToolType.TEXT) {
                            lifecycleScope.launch {
                                binding.canvasView.getController().updateSelectedTextStyle(
                                    fontSize = updatedTool.width,
                                    color = updatedTool.color,
                                )
                            }
                        }
                    },
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
