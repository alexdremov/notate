package com.alexdremov.notate.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher

class TextEditDialog(
    context: Context,
    private val initialText: String,
    private val fontSize: Float,
    private val textColor: Int,
    private val onTextConfirmed: (String) -> Unit
) : Dialog(context) {

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val frame = FrameLayout(context)
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        frame.setPadding(32, 32, 32, 32)
        frame.setBackgroundColor(Color.WHITE) // Ensure visibility on E-Ink

        editText = EditText(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setText(initialText)
            textSize = fontSize / context.resources.displayMetrics.scaledDensity // Convert px to sp roughly, or keep px if needed
            setTextColor(textColor)
            background = null // Remove underline
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            hint = "Type here..."
        }

        frame.addView(editText)
        setContentView(frame)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes.gravity = Gravity.CENTER
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        // Setup Markwon Editor
        val markwon = Markwon.create(context)
        val editor = MarkwonEditor.create(markwon)
        editText.addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))
    }

    override fun dismiss() {
        val text = editText.text.toString()
        if (text.isNotBlank() && text != initialText) {
            onTextConfirmed(text)
        } else if (initialText.isNotBlank() && text.isBlank()) {
             // Handle deletion? For now, if it becomes empty, we might want to delete it or just keep empty
             // logic should be in caller. If cleared, maybe return empty string?
             onTextConfirmed("")
        }
        super.dismiss()
    }
}
