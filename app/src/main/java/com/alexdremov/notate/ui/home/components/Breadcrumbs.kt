package com.alexdremov.notate.ui.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.model.BreadcrumbItem

@Composable
fun Breadcrumbs(
    items: List<BreadcrumbItem>,
    onItemClick: (BreadcrumbItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to end when items change
    LaunchedEffect(items) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier =
            modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.lastIndex

            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .clickable(enabled = !isLast) { onItemClick(item) }
                        .padding(4.dp),
            )

            if (!isLast) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}
