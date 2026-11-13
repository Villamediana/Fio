package com.villamediana.novelo.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The opening, from the splash's logo to the button. The frame the system's splash goes away, its
 * ball is already drawn here, over the app, in the same place: it glides to the button while the
 * paper fades, lands with a few waves, and its stitch and loose end draw out of it. Its last frame
 * is the button's own drawing, so the button takes over without a jump; until then the button is
 * hidden ([orbSpot]), so there is never a second ball showing through the paper.
 */
object Opening {
    /** One opening: where the splash's ball was (window px), the splash's paper and colors, and how to take the splash away. */
    class Run(
        val from: Offset,
        val paper: Color? = null,
        val ball: Color? = null,
        val onBall: Color? = null,
        private val dropSplash: () -> Unit = {},
    ) {
        private var dropped = false

        /** The splash goes (once): what is under it is the same picture. */
        fun drop() {
            if (dropped) return
            dropped = true
            runCatching { dropSplash() }
        }
    }

    var run by mutableStateOf<Run?>(null)
        private set

    fun start(r: Run) {
        run?.drop()
        run = r
    }

    /** Done, or cut short by a touch: the button is itself again (and the splash surely gone). */
    fun end(r: Run? = run) {
        if (r == null || r !== run) return
        r.drop()
        run = null
    }
}

/** The opening's timeline, in ms from when the splash goes away. */
internal object OpeningTime {
    const val MOVE = 420f
    const val PAPER_FROM = 0f
    const val PAPER_TO = 340f
    /** The ball touches down: a small swell, and the waves leave from under it. */
    const val SWELL_FROM = 180f
    const val SWELL_TO = 460f
    val WAVES = floatArrayOf(180f, 360f)
    const val WAVE = 540f
    const val RING_FROM = 240f
    const val RING_TO = 600f
    const val TAIL_FROM = 300f
    const val TAIL_TO = 660f
    const val END = 900f
}

/** The splash's ball: 31.5 of the logo's 108 units, in the system's 288dp icon box. */
internal val SPLASH_BALL = 84.dp

/** Straight off, then a soft landing. */
private val Glide = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Over the app while an opening runs; nothing otherwise. */
@Composable
fun OpeningLayer() {
    val run = Opening.run ?: return
    val clock = remember(run) { Animatable(0f) }
    LaunchedEffect(run) {
        try {
            // A couple of frames with the stand-in drawn under the splash, then the splash goes.
            withFrameNanos { }
            withFrameNanos { }
            run.drop()
            clock.animateTo(OpeningTime.END, tween(OpeningTime.END.toInt(), easing = LinearEasing))
        } finally {
            Opening.end(run)
        }
    }
    OpeningFrame(run) { clock.value }
}

/** The opening at [time] (ms), over the whole window. */
@Composable
internal fun OpeningFrame(run: Opening.Run, time: () -> Float) {
    val p = NoveloColors.palette
    val density = LocalDensity.current
    val look = OpeningLook(
        paper = run.paper ?: p.background,
        ballFrom = run.ball ?: p.accent,
        ball = p.accent,
        onBallFrom = run.onBall ?: p.onAccent,
        onBall = p.onAccent,
        mic = rememberVectorPainter(NoveloIcons.Mic),
        lock = rememberVectorPainter(NoveloIcons.Lock),
        micSize = with(density) { MIC.toPx() },
        splashRadius = with(density) { SPLASH_BALL.toPx() },
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val target = OrbSpot.center ?: return@drawBehind
                val mic = OrbSpot.mic?.let { it - target } ?: Offset.Zero
                drawOpening(time(), run.from - origin, target - origin, OrbSpot.radius, mic, look, locked = OrbSpot.locked)
            },
    )
}

internal class OpeningLook(
    val paper: Color,
    val ballFrom: Color,
    val ball: Color,
    val onBallFrom: Color,
    val onBall: Color,
    val mic: VectorPainter,
    /** The button's lock, when something is missing: the splash's mic turns into it on the way. */
    val lock: VectorPainter,
    val micSize: Float,
    val splashRadius: Float,
)

private fun span(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/**
 * One frame: the paper fading, the waves, the stitch and loose end drawing out, and the ball on its
 * way from [from] (the splash's size) to [to] (the button's [toRadius], its mic [mic] off the center). At [OpeningTime.END] it is
 * exactly the button at rest, drawn the button's way.
 */
internal fun DrawScope.drawOpening(t: Float, from: Offset, to: Offset, toRadius: Float, mic: Offset, look: OpeningLook, locked: Boolean = false) {
    val move = Glide.transform(span(t, 0f, OpeningTime.MOVE))
    val c = lerp(from, to, move)
    val swell = sin(PI.toFloat() * span(t, OpeningTime.SWELL_FROM, OpeningTime.SWELL_TO))
    val r = (look.splashRadius + (toRadius - look.splashRadius) * move) * (1f + 0.026f * swell)
    val fill = lerp(look.ballFrom, look.ball, move)
    val on = lerp(look.onBallFrom, look.onBall, move)

    val paper = 1f - FastOutSlowInEasing.transform(span(t, OpeningTime.PAPER_FROM, OpeningTime.PAPER_TO))
    if (paper > 0f) drawRect(look.paper, alpha = paper)

    // Waves: out from under the ball, thinner and fainter as they go.
    for (start in OpeningTime.WAVES) {
        val w = span(t, start, start + OpeningTime.WAVE)
        if (w <= 0f || w >= 1f) continue
        // Out to a little past the fine thread: never over the words under the button.
        val rr = r * (1.02f + 0.72f * LinearOutSlowInEasing.transform(w))
        val a = 0.34f * (1f - w) * (1f - w)
        drawCircle(look.ball, radius = rr, center = c, alpha = a, style = Stroke(width = (1.7f - 0.8f * w).dp.toPx()))
    }

    // The stitch comes out of the ball to its place, and the loose end runs out to its curl.
    val ring = FastOutSlowInEasing.transform(span(t, OpeningTime.RING_FROM, OpeningTime.RING_TO))
    if (ring >= 1f) drawStitchRing(c, r, look.ball, 1f) else if (ring > 0f) drawStitchRing(c, r * (0.86f + 0.14f * ring), look.ball, ring)
    val tail = FastOutSlowInEasing.transform(span(t, OpeningTime.TAIL_FROM, OpeningTime.TAIL_TO))
    if (tail >= 1f) drawLooseEnd(c, r, fill) else if (tail > 0f) drawPart(looseEndPath(c, r), tail, fill)

    // The ball, as the button draws it at rest.
    val drop = ballPath(c, r)
    drawPath(drop, fill)
    clipPath(drop) { drawYarn(c, r, on.copy(alpha = 0.24f)) }
    drawCircle(fill, radius = r * 0.36f, center = c)
    val m = c + mic * move
    // Locked: the mic gives way to the lock as it lands (the button's own then).
    val lock = if (locked) move else 0f
    translate(m.x - look.micSize / 2f, m.y - look.micSize / 2f) {
        if (lock < 1f) with(look.mic) { draw(Size(look.micSize, look.micSize), alpha = 1f - lock, colorFilter = ColorFilter.tint(on)) }
        if (lock > 0f) with(look.lock) { draw(Size(look.micSize, look.micSize), alpha = lock, colorFilter = ColorFilter.tint(on)) }
    }
}

/** The ball's outline the way the button makes it (still, it is the same 120 points). */
private fun ballPath(c: Offset, r: Float): Path = Path().apply {
    val n = 120
    for (k in 0..n) {
        val th = (k.toFloat() / n) * 2f * PI.toFloat()
        val x = c.x + r * cos(th)
        val y = c.y + r * sin(th)
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The first [part] of the loose end. */
private fun DrawScope.drawPart(path: Path, part: Float, color: Color) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val piece = Path()
    measure.getSegment(0f, measure.length * part, piece, true)
    drawPath(piece, color, style = Stroke(width = LOOSE_END.toPx(), cap = StrokeCap.Round))
}
