package com.alexdremov.notate.vm

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.FileSystemItem
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.data.ProjectConfig
import com.alexdremov.notate.data.ProjectRepository
import com.alexdremov.notate.model.BreadcrumbItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // --- State: Project List (Level 0) ---
    private val _projects = MutableStateFlow<List<ProjectConfig>>(emptyList())
    val projects: StateFlow<List<ProjectConfig>> = _projects.asStateFlow()

    // --- State: Active Project (Level 1+) ---
    private val _currentProject = MutableStateFlow<ProjectConfig?>(null)
    val currentProject: StateFlow<ProjectConfig?> = _currentProject.asStateFlow()

    // File Browser State
    private var repository: ProjectRepository? = null

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _browserItems = MutableStateFlow<List<FileSystemItem>>(emptyList())
    val browserItems: StateFlow<List<FileSystemItem>> = _browserItems.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbItem>>(emptyList())
    val breadcrumbs: StateFlow<List<BreadcrumbItem>> = _breadcrumbs.asStateFlow()

    private val _title = MutableStateFlow("My Projects")
    val title: StateFlow<String> = _title.asStateFlow()

    init {
        loadProjects()
    }

    private fun loadProjects() {
        _projects.value = PreferencesManager.getProjects(getApplication())
    }

    private fun updateTitle() {
        val project = _currentProject.value
        if (project == null) {
            _title.value = "My Projects"
            return
        }

        val current = _currentPath.value
        val root = repository?.getRootPath()

        if (current == null || current == root) {
            _title.value = "Project: ${project.name}"
        } else {
            val folderName =
                if (current.startsWith("content://")) {
                    val uri = Uri.parse(current)
                    DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: "Folder"
                } else {
                    File(current).name
                }
            _title.value = folderName
        }
    }

    private fun updateBreadcrumbs() {
        val project = _currentProject.value
        val repo = repository
        if (project == null || repo == null) {
            _breadcrumbs.value = emptyList()
            return
        }

        val rootPath = repo.getRootPath()
        val currentPath = _currentPath.value
        val items = mutableListOf<BreadcrumbItem>()

        // Root
        items.add(BreadcrumbItem(project.name, rootPath))

        if (currentPath != null && currentPath != rootPath) {
            if (currentPath.startsWith("content://")) {
                val uri = Uri.parse(currentPath)
                val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: "Folder"
                items.add(BreadcrumbItem(name, currentPath))
            } else {
                if (currentPath.startsWith(rootPath)) {
                    val relative = currentPath.removePrefix(rootPath).trimStart('/')
                    if (relative.isNotEmpty()) {
                        val segments = relative.split('/')
                        var builtPath = rootPath
                        segments.forEach { segment ->
                            builtPath =
                                if (builtPath.endsWith(File.separator)) {
                                    builtPath + segment
                                } else {
                                    builtPath + File.separator + segment
                                }
                            items.add(BreadcrumbItem(segment, builtPath))
                        }
                    }
                } else {
                    items.add(BreadcrumbItem(File(currentPath).name, currentPath))
                }
            }
        }
        _breadcrumbs.value = items
    }

    // --- Project Management ---

    fun addProject(
        name: String,
        uri: String,
    ) {
        val config =
            ProjectConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                uri = uri,
            )
        PreferencesManager.addProject(getApplication(), config)
        loadProjects()
    }

    fun removeProject(project: ProjectConfig) {
        PreferencesManager.removeProject(getApplication(), project.id)
        loadProjects()
    }

    fun openProject(project: ProjectConfig) {
        _currentProject.value = project
        repository = ProjectRepository(getApplication(), project.uri)
        loadBrowserItems(null)
    }

    fun closeProject() {
        _currentProject.value = null
        repository = null
        _browserItems.value = emptyList()
        _currentPath.value = null
        _breadcrumbs.value = emptyList()
        updateTitle()
    }

    // --- File Browser Logic ---

    fun loadBrowserItems(path: String?) {
        val repo = repository ?: return
        _currentPath.value = path
        updateTitle()
        updateBreadcrumbs()
        viewModelScope.launch {
            _browserItems.value = repo.getItems(path)
        }
    }

    fun navigateUp() {
        val repo = repository ?: return
        val current = _currentPath.value

        // If we are at the root of the repo (Project Root), close the project
        if (current == null || current == repo.getRootPath()) {
            closeProject()
            return
        }

        val parentPath =
            if (current.startsWith("content://")) {
                null
            } else {
                val parent = File(current).parentFile
                if (parent != null && parent.absolutePath.startsWith(repo.getRootPath())) {
                    parent.absolutePath
                } else {
                    null
                }
            }
        loadBrowserItems(parentPath)
    }

    fun createFolder(name: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.createProject(name, _currentPath.value)) {
                loadBrowserItems(_currentPath.value)
            }
        }
    }

    fun createCanvas(
        name: String,
        type: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
        onSuccess: (String) -> Unit,
    ) {
        val repo = repository ?: return
        viewModelScope.launch {
            val path = repo.createCanvas(name, _currentPath.value, type, pageWidth, pageHeight)
            if (path != null) {
                loadBrowserItems(_currentPath.value)
                onSuccess(path)
            }
        }
    }

    fun deleteItem(item: FileSystemItem) {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.deleteItem(item.path)) {
                loadBrowserItems(_currentPath.value)
            }
        }
    }

    fun renameItem(
        item: FileSystemItem,
        newName: String,
    ) {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.renameItem(item.path, newName)) {
                loadBrowserItems(_currentPath.value)
            }
        }
    }

    fun duplicateItem(item: FileSystemItem) {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.duplicateItem(item.path, _currentPath.value)) {
                loadBrowserItems(_currentPath.value)
            }
        }
    }

    fun renameProject(
        project: ProjectConfig,
        newName: String,
    ) {
        val updated = project.copy(name = newName)
        PreferencesManager.updateProject(getApplication(), updated)
        loadProjects()
    }

    fun refresh() {
        if (_currentProject.value == null) {
            loadProjects()
        } else {
            loadBrowserItems(_currentPath.value)
        }
    }

    fun isAtTopLevel(): Boolean = _currentProject.value == null
}
