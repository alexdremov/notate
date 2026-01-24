@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.model.ToolbarItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class SortOption(
    val displayName: String,
) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_NEWEST("Date (Newest)"),
    DATE_OLDEST("Date (Oldest)"),
}

@Serializable
data class ProjectConfig(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val uri: String,
)

@Serializable
data class FavoriteColors(
    @ProtoNumber(1)
    val colors: List<Int>,
)

object PreferencesManager {
    private const val PREFS_NAME = "notate_prefs"
    private const val KEY_PROJECTS = "projects_list"
    private const val KEY_TOOLBAR_ITEMS = "toolbar_items_config"
    private const val KEY_COLORS = "favorite_colors"
    private const val KEY_TAGS = "defined_tags"
    private const val KEY_SORT_OPTION = "browser_sort_option"
    private const val KEY_SCRIBBLE_TO_ERASE = "scribble_to_erase"
    private const val KEY_SHAPE_PERFECTION_ENABLED = "shape_perfection_enabled"
    private const val KEY_SHAPE_PERFECTION_DELAY = "shape_perfection_delay"
    private const val KEY_ANGLE_SNAPPING = "angle_snapping_enabled"
    private const val KEY_AXIS_LOCKING = "axis_locking_enabled"
    private const val KEY_COLLAPSIBLE_TOOLBAR = "collapsible_toolbar_enabled"
    private const val KEY_TOOLBAR_COLLAPSE_TIMEOUT = "toolbar_collapse_timeout"
    private const val KEY_MIN_LOG_LEVEL = "min_log_level_to_show"
    private const val KEY_PDF_EXPORT_SCALE = "pdf_export_scale"
    private const val KEY_SYNC_PDF_TYPE = "sync_pdf_type"

    // Debug Preferences
    private const val KEY_DEBUG_USE_SIMPLE_RENDERER = "debug_use_simple_renderer"
    private const val KEY_DEBUG_SHOW_RAM_USAGE = "debug_show_ram_usage"
    private const val KEY_DEBUG_SHOW_TILES = "debug_show_tiles"
    private const val KEY_DEBUG_SHOW_BOUNDING_BOX = "debug_show_bounding_box"
    private const val KEY_DEBUG_SHOW_REGIONS = "debug_show_regions"
    private const val KEY_DEBUG_ENABLE_PROFILING = "debug_enable_profiling"

    private val protoBuf = ProtoBuf

    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSortOption(context: Context): SortOption {
        val name = getPrefs(context).getString(KEY_SORT_OPTION, SortOption.DATE_NEWEST.name)
        return try {
            SortOption.valueOf(name ?: SortOption.DATE_NEWEST.name)
        } catch (e: Exception) {
            SortOption.DATE_NEWEST
        }
    }

    fun saveSortOption(
        context: Context,
        option: SortOption,
    ) {
        getPrefs(context).edit().putString(KEY_SORT_OPTION, option.name).apply()
    }

    fun getPdfExportScale(context: Context): Float = getPrefs(context).getFloat(KEY_PDF_EXPORT_SCALE, 2.0f)

    fun setPdfExportScale(
        context: Context,
        scale: Float,
    ) {
        getPrefs(context).edit().putFloat(KEY_PDF_EXPORT_SCALE, scale).apply()
    }

    fun getSyncPdfType(context: Context): String = getPrefs(context).getString(KEY_SYNC_PDF_TYPE, "VECTOR") ?: "VECTOR"

    fun setSyncPdfType(
        context: Context,
        type: String,
    ) {
        getPrefs(context).edit().putString(KEY_SYNC_PDF_TYPE, type).apply()
    }

    fun getMinLogLevel(context: Context): Int = getPrefs(context).getInt(KEY_MIN_LOG_LEVEL, 4) // Default to NONE (4)

    fun setMinLogLevel(
        context: Context,
        level: Int,
    ) {
        getPrefs(context).edit().putInt(KEY_MIN_LOG_LEVEL, level).apply()
    }

    // --- Debug Preferences Accessors ---

    fun isDebugSimpleRendererEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_USE_SIMPLE_RENDERER, false)

    fun setDebugSimpleRendererEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_USE_SIMPLE_RENDERER, enabled).apply()
    }

    fun isDebugRamUsageEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_RAM_USAGE, false)

    fun setDebugRamUsageEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_RAM_USAGE, enabled).apply()
    }

    fun isDebugShowTilesEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_TILES, false)

    fun setDebugShowTilesEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_TILES, enabled).apply()
    }

    fun isDebugShowRegionsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_REGIONS, false)

    fun setDebugShowRegionsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_REGIONS, enabled).apply()
    }

    fun isDebugBoundingBoxEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_BOUNDING_BOX, false)

    fun setDebugBoundingBoxEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_BOUNDING_BOX, enabled).apply()
    }

    fun isDebugProfilingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_ENABLE_PROFILING, false)

    fun setDebugProfilingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_ENABLE_PROFILING, enabled).apply()
    }

    fun isScribbleToEraseEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SCRIBBLE_TO_ERASE, true)

    fun setScribbleToEraseEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_SCRIBBLE_TO_ERASE, enabled).apply()
    }

    fun isCollapsibleToolbarEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COLLAPSIBLE_TOOLBAR, false)

    fun setCollapsibleToolbarEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_COLLAPSIBLE_TOOLBAR, enabled).apply()
    }

    fun getToolbarCollapseTimeout(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOOLBAR_COLLAPSE_TIMEOUT, 3000L) // Default 3000ms
    }

    fun setToolbarCollapseTimeout(
        context: Context,
        timeoutMs: Long,
    ) {
        getPrefs(context).edit().putLong(KEY_TOOLBAR_COLLAPSE_TIMEOUT, timeoutMs).apply()
    }

    fun isAngleSnappingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ANGLE_SNAPPING, true)

    fun setAngleSnappingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_ANGLE_SNAPPING, enabled).apply()
    }

    fun isAxisLockingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AXIS_LOCKING, true)

    fun setAxisLockingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_AXIS_LOCKING, enabled).apply()
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
        val data = getPrefs(context).getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(ProjectConfig.serializer()), bytes)
        } catch (e: Exception) {
            // Fallback to JSON for migration
            try {
                val projects = Json.decodeFromString<List<ProjectConfig>>(data)
                saveProjects(context, projects) // Resave in new format
                projects
            } catch (jsonError: Exception) {
                emptyList()
            }
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
        val bytes = protoBuf.encodeToByteArray(ListSerializer(ProjectConfig.serializer()), list)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_PROJECTS, string).apply()
    }

    // --- Tags Persistence ---

    fun getTags(context: Context): List<Tag> {
        val data = getPrefs(context).getString(KEY_TAGS, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(Tag.serializer()), bytes).sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTags(
        context: Context,
        tags: List<Tag>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(Tag.serializer()), tags)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_TAGS, string).apply()
    }

    // --- Toolbox Persistence ---

    fun saveToolbarItems(
        context: Context,
        items: List<com.alexdremov.notate.model.ToolbarItem>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(ToolbarItem.serializer()), items)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_TOOLBAR_ITEMS, string).apply()
    }

    fun getToolbarItems(context: Context): List<com.alexdremov.notate.model.ToolbarItem> {
        val data = getPrefs(context).getString(KEY_TOOLBAR_ITEMS, null)
        if (data != null) {
            return try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                protoBuf.decodeFromByteArray(ListSerializer(ToolbarItem.serializer()), bytes)
            } catch (e: Exception) {
                // Fallback to JSON
                try {
                    val items = Json.decodeFromString<List<com.alexdremov.notate.model.ToolbarItem>>(data)
                    saveToolbarItems(context, items) // Resave
                    items
                } catch (jsonError: Exception) {
                    defaultToolbarItems()
                }
            }
        }
        return defaultToolbarItems()
    }

    private fun defaultToolbarItems(): List<com.alexdremov.notate.model.ToolbarItem> {
        val items = mutableListOf<com.alexdremov.notate.model.ToolbarItem>()
        val defaultPens = PenTool.defaultPens()

        defaultPens.forEach { tool ->
            when (tool.type) {
                com.alexdremov.notate.model.ToolType.PEN -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Pen(tool),
                    )
                }

                com.alexdremov.notate.model.ToolType.ERASER -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Eraser(tool),
                    )
                }

                com.alexdremov.notate.model.ToolType.SELECT -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Select(tool),
                    )
                }
            }
        }

        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.UNDO),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.REDO),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Widget(com.alexdremov.notate.model.WidgetType.PAGE_NAVIGATION),
        )
        return items
    }

    // --- Favorite Colors Persistence ---

    fun saveFavoriteColors(
        context: Context,
        colors: List<Int>,
    ) {
        val wrapper = FavoriteColors(colors)
        val bytes = protoBuf.encodeToByteArray(FavoriteColors.serializer(), wrapper)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_COLORS, string).apply()
    }

    fun getFavoriteColors(context: Context): List<Int> {
        val data = getPrefs(context).getString(KEY_COLORS, null) ?: return defaultColors()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(FavoriteColors.serializer(), bytes).colors
        } catch (e: Exception) {
            // Fallback to JSON
            try {
                val colors = Json.decodeFromString<List<Int>>(data)
                saveFavoriteColors(context, colors) // Resave
                colors
            } catch (jsonError: Exception) {
                defaultColors()
            }
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
