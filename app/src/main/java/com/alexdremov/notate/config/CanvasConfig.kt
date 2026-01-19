package com.alexdremov.notate.config

import android.graphics.Color

object CanvasConfig {
    // Canvas & World
    const val MIN_SCALE = 0.01f
    const val MAX_SCALE = 100.0f
    const val WORLD_BOUNDS_SIZE = 50000f // +/- from origin

    // Tiling & Performance
    const val TILE_SIZE = 512
    const val TILE_BYTES_PER_PIXEL = 4 // ARGB_8888
    const val TILE_MANAGER_TARGET_FPS = 30
    const val MIN_ZOOM_LEVEL = -10
    const val MAX_ZOOM_LEVEL = 10
    const val LOD_BIAS = 0.5f // Switch to lower resolution sooner
    const val CACHE_MEMORY_PERCENT = 0.65 // of heap
    const val IMAGE_CACHE_MEMORY_PERCENT = 0.25 // of heap
    const val IMAGE_METADATA_CACHE_SIZE = 200
    const val ERROR_CACHE_SIZE = 100
    const val NEIGHBOR_PRECACHE_THRESHOLD_PERCENT = 0.9
    const val NEIGHBOR_COUNT = 2
    val THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors()

    // Debugging
    var DEBUG_USE_SIMPLE_RENDERER = false
    var DEBUG_SHOW_RAM_USAGE = false
    var DEBUG_SHOW_TILES = false
    var DEBUG_SHOW_BOUNDING_BOX = false
    const val DEBUG_TEXT_SIZE_BASE = 40f
    const val DEBUG_STROKE_WIDTH_BASE = 2f
    const val DEBUG_TEXT_OFFSET_Y_BASE = 50f
    const val DEBUG_TEXT_OFFSET_X_BASE = 10f
    const val DEBUG_TEXT_LINE_HEIGHT_BASE = 40f
    const val DEBUG_ERROR_MSG_CHUNK_SIZE = 30

    // Profiling
    var DEBUG_ENABLE_PROFILING = false
    const val PROFILING_INTERVAL_MS = 2000L

    // UI / Minimap
    const val MINIMAP_WIDTH = 300f
    const val MINIMAP_PADDING = 20f
    const val MINIMAP_BORDER_COLOR = Color.LTGRAY
    const val MINIMAP_VIEWPORT_COLOR = Color.RED
    const val MINIMAP_STROKE_WIDTH = 2f
    const val MINIMAP_HIDE_DELAY_MS = 500L
    const val UI_TEXT_SIZE_LARGE = 30f

    // Pen Settings
    const val POPUP_ELEVATION = 16f
    const val PALETTE_ITEM_SIZE_DP = 48
    const val PALETTE_ITEM_MARGIN_DP = 4
    const val PALETTE_COLOR_CIRCLE_SIZE_DP = 32
    const val PALETTE_SELECTED_BORDER_WIDTH_DP = 2
    const val PALETTE_ADD_ICON_DASH_GAP_DP = 4

    // Tools
    const val TOOLS_MIN_STROKE_MM = 0.1f
    const val TOOLS_MAX_STROKE_MM = 3.0f
    const val EINK_RENDER_THRESHOLD_MM = 10.0f

    // Gestures
    const val TWO_FINGER_TAP_MAX_DELAY = 250L
    const val TWO_FINGER_TAP_DOUBLE_TIMEOUT = 500L
    const val TWO_FINGER_TAP_SLOP_SQ = 2500f // 50px squared

    // Fixed Page Defaults (A4 @ ~300 DPI or Logic Units)
    // Using standard A4 ratio 1 : 1.414
    // Width 2480, Height 3508
    const val PAGE_A4_WIDTH = 2480f
    const val PAGE_A4_HEIGHT = 3508f
    const val PAGE_SPACING = 100f // Gap between pages
    val PAGE_BACKGROUND_COLOR = Color.WHITE
    val FIXED_PAGE_CANVAS_BG_COLOR = Color.rgb(226, 226, 226) // Slightly gray, outside pages

    // Fountain Pen Rendering
    const val FOUNTAIN_PRESSURE_POWER_EXPONENT = 0.95f
    const val FOUNTAIN_MIN_WIDTH = 1.5f
    const val FOUNTAIN_PRESSURE_SMOOTHING_FACTOR = 0.9f
    const val FOUNTAIN_SMOOTHING_WINDOW_SIZE = 0
    const val FOUNTAIN_TINY_SEGMENT_THRESHOLD = 0.3f
    const val FOUNTAIN_VELOCITY_INFLUENCE = 0.3f
    const val FOUNTAIN_WIDTH_SMOOTHING = 0.1f // Lower is stronger smoothing (EMA)
    const val FOUNTAIN_MIN_WIDTH_FACTOR = 0.4f
    const val FOUNTAIN_SPLINE_STEPS = 6
    const val FOUNTAIN_BASE_WIDTH_MULTIPLIER = 1.2f
}
