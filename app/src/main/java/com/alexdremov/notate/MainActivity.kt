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
import com.alexdremov.notate.CanvasActivity
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.ui.home.*
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.vm.HomeViewModel
import com.onyx.android.sdk.api.device.EpdDeviceManager

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

    val context = LocalContext.current

    // Dialog State
    var showNameDialog by remember { mutableStateOf<DialogType?>(null) }
    var showRemoteStorages by remember { mutableStateOf(false) }
    var showEditStorage by remember { mutableStateOf(false) }
    var editingStorage by remember { mutableStateOf<com.alexdremov.notate.data.RemoteStorageConfig?>(null) }

    // Temporary state for Project Creation flow
    var pendingProjectName by remember { mutableStateOf<String?>(null) }

    // Folder Picker for "Add Project"
    val projectLocationPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
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

    Scaffold(
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
                    if (currentProject == null) {
                        // Project List Actions
                        IconButton(onClick = { showRemoteStorages = true }) {
                            Icon(Icons.Filled.CloudSync, contentDescription = "Sync Settings")
                        }
                        IconButton(onClick = { showNameDialog = DialogType.ADD_PROJECT }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add Project")
                        }
                    } else {
                        // Browser Actions
                        IconButton(onClick = { showNameDialog = DialogType.CREATE_FOLDER }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Folder")
                        }
                        IconButton(onClick = { showNameDialog = DialogType.CREATE_CANVAS }) {
                            Icon(Icons.Filled.Edit, contentDescription = "New Canvas")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (currentProject == null) {
                // --- Level 0: Project List ---
                ProjectListScreen(
                    projects = projects,
                    onOpenProject = { viewModel.openProject(it) },
                    onDeleteProject = { viewModel.removeProject(it) },
                    onRenameProject = { p, n -> viewModel.renameProject(p, n) },
                    onSyncProject = { viewModel.syncProject(it.id) },
                    syncingProjectIds = syncingProjectIds,
                )
            } else {
                // --- Level 1+: File Browser ---
                FileBrowserScreen(
                    items = browserItems,
                    breadcrumbs = breadcrumbs,
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

        // Handle Dialogs
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
}
