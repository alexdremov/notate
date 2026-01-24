package com.alexdremov.notate.vm

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alexdremov.notate.data.*
import com.alexdremov.notate.model.BreadcrumbItem
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val loadedRepositories = ConcurrentHashMap<String, ProjectRepository>()
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

    // --- State: Saving Background Processes ---
    private val _savingPaths = MutableStateFlow<Set<String>>(emptySet())
    val savingPaths: StateFlow<Set<String>> = _savingPaths.asStateFlow()

    init {
        loadProjects()
        loadTags()
        loadSortOption()
        startIndexing()
        observeSaveStatus()
        observeGlobalSync()
    }

    private fun observeSaveStatus() {
        viewModelScope.launch {
            SaveStatusManager.savingFiles.collect { files ->
                val previous = _savingPaths.value
                _savingPaths.value = files

                // If a file was in 'previous' but not in 'files', it finished saving
                val finished = previous - files
                if (finished.isNotEmpty()) {
                    Logger.d("HomeViewModel", "Detected background save completion for $finished. Refreshing UI.")
                    refresh()
                }
            }
        }
    }

    private fun observeGlobalSync() {
        viewModelScope.launch {
            SyncManager.globalSyncProgress.collect { progressMap ->
                val currentIds = _syncingProjectIds.value
                val newIds = progressMap.keys

                // Detect completions (in current but not in new)
                val completed = currentIds - newIds
                if (completed.isNotEmpty()) {
                    refresh()
                }

                if (progressMap.isEmpty()) {
                    _syncProgress.value = null
                    _syncingProjectIds.value = emptySet()
                } else {
                    // Show the first active sync
                    val (id, status) = progressMap.entries.first()
                    _syncProgress.value = status
                    _syncingProjectIds.value = newIds
                }
            }
        }
    }

    private fun resumeInterruptedSyncs() {
        val interrupted = SyncManager.getInterruptedProjects()
        if (interrupted.isNotEmpty()) {
            Logger.d("HomeViewModel", "Resuming interrupted syncs for: $interrupted")
            interrupted.forEach { projectId ->
                // Don't call syncProject directly to avoid double-checking existing IDs or UI logic
                // Just launch the sync via manager, let global observer handle UI
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            syncManager.syncProject(projectId)
                        }
                    } catch (e: Exception) {
                        Logger.e("HomeViewModel", "Failed to resume sync for $projectId", e)
                    }
                }
            }
        }
    }

    private fun getRepository(project: ProjectConfig): ProjectRepository =
        loadedRepositories.getOrPut(project.id) {
            ProjectRepository(getApplication(), project.uri)
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

    private fun startIndexing() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value.forEach { project ->
                try {
                    val repo = getRepository(project)
                    repo.refreshIndex()
                } catch (e: Exception) {
                    Logger.e("Indexing", "Failed to refresh index for project ${project.name}", e)
                }
            }
            importTagsFromAllProjects()

            withContext(Dispatchers.Main) {
                val tag = _selectedTag.value
                if (tag != null) {
                    loadTaggedItems(tag.id)
                }
            }
        }
    }

    private suspend fun importTagsFromAllProjects() {
        val allTags = mutableListOf<Tag>()
        val knownTagIds = _tags.value.map { it.id }.toMutableSet()

        _projects.value.forEach { project ->
            try {
                val repo = getRepository(project)
                val projectTags = repo.getAllIndexedTags()
                projectTags.forEach { tag ->
                    if (!knownTagIds.contains(tag.id)) {
                        allTags.add(tag)
                        knownTagIds.add(tag.id)
                    }
                }
            } catch (e: Exception) {
                Logger.w("Indexing", "Failed to import tags from project ${project.name}", e)
            }
        }

        if (allTags.isNotEmpty()) {
            val current = _tags.value.toMutableList()
            var nextOrder = (current.maxOfOrNull { it.order } ?: -1) + 1
            val orderedNewTags =
                allTags.map { tag ->
                    val updatedTag = tag.copy(order = nextOrder)
                    nextOrder++
                    updatedTag
                }
            current.addAll(orderedNewTags)
            PreferencesManager.saveTags(getApplication(), current)
            loadTags()
        }
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
        startIndexing()
    }

    fun removeProject(project: ProjectConfig) {
        PreferencesManager.removeProject(getApplication(), project.id)
        loadedRepositories.remove(project.id)
        loadProjects()
    }

    fun openProject(project: ProjectConfig) {
        _selectedTag.value = null // Clear tag selection
        _currentProject.value = project
        val repo = getRepository(project)
        repository = repo

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.refreshIndex()
            }
            loadBrowserItems(null)
        }
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

        // Optimistic Update
        if (item is CanvasItem) {
            val updatedItem = item.copy(tagIds = tagIds, embeddedTags = definitions)
            val currentItems =
                _browserItems.value.map {
                    if (it.path == item.path) updatedItem else it
                }
            _browserItems.value = sortItems(currentItems)
        }

        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.setTags(item.path, tagIds, definitions)
                }
            if (success) {
                val selectedTag = _selectedTag.value
                if (selectedTag != null) {
                    loadTaggedItems(selectedTag.id)
                } else {
                    loadBrowserItems(_currentPath.value)
                }
            } else {
                loadBrowserItems(_currentPath.value)
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
            val results =
                withContext(Dispatchers.IO) {
                    val allFiles = mutableListOf<CanvasItem>()

                    _projects.value.forEach { proj ->
                        val repo = getRepository(proj)
                        try {
                            allFiles.addAll(repo.findFilesWithTag(tagId))
                        } catch (e: Exception) {
                            Logger.w("Tags", "Failed to search project ${proj.name} for tag $tagId", e)
                        }
                    }
                    allFiles.sortedByDescending { it.lastModified }
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

            // Auto-import tags from index (Project-wide)
            withContext(Dispatchers.IO) {
                val indexedTags = repo.getAllIndexedTags()
                val knownTagIds = _tags.value.map { it.id }.toSet()
                val newTags = indexedTags.filter { !knownTagIds.contains(it.id) }

                if (newTags.isNotEmpty()) {
                    val current = _tags.value.toMutableList()
                    val startingOrder = (current.maxOfOrNull { it.order } ?: -1) + 1
                    var nextOrder = startingOrder
                    val orderedNewTags =
                        newTags.map { tag ->
                            val updatedTag = tag.copy(order = nextOrder)
                            nextOrder++
                            updatedTag
                        }
                    current.addAll(orderedNewTags)
                    PreferencesManager.saveTags(getApplication(), current)
                    loadTags()
                }
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
        val currentProject = _currentProject.value
        val currentProjectId = currentProject?.id
        val projectUri = currentProject?.uri
        val currentPathVal = _currentPath.value

        // Optimistic UI update
        _browserItems.value = _browserItems.value.filter { it.path != item.path }

        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    repo.deleteItem(item.path)
                }
            if (success) {
                // Refresh local UI immediately from the repository to confirm local deletion
                val tag = _selectedTag.value
                if (tag != null) {
                    loadTaggedItems(tag.id)
                } else {
                    loadBrowserItems(currentPathVal)
                }

                // Try to delete from remote in background
                if (currentProjectId != null && projectUri != null) {
                    launch(Dispatchers.IO) {
                        try {
                            Logger.d("HomeViewModel", "Attempting to propagate deletion to remote for ${item.name}")
                            val relativePath =
                                if (projectUri.startsWith("content://")) {
                                    if (currentPathVal == repository?.getRootPath()) {
                                        item.name
                                    } else {
                                        item.name
                                    }
                                } else {
                                    File(item.path).relativeTo(File(projectUri)).path
                                }

                            Logger.d("HomeViewModel", "Calculated relative path for deletion: $relativePath")

                            // 1. Record pending deletion
                            SyncPreferencesManager.addPendingDeletion(getApplication(), currentProjectId, relativePath)

                            // 2. Try immediate deletion
                            val result = syncManager.deleteFromRemote(currentProjectId, relativePath)
                            if (result) {
                                Logger.d("HomeViewModel", "Immediate remote deletion successful")
                            } else {
                                Logger.d("HomeViewModel", "Immediate remote deletion skipped or failed (will be retried on sync)")
                            }
                        } catch (e: Exception) {
                            Logger.w("HomeViewModel", "Failed to delete remote file: ${item.path}", e)
                        }
                    }
                }
            } else {
                // If local deletion failed, refresh to restore item in list
                refresh()
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
                val tag = _selectedTag.value
                if (tag != null) {
                    loadTaggedItems(tag.id)
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
                val tag = _selectedTag.value
                if (tag != null) {
                    loadTaggedItems(tag.id)
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
        val repo = repository
        val tag = _selectedTag.value

        viewModelScope.launch {
            if (repo != null) {
                withContext(Dispatchers.IO) {
                    repo.refreshIndex()
                }
            }

            if (tag != null) {
                loadTaggedItems(tag.id)
            } else if (_currentProject.value == null) {
                loadProjects()
            } else {
                loadBrowserItems(_currentPath.value)
            }
        }
    }

    fun syncProject(projectId: String) {
        if (_syncingProjectIds.value.contains(projectId)) return

        _syncingProjectIds.value = _syncingProjectIds.value + projectId

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    syncManager.syncProject(projectId) { progress, message ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _syncProgress.value = progress to message
                        }
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
