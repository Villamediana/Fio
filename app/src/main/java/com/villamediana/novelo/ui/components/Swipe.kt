package com.villamediana.novelo.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How much of the finger's way the screen goes along with it. A little push back makes going
 * from the main screen feel different from the tabs (which go all the way) — Miguel liked that —
 * but 0.3 was too much and 1 lost it; 0.6 still a little much: 0.7.
 */
const val SWIPE_FOLLOW = 0.7f

/** How long a screen takes to leave (the way out of NoveloRoot): the lean stays until then. */
const val SWIPE_LEAVING_MS = 420L

/** A sideways swipe is a move when it went far enough, or was quick and not tiny. */
fun swipeGoes(dx: Float, velocity: Float, density: Density): Int = with(density) {
    val far = 56.dp.toPx()
    val some = 24.dp.toPx()
    val quick = 400.dp.toPx()
    when {
        dx < -far || (dx < -some && velocity < -quick) -> -1
        dx > far || (dx > some && velocity > quick) -> 1
        else -> 0
    }
}

/**
 * The whole screen as a place on a line: a swipe to the left ([onLeft], the finger goes left) or
 * to the right ([onRight]) takes you to the neighbor. Whatever a child takes for itself (the
 * button, a list scrolling up and down) is not a swipe. [follow] gets how far the content may
 * lean with the finger while dragging, and goes back to 0 when the finger lets go.
 */
fun Modifier.swipeNav(onLeft: (() -> Unit)?, onRight: (() -> Unit)?, follow: MutableFloatState? = null): Modifier = composed {
    val scope = rememberCoroutineScope()
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    pointerInput(Unit) {
        val tracker = VelocityTracker()
        var dx = 0f
        fun settle() {
            val from = follow?.floatValue ?: return
            if (from != 0f) scope.launch { animate(from, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> follow.floatValue = v } }
        }
        // Going: the screen keeps going the finger's way while it leaves (springing back to the
        // middle under the way out looked like a stumble), and is back in the middle after.
        fun leave() {
            val f = follow ?: return
            if (f.floatValue != 0f) scope.launch {
                try { delay(SWIPE_LEAVING_MS) } finally { f.floatValue = 0f }
            }
        }
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking(); dx = 0f },
            onDragEnd = {
                val go = when (swipeGoes(dx, tracker.calculateVelocity().x, this)) {
                    -1 -> left
                    1 -> right
                    else -> null
                }
                // Gone or not, the lean goes back: a screen reused on the way back (she returned
                // before its way out ended) must not stay off-center.
                if (go != null) { go(); leave() } else settle()
            },
            onDragCancel = { settle() },
        ) { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            dx += amount
            change.consume()
            val open = (dx < 0 && left != null) || (dx > 0 && right != null)
            follow?.floatValue = if (open) dx * SWIPE_FOLLOW else 0f
        }
    }
}
