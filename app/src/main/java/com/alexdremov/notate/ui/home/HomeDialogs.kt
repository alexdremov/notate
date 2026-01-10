package com.alexdremov.notate.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.config.Orientation
import com.alexdremov.notate.config.PaperSize
import com.alexdremov.notate.data.CanvasType

enum class DialogType {
    ADD_PROJECT,
    CREATE_FOLDER,
    CREATE_CANVAS,
}

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String = "",
    confirmText: String = "Create",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun CreateCanvasDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, CanvasType, Float, Float) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(CanvasType.INFINITE) }

    // Fixed Page Options
    var selectedSize by remember { mutableStateOf(PaperSize.A4) }
    var selectedOrientation by remember { mutableStateOf(Orientation.PORTRAIT) }

    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = "New Canvas") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Canvas Type", style = MaterialTheme.typography.titleSmall)

                // Canvas Type Selection
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .selectable(
                            selected = (selectedType == CanvasType.INFINITE),
                            onClick = { selectedType = CanvasType.INFINITE },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (selectedType == CanvasType.INFINITE),
                        onClick = null,
                    )
                    Text(
                        text = "Infinite Canvas",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .selectable(
                            selected = (selectedType == CanvasType.FIXED_PAGES),
                            onClick = { selectedType = CanvasType.FIXED_PAGES },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (selectedType == CanvasType.FIXED_PAGES),
                        onClick = null,
                    )
                    Text(
                        text = "Fixed Pages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (selectedType == CanvasType.FIXED_PAGES) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // Paper Size Selection
                    Text("Paper Size", style = MaterialTheme.typography.titleSmall)
                    PaperSize.values().forEach { size ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedSize == size),
                                    onClick = { selectedSize = size },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedSize == size),
                                onClick = null,
                            )
                            Text(
                                text = size.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // Orientation Selection
                    Text("Orientation", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedOrientation == Orientation.PORTRAIT),
                                    onClick = { selectedOrientation = Orientation.PORTRAIT },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedOrientation == Orientation.PORTRAIT),
                                onClick = null,
                            )
                            Text(
                                text = "Portrait",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedOrientation == Orientation.LANDSCAPE),
                                    onClick = { selectedOrientation = Orientation.LANDSCAPE },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedOrientation == Orientation.LANDSCAPE),
                                onClick = null,
                            )
                            Text(
                                text = "Landscape",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    if (name.isNotBlank()) {
                        var width = 0f
                        var height = 0f

                        if (selectedType == CanvasType.FIXED_PAGES) {
                            val dim =
                                selectedSize.getDimensions(
                                    selectedOrientation,
                                    displayMetrics.widthPixels.toFloat(),
                                    displayMetrics.heightPixels.toFloat(),
                                )
                            width = dim.first
                            height = dim.second
                        }

                        onConfirm(name, selectedType, width, height)
                    }
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
