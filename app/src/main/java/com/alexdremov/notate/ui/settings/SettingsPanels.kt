package com.alexdremov.notate.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.model.StylusButtonAction
import com.alexdremov.notate.model.TwoFingerTapAction

data class InputSettingsState(
    val scribbleEnabled: Boolean,
    val shapeEnabled: Boolean,
    val angleSnapping: Boolean,
    val axisLocking: Boolean,
    val shapeDelay: Float,
    val palmRejection: Boolean,
    val twoFingerTapAction: TwoFingerTapAction,
    val stylusButtonAction: StylusButtonAction,
)

data class InterfaceSettingsState(
    val collapsibleToolbar: Boolean,
    val collapseTimeout: Float,
)

@Composable
fun InputSettingsPanel(
    state: InputSettingsState,
    onScribbleChange: (Boolean) -> Unit,
    onPalmRejectionChange: (Boolean) -> Unit,
    onShapeChange: (Boolean) -> Unit,
    onAngleChange: (Boolean) -> Unit,
    onAxisChange: (Boolean) -> Unit,
    onShapeDelayChange: (Float) -> Unit,
    onShapeDelayFinished: () -> Unit,
    onTwoFingerTapChange: (TwoFingerTapAction) -> Unit,
    onStylusButtonChange: (StylusButtonAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggle(
            title = "Scribble to Erase",
            checked = state.scribbleEnabled,
            onCheckedChange = onScribbleChange,
        )
        HorizontalDivider()

        SettingsToggle(
            title = "Palm Rejection (stylus only)",
            checked = state.palmRejection,
            onCheckedChange = onPalmRejectionChange,
        )
        HorizontalDivider()

        SettingsToggle(
            title = "Shape Perfection",
            checked = state.shapeEnabled,
            onCheckedChange = onShapeChange,
        )

        if (state.shapeEnabled) {
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text = "Hold stylus (${state.shapeDelay.toLong()} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.shapeDelay,
                    onValueChange = onShapeDelayChange,
                    onValueChangeFinished = onShapeDelayFinished,
                    valueRange = 100f..1500f,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        }

        HorizontalDivider()
        SettingsToggle(
            title = "Angle Snapping",
            checked = state.angleSnapping,
            onCheckedChange = onAngleChange,
        )
        SettingsToggle(
            title = "Axis Locking",
            checked = state.axisLocking,
            onCheckedChange = onAxisChange,
        )

        HorizontalDivider()
        SettingsRadioGroup(
            title = "Two-finger tap",
            options =
                listOf(
                    TwoFingerTapAction.UNDO to "Undo",
                    TwoFingerTapAction.REDO to "Redo",
                    TwoFingerTapAction.PASTE to "Paste",
                    TwoFingerTapAction.NONE to "None",
                ),
            selected = state.twoFingerTapAction,
            onSelect = onTwoFingerTapChange,
        )

        HorizontalDivider()
        SettingsRadioGroup(
            title = "Stylus side button",
            options =
                listOf(
                    StylusButtonAction.TEMPORARY_ERASER to "Temporary eraser",
                    StylusButtonAction.NONE to "Off",
                ),
            selected = state.stylusButtonAction,
            onSelect = onStylusButtonChange,
        )
    }
}

@Composable
fun InterfaceSettingsPanel(
    state: InterfaceSettingsState,
    onCollapsibleChange: (Boolean) -> Unit,
    onTimeoutChange: (Float) -> Unit,
    onTimeoutFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggle(
            title = "Collapsible Toolbar",
            checked = state.collapsibleToolbar,
            onCheckedChange = onCollapsibleChange,
        )

        if (state.collapsibleToolbar) {
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text = "Collapse after %.1fs".format(state.collapseTimeout / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.collapseTimeout,
                    onValueChange = onTimeoutChange,
                    onValueChangeFinished = onTimeoutFinished,
                    valueRange = 1000f..10000f,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        }
    }
}

@Composable
fun SettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun <T> SettingsRadioGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        options.forEach { (value, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
                Text(label)
            }
        }
    }
}

data class PdfSettingsState(
    val exportScale: Float,
    val syncPdfType: String = "VECTOR",
)

@Composable
fun PdfSettingsPanel(
    state: PdfSettingsState,
    onScaleChange: (Float) -> Unit,
    onScaleFinished: () -> Unit,
    onSyncTypeChange: (String) -> Unit = {},
    showSyncSettings: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "PDF Export Scale: %.1fx".format(state.exportScale),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Higher scale improves quality but increases file size.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = state.exportScale,
            onValueChange = onScaleChange,
            onValueChangeFinished = onScaleFinished,
            valueRange = 1.0f..4.0f,
            steps = 5, // 0.5 increments
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
        )

        if (showSyncSettings) {
            HorizontalDivider()
            Text(
                text = "Sync PDF Type",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clickable { onSyncTypeChange("VECTOR") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.syncPdfType == "VECTOR",
                        onClick = { onSyncTypeChange("VECTOR") },
                    )
                    Text("Vector")
                }
                Spacer(Modifier.width(16.dp))
                Row(
                    Modifier.clickable { onSyncTypeChange("BITMAP") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.syncPdfType == "BITMAP",
                        onClick = { onSyncTypeChange("BITMAP") },
                    )
                    Text("Bitmap")
                }
            }
            Text(
                text = if (state.syncPdfType == "VECTOR") "Smaller size, infinite zoom." else "Better for complex brushes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
