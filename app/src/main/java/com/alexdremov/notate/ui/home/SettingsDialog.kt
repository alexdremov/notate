package com.alexdremov.notate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdremov.notate.BuildConfig
import com.alexdremov.notate.R
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.ui.settings.InputSettingsPanel
import com.alexdremov.notate.ui.settings.InputSettingsState
import com.alexdremov.notate.ui.settings.InterfaceSettingsPanel
import com.alexdremov.notate.ui.settings.InterfaceSettingsState

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onOpenSync: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }
    val context = LocalContext.current

    AlertDialog(
        containerColor = Color.White,
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)), // Keep border for E-Ink visibility
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentScreen != SettingsScreen.MAIN) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        modifier =
                            Modifier
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(end = 8.dp),
                    )
                }
                Text(
                    text =
                        when (currentScreen) {
                            SettingsScreen.MAIN -> "Settings"
                            SettingsScreen.INPUT -> "Input & Gestures"
                            SettingsScreen.INTERFACE -> "Interface"
                            SettingsScreen.ABOUT -> "About"
                        },
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                when (currentScreen) {
                    SettingsScreen.MAIN -> {
                        SettingsMenuItem(
                            title = "Synchronization",
                            subtitle = "Cloud storage and sync options",
                            icon = Icons.Default.CloudSync,
                            onClick = onOpenSync,
                        )
                        SettingsMenuItem(
                            title = "Input & Gestures",
                            subtitle = "Pen behavior, shapes, and eraser",
                            icon = Icons.Default.Brush,
                            onClick = { currentScreen = SettingsScreen.INPUT },
                        )
                        SettingsMenuItem(
                            title = "Interface",
                            subtitle = "Toolbar and visual settings",
                            icon = Icons.Default.ViewQuilt,
                            onClick = { currentScreen = SettingsScreen.INTERFACE },
                        )
                        SettingsMenuItem(
                            title = "About",
                            subtitle = "Version and app info",
                            icon = Icons.Default.Info,
                            onClick = { currentScreen = SettingsScreen.ABOUT },
                        )
                    }

                    SettingsScreen.INPUT -> {
                        var scribbleEnabled by remember { mutableStateOf(PreferencesManager.isScribbleToEraseEnabled(context)) }
                        var shapeEnabled by remember { mutableStateOf(PreferencesManager.isShapePerfectionEnabled(context)) }
                        var angleSnapping by remember { mutableStateOf(PreferencesManager.isAngleSnappingEnabled(context)) }
                        var axisLocking by remember { mutableStateOf(PreferencesManager.isAxisLockingEnabled(context)) }
                        var shapeDelay by remember { mutableFloatStateOf(PreferencesManager.getShapePerfectionDelay(context).toFloat()) }

                        InputSettingsPanel(
                            state = InputSettingsState(scribbleEnabled, shapeEnabled, angleSnapping, axisLocking, shapeDelay),
                            onScribbleChange = {
                                scribbleEnabled = it
                                PreferencesManager.setScribbleToEraseEnabled(context, it)
                            },
                            onShapeChange = {
                                shapeEnabled = it
                                PreferencesManager.setShapePerfectionEnabled(context, it)
                            },
                            onAngleChange = {
                                angleSnapping = it
                                PreferencesManager.setAngleSnappingEnabled(context, it)
                            },
                            onAxisChange = {
                                axisLocking = it
                                PreferencesManager.setAxisLockingEnabled(context, it)
                            },
                            onShapeDelayChange = { shapeDelay = it },
                            onShapeDelayFinished = {
                                PreferencesManager.setShapePerfectionDelay(context, shapeDelay.toLong())
                            },
                        )
                    }

                    SettingsScreen.INTERFACE -> {
                        var collapsibleToolbar by remember { mutableStateOf(PreferencesManager.isCollapsibleToolbarEnabled(context)) }
                        var collapseTimeout by remember {
                            mutableFloatStateOf(
                                PreferencesManager.getToolbarCollapseTimeout(context).toFloat(),
                            )
                        }

                        InterfaceSettingsPanel(
                            state = InterfaceSettingsState(collapsibleToolbar, collapseTimeout),
                            onCollapsibleChange = {
                                collapsibleToolbar = it
                                PreferencesManager.setCollapsibleToolbarEnabled(context, it)
                            },
                            onTimeoutChange = { collapseTimeout = it },
                            onTimeoutFinished = {
                                PreferencesManager.setToolbarCollapseTimeout(context, collapseTimeout.toLong())
                            },
                        )
                    }

                    SettingsScreen.ABOUT -> {
                        AboutSettings()
                    }
                }
            }
        },
    )
}

enum class SettingsScreen {
    MAIN,
    INPUT,
    INTERFACE,
    ABOUT,
}

@Composable
private fun SettingsMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.Gray,
        )
    }
}

@Composable
private fun AboutSettings() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Designed for Onyx Boox devices.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
