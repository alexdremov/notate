package com.example.notate.vm

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import com.example.notate.data.PreferencesManager
import com.example.notate.model.PenTool
import com.example.notate.model.ToolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DrawingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _tools = MutableStateFlow(PreferencesManager.getTools(application).ifEmpty { PenTool.defaultPens() })
    val tools: StateFlow<List<PenTool>> = _tools.asStateFlow()

    private val _activeToolId = MutableStateFlow(if (_tools.value.isNotEmpty()) _tools.value[0].id else "")
    val activeToolId: StateFlow<String> = _activeToolId.asStateFlow()

    private val _activeTool = MutableStateFlow(if (_tools.value.isNotEmpty()) _tools.value[0] else PenTool.defaultPens()[0])
    val activeTool: StateFlow<PenTool> = _activeTool.asStateFlow()

    // Canvas State
    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val _isDrawingEnabled = MutableStateFlow(true)
    val isDrawingEnabled: StateFlow<Boolean> = _isDrawingEnabled.asStateFlow()

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
                strokeType = com.example.notate.model.StrokeType.FOUNTAIN,
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
