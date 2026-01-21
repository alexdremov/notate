package com.alexdremov.notate

import android.app.Application
import android.os.Build
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.util.Logger
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NotateApplication : Application() {
    companion object {
        private const val TAG = "NotateApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Restore Log Level
        val minLevelPriority = PreferencesManager.getMinLogLevel(this)
        val minLevel = Logger.Level.values().find { it.priority == minLevelPriority } ?: Logger.Level.NONE
        Logger.setMinLogLevelToShow(minLevel)

        // Restore Debug Settings
        CanvasConfig.DEBUG_USE_SIMPLE_RENDERER = PreferencesManager.isDebugSimpleRendererEnabled(this)
        CanvasConfig.DEBUG_SHOW_RAM_USAGE = PreferencesManager.isDebugRamUsageEnabled(this)
        CanvasConfig.DEBUG_SHOW_TILES = PreferencesManager.isDebugShowTilesEnabled(this)
        CanvasConfig.DEBUG_SHOW_REGIONS = PreferencesManager.isDebugShowRegionsEnabled(this)
        CanvasConfig.DEBUG_SHOW_BOUNDING_BOX = PreferencesManager.isDebugBoundingBoxEnabled(this)
        CanvasConfig.DEBUG_ENABLE_PROFILING = PreferencesManager.isDebugProfilingEnabled(this)

        logDeviceInfo()

        // Initialize Onyx SDK managers
        ResManager.init(this)
        RxBaseAction.init(this)

        // Bypass hidden API restrictions for SDK functionality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (e: Throwable) {
                Logger.e(TAG, "HiddenApiBypass failed", e)
            }
        }

        cleanupSessions()
    }

    private fun cleanupSessions() {
        try {
            val sessionsDir = java.io.File(cacheDir, "sessions")
            if (sessionsDir.exists()) {
                val currentTime = System.currentTimeMillis()
                val oneHourAgo = currentTime - (1000 * 60 * 60)

                sessionsDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.lastModified() < oneHourAgo) {
                        file.deleteRecursively()
                        Logger.i(TAG, "Cleaned up old session: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to cleanup sessions", e)
        }
    }

    private fun logDeviceInfo() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val availableCores = runtime.availableProcessors()

        // Get Total System RAM
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRam = memoryInfo.totalMem / (1024 * 1024)

        Logger.i(TAG, "--- App Starting ---")
        Logger.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        Logger.i(TAG, "Cores: $availableCores")
        Logger.i(
            TAG,
            "App Heap Limit (VM): ${maxMemory}MB (largeHeap=${(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0})",
        )
        Logger.i(TAG, "Device Physical RAM: ${totalRam}MB")
        Logger.i(TAG, "Thread Pool Size (Config): ${CanvasConfig.THREAD_POOL_SIZE}")
        Logger.i(TAG, "Tile Size (Config): ${CanvasConfig.TILE_SIZE}")
        Logger.i(TAG, "--------------------")
    }
}
