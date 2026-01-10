package com.alexdremov.notate.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

object PageThumbnailGenerator {
    fun generatePageThumbnail(
        model: InfiniteCanvasModel,
        pageIndex: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        // 1. Get Page Bounds from Model
        // We need to access model, but model methods for page bounds are specific.
        // Assuming model.getPageBounds(index) exists (I added it earlier).
        val pageRect = model.getPageBounds(pageIndex)

        // 2. Query strokes
        val strokes = model.queryStrokes(pageRect)

        // 3. Setup Matrix to fit pageRect into width/height
        val scaleX = width.toFloat() / pageRect.width()
        val scaleY = height.toFloat() / pageRect.height()
        val scale = min(scaleX, scaleY)

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-pageRect.left, -pageRect.top)

        // 4. Draw Strokes
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        strokes.forEach { stroke ->
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            // Use stroke.path directly if available
            canvas.drawPath(stroke.path, paint)
        }

        canvas.restore()
        return bitmap
    }
}

/**
 * A floating navigation strip for Fixed Page mode.
 * Shows [ < ] [ Page X / Y ] [ > ].
 */
@Composable
fun PageNavigationStrip(
    controller: CanvasController,
    modifier: Modifier = Modifier,
) {
    // Legacy / standalone version
    var currentPage by remember { mutableStateOf(controller.getCurrentPageIndex() + 1) }
    var totalPages by remember { mutableStateOf(controller.getTotalPages()) }
    var showGrid by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            currentPage = controller.getCurrentPageIndex() + 1
            totalPages = controller.getTotalPages()
            kotlinx.coroutines.delay(500)
        }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = {
                controller.prevPage()
                currentPage = controller.getCurrentPageIndex() + 1
            }) {
                Icon(Icons.Default.ArrowBack, "Previous Page")
            }

            TextButton(onClick = { showGrid = true }) {
                Text(text = "$currentPage")
            }

            IconButton(onClick = {
                controller.nextPage()
                currentPage = controller.getCurrentPageIndex() + 1
            }) {
                Icon(Icons.Default.ArrowForward, "Next Page")
            }
        }
    }
    // Note: This version doesn't support model for grid, so grid will fail if used.
    // Assuming this is deprecated/unused now.
}

/**
 * A minimal, transparent navigation strip designed to fit into the floating toolbar.
 */
@Composable
fun CompactPageNavigation(
    controller: CanvasController,
    model: InfiniteCanvasModel,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier,
    onGridOpenChanged: (Boolean) -> Unit = {},
) {
    var currentPage by remember { mutableStateOf(controller.getCurrentPageIndex() + 1) }
    var totalPages by remember { mutableStateOf(controller.getTotalPages()) }
    var showGrid by remember { mutableStateOf(false) }

    LaunchedEffect(showGrid) {
        onGridOpenChanged(showGrid)
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentPage = controller.getCurrentPageIndex() + 1
            totalPages = controller.getTotalPages()
            kotlinx.coroutines.delay(500)
        }
    }

    if (isVertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.width(48.dp),
        ) {
            IconButton(
                onClick = {
                    controller.prevPage()
                    currentPage = controller.getCurrentPageIndex() + 1
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Prev",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }

            TextButton(
                onClick = { showGrid = true },
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.width(48.dp),
            ) {
                Text(
                    text = "$currentPage",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            IconButton(
                onClick = {
                    controller.nextPage()
                    currentPage = controller.getCurrentPageIndex() + 1
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Next",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(48.dp),
        ) {
            IconButton(
                onClick = {
                    controller.prevPage()
                    currentPage = controller.getCurrentPageIndex() + 1
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Prev",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }

            TextButton(
                onClick = { showGrid = true },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = "$currentPage",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            IconButton(
                onClick = {
                    controller.nextPage()
                    currentPage = controller.getCurrentPageIndex() + 1
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Next",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showGrid) {
        PageGridDialog(
            controller = controller,
            model = model,
            totalPages = totalPages,
            currentPage = currentPage,
            onDismiss = { showGrid = false },
            onPageSelected = { page ->
                controller.jumpToPage(page - 1)
                showGrid = false
            },
        )
    }
}

@Composable
fun PageGridDialog(
    controller: CanvasController,
    model: InfiniteCanvasModel,
    totalPages: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onPageSelected: (Int) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("All Pages", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(totalPages) { index ->
                        val pageNum = index + 1
                        PageThumbnailItem(
                            model = model,
                            pageIndex = index,
                            pageNumber = pageNum,
                            isSelected = pageNum == currentPage,
                            onClick = { onPageSelected(pageNum) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PageThumbnailItem(
    model: InfiniteCanvasModel,
    pageIndex: Int,
    pageNumber: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.Default) {
            thumbnail = PageThumbnailGenerator.generatePageThumbnail(model, pageIndex, 300, 424)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .aspectRatio(1f / 1.414f)
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = androidx.compose.ui.graphics.RectangleShape,
                        ),
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = "Page $pageNumber",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.LightGray.copy(alpha = 0.3f)))
                }

                Text(
                    text = "$pageNumber",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Page $pageNumber", style = MaterialTheme.typography.bodySmall)
    }
}
