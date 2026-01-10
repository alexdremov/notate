package com.alexdremov.notate

import android.app.Application
import android.os.Build
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NotateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
}
