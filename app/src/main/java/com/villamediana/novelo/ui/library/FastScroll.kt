package com.villamediana.novelo.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A long list crossed by its edge (the diary, years of days). While the list moves, a small handle
 * slides in on the right where you are; the finger takes it anywhere along that edge (not only on
 * the handle), the handle comes under it and the list goes with it, and a tag beside the finger says
 * where it is ([labelAt], the month) — the first entry is one drag down. The list still for a moment,
 * the handle fades and slides away and the edge is the list's again; it moves, the handle comes back.
 *
 * Nothing jumps: where the handle sits is measured so it only ever glides ([scrolled]); a finger held
 * still on the line between two days doesn't shake the list between them ([nextItem]); let go, the
 * handle glides from the finger to where the list is, and the tag fades.
 */
@Composable
internal fun FastScroll(state: LazyListState, labelAt: (Int) -> String?, modifier: Modifier = Modifier) {
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < FAST_SCROLL_MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    var asked by remember { mutableIntStateOf(-1) }
    var span by remember { mutableIntStateOf(1) }
    var label by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(1f) }
    val where by remember(state) { derivedStateOf { position(state) } }
    val moving = dragging || state.isScrollInProgress
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) awake = true else { delay(FAST_SCROLL_STAYS_MS); awake = false }
    }
    val shown by animateFloatAsState(
        if (awake) 1f else 0f,
        if (awake) tween(280, easing = Motion.Ease) else tween(560, easing = FastOutSlowInEasing),
        label = "fast-scroll",
    )
    val tagShown by animateFloatAsState(
        if (dragging) 1f else 0f,
        tween(if (dragging) 140 else 320, easing = FastOutSlowInEasing),
        label = "fast-scroll-tag",
    )
    // Gone: nothing on the edge, the touches there are the list's.
    if (!awake && shown == 0f) return
    val thick by animateDpAsState(if (dragging) 6.dp else 4.dp, Motion.soft(), label = "fast-scroll-thick")
    val density = LocalDensity.current
    val labelNow by rememberUpdatedState(labelAt)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val thumb = with(density) { THUMB_TALL.toPx() }
        val track = (constraints.maxHeight - thumb).coerceAtLeast(1f)
        val trackNow by rememberUpdatedState(track)
        val totalNow by rememberUpdatedState(total)
        val fraction = when {
            dragging -> held
            settle.value < 1f -> from + (where - from) * settle.value
            else -> where
        }
        val top = (fraction * track).roundToInt()
        val away = with(density) { 14.dp.toPx() } * (1f - shown)

        // The tag: the month where the finger is, beside it; it fades in and out.
        val text = label
        if (text != null && tagShown > 0f) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = NoveloColors.Background,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 48.dp.roundToPx() }, top + ((thumb - with(density) { 36.dp.toPx() }) / 2).roundToInt()) }
                    .graphicsLayer { alpha = tagShown; translationX = (1f - tagShown) * with(density) { 10.dp.toPx() } }
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(NoveloColors.Text)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(if (dragging) "fast-scroll-label" else "fast-scroll-label-going"),
            )
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
                        label = labelNow(index(held, totalNow))
                        // At the bottom of the edge, the very end of the list (the first entry whole).
                        val to = if (held >= 1f) (totalNow - 1).takeIf { it != asked } else nextItem(held * span, asked)
                        to?.let { i ->
                            asked = i
                            scope.launch { state.scrollToItem(i) }
                        }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { at ->
                            scope.launch { settle.snapTo(1f) }
                            // The first item at the top runs 0 → this, and the list ends there.
                            span = (totalNow - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
                            asked = -1
                            dragging = true
                            follow(at.y)
                        },
                        onDragEnd = { from = held; dragging = false; scope.launch { settle.snapTo(0f); settle.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } },
                        onDragCancel = { from = held; dragging = false; scope.launch { settle.snapTo(0f); settle.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } },
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

/** Where the list is now ([scrolled]): the item at the top and the one at the bottom, and how much of each is out of view. */
private fun position(state: LazyListState): Float {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (info.totalItemsCount == 0 || items.isEmpty()) return 0f
    val start = info.viewportStartOffset + info.beforeContentPadding
    val end = info.viewportEndOffset - info.afterContentPadding
    val first = items.first()
    val last = items.last()
    return scrolled(info.totalItemsCount, first.index, out(start - first.offset, first.size), last.index, out(last.offset + last.size - end, last.size))
}

private fun out(px: Int, size: Int): Float = if (size <= 0) 0f else (px.toFloat() / size).coerceIn(0f, 1f)

/**
 * Where the list is, 0 at the top and 1 at the end: the items gone above the screen against those
 * above plus those still below (the top one and the bottom one counted by the part out of view). It
 * only grows as the list goes down and never jumps — a tall card or a short day label coming in
 * doesn't move it back (counting the items on screen did, and the handle trembled).
 */
internal fun scrolled(total: Int, firstIndex: Int, firstOut: Float, lastIndex: Int, lastOut: Float): Float {
    val above = firstIndex + firstOut
    val below = (total - 1 - lastIndex) + lastOut
    return if (above + below <= 0f) 0f else (above / (above + below)).coerceIn(0f, 1f)
}

/**
 * The item to bring to the top for the finger at [x] (in items), or null to stay on [asked]: it
 * changes only once the finger is well past the line between two, so a finger held still (never
 * quite still) doesn't shake the list between two days.
 */
internal fun nextItem(x: Float, asked: Int): Int? {
    val i = x.roundToInt()
    if (i == asked) return null
    if (asked >= 0 && abs(x - asked) < FAST_SCROLL_HOLD) return null
    return i
}

private fun index(fraction: Float, total: Int): Int = (fraction * (total - 1)).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))

/** Below this, the list is short enough to just scroll. */
internal const val FAST_SCROLL_MIN_ITEMS = 30
/** How far past an item (in items) the finger goes before the list moves to the next one. */
internal const val FAST_SCROLL_HOLD = 0.8f
/** How long the handle stays once the list is still. */
private const val FAST_SCROLL_STAYS_MS = 1_000L
private val THUMB_TALL = 56.dp
/** The edge the finger takes it on: wider than the line, easy for a thumb. */
private val TOUCH_WIDE = 40.dp
