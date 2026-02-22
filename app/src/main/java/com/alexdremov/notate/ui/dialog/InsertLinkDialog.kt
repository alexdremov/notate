package com.alexdremov.notate.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import com.alexdremov.notate.R
import com.alexdremov.notate.data.LinkType
import com.alexdremov.notate.util.EpdFastModeController
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class InsertLinkDialog(
    context: Context,
    private val onConfirm: (String, String, LinkType) -> Unit,
    private val onBrowse: (onResult: (name: String, uuid: String) -> Unit) -> Unit,
) : Dialog(context) {
    private lateinit var editName: EditText
    private lateinit var editTarget: EditText
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioInternal: RadioButton
    private lateinit var radioExternal: RadioButton
    private lateinit var btnBrowse: Button
    private lateinit var btnInsert: Button
    private lateinit var btnCancel: Button

    private var selectedType: LinkType = LinkType.INTERNAL_NOTE
    private var targetUuid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_insert_link)

        editName = findViewById(R.id.edit_link_name)
        editTarget = findViewById(R.id.edit_target)
        radioGroup = findViewById(R.id.radio_group_type)
        radioInternal = findViewById(R.id.radio_internal)
        radioExternal = findViewById(R.id.radio_external)
        btnBrowse = findViewById(R.id.btn_browse)
        btnInsert = findViewById(R.id.btn_insert)
        btnCancel = findViewById(R.id.btn_cancel)

        setupListeners()
        updateState()
    }

    override fun onStart() {
        super.onStart()
        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val params = win.attributes
            params.gravity = android.view.Gravity.CENTER
            win.attributes = params
        }
    }

    private fun setupListeners() {
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedType = if (checkedId == R.id.radio_internal) LinkType.INTERNAL_NOTE else LinkType.EXTERNAL_URL
            updateState()
        }

        btnBrowse.setOnClickListener {
            onBrowse { name, uuid ->
                targetUuid = uuid
                editTarget.setText(name) // Show name but store UUID
                // Auto-fill name if empty
                if (editName.text.isBlank()) {
                    editName.setText(name)
                }
            }
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnInsert.setOnClickListener {
            val name = editName.text.toString().trim()
            val rawTarget = editTarget.text.toString().trim()

            val target =
                if (selectedType == LinkType.INTERNAL_NOTE) {
                    targetUuid ?: "" // Should be validated
                } else {
                    rawTarget
                }

            if (name.isNotEmpty() && target.isNotEmpty()) {
                onConfirm(name, target, selectedType)
                dismiss()
            }
        }

        // EPD Optimization: Force refresh on show
        window?.decorView?.post {
            EpdFastModeController.exitFastMode()
            EpdController.invalidate(window?.decorView, UpdateMode.GC)
        }
    }

    private fun updateState() {
        if (selectedType == LinkType.INTERNAL_NOTE) {
            editTarget.isEnabled = false
            editTarget.hint = "Select a note..."
            btnBrowse.visibility = View.VISIBLE
            if (targetUuid == null) {
                editTarget.text.clear()
            }
        } else {
            editTarget.isEnabled = true
            editTarget.hint = "https://example.com"
            btnBrowse.visibility = View.GONE
            // Clear target if it was a UUID before
            if (targetUuid != null) {
                editTarget.text.clear()
                targetUuid = null
            }
        }
    }
}
