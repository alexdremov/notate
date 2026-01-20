@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alexdremov.notate.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.data.ProjectConfig
import com.alexdremov.notate.model.Tag

@Composable
fun Sidebar(
    projects: List<ProjectConfig>,
    tags: List<Tag>,
    selectedProject: ProjectConfig?,
    selectedTag: Tag?,
    onProjectSelected: (ProjectConfig) -> Unit,
    onProjectLongClick: (ProjectConfig) -> Unit,
    onTagSelected: (Tag) -> Unit,
    onAddProject: () -> Unit,
    onManageTags: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(280.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), // Finder-like gray
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = 16.dp, horizontal = 12.dp),
        ) {
            // Projects Section
            SidebarHeader(
                title = "Projects",
                actionIcon = Icons.Default.Add,
                onActionClick = onAddProject,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (projects.isEmpty()) {
                    item {
                        Text(
                            text = "No projects",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        )
                    }
                }
                items(projects) { project ->
                    SidebarItem(
                        text = project.name,
                        icon = Icons.Default.Folder,
                        isSelected = selectedProject?.id == project.id,
                        onClick = { onProjectSelected(project) },
                        onLongClick = { onProjectLongClick(project) },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    SidebarHeader(
                        title = "Tags",
                        actionIcon = Icons.Default.Add,
                        onActionClick = onManageTags,
                    )
                }

                if (tags.isEmpty()) {
                    item {
                        Text(
                            text = "No tags",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        )
                    }
                }

                items(tags) { tag ->
                    SidebarItem(
                        text = tag.name,
                        icon = Icons.Default.Circle,
                        iconTint = Color(tag.color),
                        isSelected = selectedTag?.id == tag.id,
                        onClick = { onTagSelected(tag) },
                    )
                }
            }

            // Bottom Settings Area
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SidebarItem(
                text = "Settings",
                icon = Icons.Default.Settings,
                isSelected = false,
                onClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun SidebarHeader(
    title: String,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
        if (actionIcon != null && onActionClick != null) {
            Icon(
                imageVector = actionIcon,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(16.dp)
                        .clickable(onClick = onActionClick),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val backgroundColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }

    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (icon == Icons.Default.Circle) iconTint else contentColor,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
