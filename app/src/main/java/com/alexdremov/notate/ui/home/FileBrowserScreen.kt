@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.alexdremov.notate.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.FileSystemItem
import com.alexdremov.notate.model.BreadcrumbItem
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.ui.home.components.Breadcrumbs
import com.alexdremov.notate.ui.home.components.DeleteConfirmationDialog
import com.alexdremov.notate.ui.home.components.EmptyState
import com.alexdremov.notate.ui.home.components.FileGridItem

/**
 * Displays the file structure of a specific project (Level 1+ navigation).
 * Updated to use Grid Layout and Thumbnails.
 */
@Composable
fun FileBrowserScreen(
    items: List<FileSystemItem>,
    breadcrumbs: List<BreadcrumbItem>,
    allTags: List<Tag> = emptyList(),
    onBreadcrumbClick: (BreadcrumbItem) -> Unit,
    onItemClick: (FileSystemItem) -> Unit,
    onItemDelete: (FileSystemItem) -> Unit,
    onItemRename: (FileSystemItem, String) -> Unit,
    onItemDuplicate: (FileSystemItem) -> Unit,
    onSetFileTags: (FileSystemItem, List<String>) -> Unit = { _, _ -> },
) {
    var itemToDelete by remember { mutableStateOf<FileSystemItem?>(null) }
    var managingItemPath by remember { mutableStateOf<String?>(null) }
    var itemToRename by remember { mutableStateOf<FileSystemItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        Breadcrumbs(
            items = breadcrumbs,
            onItemClick = onBreadcrumbClick,
            modifier = Modifier.fillMaxWidth(),
        )

        if (items.isEmpty()) {
            EmptyState("Folder is empty.\nCreate a folder or canvas.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items) {
                    FileGridItem(
                        item = it,
                        onClick = { onItemClick(it) },
                        onLongClick = { managingItemPath = it.path },
                    )
                }
            }
        }
    }

    // Context Menu / Options Dialog
    val currentItem = if (managingItemPath != null) items.find { it.path == managingItemPath } else null

    if (currentItem != null) {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { managingItemPath = null },
            title = { Text("Actions for \"${currentItem.name}\"") },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { managingItemPath = null }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            itemToRename = currentItem
                            managingItemPath = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            onItemDuplicate(currentItem)
                            managingItemPath = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Duplicate", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            itemToDelete = currentItem
                            managingItemPath = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete", color = Color.Red)
                    }

                    if (currentItem is CanvasItem && allTags.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.height(16.dp))
                        Text("Tags", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            allTags.forEach { tag ->
                                val selected = currentItem.tagIds.contains(tag.id)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val newTags =
                                            if (selected) {
                                                currentItem.tagIds - tag.id
                                            } else {
                                                currentItem.tagIds + tag.id
                                            }
                                        onSetFileTags(currentItem, newTags)
                                    },
                                    label = { Text(tag.name) },
                                    colors =
                                        FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(tag.color).copy(alpha = 0.2f),
                                            selectedLabelColor = Color.Black,
                                            selectedLeadingIconColor = Color(tag.color),
                                        ),
                                    border =
                                        FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = selected,
                                            borderColor = Color(tag.color),
                                            selectedBorderColor = Color(tag.color),
                                        ),
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // Rename Dialog
    if (itemToRename != null) {
        TextInputDialog(
            title = "Rename \"${itemToRename!!.name}\"",
            initialValue = itemToRename!!.name,
            confirmText = "Rename",
            onDismiss = { itemToRename = null },
            onConfirm = { newName ->
                onItemRename(itemToRename!!, newName)
                itemToRename = null
            },
        )
    }

    // Delete Confirmation
    if (itemToDelete != null) {
        DeleteConfirmationDialog(
            itemName = itemToDelete!!.name,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                onItemDelete(itemToDelete!!)
                itemToDelete = null
            },
        )
    }
}
