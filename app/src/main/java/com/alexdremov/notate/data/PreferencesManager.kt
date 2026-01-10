package com.alexdremov.notate.data

import android.content.Context
import android.content.SharedPreferences
import com.alexdremov.notate.model.PenTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProjectConfig(
    val id: String,
    val name: String,
    val uri: String,
)

object PreferencesManager {
    private const val PREFS_NAME = "notate_prefs"
    private const val KEY_PROJECTS = "projects_list"
    private const val KEY_TOOLBOX = "toolbox_config"
    private const val KEY_COLORS = "favorite_colors"
    private const val KEY_SCRIBBLE_TO_ERASE = "scribble_to_erase"
    private const val KEY_SHAPE_PERFECTION_ENABLED = "shape_perfection_enabled"
    private const val KEY_SHAPE_PERFECTION_DELAY = "shape_perfection_delay"

    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isScribbleToEraseEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SCRIBBLE_TO_ERASE, true)

    fun setScribbleToEraseEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_SCRIBBLE_TO_ERASE, enabled).apply()
    }

    fun isShapePerfectionEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHAPE_PERFECTION_ENABLED, true)

    fun setShapePerfectionEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_SHAPE_PERFECTION_ENABLED, enabled).apply()
    }

    fun getShapePerfectionDelay(context: Context): Long {
        return getPrefs(context).getLong(KEY_SHAPE_PERFECTION_DELAY, 600L) // Default 600ms
    }

    fun setShapePerfectionDelay(
        context: Context,
        delayMs: Long,
    ) {
        getPrefs(context).edit().putLong(KEY_SHAPE_PERFECTION_DELAY, delayMs).apply()
    }

    fun getProjects(context: Context): List<ProjectConfig> {
        val json = getPrefs(context).getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addProject(
        context: Context,
        config: ProjectConfig,
    ) {
        val current = getProjects(context).toMutableList()
        current.add(config)
        saveProjects(context, current)
    }

    fun removeProject(
        context: Context,
        id: String,
    ) {
        val current = getProjects(context).toMutableList()
        current.removeAll { it.id == id }
        saveProjects(context, current)
    }

    fun updateProject(
        context: Context,
        config: ProjectConfig,
    ) {
        val current = getProjects(context).toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index != -1) {
            current[index] = config
            saveProjects(context, current)
        }
    }

    private fun saveProjects(
        context: Context,
        list: List<ProjectConfig>,
    ) {
        val json = Json.encodeToString(list)
        getPrefs(context).edit().putString(KEY_PROJECTS, json).apply()
    }

    // --- Toolbox Persistence ---

    fun saveTools(
        context: Context,
        tools: List<PenTool>,
    ) {
        val json = Json.encodeToString(tools)
        getPrefs(context).edit().putString(KEY_TOOLBOX, json).apply()
    }

    fun getTools(context: Context): List<PenTool> {
        val json = getPrefs(context).getString(KEY_TOOLBOX, null) ?: return PenTool.defaultPens()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            PenTool.defaultPens()
        }
    }

    // --- Favorite Colors Persistence ---

    fun saveFavoriteColors(
        context: Context,
        colors: List<Int>,
    ) {
        val json = Json.encodeToString(colors)
        getPrefs(context).edit().putString(KEY_COLORS, json).apply()
    }

    fun getFavoriteColors(context: Context): List<Int> {
        val json = getPrefs(context).getString(KEY_COLORS, null) ?: return defaultColors()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            defaultColors()
        }
    }

    private fun defaultColors(): List<Int> =
        listOf(
            android.graphics.Color.BLACK,
            android.graphics.Color.parseColor("#424242"), // Dark Gray
            android.graphics.Color.parseColor("#1A237E"), // Navy Blue
            android.graphics.Color.parseColor("#B71C1C"), // Dark Red
            android.graphics.Color.parseColor("#1B5E20"), // Forest Green
            android.graphics.Color.parseColor("#2196F3"), // Blue
            android.graphics.Color.parseColor("#4CAF50"), // Green
            android.graphics.Color.parseColor("#BBDEFB"), // Pastel Blue
            android.graphics.Color.parseColor("#FFCDD2"), // Pastel Pink
            android.graphics.Color.parseColor("#FFF9C4"), // Pastel Yellow
        )
}
