package com.alexdremov.notate.ui.toolbar

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.alexdremov.notate.R
import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.model.*
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.ui.navigation.CompactPageNavigation
import com.alexdremov.notate.vm.DrawingViewModel
import java.util.Collections
import kotlin.math.roundToInt

@Composable
fun MainToolbar(
    viewModel: DrawingViewModel,
    isHorizontal: Boolean,
    canvasController: CanvasController?,
    canvasModel: InfiniteCanvasModel?,
    onToolClick: (ToolbarItem, Rect) -> Unit,
    onActionClick: (ActionType) -> Unit,
    onOpenSidebar: () -> Unit,
) {
    val items by viewModel.toolbarItems.collectAsState()
    val activeToolId by viewModel.activeToolId.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()
    val isFixedPageMode by viewModel.isFixedPageMode.collectAsState()

    // --- State ---
    // Filter items based on mode
    val effectiveItems =
        remember(items, isFixedPageMode) {
            if (isFixedPageMode) {
                items
            } else {
                items.filter { !(it is ToolbarItem.Widget && it.widgetType == WidgetType.PAGE_NAVIGATION) }
            }
        }

    var localItems by remember(effectiveItems) { mutableStateOf(effectiveItems) }

    // Drag State
    var draggingItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // Layout State: Map index -> Center Offset (relative to the container Box)
    val slotCenters = remember { mutableMapOf<Int, Offset>() }

    // Sync when not dragging
    LaunchedEffect(effectiveItems, draggingItem) {
        if (draggingItem == null) {
            localItems = effectiveItems
        }
    }

    // --- Main Container ---
    Box(
        modifier =
            Modifier
                .wrapContentSize() // Wrap content to allow DraggableLinearLayout to size correctly
                .padding(0.dp),
    ) {
        val density = LocalDensity.current

        // We render items in a layout (Row/Column).
        // To allow z-index reordering without changing the underlying tree order (which kills state),
        // we use a Box and manual offsets for the Ghost, OR we stick to the "Swap List" approach
        // which forces recomposition but is robust.

        // Let's stick to the "Swap List" approach but with better coordinate handling.

        val layoutModifier =
            if (isHorizontal) {
                Modifier
                    .wrapContentSize()
                    .padding(2.dp)
            } else {
                Modifier
                    .wrapContentSize()
                    .padding(2.dp)
            }

        // Layout Container
        if (isHorizontal) {
            Row(
                modifier = layoutModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DraggableItems(
                    items = localItems,
                    draggingItem = draggingItem,
                    isEditMode = isEditMode,
                    activeToolId = activeToolId,
                    canvasController = canvasController,
                    canvasModel = canvasModel,
                    isHorizontal = true,
                    onSlotPositioned = { index, center -> slotCenters[index] = center },
                    onDragStart = { item ->
                        draggingItem = item
                        dragOffset = Offset.Zero
                    },
                    onDrag = { delta ->
                        dragOffset += delta

                        // Check for swaps
                        // We need to know where the dragging item IS relative to the container.
                        // We assume the drag started at the center of the item's slot.
                        // So Current Position = SlotCenter[originalIndex] + dragOffset

                        val originalIndex = localItems.indexOf(draggingItem)
                        if (originalIndex != -1) {
                            val originalCenter = slotCenters[originalIndex] ?: Offset.Zero
                            val currentCenter = originalCenter + dragOffset

                            // Find closest slot
                            var closestIndex = originalIndex
                            var minDistance = Float.MAX_VALUE

                            slotCenters.forEach { (index, center) ->
                                val dist = (center - currentCenter).getDistance()
                                if (dist < minDistance) {
                                    minDistance = dist
                                    closestIndex = index
                                }
                            }

                            // Trigger Swap
                            if (closestIndex != originalIndex) {
                                val newList = localItems.toMutableList()
                                Collections.swap(newList, originalIndex, closestIndex)
                                localItems = newList

                                // Adjust dragOffset so the item doesn't jump visualy
                                // New Center is slotCenters[closestIndex] (approx, assuming layout updated)
                                // We want Current Position to remain same.
                                // Current = OldCenter + OldDragOffset
                                // Current = NewCenter + NewDragOffset
                                // NewDragOffset = OldCenter + OldDragOffset - NewCenter

                                val oldSlotCenter = slotCenters[originalIndex] ?: Offset.Zero
                                val newSlotCenter = slotCenters[closestIndex] ?: Offset.Zero
                                dragOffset = (oldSlotCenter + dragOffset) - newSlotCenter
                            }
                        }
                    },
                    onDragEnd = {
                        viewModel.setToolbarItems(localItems)
                        draggingItem = null
                        dragOffset = Offset.Zero
                    },
                    onToolClick = onToolClick,
                    onActionClick = onActionClick,
                    onRemove = { viewModel.removeToolbarItem(it) },
                )

                SettingsButton(onClick = onOpenSidebar)
            }
        } else {
            Column(
                modifier = layoutModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DraggableItems(
                    items = localItems,
                    draggingItem = draggingItem,
                    isEditMode = isEditMode,
                    activeToolId = activeToolId,
                    canvasController = canvasController,
                    canvasModel = canvasModel,
                    isHorizontal = false,
                    onSlotPositioned = { index, center -> slotCenters[index] = center },
                    onDragStart = { item ->
                        draggingItem = item
                        dragOffset = Offset.Zero
                    },
                    onDrag = { delta ->
                        dragOffset += delta

                        val originalIndex = localItems.indexOf(draggingItem)
                        if (originalIndex != -1) {
                            val originalCenter = slotCenters[originalIndex] ?: Offset.Zero
                            val currentCenter = originalCenter + dragOffset

                            var closestIndex = originalIndex
                            var minDistance = Float.MAX_VALUE

                            slotCenters.forEach { (index, center) ->
                                val dist = (center - currentCenter).getDistance()
                                if (dist < minDistance) {
                                    minDistance = dist
                                    closestIndex = index
                                }
                            }

                            if (closestIndex != originalIndex) {
                                val newList = localItems.toMutableList()
                                Collections.swap(newList, originalIndex, closestIndex)
                                localItems = newList

                                val oldSlotCenter = slotCenters[originalIndex] ?: Offset.Zero
                                val newSlotCenter = slotCenters[closestIndex] ?: Offset.Zero
                                dragOffset = (oldSlotCenter + dragOffset) - newSlotCenter
                            }
                        }
                    },
                    onDragEnd = {
                        viewModel.setToolbarItems(localItems)
                        draggingItem = null
                        dragOffset = Offset.Zero
                    },
                    onToolClick = onToolClick,
                    onActionClick = onActionClick,
                    onRemove = { viewModel.removeToolbarItem(it) },
                )

                SettingsButton(onClick = onOpenSidebar)
            }
        }

        // Edit Mode Popup
        if (isEditMode) {
            Popup(
                popupPositionProvider = SmartToolbarPopupPositionProvider(isHorizontal),
                onDismissRequest = { /* No-op */ },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = false),
            ) {
                ToolbarEditPanel(viewModel = viewModel, onDone = { viewModel.setEditMode(false) })
            }
        }
    }
}

@Composable
fun DraggableItems(
    items: List<ToolbarItem>,
    draggingItem: ToolbarItem?,
    isEditMode: Boolean,
    activeToolId: String,
    canvasController: CanvasController?,
    canvasModel: InfiniteCanvasModel?,
    isHorizontal: Boolean,
    onSlotPositioned: (Int, Offset) -> Unit,
    onDragStart: (ToolbarItem) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onToolClick: (ToolbarItem, Rect) -> Unit,
    onActionClick: (ActionType) -> Unit,
    onRemove: (ToolbarItem) -> Unit,
) {
    items.forEachIndexed { index, item ->
        key(item.id) {
            val isDragging = item.id == draggingItem?.id

            // The Item Wrapper
            Box(
                modifier =
                    Modifier
                        .zIndex(if (isDragging) 100f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationX = if (isHorizontal) 0f else 0f // Handled by offset? No, let's use translation
                                // We use `offset` modifier for dragging usually, or translation.
                                // But here we need to offset RELATIVE to the slot.
                            }
                        }.onGloballyPositioned { coordinates ->
                            // Capture center relative to Parent (Row/Column)
                            // parentCoordinates are tricky. Let's just use `positionInParent`.
                            // Wait, `positionInParent` is relative to the direct parent (Row). That's perfect!

                            val parentPos = coordinates.positionInParent()
                            val size = coordinates.size
                            val center =
                                Offset(
                                    parentPos.x + size.width / 2f,
                                    parentPos.y + size.height / 2f,
                                )
                            onSlotPositioned(index, center)
                        },
            ) {
                // Render the actual item with offset if dragging
                ToolbarItemWrapper(
                    item = item,
                    index = index,
                    isActive = item.id == activeToolId,
                    isEditMode = isEditMode,
                    rotation = 0f,
                    canvasController = canvasController,
                    canvasModel = canvasModel,
                    isHorizontal = isHorizontal,
                    onClick = { rect ->
                        if (item is ToolbarItem.Action) {
                            onActionClick(item.actionType)
                        } else {
                            onToolClick(item, rect)
                        }
                    },
                    onRemove = { onRemove(item) },
                    modifier =
                        if (isEditMode) {
                            Modifier
                                .offset {
                                    if (isDragging) {
                                        // Use the shared dragOffset
                                        // We need to pass dragOffset into this composable or access it.
                                        // But `DraggableItems` doesn't know `dragOffset`.
                                        // Let's pass `dragOffset` in? Or better:
                                        // Use translation in graphicsLayer which is efficient.
                                        // We can't access `dragOffset` here easily without passing it down.
                                        IntOffset.Zero // Placeholder, fixed below
                                    } else {
                                        IntOffset.Zero
                                    }
                                }.pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { onDragStart(item) },
                                        onDragEnd = { onDragEnd() },
                                        onDragCancel = { onDragEnd() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        onDrag(dragAmount)
                                    }
                                }
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@Composable
fun ToolbarItemWrapper(
    item: ToolbarItem,
    index: Int,
    isActive: Boolean,
    isEditMode: Boolean,
    rotation: Float,
    canvasController: CanvasController?,
    canvasModel: InfiniteCanvasModel?,
    isHorizontal: Boolean,
    onClick: (Rect) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isGhost: Boolean = false,
) {
    var itemBounds by remember { mutableStateOf<Rect?>(null) }
    val rootView = LocalView.current

    Box(
        modifier =
            modifier
                .rotate(rotation)
                .onGloballyPositioned { coordinates ->
                    // Robust calculation for absolute screen coordinates
                    // We use positionInWindow() which is relative to the Activity's window
                    // And add the window's screen location to handle cases where it's not at (0,0)
                    // (though for immersive it usually is).
                    val posInWindow = coordinates.positionInWindow()
                    val size = coordinates.size

                    val rootLocation = IntArray(2)
                    rootView.getLocationOnScreen(rootLocation)

                    val absLeft = posInWindow.x + rootLocation[0]
                    val absTop = posInWindow.y + rootLocation[1]

                    itemBounds =
                        Rect(
                            absLeft,
                            absTop,
                            absLeft + size.width,
                            absTop + size.height,
                        )
                    android.util.Log.d("BooxVibesDebug", "ToolbarView: Item Positioned: $itemBounds")
                },
        contentAlignment = Alignment.Center,
    ) {
        if (item is ToolbarItem.Widget && item.widgetType == WidgetType.PAGE_NAVIGATION) {
            // Render Widget directly
            if (canvasController != null && canvasModel != null) {
                // Disable interaction in edit mode
                Box(modifier = Modifier.clickable(enabled = !isEditMode) { /* absorb clicks */ }) {
                    CompactPageNavigation(
                        controller = canvasController,
                        model = canvasModel,
                        isVertical = !isHorizontal,
                    )
                }
            } else {
                // Fallback icon
                Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = "Page Nav")
            }
        } else {
            // Standard Icon Item
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive && !isEditMode) Color.LightGray.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable(enabled = !isEditMode) {
                            android.util.Log.d("BooxVibesDebug", "ToolbarView: Item Clicked! ID=${item.id}, Bounds=$itemBounds")
                            itemBounds?.let { onClick(it) }
                        },
                contentAlignment = Alignment.Center,
            ) {
                RenderToolbarItemIcon(item)

                if (item is ToolbarItem.Pen) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .align(Alignment.BottomEnd)
                                .offset(x = (-4).dp, y = (-4).dp)
                                .background(Color(item.penTool.color), CircleShape),
                    )
                }
            }
        }

        // Remove Badge
        if (isEditMode) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-8).dp, y = (-8).dp)
                        .size(32.dp) // Touch area
                        .zIndex(10f)
                        .clickable {
                            android.util.Log.d("BooxVibesDebug", "ToolbarView: Remove CLICKED for item $index")
                            onRemove()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(20.dp),
                    shape = CircleShape,
                    color = Color.Gray,
                    tonalElevation = 4.dp,
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = "Settings",
            tint = Color.Black,
        )
    }
}

@Composable
fun RenderToolbarItemIcon(item: ToolbarItem) {
    when (item) {
        is ToolbarItem.Pen -> {
            val resId =
                when (item.penTool.strokeType) {
                    StrokeType.BALLPOINT -> R.drawable.stylus_ballpoint_24
                    StrokeType.FINELINER -> R.drawable.stylus_fineliner_24
                    StrokeType.HIGHLIGHTER -> R.drawable.stylus_highlighter_24
                    StrokeType.BRUSH -> R.drawable.stylus_brush_24
                    StrokeType.CHARCOAL -> R.drawable.stylus_pen_24
                    StrokeType.DASH -> R.drawable.stylus_dash_24
                    else -> R.drawable.stylus_fountain_pen_24
                }
            Icon(painter = painterResource(resId), contentDescription = item.penTool.name, tint = Color.Black)
        }

        is ToolbarItem.Eraser -> {
            Icon(painter = painterResource(R.drawable.ink_eraser_24), contentDescription = "Eraser", tint = Color.Black)
        }

        is ToolbarItem.Select -> {
            Icon(painter = painterResource(R.drawable.ic_tool_select), contentDescription = "Select", tint = Color.Black)
        }

        is ToolbarItem.Action -> {
            val iconRes =
                when (item.actionType) {
                    ActionType.UNDO -> R.drawable.ic_undo
                    ActionType.REDO -> R.drawable.ic_redo
                }
            Icon(painter = painterResource(iconRes), contentDescription = item.actionType.name, tint = Color.Black)
        }

        is ToolbarItem.Widget -> {
            when (item.widgetType) {
                WidgetType.PAGE_NAVIGATION -> {
                    Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = "Page Nav", tint = Color.Black)
                }
            }
        }
    }
}

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
