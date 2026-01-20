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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.model.Tag

@Composable
fun ManageTagsDialog(
    tags: List<Tag>,
    onAddTag: (String, Int) -> Unit,
    onUpdateTag: (Tag) -> Unit,
    onRemoveTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var isCreating by remember { mutableStateOf(false) }

    if (editingTag != null || isCreating) {
        EditTagDialog(
            initialName = editingTag?.name ?: "",
            initialColor = editingTag?.color ?: Color.Gray.toArgb(),
            title = if (isCreating) "New Tag" else "Edit Tag",
            onDismiss = {
                editingTag = null
                isCreating = false
            },
            onConfirm = { name, color ->
                if (isCreating) {
                    onAddTag(name, color)
                } else {
                    onUpdateTag(editingTag!!.copy(name = name, color = color))
                }
                editingTag = null
                isCreating = false
            },
        )
    } else {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = onDismiss,
            title = { Text("Manage Tags") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { isCreating = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("New Tag")
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(300.dp),
                    ) {
                        items(tags) { tag ->
                            TagListItem(
                                tag = tag,
                                isFirst = tag == tags.firstOrNull(),
                                isLast = tag == tags.lastOrNull(),
                                onEdit = { editingTag = tag },
                                onDelete = { onRemoveTag(tag.id) },
                                onMoveUp = {
                                    val index = tags.indexOf(tag)
                                    if (index > 0) {
                                        val mutableTags = tags.toMutableList()
                                        val movedTag = mutableTags.removeAt(index)
                                        mutableTags.add(index - 1, movedTag)
                                        mutableTags.forEachIndexed { newIndex, t ->
                                            onUpdateTag(t.copy(order = newIndex))
                                        }
                                    }
                                },
                                onMoveDown = {
                                    val index = tags.indexOf(tag)
                                    if (index < tags.size - 1 && index >= 0) {
                                        val mutableTags = tags.toMutableList()
                                        val movedTag = mutableTags.removeAt(index)
                                        mutableTags.add(index + 1, movedTag)
                                        mutableTags.forEachIndexed { newIndex, t ->
                                            onUpdateTag(t.copy(order = newIndex))
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            },
        )
    }
}

@Composable
fun EditTagDialog(
    initialName: String,
    initialColor: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    val presetColors =
        listOf(
            Color(0xFFE53935), // Red
            Color(0xFFD81B60), // Pink
            Color(0xFF8E24AA), // Purple
            Color(0xFF3949AB), // Indigo
            Color(0xFF1E88E5), // Blue
            Color(0xFF039BE5), // Light Blue
            Color(0xFF00ACC1), // Cyan
            Color(0xFF00897B), // Teal
            Color(0xFF43A047), // Green
            Color(0xFF7CB342), // Light Green
            Color(0xFFC0CA33), // Lime
            Color(0xFFFDD835), // Yellow
            Color(0xFFFFB300), // Amber
            Color(0xFFFB8C00), // Orange
            Color(0xFFF4511E), // Deep Orange
            Color(0xFF6D4C41), // Brown
            Color(0xFF757575), // Grey
            Color(0xFF546E7A), // Blue Grey
        )

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tag Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Text("Color", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(presetColors) { color ->
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedColor == color.toArgb()) 2.dp else 0.dp,
                                        color = Color.Black,
                                        shape = CircleShape,
                                    ).clickable { selectedColor = color.toArgb() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selectedColor == color.toArgb()) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, selectedColor)
                    }
                },
            ) {
                Text("Save")
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
fun TagListItem(
    tag: Tag,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color)),
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
        }

        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
        }

        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit")
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
        }
    }
}
