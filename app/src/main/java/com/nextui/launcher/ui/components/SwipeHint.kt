package com.nextui.launcher.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * SwipeHint — instant-scrubbable page indicator.
 *
 * ── Zero-lag design ─────────────────────────────────────────────────────────
 * • Instant touch response: touch down or tap immediately selects the target page.
 * • Continuous slide scrubbing: dragging across dots immediately scrubs through
 *   pages with 0ms delay (no long-press required).
 * • Dot selection is expressed purely in the DRAW phase via [Modifier.graphicsLayer].
 */
@Composable
fun SwipeHint(
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isScrubbing by remember { mutableStateOf(false) }

    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun scrollTo(target: Int) {
        scrollJob?.cancel()
        scrollJob = scope.launch { pagerState.scrollToPage(target) }
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                val s = if (isScrubbing) 1.15f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = if (isScrubbing) 0.85f else 0.35f
                )
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .pointerInput(pagerState.pageCount) {
                fun pageAt(x: Float): Int {
                    val w = size.width
                    if (w <= 0) return -1
                    val fraction = (x / w).coerceIn(0f, 1f)
                    return (fraction * (pagerState.pageCount - 1)).roundToInt()
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isScrubbing = true
                    var lastTarget = pageAt(down.position.x)
                    if (lastTarget in 0 until pagerState.pageCount) {
                        scrollTo(lastTarget)
                    }

                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) {
                            val target = pageAt(pointer.position.x)
                            if (target in 0 until pagerState.pageCount && target != lastTarget) {
                                lastTarget = target
                                scrollTo(target)
                            }
                            pointer.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isScrubbing = false
                    if (lastTarget in 0 until pagerState.pageCount) {
                        scrollTo(lastTarget)
                    }
                }
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
