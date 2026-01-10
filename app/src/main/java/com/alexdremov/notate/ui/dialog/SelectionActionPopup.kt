package com.alexdremov.notate.ui.dialog

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.alexdremov.notate.databinding.DialogSelectionActionsBinding
import com.alexdremov.notate.util.EpdFastModeController
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class SelectionActionPopup(
    private val context: Context,
    private val onCopy: () -> Unit,
    private val onDelete: () -> Unit,
    private val onDismiss: () -> Unit
) {
    private val binding = DialogSelectionActionsBinding.inflate(LayoutInflater.from(context), null, false)
    private val popupWindow = PopupWindow(
        binding.root,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        false
    )

    init {
        popupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        popupWindow.elevation = 16f
        popupWindow.isOutsideTouchable = false 

        binding.btnCopy.setOnClickListener {
            onCopy()
            dismiss()
        }

        binding.btnDelete.setOnClickListener {
            onDelete()
            dismiss()
        }
    }

    fun show(parent: View, selectionBounds: RectF, matrix: android.graphics.Matrix) {
        if (popupWindow.isShowing) return

        // Calculate screen coordinates of selection points
        val topCenter = floatArrayOf(selectionBounds.centerX(), selectionBounds.top)
        val bottomCenter = floatArrayOf(selectionBounds.centerX(), selectionBounds.bottom)
        matrix.mapPoints(topCenter)
        matrix.mapPoints(bottomCenter)
        
        val screenX = topCenter[0].toInt()
        val screenYTop = topCenter[1].toInt()
        val screenYBottom = bottomCenter[1].toInt()

        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight

        // Bounds check
        val displayMetrics = context.resources.displayMetrics
        
        // Default: Show above selection with clearance for Rotation Handle (approx 70px)
        // 50px handle + 15px radius + 25px padding = 90px
        val handleClearance = 90
        var x = screenX - (popupWidth / 2)
        var y = screenYTop - popupHeight - handleClearance

        // If no space on top, flip to bottom
        if (y < 0) {
             // Below selection + Handle radius clearance (20px) + Padding
             y = screenYBottom + 40
        }

        // Horizontal Clamping
        if (x < 0) x = 0
        if (x + popupWidth > displayMetrics.widthPixels) x = displayMetrics.widthPixels - popupWidth

        popupWindow.showAtLocation(parent, Gravity.NO_GRAVITY, x, y)
        
        // Force refresh for E-Ink
        EpdFastModeController.exitFastMode()
        parent.post {
            EpdController.invalidate(binding.root, UpdateMode.GC)
        }
    }

    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
            onDismiss()
        }
    }
    
    fun isShowing() = popupWindow.isShowing
}
