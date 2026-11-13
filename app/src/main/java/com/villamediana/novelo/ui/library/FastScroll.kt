package com.villamediana.novelo.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.ui.theme.Motion
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A long list crossed by its edge (the diary, years of days). While the list moves, a small handle
 * slides in on the right where you are; the finger takes it anywhere along that edge (not only on
 * the handle), the handle comes under it and the list goes with it, and a tag beside the finger says
 * where it is ([labelAt], the month) — the first entry is one drag down. The list still for a moment,
 * the handle slides away and the edge is the list's again; it moves, the handle comes back.
 */
@Composable
internal fun FastScroll(state: LazyListState, labelAt: (Int) -> String?, modifier: Modifier = Modifier) {
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < FAST_SCROLL_MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    var asked by remember { mutableIntStateOf(-1) }
    val where by remember(state) { derivedStateOf { position(state) } }
    val moving = dragging || state.isScrollInProgress
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) awake = true else { delay(FAST_SCROLL_STAYS_MS); awake = false }
    }
    val shown by animateFloatAsState(
        if (awake) 1f else 0f,
        tween(if (awake) 260 else 420, easing = Motion.Ease),
        label = "fast-scroll",
    )
    // Gone: nothing on the edge, the touches there are the list's.
    if (!awake && shown == 0f) return
    val thick by animateDpAsState(if (dragging) 6.dp else 4.dp, Motion.soft(), label = "fast-scroll-thick")
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val thumb = with(density) { THUMB_TALL.toPx() }
        val track = (constraints.maxHeight - thumb).coerceAtLeast(1f)
        val trackNow by rememberUpdatedState(track)
        val totalNow by rememberUpdatedState(total)
        val fraction = if (dragging) held else where
        val top = (fraction * track).roundToInt()
        val away = with(density) { 14.dp.toPx() } * (1f - shown)

        // The tag: the month where the finger is, beside it.
        if (dragging) {
            labelAt(index(held, total))?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = NoveloColors.Background,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-with(density) { 48.dp.roundToPx() }, top + ((thumb - with(density) { 36.dp.toPx() }) / 2).roundToInt()) }
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(NoveloColors.Text)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("fast-scroll-label"),
                )
            }
        }
        // The edge: anywhere along it the finger takes the handle, which comes under the finger.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(TOUCH_WIDE)
                .testTag("fast-scroll-track")
                .pointerInput(Unit) {
                    fun follow(y: Float) {
                        held = ((y - thumb / 2) / trackNow).coerceIn(0f, 1f)
                        val i = index(held, totalNow)
                        if (i != asked) {
                            asked = i
                            scope.launch { state.scrollToItem(i) }
                        }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { at -> dragging = true; asked = -1; follow(at.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        follow(change.position.y)
                    }
                },
        ) {
            // The handle: a thin line on the edge, sliding in and out with the list.
            Box(
                Modifier
                    .offset { IntOffset(away.roundToInt(), top) }
                    .size(TOUCH_WIDE, THUMB_TALL)
                    .graphicsLayer { alpha = shown }
                    .testTag("fast-scroll"),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .width(thick)
                        .height(THUMB_TALL - 12.dp)
                        .clip(CircleShape)
                        .background(if (dragging) NoveloColors.Accent else NoveloColors.TextFaint),
                )
            }
        }
    }
}

/** Where the list is, 0 at the top and 1 at the end (by items: the cards are taller than the day labels). */
private fun position(state: LazyListState): Float {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (info.totalItemsCount == 0 || visible.isEmpty()) return 0f
    if (!state.canScrollForward) return 1f
    val first = visible.first()
    val within = if (first.size > 0) state.firstVisibleItemScrollOffset.toFloat() / first.size else 0f
    val span = (info.totalItemsCount - visible.size).coerceAtLeast(1)
    return ((state.firstVisibleItemIndex + within) / span).coerceIn(0f, 1f)
}

private fun index(fraction: Float, total: Int): Int = (fraction * (total - 1)).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))

/** Below this, the list is short enough to just scroll. */
internal const val FAST_SCROLL_MIN_ITEMS = 30
/** How long the handle stays once the list is still. */
private const val FAST_SCROLL_STAYS_MS = 1_000L
private val THUMB_TALL = 56.dp
/** The edge the finger takes it on: wider than the line, easy for a thumb. */
private val TOUCH_WIDE = 40.dp
