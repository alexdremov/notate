package com.alexdremov.notate.ui.home

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
import com.alexdremov.notate.data.ProjectConfig
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.ui.home.components.DeleteConfirmationDialog
import com.alexdremov.notate.ui.home.components.EmptyState
import com.alexdremov.notate.ui.home.components.FileGridItem

/**
 * Displays the list of configured projects (Level 0 navigation).
 * Updated to use Grid Layout and clean style.
 */
@Composable
fun ProjectListScreen(
    projects: List<ProjectConfig>,
    onOpenProject: (ProjectConfig) -> Unit,
    onDeleteProject: (ProjectConfig) -> Unit,
    onRenameProject: (ProjectConfig, String) -> Unit,
) {
    var projectToDelete by remember { mutableStateOf<ProjectConfig?>(null) }
    var projectToManage by remember { mutableStateOf<ProjectConfig?>(null) }
    var projectToRename by remember { mutableStateOf<ProjectConfig?>(null) }

    if (projects.isEmpty()) {
        EmptyState("No projects yet.\nTap + to add one.")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(projects) { project ->
                // Map ProjectConfig to ProjectItem for the shared component
                val item =
                    ProjectItem(
                        name = project.name,
                        path = project.uri,
                        lastModified = 0L, // Metadata not tracked for root projects
                        itemsCount = 0,
                    )

                FileGridItem(
                    item = item,
                    onClick = { onOpenProject(project) },
                    onLongClick = { projectToManage = project },
                )
            }
        }
    }

    // Context Menu
    if (projectToManage != null) {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { projectToManage = null },
            title = { Text("Actions for \"${projectToManage!!.name}\"") },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { projectToManage = null }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            projectToRename = projectToManage
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            projectToDelete = projectToManage
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove Project", color = Color.Red)
                    }
                }
            },
        )
    }

    // Rename Dialog
    if (projectToRename != null) {
        TextInputDialog(
            title = "Rename Project",
            initialValue = projectToRename!!.name,
            confirmText = "Rename",
            onDismiss = { projectToRename = null },
            onConfirm = { newName ->
                onRenameProject(projectToRename!!, newName)
                projectToRename = null
            },
        )
    }

    // Delete Confirmation
    if (projectToDelete != null) {
        DeleteConfirmationDialog(
            itemName = projectToDelete!!.name,
            onDismiss = { projectToDelete = null },
            onConfirm = {
                onDeleteProject(projectToDelete!!)
                projectToDelete = null
            },
        )
    }
}
