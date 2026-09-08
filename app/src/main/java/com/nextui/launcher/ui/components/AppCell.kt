package com.nextui.launcher.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextui.launcher.data.IconCache
import com.nextui.launcher.data.LauncherItemEntity

/**
 * AppCell — 100% stateless, skippable grid cell.
 *
 * • Holds ZERO mutable state: no expanded menus, no dialogs, no text inputs.
 *   Long-press dispatches [onShowMenu] upward; the root screen renders ONE
 *   shared bottom-sheet menu for every cell (see LauncherScreen).
 * • All inputs are stable (@Immutable entity + function references), so the
 *   Compose compiler skips this composable entirely when nothing changed —
 *   even while sibling cells or the pager recompose.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(
    item: LauncherItemEntity,
    onLaunch: (LauncherItemEntity) -> Unit,
    onShowMenu: (LauncherItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "app_cell_press"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onLaunch(item) },
                onLongClick = { onShowMenu(item) }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AppIcon(item = item, disabled = false)
            if (item.isPinned) {
                Icon(
                    Icons.Rounded.PushPin, null,
                    modifier = Modifier
                        .size(12.dp)
                        .offset(x = 2.dp, y = (-2).dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * RecycleCell — stateless recycle-bin variant (greyed icon + store launch).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecycleCell(
    item: LauncherItemEntity,
    onOpenStore: (LauncherItemEntity) -> Unit,
    onShowMenu: (LauncherItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "recycle_cell_press"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onOpenStore(item) },
                onLongClick = { onShowMenu(item) }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(item = item, disabled = true)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * AppIcon — zero-jank icon renderer.
 *
 * • Synchronous L1 cache probe as the initial value: icons already in RAM
 *   appear in the SAME frame as the cell — no pop-in, no placeholder flash.
 * • Misses resolve asynchronously into [produceState]; when the bitmap
 *   arrives it is swapped INSTANTLY (no alpha spring animations, which
 *   previously cost extra frames during flings).
 * • Behind the bitmap sits a deterministic hash-colored letter badge, so the
 *   cell is never visually empty and never shifts layout.
 */
@Composable
fun AppIcon(
    item: LauncherItemEntity,
    disabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val key = item.componentKey

    val imageBitmap by produceState<ImageBitmap?>(
        initialValue = IconCache.getCachedImageBitmap(key),
        key1 = key
    ) {
        // Re-check L1 cache: the icon may have arrived between composition
        // and this coroutine start (e.g. from a preload on an adjacent page).
        val cached = IconCache.getCachedImageBitmap(key)
        if (cached != null) {
            value = cached
        } else if (value == null && item.isInstalled) {
            value = IconCache.loadIcon(context, key, item.packageName)
        }
    }

    // Deterministic per-app placeholder color (stable across sessions).
    val placeholderColor = remember(key) { placeholderColorFor(key) }

    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (disabled) 0.5f else 1f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(placeholderColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
        val bitmap = imageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.label,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/** Maps a component key to a pleasant, deterministic HSL badge color. */
private fun placeholderColorFor(key: String): Color {
    val hue = (key.hashCode() and 0x7FFFFFFF) % 360
    return Color.hsl(hue.toFloat(), 0.45f, 0.50f)
}
