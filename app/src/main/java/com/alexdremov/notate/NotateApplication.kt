package com.alexdremov.notate

import android.app.Application
import android.os.Build
import android.util.Log
import com.alexdremov.notate.config.CanvasConfig
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NotateApplication : Application() {
    companion object {
        private const val TAG = "NotateApplication"
    }

    override fun onCreate() {
        super.onCreate()
        logDeviceInfo()

        // Initialize Onyx SDK managers
        ResManager.init(this)
        RxBaseAction.init(this)

        // Bypass hidden API restrictions for SDK functionality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
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

        Log.i(TAG, "--- App Starting ---")
        Log.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Cores: $availableCores")
        Log.i(
            TAG,
            "App Heap Limit (VM): ${maxMemory}MB (largeHeap=${(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0})",
        )
        Log.i(TAG, "Device Physical RAM: ${totalRam}MB")
        Log.i(TAG, "Thread Pool Size (Config): ${CanvasConfig.THREAD_POOL_SIZE}")
        Log.i(TAG, "Tile Size (Config): ${CanvasConfig.TILE_SIZE}")
        Log.i(TAG, "--------------------")
    }
}
