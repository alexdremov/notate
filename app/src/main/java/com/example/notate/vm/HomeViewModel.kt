package com.example.notate.vm

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.notate.data.CanvasType
import com.example.notate.data.FileSystemItem
import com.example.notate.data.PreferencesManager
import com.example.notate.data.ProjectConfig
import com.example.notate.data.ProjectRepository
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
        updateTitle()
    }

    // --- File Browser Logic ---

    fun loadBrowserItems(path: String?) {
        val repo = repository ?: return
        _currentPath.value = path
        updateTitle()
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
