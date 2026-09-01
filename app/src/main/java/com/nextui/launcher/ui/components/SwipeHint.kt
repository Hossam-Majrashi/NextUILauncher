package com.nextui.launcher.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * SwipeHint — scrubbable page indicator.
 *
 * ── Zero-jank design ────────────────────────────────────────────────────────
 * • Dot selection is expressed purely in the DRAW phase: fixed 8.dp dots
 *   whose scale/alpha run through [Modifier.graphicsLayer]. No dot ever
 *   triggers layout re-measurement (the old animateDpAsState size did).
 * • Long-press + drag scrubs pages. Scrub events are THROTTLED by page-change
 *   deduplication: [PagerState.scrollToPage] fires at most once per crossed
 *   page boundary instead of 60×/second per finger pixel.
 * • Scroll commands are strictly SEQUENTIAL: each new target cancels the
 *   previous in-flight scroll job, so two page transitions never overlap or
 *   arrive out of order. On release the pager is snapped to the final target,
 *   guaranteeing the scrub never ends on a fractional, between-pages offset.
 */
@Composable
fun SwipeHint(
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isScrubbing by remember { mutableStateOf(false) }

    // Single in-flight scroll command — canceled+replaced on every new target
    // so page transitions can never duplicate or conflict with each other.
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun scrollTo(target: Int) {
        scrollJob?.cancel()
        scrollJob = scope.launch { pagerState.scrollToPage(target) }
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                // Container "pop" while scrubbing — draw-phase only.
                val s = if (isScrubbing) 1.1f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = if (isScrubbing) 0.8f else 0f
                )
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .pointerInput(pagerState.pageCount) {
                var lastTarget = -1

                fun pageAt(x: Float): Int {
                    val w = size.width
                    if (w <= 0) return -1
                    val fraction = (x / w).coerceIn(0f, 1f)
                    return (fraction * (pagerState.pageCount - 1)).roundToInt()
                }

                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        isScrubbing = true
                        lastTarget = -1
                        val target = pageAt(offset.x)
                        if (target >= 0) {
                            lastTarget = target
                            scrollTo(target)
                        }
                    },
                    onDrag = { change, _ ->
                        val target = pageAt(change.position.x)
                        // Throttle: only reposition when a boundary is crossed.
                        if (target >= 0 && target != lastTarget) {
                            lastTarget = target
                            scrollTo(target)
                        }
                    },
                    onDragEnd = {
                        isScrubbing = false
                        // Final snap: a canceled scrollToPage may have left the
                        // pager at a fractional offset — land exactly on target.
                        if (lastTarget >= 0) scrollTo(lastTarget)
                        lastTarget = -1
                    },
                    onDragCancel = {
                        isScrubbing = false
                        if (lastTarget >= 0) scrollTo(lastTarget)
                        lastTarget = -1
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pagerState.pageCount) { index ->
            val isSelected = pagerState.currentPage == index

            // graphicsLayer-animated scale/alpha: layout never re-measures.
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.625f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "dot_scale"
            )
            val dotAlpha by animateFloatAsState(
                targetValue = if (isSelected) 0.65f else 0.25f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "dot_alpha"
            )

            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = dotAlpha
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground)
            )
        }
    }
}
