package com.alexdremov.notate.ui.home.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.FileSystemItem
import com.alexdremov.notate.data.ProjectItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: FileSystemItem,
    isSyncing: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Thumbnail / Icon Area
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F7)),
                // Light Apple-like Gray
                contentAlignment = Alignment.Center,
            ) {
                if (item is CanvasItem && !item.thumbnail.isNullOrEmpty()) {
                    val bitmap =
                        remember(item.thumbnail) {
                            try {
                                val decoded = Base64.decode(item.thumbnail, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit, // Contain within box
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    } else {
                        // Fallback icon
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                } else {
                    // Folder or No Thumbnail
                    Icon(
                        imageVector = if (item is ProjectItem) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        tint = if (item is ProjectItem) Color(0xFF5AC8FA) else Color.Gray, // Apple-like Blue for folders
                        modifier = Modifier.size(56.dp),
                    )
                }

                if (isSyncing) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                            ),
                    )

                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(24.dp)
                                .background(Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
                                .padding(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Syncing",
                            tint = Color(0xFF007AFF),
                            modifier = Modifier.fillMaxSize().rotate(angle),
                        )
                    }
                }
            }

            // Text Area
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black,
                )

                val date =
                    remember(item.lastModified) {
                        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.lastModified))
                    }

                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    maxLines = 1,
                )
            }
        }
    }
}
