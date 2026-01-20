package com.alexdremov.notate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class InputSettingsState(
    val scribbleEnabled: Boolean,
    val shapeEnabled: Boolean,
    val angleSnapping: Boolean,
    val axisLocking: Boolean,
    val shapeDelay: Float,
)

data class InterfaceSettingsState(
    val collapsibleToolbar: Boolean,
    val collapseTimeout: Float,
)

@Composable
fun InputSettingsPanel(
    state: InputSettingsState,
    onScribbleChange: (Boolean) -> Unit,
    onShapeChange: (Boolean) -> Unit,
    onAngleChange: (Boolean) -> Unit,
    onAxisChange: (Boolean) -> Unit,
    onShapeDelayChange: (Float) -> Unit,
    onShapeDelayFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggle(
            title = "Scribble to Erase",
            checked = state.scribbleEnabled,
            onCheckedChange = onScribbleChange,
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
                    color = Color.Gray,
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
