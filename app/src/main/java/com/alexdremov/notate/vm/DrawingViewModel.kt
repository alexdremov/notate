package com.alexdremov.notate.vm

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ToolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DrawingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _tools = MutableStateFlow<List<PenTool>>(emptyList())
    val tools: StateFlow<List<PenTool>> = _tools.asStateFlow()

    private val _activeToolId = MutableStateFlow("")
    val activeToolId: StateFlow<String> = _activeToolId.asStateFlow()

    private val _activeTool = MutableStateFlow(PenTool.defaultPens()[0])
    val activeTool: StateFlow<PenTool> = _activeTool.asStateFlow()

    // Canvas State
    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val _isDrawingEnabled = MutableStateFlow(true)
    val isDrawingEnabled: StateFlow<Boolean> = _isDrawingEnabled.asStateFlow()

    init {
        loadTools()
    }

    private fun loadTools() {
        val savedTools = PreferencesManager.getTools(getApplication())
        val toolsToUse: MutableList<PenTool> =
            if (savedTools.isEmpty()) {
                PenTool.defaultPens().toMutableList()
            } else {
                savedTools.toMutableList()
            }

        // Enforce Eraser
        if (toolsToUse.none { it.type == ToolType.ERASER }) {
            toolsToUse.add(
                PenTool(
                    "eraser_std",
                    "Standard Eraser",
                    ToolType.ERASER,
                    android.graphics.Color.WHITE,
                    30f,
                    com.alexdremov.notate.model.StrokeType.PENCIL,
                    com.alexdremov.notate.model.EraserType.STANDARD,
                ),
            )
        }

        // Enforce Select
        if (toolsToUse.none { it.type == ToolType.SELECT }) {
            toolsToUse.add(
                PenTool(
                    "select_tool",
                    "Select",
                    ToolType.SELECT,
                    android.graphics.Color.BLACK,
                    2f,
                    com.alexdremov.notate.model.StrokeType.DASH,
                    com.alexdremov.notate.model.EraserType.STANDARD,
                    com.alexdremov.notate.model.SelectionType.RECTANGLE,
                ),
            )
        }

        // Ensure only one Select tool exists (remove duplicates if any)
        val selectTools = toolsToUse.filter { it.type == ToolType.SELECT }
        if (selectTools.size > 1) {
            // Keep the first one, remove others
            toolsToUse.removeAll(selectTools.drop(1))
        }

        // Ensure only one Eraser exists
        val eraserTools = toolsToUse.filter { it.type == ToolType.ERASER }
        if (eraserTools.size > 1) {
            toolsToUse.removeAll(eraserTools.drop(1))
        }

        _tools.value = toolsToUse
        if (_activeToolId.value.isEmpty() && toolsToUse.isNotEmpty()) {
            _activeToolId.value = toolsToUse[0].id
            _activeTool.value = toolsToUse[0]
        }
    }

    fun setDrawingEnabled(enabled: Boolean) {
        _isDrawingEnabled.value = enabled
    }

    fun selectTool(id: String) {
        val tool = _tools.value.find { it.id == id } ?: return

        _activeToolId.value = id
        _activeTool.value = tool
    }

    fun updateTool(updatedTool: PenTool) {
        val currentList = _tools.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedTool.id }
        if (index != -1) {
            currentList[index] = updatedTool
            _tools.value = currentList
            saveTools()

            if (_activeToolId.value == updatedTool.id) {
                _activeTool.value = updatedTool
            }
        }
    }

    fun addPen() {
        val currentList = _tools.value.toMutableList()
        // Count only PEN type tools
        if (currentList.count { it.type == ToolType.PEN } >= 7) return

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

        // Insert before Eraser (assuming Eraser is kept at the end)
        val eraserIndex = currentList.indexOfLast { it.type == ToolType.ERASER }
        if (eraserIndex != -1) {
            currentList.add(eraserIndex, newPen)
        } else {
            currentList.add(newPen)
        }

        _tools.value = currentList
        saveTools()
        selectTool(newId) // Auto-select new pen
    }

    fun removePen(id: String) {
        val currentList = _tools.value.toMutableList()
        val toolToRemove = currentList.find { it.id == id } ?: return

        // Don't remove eraser or the last pen
        if (toolToRemove.type == ToolType.ERASER) return
        if (currentList.count { it.type == ToolType.PEN } <= 1) return

        currentList.remove(toolToRemove)
        _tools.value = currentList
        saveTools()

        // If removed was active, select the first available pen
        if (_activeToolId.value == id) {
            val firstPen = currentList.firstOrNull { it.type == ToolType.PEN }
            if (firstPen != null) {
                selectTool(firstPen.id)
            }
        }
    }

    private fun saveTools() {
        PreferencesManager.saveTools(getApplication(), _tools.value)
    }
}
