package com.alexdremov.notate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alexdremov.notate.CanvasActivity
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.data.SortOption
import com.alexdremov.notate.ui.home.*
import com.alexdremov.notate.ui.home.components.DeleteConfirmationDialog
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.HomeViewModel
import com.onyx.android.sdk.api.device.EpdDeviceManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use fast animation mode for the menu UI
        EpdDeviceManager.enterAnimationUpdate(true)

        // Handle incoming intent (e.g. from File Manager)
        handleIntent(intent)

        setContent {
            NotateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val path = if (uri.scheme == "file") uri.path else uri.toString()
            if (path != null) {
                val canvasIntent =
                    Intent(this, CanvasActivity::class.java).apply {
                        putExtra("CANVAS_PATH", path)
                    }
                startActivity(canvasIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        EpdDeviceManager.enterAnimationUpdate(true)
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        EpdDeviceManager.exitAnimationUpdate(true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HomeViewModel) {
    val currentProject by viewModel.currentProject.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val browserItems by viewModel.browserItems.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val title by viewModel.title.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val syncingProjectIds by viewModel.syncingProjectIds.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Listen for global errors
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Logger.userEvents.collect { event ->
                snackbarHostState.showSnackbar(
                    message = event.message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Dialog State
    var showNameDialog by remember { mutableStateOf<DialogType?>(null) }
    var showRemoteStorages by remember { mutableStateOf(false) }
    var showEditStorage by remember { mutableStateOf(false) }
    var editingStorage by remember { mutableStateOf<com.alexdremov.notate.data.RemoteStorageConfig?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Project Actions State
    var projectToDelete by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToManage by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToRename by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToSync by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }

    // Temporary state for Project Creation flow
    var pendingProjectName by remember { mutableStateOf<String?>(null) }

    // Folder Picker for "Add Project"
    val projectLocationPicker =
        rememberLauncherForActivityResult(
            contract =
                object : ActivityResultContracts.OpenDocumentTree() {
                    override fun createIntent(
                        context: android.content.Context,
                        input: android.net.Uri?,
                    ): Intent {
                        val intent = super.createIntent(context, input)
                        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        intent.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        )
                        return intent
                    }
                },
        ) { uri ->
            if (uri != null && pendingProjectName != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {
                    // Ignore if already granted or failed
                }
                viewModel.addProject(pendingProjectName!!, uri.toString())
                pendingProjectName = null
            }
        }

    // Back Handler
    BackHandler {
        viewModel.navigateUp() // Will close project if at root
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- Sidebar (Left) ---
        Sidebar(
            projects = projects,
            tags = tags,
            selectedProject = currentProject,
            selectedTag = selectedTag,
            onProjectSelected = { viewModel.openProject(it) },
            onProjectLongClick = { projectToManage = it },
            onTagSelected = { viewModel.selectTag(it) },
            onAddProject = { showNameDialog = DialogType.ADD_PROJECT },
            onManageTags = { showNameDialog = DialogType.MANAGE_TAGS },
            onSettingsClick = { showSettingsDialog = true },
        )

        // --- Content Area (Right) ---
        Scaffold(
            modifier = Modifier.weight(1f),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        if (currentProject != null || selectedTag != null) {
                            // Sort Action
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.SortByAlpha, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOption.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            showSortMenu = false
                                        },
                                        leadingIcon =
                                            if (option == sortOption) {
                                                { Icon(Icons.Filled.Check, contentDescription = null) }
                                            } else {
                                                null
                                            },
                                    )
                                }
                            }

                            // Browser Actions
                            if (currentProject != null && selectedTag == null) {
                                IconButton(onClick = { showNameDialog = DialogType.CREATE_FOLDER }) {
                                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Folder")
                                }
                                IconButton(onClick = { showNameDialog = DialogType.CREATE_CANVAS }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "New Canvas")
                                }
                            }
                        } else {
                            // Project List Actions
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                            IconButton(onClick = { showNameDialog = DialogType.ADD_PROJECT }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add Project")
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                if (currentProject == null && selectedTag == null) {
                    // --- Empty State ---
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select a project from the sidebar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // --- Level 1+: File Browser ---
                    FileBrowserScreen(
                        items = browserItems,
                        breadcrumbs = breadcrumbs,
                        allTags = tags,
                        onBreadcrumbClick = { viewModel.loadBrowserItems(it.path) },
                        onItemClick = { item ->
                            when (item) {
                                is ProjectItem -> {
                                    viewModel.loadBrowserItems(item.path)
                                }

                                is CanvasItem -> {
                                    val intent =
                                        Intent(context, CanvasActivity::class.java).apply {
                                            putExtra("CANVAS_PATH", item.path)
                                        }
                                    context.startActivity(intent)
                                }
                            }
                        },
                        onItemDelete = { viewModel.deleteItem(it) },
                        onItemRename = { item, newName -> viewModel.renameItem(item, newName) },
                        onItemDuplicate = { item -> viewModel.duplicateItem(item) },
                        onSetFileTags = { item, newTags -> viewModel.setFileTags(item, newTags) },
                    )
                }

                // Sync Progress Overlay
                syncProgress?.let { (progress, message) ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Card(
                            modifier =
                                Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth()
                                    .border(2.dp, Color.Black, RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color.Black,
                                    trackColor = Color.LightGray,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Handle Project Actions Dialogs
    val manageTarget = projectToManage
    if (manageTarget != null) {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { projectToManage = null },
            title = { Text("Actions for \"${manageTarget.name}\"") },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { projectToManage = null }) {
                    Text("Close")
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            projectToSync = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Synchronization", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            projectToRename = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            projectToDelete = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove Project", color = Color.Red)
                    }
                }
            },
        )
    }

    val syncTarget = projectToSync
    if (syncTarget != null) {
        ProjectSyncConfigDialog(
            projectId = syncTarget.id,
            onDismiss = { projectToSync = null },
            onSyncNow = {
                viewModel.syncProject(syncTarget.id)
                projectToSync = null
            },
        )
    }

    val renameTarget = projectToRename
    if (renameTarget != null) {
        TextInputDialog(
            title = "Rename Project",
            initialValue = renameTarget.name,
            confirmText = "Rename",
            onDismiss = { projectToRename = null },
            onConfirm = { newName ->
                viewModel.renameProject(renameTarget, newName)
                projectToRename = null
            },
        )
    }

    val deleteTarget = projectToDelete
    if (deleteTarget != null) {
        DeleteConfirmationDialog(
            itemName = deleteTarget.name,
            onDismiss = { projectToDelete = null },
            onConfirm = {
                viewModel.removeProject(deleteTarget)
                projectToDelete = null
            },
        )
    }

    // Handle Dialogs
    if (showSettingsDialog) {
        SettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onOpenSync = {
                showSettingsDialog = false
                showRemoteStorages = true
            },
        )
    }

    if (showRemoteStorages) {
        RemoteStorageListDialog(
            onDismiss = { showRemoteStorages = false },
            onManageStorage = { storage ->
                editingStorage = storage
                showEditStorage = true
            },
        )
    }

    if (showEditStorage) {
        EditRemoteStorageDialog(
            storage = editingStorage,
            onDismiss = { showEditStorage = false },
            onConfirm = { config, password ->
                val current =
                    com.alexdremov.notate.data.SyncPreferencesManager
                        .getRemoteStorages(context)
                        .toMutableList()
                // Remove existing if editing
                current.removeAll { it.id == config.id }
                current.add(config)
                com.alexdremov.notate.data.SyncPreferencesManager
                    .saveRemoteStorages(context, current)

                if (password.isNotBlank()) {
                    com.alexdremov.notate.data.SyncPreferencesManager
                        .savePassword(context, config.id, password)
                }
                showEditStorage = false
                // Force refresh of the list dialog by toggling visibility (optional, but helps if state isn't observed)
                showRemoteStorages = false
                showRemoteStorages = true
            },
        )
    }

    when (showNameDialog) {
        DialogType.MANAGE_TAGS -> {
            ManageTagsDialog(
                tags = tags,
                onAddTag = { name, color -> viewModel.addTag(name, color) },
                onUpdateTag = { tag -> viewModel.updateTag(tag) },
                onRemoveTag = { id -> viewModel.removeTag(id) },
                onDismiss = { showNameDialog = null },
            )
        }

        DialogType.CREATE_CANVAS -> {
            CreateCanvasDialog(
                onDismiss = { showNameDialog = null },
                onConfirm = { name, type, w, h ->
                    viewModel.createCanvas(name, type, w, h) { path ->
                        val intent =
                            Intent(context, CanvasActivity::class.java).apply {
                                putExtra("CANVAS_PATH", path)
                            }
                        context.startActivity(intent)
                    }
                    showNameDialog = null
                },
            )
        }

        DialogType.ADD_PROJECT, DialogType.CREATE_FOLDER -> {
            TextInputDialog(
                title = if (showNameDialog == DialogType.ADD_PROJECT) "New Project" else "New Folder",
                onDismiss = { showNameDialog = null },
                onConfirm = { name ->
                    if (showNameDialog == DialogType.ADD_PROJECT) {
                        pendingProjectName = name
                        projectLocationPicker.launch(null)
                    } else {
                        viewModel.createFolder(name)
                    }
                    showNameDialog = null
                },
            )
        }

        null -> {}
    }
}
