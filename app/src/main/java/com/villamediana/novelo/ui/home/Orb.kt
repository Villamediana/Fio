package com.villamediana.novelo.ui.home

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.villamediana.novelo.ui.components.NoveloIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbMode { IDLE, HOLDING, LOCKED, WORKING }

/**
 * The one button, a ball of yarn (the novelo) with its loose end. Hold to talk (letting go keeps
 * it), tap to leave it listening (tap again keeps it), drag up while holding to throw it away.
 * While listening the ball breathes and wobbles with the voice; while it works, a thread runs
 * around it. Nothing moves when nothing happens.
 */
@Composable
fun Orb(
    mode: OrbMode,
    level: State<Float>,
    onStart: () -> Boolean,
    onLock: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    onCancelZone: (Boolean) -> Unit = {},
) {
    val view = LocalView.current
    val current by rememberUpdatedState(mode)
    val start by rememberUpdatedState(onStart)
    val lock by rememberUpdatedState(onLock)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val zone by rememberUpdatedState(onCancelZone)
    val cancelDistance = with(LocalDensity.current) { 120.dp.toPx() }

    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableFloatStateOf(0f) } // 0..1 toward "descartar"
    val listening = mode == OrbMode.HOLDING || mode == OrbMode.LOCKED
    val working = mode == OrbMode.WORKING

    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(0.55f, Spring.StiffnessMedium), label = "press")
    val listen by animateFloatAsState(if (listening) 1f else 0f, spring(0.62f, 220f), label = "listen")
    val work by animateFloatAsState(if (working) 1f else 0f, tween(420), label = "work")
    // The drop is the thread's own color; it only goes gray when it is about to be thrown away.
    val discarding = listening && dragging > 0.99f
    val fill by animateColorAsState(if (discarding) NoveloColors.TextMuted else NoveloColors.Accent, tween(260), label = "fill")
    val ring = NoveloColors.Accent
    val iconTint = NoveloColors.OnAccent

    // The clock only runs while something moves (listening, working, or settling back).
    var time by remember { mutableFloatStateOf(0f) }
    var smooth by remember { mutableFloatStateOf(0f) }
    val animating = listening || working || listen > 0.001f || work > 0.001f
    val listeningNow by rememberUpdatedState(listening)
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                time += dt
                val target = if (listeningNow) level.value else 0f
                smooth += (target - smooth) * (1f - kotlin.math.exp(-dt * 14f))
            }
        }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension * BALL * press * (1f - 0.06f * work)
            val lvl = smooth
            val t = time

            val swell = 1f + listen * (0.04f + 0.12f * lvl)

            // At rest: a stitch around it and a fine thread further out. They give way to the
            // ripples and to the running thread.
            val rest = (1f - listen).coerceIn(0f, 1f) * (1f - work)
            if (rest > 0.01f) drawStitchRing(c, r, ring, rest)

            // The yarn's loose end stays, still (a moving tail only pulls the eye): it follows the
            // ball as it grows and goes gray with it when it is being thrown away.
            drawLooseEnd(c, r, fill, swell = swell)

            // Ripples going out with the voice.
            if (listen > 0.01f) {
                for (i in 0 until 3) {
                    val phase = ((t * 0.42f + i / 3f) % 1f)
                    val rr = r * (1.10f + 0.55f * phase) * (1f + 0.08f * lvl)
                    val a = (1f - phase) * (0.12f + 0.26f * lvl) * listen
                    drawCircle(ring.copy(alpha = a), radius = rr, center = c, style = Stroke(width = (1.0f + 1.2f * lvl).dp.toPx()))
                }
            }

            // The drop: a circle that breathes and wobbles with the voice while listening.
            val amp = listen * r * (0.008f + 0.030f * lvl)
            val path = Path()
            val n = 120
            for (k in 0..n) {
                val th = (k.toFloat() / n) * 2f * PI.toFloat()
                val wob = amp * (
                    0.50f * sin(3f * th + t * 2.3f) +
                        0.32f * sin(5f * th - t * 1.9f + 1.3f) +
                        0.26f * sin(2f * th + t * 1.1f + 2.2f)
                    )
                val rad = r * swell + wob
                val x = c.x + rad * cos(th)
                val y = c.y + rad * sin(th)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, fill)
            // The yarn wound inside, and a clean middle for the mic.
            clipPath(path) { drawYarn(c, r * swell, iconTint.copy(alpha = 0.24f)) }
            drawCircle(fill, radius = r * swell * 0.36f, center = c)

            // Working: a thread running around.
            if (work > 0.01f) {
                val rr = r * 1.18f
                val sweep = 64f + 46f * sin(t * 2.1f)
                drawArc(
                    color = ring.copy(alpha = work),
                    startAngle = (t * 250f) % 360f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr),
                    size = Size(rr * 2, rr * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

        }

        // The touch area is the drop itself.
        Box(
            Modifier
                .size(size * 0.62f)
                .semantics {
                    role = Role.Button
                    contentDescription = if (listening) t(R.string.orb_stop_keep) else t(R.string.orb_talk)
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val at = current
                        if (at == OrbMode.LOCKED) {
                            pressed = true
                            do {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                            pressed = false
                            haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                            stop()
                            return@awaitEachGesture
                        }
                        if (!start()) return@awaitEachGesture
                        haptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
                        pressed = true
                        val t0 = down.uptimeMillis
                        var dy = 0f
                        var upAt = t0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                            dy += change.positionChange().y
                            // The finger on the button is the button's: no swipe of the screen under it.
                            change.consume()
                            val toward = (-dy / cancelDistance).coerceIn(0f, 1f)
                            if ((toward > 0.99f) != (dragging > 0.99f)) zone(toward > 0.99f)
                            dragging = toward
                            if (!change.pressed) { upAt = change.uptimeMillis; break }
                        }
                        pressed = false
                        val cancelled = dragging > 0.99f
                        dragging = 0f
                        zone(false)
                        when {
                            cancelled -> { haptic(view, HapticFeedbackConstants.LONG_PRESS); cancel() }
                            upAt - t0 < TAP_MS -> lock()
                            else -> { haptic(view, HapticFeedbackConstants.CONTEXT_CLICK); stop() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Listening by itself: the stop, drawn like the mic (a thin line), says "toque pra parar".
            run {
                Icon(
                    if (mode == OrbMode.LOCKED) NoveloIcons.Stop else NoveloIcons.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(MIC).onGloballyPositioned { OrbSpot.mic = it.boundsInWindow().center }.graphicsLayer {
                        alpha = 1f - 0.45f * work
                        val s = 1f + 0.08f * listen
                        scaleX = s; scaleY = s
                    },
                )
            }
        }
    }
}

private const val TAP_MS = 320L

/** The ball's radius, in parts of the button's size. */
internal const val BALL = 0.30f

/** The mic (or the stop) in the middle of the ball. */
internal val MIC = 38.dp

/** Where the button's center and ball are on the window right now (null: not on screen), for the opening to land on it. */
object OrbSpot {
    var center by mutableStateOf<Offset?>(null)
    var radius by mutableFloatStateOf(0f)
    /** The mic's own center: the layout puts it where whole pixels fall, a hair off the ball's. */
    var mic by mutableStateOf<Offset?>(null)
}

/** On the button: tells where it is, and stays hidden while the opening's ball stands in for it (see [Opening]). */
fun Modifier.orbSpot(): Modifier = onGloballyPositioned {
    OrbSpot.center = it.boundsInWindow().center
    OrbSpot.radius = it.size.width * BALL
}.graphicsLayer { alpha = if (Opening.run != null) 0f else 1f }

/** The yarn's lines across the ball (center [c], radius [r]), as they cross it. */
fun DrawScope.drawYarn(c: Offset, r: Float, color: Color) {
    val stroke = Stroke(width = r * 0.022f + 0.6.dp.toPx(), cap = StrokeCap.Round)
    YARN.forEach { a ->
        val p = Path().apply {
            moveTo(c.x + a[0] * r, c.y + a[1] * r)
            cubicTo(c.x + a[2] * r, c.y + a[3] * r, c.x + a[4] * r, c.y + a[5] * r, c.x + a[6] * r, c.y + a[7] * r)
        }
        drawPath(p, color, style = stroke)
    }
}

/** The stitch around the ball and the fine thread further out, at [strength]. */
fun DrawScope.drawStitchRing(c: Offset, r: Float, color: Color, strength: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.5.dp.toPx()))
    drawCircle(color.copy(alpha = 0.42f * strength), radius = r * 1.16f, center = c, style = Stroke(width = 1.4.dp.toPx(), pathEffect = dash))
    drawCircle(color.copy(alpha = 0.18f * strength), radius = r * 1.40f, center = c, style = Stroke(width = 1.dp.toPx()))
}

/** The yarn's loose end: out of the ball at the bottom right, a little curl; [swell] keeps it on the ball as it grows. */
fun DrawScope.drawLooseEnd(c: Offset, r: Float, color: Color, swell: Float = 1f) {
    drawPath(looseEndPath(c, r, swell), color, style = Stroke(width = LOOSE_END.toPx(), cap = StrokeCap.Round))
}

/** The loose end's line, from the ball out to the curl. */
internal fun looseEndPath(c: Offset, r: Float, swell: Float = 1f): Path {
    val o = Offset(c.x + (swell - 1f) * r * 0.66f, c.y + (swell - 1f) * r * 0.66f)
    return Path().apply {
        moveTo(o.x + r * 0.62f, o.y + r * 0.66f)
        cubicTo(o.x + r * 0.92f, o.y + r * 0.96f, o.x + r * 1.20f, o.y + r * 0.94f, o.x + r * 1.24f, o.y + r * 1.20f)
        cubicTo(o.x + r * 1.28f, o.y + r * 1.44f, o.x + r * 1.02f, o.y + r * 1.50f, o.x + r * 0.98f, o.y + r * 1.34f)
    }
}

internal val LOOSE_END = 2.4.dp

/** From the center, in radii: start, two handles, end. */
private val YARN = listOf(
    floatArrayOf(-0.82f, -0.28f, -0.36f, -0.62f, 0.40f, -0.64f, 0.86f, -0.16f),
    floatArrayOf(-0.90f, 0.02f, -0.36f, -0.34f, 0.42f, -0.32f, 0.94f, 0.16f),
    floatArrayOf(-0.80f, 0.38f, -0.30f, 0.04f, 0.40f, 0.06f, 0.84f, 0.50f),
    floatArrayOf(-0.56f, 0.78f, -0.18f, 0.50f, 0.28f, 0.52f, 0.60f, 0.80f),
    floatArrayOf(-0.38f, -0.82f, -0.64f, -0.28f, -0.58f, 0.44f, -0.10f, 0.94f),
    floatArrayOf(0.04f, -0.92f, -0.22f, -0.32f, -0.16f, 0.42f, 0.32f, 0.96f),
    floatArrayOf(0.48f, -0.80f, 0.24f, -0.30f, 0.30f, 0.40f, 0.72f, 0.68f),
)

private fun haptic(view: android.view.View, kind: Int) {
    val k = if (kind == HapticFeedbackConstants.CONTEXT_CLICK && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else kind
    view.performHapticFeedback(k)
}
