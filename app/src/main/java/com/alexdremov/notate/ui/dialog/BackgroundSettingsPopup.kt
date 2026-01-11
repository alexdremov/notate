package com.alexdremov.notate.ui.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import com.alexdremov.notate.R
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.ui.settings.BackgroundSettingsBinder

class BackgroundSettingsPopup(
    private val context: Context,
    private val currentStyle: BackgroundStyle,
    private val isFixedPageMode: Boolean,
    private val onUpdate: (BackgroundStyle) -> Unit,
    private val onDismiss: () -> Unit,
) : PopupWindow(context) {
    private val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_background_settings, null)

    init {
        contentView = view
        isFocusable = true
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = 16f

        BackgroundSettingsBinder(
            context,
            view,
            { currentStyle },
            isFixedPageMode,
            onUpdate,
        )

        setOnDismissListener {
            onDismiss()
        }
    }
}
