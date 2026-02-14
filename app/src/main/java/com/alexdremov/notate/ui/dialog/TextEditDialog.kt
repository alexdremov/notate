package com.alexdremov.notate.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.widget.addTextChangedListener
import com.alexdremov.notate.R
import com.alexdremov.notate.ui.dpToPx
import com.onyx.android.sdk.api.device.EpdDeviceManager
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher

class TextEditDialog(
    context: Context,
    private val initialText: String,
    private val fontSize: Float,
    private val textColor: Int,
    private val onTextConfirmed: (String) -> Unit,
) : Dialog(context) {
    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val frame = FrameLayout(context)
        frame.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

        // E-Ink optimization: Thick black border for visibility
        val padding = context.dpToPx(16)
        frame.setPadding(padding, padding, padding, padding)

        val frameBackground =
            GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(context.dpToPx(3), Color.BLACK)
                cornerRadius = context.dpToPx(8).toFloat()
            }
        frame.background = frameBackground

        editText =
            EditText(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setText(initialText)
                // Use a reasonable minimum font size for the editor to ensure readability
                val displayFontSize = if (fontSize < 20f) 20f else fontSize
                textSize = displayFontSize / context.resources.displayMetrics.scaledDensity
                setTextColor(Color.BLACK) // Always black for maximum contrast in editor
                this.background = null // Remove standard underline
                gravity = Gravity.TOP or Gravity.START
                minLines = 3
                hint = context.getString(R.string.type_here_hint)

                // Try to make cursor more visible if possible on standard Android
                // On some devices this helps, on others it's ignored.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    textCursorDrawable = ColorDrawable(Color.BLACK)
                }
            }

        frame.addView(editText)
        setContentView(frame)

        window?.apply {
            // Give the dialog some margin from screen edges
            val horizontalMargin = context.dpToPx(32)
            setLayout(context.resources.displayMetrics.widthPixels - horizontalMargin * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes.gravity = Gravity.CENTER
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        // Setup Markwon Editor
        val markwon = Markwon.create(context)
        val editor = MarkwonEditor.create(markwon)
        editText.addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))
    }

    override fun onStart() {
        super.onStart()
        // Enter animation mode for responsive typing on E-Ink
        EpdDeviceManager.enterAnimationUpdate(true)
    }

    override fun onStop() {
        super.onStop()
        EpdDeviceManager.exitAnimationUpdate(true)
    }

    override fun dismiss() {
        val text = editText.text.toString()
        if (text != initialText) {
            onTextConfirmed(text)
        }
        super.dismiss()
    }
}
