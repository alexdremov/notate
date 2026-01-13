package com.alexdremov.notate.vm

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.ActionType
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DrawingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // New Toolbar State
    private val _toolbarItems = MutableStateFlow<List<ToolbarItem>>(emptyList())
    val toolbarItems: StateFlow<List<ToolbarItem>> = _toolbarItems.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    // Legacy / Active Tool State
    private val _activeToolId = MutableStateFlow("")
    val activeToolId: StateFlow<String> = _activeToolId.asStateFlow()

    private val _activeTool = MutableStateFlow(PenTool.defaultPens()[0])
    val activeTool: StateFlow<PenTool> = _activeTool.asStateFlow()

    // Canvas State
    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val _isDrawingEnabled = MutableStateFlow(true)
    val isDrawingEnabled: StateFlow<Boolean> = _isDrawingEnabled.asStateFlow()

    private val _isFixedPageMode = MutableStateFlow(false)
    val isFixedPageMode: StateFlow<Boolean> = _isFixedPageMode.asStateFlow()

    init {
        loadTools()
    }

    private fun loadTools() {
        val items = PreferencesManager.getToolbarItems(getApplication())
        _toolbarItems.value = items

        // Set initial active tool if not set
        if (_activeToolId.value.isEmpty()) {
            val firstTool =
                items.firstOrNull { it is ToolbarItem.Pen }
                    ?: items.firstOrNull { it is ToolbarItem.Eraser }
                    ?: items.firstOrNull { it is ToolbarItem.Select }

            firstTool?.let { selectTool(it.id) }
        }
    }

    fun setDrawingEnabled(enabled: Boolean) {
        _isDrawingEnabled.value = enabled
    }

    fun setFixedPageMode(isFixed: Boolean) {
        _isFixedPageMode.value = isFixed
    }

    fun setEditMode(enabled: Boolean) {
        _isEditMode.value = enabled
        if (enabled) {
            // Disable drawing while editing toolbar
            setDrawingEnabled(false)
        } else {
            setDrawingEnabled(true)
        }
    }

    fun selectTool(id: String) {
        val item = _toolbarItems.value.find { it.id == id } ?: return

        // Handle Actions immediately
        if (item is ToolbarItem.Action) {
            // Actions are handled by UI click listeners invoking specific methods (undo/redo)
            // But if we need to track "active" state for buttons, we might need more logic.
            // For now, Actions don't become "activeTool".
            return
        }

        if (item is ToolbarItem.Widget) return

        _activeToolId.value = id

        // Convert ToolbarItem to PenTool for the CanvasView
        val penTool =
            when (item) {
                is ToolbarItem.Pen -> item.penTool
                is ToolbarItem.Eraser -> item.penTool
                is ToolbarItem.Select -> item.penTool
                else -> null
            }

        penTool?.let { _activeTool.value = it }
    }

    fun updateTool(updatedTool: PenTool) {
        val currentList = _toolbarItems.value.toMutableList()
        val index =
            currentList.indexOfFirst {
                when (it) {
                    is ToolbarItem.Pen -> it.penTool.id == updatedTool.id
                    is ToolbarItem.Eraser -> it.penTool.id == updatedTool.id
                    is ToolbarItem.Select -> it.penTool.id == updatedTool.id
                    else -> false
                }
            }

        if (index != -1) {
            val oldItem = currentList[index]
            currentList[index] =
                when (oldItem) {
                    is ToolbarItem.Pen -> ToolbarItem.Pen(updatedTool)
                    is ToolbarItem.Eraser -> ToolbarItem.Eraser(updatedTool)
                    is ToolbarItem.Select -> ToolbarItem.Select(updatedTool)
                    else -> oldItem // Should not happen
                }

            _toolbarItems.value = currentList
            saveToolbar()

            if (_activeToolId.value == updatedTool.id) {
                _activeTool.value = updatedTool
            }
        }
    }

    fun addPen() {
        val newId = "pen_${System.currentTimeMillis()}"
        val newPen =
            PenTool(
                id = newId,
                name = "New Pen",
                type = ToolType.PEN,
                color = Color.BLACK,
                width = 3f,
                strokeType = com.alexdremov.notate.model.StrokeType.FOUNTAIN,
            )
        addToolbarItem(ToolbarItem.Pen(newPen))
        selectTool(newId)
    }

    fun addToolbarItem(item: ToolbarItem) {
        val currentList = _toolbarItems.value.toMutableList()

        // Prevent duplicates for non-PEN items
        if (item !is ToolbarItem.Pen) {
            val exists =
                currentList.any {
                    it::class == item::class &&
                        (item !is ToolbarItem.Action || (it as ToolbarItem.Action).actionType == item.actionType) &&
                        (item !is ToolbarItem.Widget || (it as ToolbarItem.Widget).widgetType == item.widgetType)
                }
            if (exists) return
        }

        currentList.add(item)
        _toolbarItems.value = currentList
        saveToolbar()
    }

    fun setToolbarItems(items: List<ToolbarItem>) {
        _toolbarItems.value = items
        saveToolbar()
    }

    fun removeToolbarItem(item: ToolbarItem) {
        android.util.Log.d("BooxVibesDebug", "ViewModel: removeToolbarItem ID=${item.id}")
        val currentList = _toolbarItems.value.toMutableList()
        currentList.remove(item)
        _toolbarItems.value = currentList
        saveToolbar()

        if (_activeToolId.value == item.id) {
            // Fallback selection
            val firstTool = currentList.firstOrNull { it is ToolbarItem.Pen }
            firstTool?.let { selectTool(it.id) }
        }
    }

    fun moveToolbarItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val currentList = _toolbarItems.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _toolbarItems.value = currentList
            saveToolbar()
        }
    }

    // Deprecated methods compatibility
    fun removePen(id: String) {
        val item = _toolbarItems.value.find { it.id == id } ?: return
        removeToolbarItem(item)
    }

    // Compatibility property for CanvasActivity observation
    // We construct a list of PenTools just for the legacy renderToolbar,
    // but we will be replacing that soon.
    val tools: StateFlow<List<PenTool>> get() = _tools // Kept to avoid compilation errors before refactor complete
    private val _tools = MutableStateFlow<List<PenTool>>(emptyList()) // Dummy

    private fun saveToolbar() {
        PreferencesManager.saveToolbarItems(getApplication(), _toolbarItems.value)
    }

    // Helpers to recreate default tools
    private fun createEraserTool(id: String) =
        PenTool(
            id,
            "Eraser",
            ToolType.ERASER,
            Color.WHITE,
            30f,
            com.alexdremov.notate.model.StrokeType.PENCIL,
            com.alexdremov.notate.model.EraserType.STANDARD,
        )

    private fun createSelectTool(id: String) =
        PenTool(
            id,
            "Select",
            ToolType.SELECT,
            Color.BLACK,
            2f,
            com.alexdremov.notate.model.StrokeType.DASH,
            com.alexdremov.notate.model.EraserType.STANDARD,
            com.alexdremov.notate.model.SelectionType.RECTANGLE,
        )
}
