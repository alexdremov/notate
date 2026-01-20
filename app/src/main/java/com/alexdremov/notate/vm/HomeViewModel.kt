package com.alexdremov.notate.vm

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alexdremov.notate.data.*
import com.alexdremov.notate.model.BreadcrumbItem
import com.alexdremov.notate.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // --- State: Sync Progress ---
    private val _syncProgress = MutableStateFlow<Pair<Int, String>?>(null)
    val syncProgress = _syncProgress.asStateFlow()

    private val _syncingProjectIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingProjectIds = _syncingProjectIds.asStateFlow()

    // File Browser State
    private var repository: ProjectRepository? = null
    private val canvasRepository = CanvasRepository(application)
    private val syncManager = SyncManager(application, canvasRepository)

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _browserItems = MutableStateFlow<List<FileSystemItem>>(emptyList())
    val browserItems: StateFlow<List<FileSystemItem>> = _browserItems.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbItem>>(emptyList())
    val breadcrumbs: StateFlow<List<BreadcrumbItem>> = _breadcrumbs.asStateFlow()

    private val _title = MutableStateFlow("My Projects")
    val title: StateFlow<String> = _title.asStateFlow()

    // --- State: Tags ---
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _selectedTag = MutableStateFlow<Tag?>(null)
    val selectedTag: StateFlow<Tag?> = _selectedTag.asStateFlow()

    // --- State: Sort Option ---
    private val _sortOption = MutableStateFlow(SortOption.DATE_NEWEST)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    init {
        loadProjects()
        loadTags()
        loadSortOption()
    }

    private fun loadSortOption() {
        _sortOption.value = PreferencesManager.getSortOption(getApplication())
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
        PreferencesManager.saveSortOption(getApplication(), option)
        refresh()
    }

    private fun loadProjects() {
        _projects.value = PreferencesManager.getProjects(getApplication())
    }

    private fun loadTags() {
        _tags.value = PreferencesManager.getTags(getApplication())
    }

    private fun updateTitle() {
        val tag = _selectedTag.value
        if (tag != null) {
            _title.value = "Tag: ${tag.name}"
            return
        }

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
        if (_selectedTag.value != null) {
            _breadcrumbs.value = emptyList()
            return
        }

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

    private fun sortItems(items: List<FileSystemItem>): List<FileSystemItem> =
        when (_sortOption.value) {
            SortOption.NAME_ASC -> items.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
            SortOption.DATE_NEWEST -> items.sortedByDescending { it.lastModified }
            SortOption.DATE_OLDEST -> items.sortedBy { it.lastModified }
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
        _selectedTag.value = null // Clear tag selection
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

    // --- Tag Management ---

    fun addTag(
        name: String,
        color: Int,
    ) {
        val newTag =
            Tag(
                id = UUID.randomUUID().toString(),
                name = name,
                color = color,
                order = _tags.value.size,
            )
        val current = _tags.value.toMutableList()
        current.add(newTag)
        PreferencesManager.saveTags(getApplication(), current)
        loadTags()
    }

    fun updateTag(tag: Tag) {
        val current = _tags.value.toMutableList()
        val index = current.indexOfFirst { it.id == tag.id }
        if (index != -1) {
            current[index] = tag
            PreferencesManager.saveTags(getApplication(), current)
            loadTags()
        }
    }

    fun removeTag(tagId: String) {
        val current = _tags.value.toMutableList()
        current.removeAll { it.id == tagId }
        PreferencesManager.saveTags(getApplication(), current)
        loadTags()
    }

    fun setFileTags(
        item: FileSystemItem,
        tagIds: List<String>,
    ) {
        val repo = repository ?: return
        val definitions = _tags.value.filter { it.id in tagIds }
        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.setTags(item.path, tagIds, definitions)
                }
            if (success) {
                if (_selectedTag.value != null) {
                    loadTaggedItems(_selectedTag.value!!.id)
                } else {
                    loadBrowserItems(_currentPath.value)
                }
            }
        }
    }

    fun selectTag(tag: Tag?) {
        _selectedTag.value = tag
        if (tag != null) {
            loadTaggedItems(tag.id)
        } else {
            // Return to regular view
            if (_currentProject.value != null) {
                loadBrowserItems(_currentPath.value)
            } else {
                _browserItems.value = emptyList()
                updateTitle()
            }
        }
    }

    private fun loadTaggedItems(tagId: String) {
        viewModelScope.launch {
            val currentProj = _currentProject.value
            val results =
                withContext(Dispatchers.IO) {
                    if (currentProj != null) {
                        repository?.findFilesWithTag(tagId) ?: emptyList()
                    } else {
                        val allFiles = mutableListOf<CanvasItem>()
                        _projects.value.forEach { proj ->
                            val repo = ProjectRepository(getApplication(), proj.uri)
                            allFiles.addAll(repo.findFilesWithTag(tagId))
                        }
                        allFiles.sortedByDescending { it.lastModified }
                    }
                }
            _browserItems.value = sortItems(results)
            updateTitle()
            updateBreadcrumbs()
        }
    }

    // --- File Browser Logic ---

    fun loadBrowserItems(path: String?) {
        // If tag selected, ignore path navigation (unless we explicitly clear tag)
        if (_selectedTag.value != null) return

        val repo = repository ?: return
        _currentPath.value = path
        updateTitle()
        updateBreadcrumbs()
        viewModelScope.launch {
            val items =
                withContext(Dispatchers.IO) {
                    repo.getItems(path)
                }
            _browserItems.value = sortItems(items)

            // Auto-import tags from files
            // This is fast enough to do on computation/main if list is small, but better on IO or Default if checking strings
            // However, we need to read from items which are already in memory.
            val knownTagIds = _tags.value.map { it.id }.toSet()
            val newTags = mutableListOf<Tag>()

            items.forEach { item ->
                if (item is CanvasItem) {
                    item.embeddedTags.forEach { tag ->
                        if (!knownTagIds.contains(tag.id) && newTags.none { it.id == tag.id }) {
                            newTags.add(tag)
                        }
                    }
                }
            }

            if (newTags.isNotEmpty()) {
                val current = _tags.value.toMutableList()
                current.addAll(newTags)
                PreferencesManager.saveTags(getApplication(), current)
                loadTags()
            }
        }
    }

    fun navigateUp() {
        if (_selectedTag.value != null) {
            selectTag(null) // Exit tag view
            return
        }

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
            val success =
                withContext(Dispatchers.IO) {
                    repo.createProject(name, _currentPath.value)
                }
            if (success) {
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
            val path =
                withContext(Dispatchers.IO) {
                    repo.createCanvas(name, _currentPath.value, type, pageWidth, pageHeight)
                }
            if (path != null) {
                loadBrowserItems(_currentPath.value)
                onSuccess(path)
            }
        }
    }

    fun deleteItem(item: FileSystemItem) {
        val repo = repository ?: return
        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.deleteItem(item.path)
                }
            if (success) {
                if (_selectedTag.value != null) {
                    loadTaggedItems(_selectedTag.value!!.id)
                } else {
                    loadBrowserItems(_currentPath.value)
                }
            }
        }
    }

    fun renameItem(
        item: FileSystemItem,
        newName: String,
    ) {
        val repo = repository ?: return
        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.renameItem(item.path, newName)
                }
            if (success) {
                if (_selectedTag.value != null) {
                    loadTaggedItems(_selectedTag.value!!.id)
                } else {
                    loadBrowserItems(_currentPath.value)
                }
            }
        }
    }

    fun duplicateItem(item: FileSystemItem) {
        val repo = repository ?: return
        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.duplicateItem(item.path, _currentPath.value)
                }
            if (success) {
                if (_selectedTag.value != null) {
                    loadTaggedItems(_selectedTag.value!!.id)
                } else {
                    loadBrowserItems(_currentPath.value)
                }
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
        if (_selectedTag.value != null) {
            loadTaggedItems(_selectedTag.value!!.id)
        } else if (_currentProject.value == null) {
            loadProjects()
        } else {
            loadBrowserItems(_currentPath.value)
        }
    }

    fun syncProject(projectId: String) {
        if (_syncingProjectIds.value.contains(projectId)) return

        _syncingProjectIds.value = _syncingProjectIds.value + projectId

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    syncManager.syncProject(projectId) { progress, message ->
                        _syncProgress.value = progress to message
                    }
                }
            } finally {
                _syncingProjectIds.value = _syncingProjectIds.value - projectId
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (_syncingProjectIds.value.isEmpty()) {
                        _syncProgress.value = null
                    }
                }
                refresh()
            }
        }
    }

    fun isAtTopLevel(): Boolean = _currentProject.value == null
}
