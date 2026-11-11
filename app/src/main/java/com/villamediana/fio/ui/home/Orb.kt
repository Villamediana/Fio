package com.villamediana.fio.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import com.villamediana.fio.ui.components.FioIcons
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.fio.ui.theme.FioColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbMode { IDLE, HOLDING, LOCKED, WORKING }

/**
 * The one button. Hold to talk (letting go keeps it), tap to leave it listening (tap again keeps
 * it), drag up while holding to throw it away. While listening it is a living drop that follows
 * the voice; while Fio works, a thread runs around it. Nothing moves when nothing happens.
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
    val fill by animateColorAsState(if (discarding) FioColors.TextMuted else FioColors.Accent, tween(260), label = "fill")
    val ring = FioColors.Accent
    val iconTint = Color.White

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
            val r = this.size.minDimension * 0.30f * press * (1f - 0.06f * work)
            val lvl = smooth
            val t = time

            // At rest: one quiet, flat halo. It gives way to the ripples and to the thread.
            val rest = (1f - listen).coerceIn(0f, 1f) * (1f - work)
            if (rest > 0.01f) drawCircle(ring.copy(alpha = 0.10f * rest), radius = r * 1.30f, center = c)

            // Ripples going out with the voice.
            if (listen > 0.01f) {
                for (i in 0 until 3) {
                    val phase = ((t * 0.42f + i / 3f) % 1f)
                    val rr = r * (1.08f + 0.62f * phase) * (1f + 0.12f * lvl)
                    val a = (1f - phase) * (0.10f + 0.30f * lvl) * listen
                    drawCircle(ring.copy(alpha = a), radius = rr, center = c, style = Stroke(width = (1.4f + 2.2f * lvl).dp.toPx()))
                }
            }

            // The drop: a circle that breathes and wobbles with the voice while listening.
            val swell = 1f + listen * (0.05f + 0.20f * lvl)
            val amp = listen * r * (0.016f + 0.060f * lvl)
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
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Locked: a rounded square says "toque pra parar".
            if (mode == OrbMode.LOCKED) {
                val s = r * 0.42f
                drawRoundRect(iconTint, Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(s * 0.22f))
            }
        }

        // The touch area is the drop itself.
        Box(
            Modifier
                .size(size * 0.62f)
                .semantics {
                    role = Role.Button
                    contentDescription = if (listening) "Parar e guardar" else "Falar"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val at = current
                        if (at == OrbMode.LOCKED) {
                            pressed = true
                            do {
                                val ev = awaitPointerEvent()
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
            if (mode != OrbMode.LOCKED) {
                Icon(
                    FioIcons.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp).graphicsLayer {
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

private fun haptic(view: android.view.View, kind: Int) {
    val k = if (kind == HapticFeedbackConstants.CONTEXT_CLICK && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else kind
    view.performHapticFeedback(k)
}
