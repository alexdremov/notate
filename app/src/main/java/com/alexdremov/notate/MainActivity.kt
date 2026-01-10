package com.alexdremov.notate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.alexdremov.notate.CanvasActivity
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.ui.home.CreateCanvasDialog
import com.alexdremov.notate.ui.home.DialogType
import com.alexdremov.notate.ui.home.FileBrowserScreen
import com.alexdremov.notate.ui.home.ProjectListScreen
import com.alexdremov.notate.ui.home.TextInputDialog
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.vm.HomeViewModel
import com.onyx.android.sdk.api.device.EpdDeviceManager
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use fast animation mode for the menu UI
        EpdDeviceManager.enterAnimationUpdate(true)

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
    val title by viewModel.title.collectAsState()

    val context = LocalContext.current

    // Dialog State
    var showNameDialog by remember { mutableStateOf<DialogType?>(null) }
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
                )
            } else {
                // --- Level 1+: File Browser ---
                FileBrowserScreen(
                    items = browserItems,
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
        }

        // Handle Dialogs
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
