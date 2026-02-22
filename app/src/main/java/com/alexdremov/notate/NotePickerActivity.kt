package com.alexdremov.notate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.HomeViewModel
import com.onyx.android.sdk.api.device.EpdDeviceManager
import kotlinx.coroutines.launch

class NotePickerActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EpdDeviceManager.enterAnimationUpdate(true)

        val lockedProjectId = intent.getStringExtra("LOCKED_PROJECT_ID")
        val disabledItemUuid = intent.getStringExtra("DISABLED_ITEM_UUID")

        setContent {
            NotateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val projects by viewModel.projects.collectAsState()
                    val currentProject by viewModel.currentProject.collectAsState()

                    // Automatically open the locked project once projects are loaded
                    LaunchedEffect(projects, lockedProjectId) {
                        if (lockedProjectId != null && currentProject?.id != lockedProjectId) {
                            val target = projects.find { it.id == lockedProjectId }
                            if (target != null) {
                                viewModel.openProject(target)
                            }
                        }
                    }

                    MainScreen(
                        viewModel = viewModel,
                        isPickerMode = true,
                        lockedProjectId = lockedProjectId,
                        disabledItemUuid = disabledItemUuid,
                        onFilePicked = { item ->
                            Logger.d("NotePicker", "Picked item: ${item.name}, UUID: ${item.uuid}")
                            if (!item.uuid.isNullOrBlank()) {
                                val resultIntent =
                                    Intent().apply {
                                        putExtra("NOTE_NAME", item.name)
                                        putExtra("NOTE_UUID", item.uuid)
                                        putExtra("NOTE_PATH", item.path)
                                    }
                                setResult(Activity.RESULT_OK, resultIntent)
                                finish()
                            } else {
                                // Try to heal missing UUID
                                Toast
                                    .makeText(
                                        this@NotePickerActivity,
                                        "Initializing note for linking...",
                                        Toast.LENGTH_SHORT,
                                    ).show()

                                lifecycleScope.launch {
                                    val newUuid = viewModel.ensureUuid(item)
                                    if (newUuid != null) {
                                        val resultIntent =
                                            Intent().apply {
                                                putExtra("NOTE_NAME", item.name)
                                                putExtra("NOTE_UUID", newUuid)
                                                putExtra("NOTE_PATH", item.path)
                                            }
                                        setResult(Activity.RESULT_OK, resultIntent)
                                        finish()
                                    } else {
                                        Toast
                                            .makeText(
                                                this@NotePickerActivity,
                                                "Failed to index note. Please open it manually once.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EpdDeviceManager.enterAnimationUpdate(true)
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        EpdDeviceManager.exitAnimationUpdate(true)
    }
}
