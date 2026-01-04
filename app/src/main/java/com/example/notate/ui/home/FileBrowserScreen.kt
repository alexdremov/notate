package com.example.notate.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.notate.data.FileSystemItem
import com.example.notate.ui.home.components.DeleteConfirmationDialog
import com.example.notate.ui.home.components.EmptyState
import com.example.notate.ui.home.components.FileGridItem

/**
 * Displays the file structure of a specific project (Level 1+ navigation).
 * Updated to use Grid Layout and Thumbnails.
 */
@Composable
fun FileBrowserScreen(
    items: List<FileSystemItem>,
    onItemClick: (FileSystemItem) -> Unit,
    onItemDelete: (FileSystemItem) -> Unit,
    onItemRename: (FileSystemItem, String) -> Unit,
    onItemDuplicate: (FileSystemItem) -> Unit,
) {
    var itemToDelete by remember { mutableStateOf<FileSystemItem?>(null) }
    var itemToManage by remember { mutableStateOf<FileSystemItem?>(null) }
    var itemToRename by remember { mutableStateOf<FileSystemItem?>(null) }

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
                    onLongClick = { itemToManage = it },
                )
            }
        }
    }

    // Context Menu / Options Dialog
    if (itemToManage != null) {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { itemToManage = null },
            title = { Text("Actions for \"${itemToManage!!.name}\"") },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { itemToManage = null }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            itemToRename = itemToManage
                            itemToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            onItemDuplicate(itemToManage!!)
                            itemToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Duplicate", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            itemToDelete = itemToManage
                            itemToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete", color = Color.Red)
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
