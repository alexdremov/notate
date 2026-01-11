package com.alexdremov.notate.ui.dialog

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.alexdremov.notate.R
import com.alexdremov.notate.util.EpdFastModeController
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class PasteActionPopup(
    private val context: Context,
    private val onPaste: () -> Unit,
) {
    private val popupWindow: PopupWindow
    private val view: View

    init {
        view = LayoutInflater.from(context).inflate(R.layout.dialog_paste_action, null)
        popupWindow =
            PopupWindow(
                view,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true, // Focusable to capture clicks, but dismiss on outside touch
            )
        popupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        popupWindow.elevation = 16f

        view.findViewById<View>(R.id.btn_paste).setOnClickListener {
            onPaste()
            dismiss()
        }
    }

    fun show(
        parent: View,
        x: Float,
        y: Float,
    ) {
        if (popupWindow.isShowing) return

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = view.measuredWidth
        val h = view.measuredHeight

        // Center horizontally on x, show above y
        var screenX = x.toInt() - (w / 2)
        var screenY = y.toInt() - h - 40

        // Bounds check
        val dm = context.resources.displayMetrics
        if (screenX < 0) screenX = 0
        if (screenX + w > dm.widthPixels) screenX = dm.widthPixels - w
        if (screenY < 0) screenY = y.toInt() + 40 // Flip below

        popupWindow.showAtLocation(parent, Gravity.NO_GRAVITY, screenX, screenY)

        // Force EPD Refresh
        EpdFastModeController.exitFastMode()
        parent.post {
            EpdController.invalidate(view, UpdateMode.GC)
        }
    }

    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }
}
