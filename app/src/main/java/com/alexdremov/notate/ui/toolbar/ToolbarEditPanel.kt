package com.alexdremov.notate.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.model.*
import com.alexdremov.notate.vm.DrawingViewModel

@Composable
fun ToolbarEditPanel(
    viewModel: DrawingViewModel,
    onDone: () -> Unit,
) {
    val isFixedPageMode by viewModel.isFixedPageMode.collectAsState()

    Column(
        modifier =
            Modifier
                .width(200.dp)
                .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Edit Toolbar", style = MaterialTheme.typography.titleMedium)

        Text("Tap to Add", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Add Pen
            AddItemButton(
                item = ToolbarItem.Pen(PenTool.defaultPens()[0]), // Dummy for icon
                label = "Pen",
                onClick = { viewModel.addPen() },
            )

            // Add Eraser
            val defaultEraser = PenTool.defaultPens().find { it.type == ToolType.ERASER }!!
            AddItemButton(
                item = ToolbarItem.Eraser(defaultEraser),
                label = "Eraser",
                onClick = { viewModel.addToolbarItem(ToolbarItem.Eraser(defaultEraser)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Add Select
            val defaultSelect = PenTool.defaultPens().find { it.type == ToolType.SELECT }!!
            AddItemButton(
                item = ToolbarItem.Select(defaultSelect),
                label = "Select",
                onClick = { viewModel.addToolbarItem(ToolbarItem.Select(defaultSelect)) },
            )

            // Add Undo
            AddItemButton(
                item = ToolbarItem.Action(ActionType.UNDO),
                label = "Undo",
                onClick = { viewModel.addToolbarItem(ToolbarItem.Action(ActionType.UNDO)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Add Redo
            AddItemButton(
                item = ToolbarItem.Action(ActionType.REDO),
                label = "Redo",
                onClick = { viewModel.addToolbarItem(ToolbarItem.Action(ActionType.REDO)) },
            )

            // Add Page Nav
            if (isFixedPageMode) {
                AddItemButton(
                    item = ToolbarItem.Widget(WidgetType.PAGE_NAVIGATION),
                    label = "Nav",
                    onClick = { viewModel.addToolbarItem(ToolbarItem.Widget(WidgetType.PAGE_NAVIGATION)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}

@Composable
fun AddItemButton(
    item: ToolbarItem,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clickable { onClick() }
                .padding(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            RenderToolbarItemIcon(item)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
