package com.nextui.launcher.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nextui.launcher.data.LauncherItemEntity
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

/**
 * PinnedAppsList — drag-to-reorder home list.
 *
 * Performance notes:
 *  • All drag movement is expressed through [Modifier.graphicsLayer]
 *    translation, so reordering NEVER triggers layout re-measurement of the
 *    list — only draw-phase work.
 *  • Neighbour displacement is animated with a medium-stiffness spring while
 *    the dragged card follows the finger 1:1.
 *  • [items] is an [ImmutableList]: reorder animations don't recompose
 *    unchanged rows.
 */
@Composable
fun PinnedAppsList(
    items: ImmutableList<LauncherItemEntity>,
    onLaunch: (LauncherItemEntity) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var measuredSlotHeightPx by remember { mutableFloatStateOf(with(density) { 84.dp.toPx() }) }
    val listSpacingPx = with(density) { 8.dp.toPx() }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val draggingIndex = remember(draggingKey, items) {
        draggingKey?.let { key -> items.indexOfFirst { it.componentKey == key } } ?: -1
    }
    val targetIndex = remember(draggingIndex, dragOffsetY, items) {
        if (draggingIndex < 0) -1
        else (draggingIndex + (dragOffsetY / measuredSlotHeightPx).roundToInt())
            .coerceIn(0, items.lastIndex)
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.componentKey },
            contentType = { _, _ -> "pinned_row" }
        ) { index, item ->
            val isDragging = item.componentKey == draggingKey

            val neighbourShift = when {
                draggingIndex < 0 -> 0f
                isDragging -> 0f
                index in (minOf(draggingIndex, targetIndex)..maxOf(draggingIndex, targetIndex)) ->
                    if (targetIndex > draggingIndex) -measuredSlotHeightPx else measuredSlotHeightPx
                else -> 0f
            }

            val animatedNeighbourShift by animateFloatAsState(
                targetValue = neighbourShift,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "neighbour_shift"
            )

            val latestIndex by rememberUpdatedState(index)

            Card(
                onClick = { if (!isDragging) onLaunch(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffsetY else animatedNeighbourShift
                    }
                    .onSizeChanged { size ->
                        val h = size.height.toFloat() + listSpacingPx
                        if (h > 0f) measuredSlotHeightPx = h
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = if (isDragging) 0.65f else 0.35f
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(item = item, disabled = false)
                    Text(
                        item.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        Icons.Rounded.DragHandle, "Drag",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.pointerInput(item.componentKey) {
                            detectDragGestures(
                                onDragStart = { draggingKey = item.componentKey; dragOffsetY = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetY += amount.y
                                },
                                onDragEnd = {
                                    val to = (latestIndex + (dragOffsetY / measuredSlotHeightPx)
                                        .roundToInt()).coerceIn(0, items.lastIndex)
                                    if (latestIndex != to) onMove(latestIndex, to)
                                    draggingKey = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = { draggingKey = null; dragOffsetY = 0f }
                            )
                        }
                    )
                }
            }
        }
    }
}
