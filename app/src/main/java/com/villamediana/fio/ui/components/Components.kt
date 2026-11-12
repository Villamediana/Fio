package com.villamediana.fio.ui.components

import com.villamediana.fio.settings.Lang
import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Status
import com.villamediana.fio.mind.Snippet
import com.villamediana.fio.mind.Steps
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.theme.FioText
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The app's language for dates and numbers (Portuguese unless she chose another). */
val PT: Locale get() = Lang.locale

/** Shrinks a little while pressed (the touch is felt, nothing flashes). */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) DecimalFormat("0.0").format(mb / 1024) + " GB" else DecimalFormat("0").format(mb) + " MB"
}

/** The mark: a ball of yarn with its loose end (Novelo), on the accent. */
@Composable
fun NoveloMark(size: Dp = 40.dp, color: Color = FioColors.OnAccent, background: Color = FioColors.Accent) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(background)) {
        // Drawn on the launcher icon's grid (108, the middle 72 showing), scaled to the tile.
        val k = this.size.width / 72f
        fun x(v: Float) = (v - 18f) * k
        val stroke = Stroke(width = 3.4f * k, cap = StrokeCap.Round)
        drawCircle(color, radius = 19f * k, center = androidx.compose.ui.geometry.Offset(x(52f), x(52f)), style = stroke)
        Novelo.ICON_LINES.forEach { c ->
            val path = Path().apply {
                moveTo(x(c[0]), x(c[1]))
                var i = 2
                while (i + 5 < c.size) { cubicTo(x(c[i]), x(c[i + 1]), x(c[i + 2]), x(c[i + 3]), x(c[i + 4]), x(c[i + 5])); i += 6 }
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/** The yarn's lines on the launcher icon's 108 grid (the same as res/drawable/ic_novelo.xml). */
object Novelo {
    val ICON_LINES: List<FloatArray> = listOf(
        floatArrayOf(36f, 44f, 44f, 38f, 58f, 37f, 68f, 45f),
        floatArrayOf(33.5f, 53f, 43f, 46f, 60f, 46f, 70.5f, 55f),
        floatArrayOf(36f, 62f, 45f, 56f, 58f, 57f, 67f, 63f),
        floatArrayOf(45f, 34.5f, 39f, 43f, 39f, 58f, 47f, 69.5f),
        floatArrayOf(55f, 33.2f, 49f, 43f, 50f, 59f, 57.5f, 70f),
        floatArrayOf(65.4f, 65.4f, 70f, 70f, 76f, 71f, 78f, 75f, 80f, 79f, 76f, 81.5f, 73.5f, 79.5f),
    )
}

/** The paper the screens sit on: the background with a fine grain (one small tile, drawn once and repeated). */
fun Modifier.paper(): Modifier = composed {
    val p = FioColors.palette
    val density = LocalDensity.current.density
    val grain = remember(density) { grainTile(density) }
    val tint = if (p.dark) Color(0xFFF5E1C8).copy(alpha = 0.03f) else Color(0xFF6B4C2E).copy(alpha = 0.045f)
    background(p.background).drawWithCache {
        val brush = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
        val filter = ColorFilter.tint(tint, BlendMode.SrcIn)
        onDrawBehind { drawRect(brush, colorFilter = filter) }
    }
}

/** Specks about a dp wide, some stronger than others, the same every time. */
private fun grainTile(density: Float): ImageBitmap {
    val cell = density.roundToInt().coerceAtLeast(1)
    val cells = 96
    val side = cells * cell
    val random = java.util.Random(11)
    val px = IntArray(side * side)
    for (cy in 0 until cells) for (cx in 0 until cells) {
        val v = random.nextFloat()
        val a = if (v > 0.6f) ((v - 0.6f) / 0.4f * 255f).roundToInt() else 0
        val argb = (a shl 24) or 0xFFFFFF
        for (y in 0 until cell) for (x in 0 until cell) px[(cy * cell + y) * side + cx * cell + x] = argb
    }
    return android.graphics.Bitmap.createBitmap(px, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The thread's stitch color: the accent, light. */
val StitchColor: Color @Composable get() = FioColors.Accent.copy(alpha = if (FioColors.palette.dark) 0.36f else 0.34f)

/**
 * A running stitch just inside [shape]'s edge, the hand-made line of the cards: it follows the
 * corners [inset] in, so it sits like a seam.
 */
fun Modifier.stitched(shape: Shape, color: Color, inset: Dp = 7.dp, width: Dp = 1.3.dp, dash: Dp = 4.dp, gap: Dp = 3.dp): Modifier =
    drawWithCache {
        val i = inset.toPx()
        val stroke = Stroke(width = width.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())))
        val path = Path()
        when (val o = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> {
                val r = o.roundRect
                fun CornerRadius.less() = CornerRadius((x - i).coerceAtLeast(0f), (y - i).coerceAtLeast(0f))
                path.addRoundRect(
                    RoundRect(
                        r.left + i, r.top + i, r.right - i, r.bottom - i,
                        r.topLeftCornerRadius.less(), r.topRightCornerRadius.less(), r.bottomRightCornerRadius.less(), r.bottomLeftCornerRadius.less(),
                    ),
                )
            }
            is Outline.Rectangle -> path.addRect(o.rect.deflate(i))
            is Outline.Generic -> path.addPath(o.path)
        }
        onDrawWithContent {
            drawContent()
            drawPath(path, color, style = stroke)
        }
    }

fun kindLabel(kind: Kind): String = when (kind) {
    Kind.TASK -> t(R.string.kind_task)
    Kind.NOTE -> t(R.string.kind_note)
    Kind.DIARY -> t(R.string.kind_diary)
}

fun statusLabel(status: Status): String = when (status) {
    Status.TODO -> t(R.string.status_todo)
    Status.DOING -> t(R.string.status_doing)
    Status.WAITING -> t(R.string.status_waiting)
    Status.DONE -> t(R.string.status_done)
}

/** A rounded word that can be chosen (statuses, choices). Chosen is a breath of the accent, never a black block. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) FioColors.Accent.copy(alpha = 0.13f) else FioColors.Surface2, label = "pillBg")
    val fg by animateColorAsState(if (selected) FioColors.Accent else FioColors.TextMuted, label = "pillFg")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * An action as an icon in a soft circle ([label] is what a screen reader says). [primary] wears
 * the accent (the likely next step); the others stay quiet.
 */
@Composable
fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, size: Dp = 44.dp) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(if (primary) FioColors.Accent.copy(alpha = 0.13f) else FioColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (primary) FioColors.Accent else FioColors.TextMuted, modifier = Modifier.size(size * 0.45f))
    }
}

/** A quiet line above a group, in the leaning serif. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = FioText.Section,
        color = FioColors.TextMuted,
        modifier = modifier.padding(start = 6.dp, top = 24.dp, bottom = 10.dp),
    )
}

/**
 * Things that belong together sit as one block: 2 dp of paper between them, round outside,
 * almost square inside. [index] of [count] says which corners are the block's.
 */
fun groupShape(index: Int, count: Int, outer: Dp = 22.dp, inner: Dp = 6.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

val GroupGap = 2.dp

/** A block of rows on paper (settings, lists): each row gets its corners from [groupShape]. */
@Composable
fun Group(modifier: Modifier = Modifier, vararg rows: (@Composable () -> Unit)?) {
    val shown = rows.filterNotNull()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        shown.forEachIndexed { i, row ->
            Box(Modifier.fillMaxWidth().clip(groupShape(i, shown.size)).background(FioColors.Surface)) { row() }
        }
    }
}

/**
 * A few words side by side, one chosen: a soft track and a paper thumb that slides to the choice.
 * All parts take the width of the widest, so the thumb never changes size on the way.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val thumbAt by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.82f, stiffness = 520f), label = "segment")
    val thumbColor = if (FioColors.palette.dark) FioColors.Surface3 else FioColors.Surface
    Layout(
        content = {
            Box(Modifier.clip(CircleShape).background(thumbColor))
            options.forEachIndexed { i, text ->
                val fg by animateColorAsState(if (i == selected) FioColors.Text else FioColors.TextMuted, label = "segmentFg")
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) }
                        .semantics { role = Role.RadioButton; this.selected = i == selected }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                }
            }
        },
        modifier = modifier.clip(CircleShape).background(FioColors.Surface2).padding(3.dp),
    ) { measurables, constraints ->
        val thumb = measurables.first()
        val parts = measurables.drop(1)
        val n = parts.size.coerceAtLeast(1)
        val widest = parts.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val w = if (constraints.hasBoundedWidth) minOf(widest, constraints.maxWidth / n) else widest
        val h = parts.maxOf { it.minIntrinsicHeight(w) }
        val placed = parts.map { it.measure(Constraints.fixed(w, h)) }
        val thumbPlaced = thumb.measure(Constraints.fixed(w, h))
        layout(w * n, h) {
            thumbPlaced.place((thumbAt * w).roundToInt(), 0)
            placed.forEachIndexed { i, p -> p.place(i * w, 0) }
        }
    }
}

/** [text] with the matched words in ink and bold on [mark] (search results, answers). */
fun highlighted(text: String, terms: Collection<String>, color: Color, mark: Color = color.copy(alpha = 0.10f)): AnnotatedString = buildAnnotatedString {
    val ranges = Snippet.ranges(text, terms)
    var at = 0
    ranges.forEach { r ->
        if (r.first > at) append(text.substring(at, r.first))
        pushStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, background = mark))
        append(text.substring(r.first, (r.last + 1).coerceAtMost(text.length)))
        pop()
        at = r.last + 1
    }
    if (at < text.length) append(text.substring(at))
}

/** A round check for tasks: empty, or filled with a tick when done. */
@Composable
fun CheckCircle(done: Boolean, color: Color, onClick: () -> Unit, size: Dp = 24.dp) {
    val fill by animateFloatAsState(if (done) 1f else 0f, label = "check")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size + 16.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size).pressScale(interaction, 0.85f)) {
            val r = this.size.minDimension / 2
            drawCircle(color.copy(alpha = 0.55f + 0.45f * fill), radius = r - 1.dp.toPx(), style = Stroke(1.6.dp.toPx()))
            if (fill > 0f) drawCircle(color, radius = (r - 1.dp.toPx()) * fill)
        }
        if (done) Icon(FioIcons.Check, null, tint = FioColors.OnAccent, modifier = Modifier.size(size * 0.62f).graphicsLayer { alpha = fill })
    }
}

object Dates {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return when (d) {
            today -> t(R.string.day_today)
            today.plusDays(1) -> t(R.string.day_tomorrow)
            today.minusDays(1) -> t(R.string.day_yesterday)
            else -> if (d.year == today.year && d.isAfter(today.minusDays(6)) && d.isBefore(today.plusDays(7)))
                d.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
            else d.format(DateTimeFormatter.ofPattern(t(if (d.year == today.year) R.string.fmt_day_month else R.string.fmt_day_month_year), PT))
        }
    }

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).format(TIME)

    fun dayTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = day(ms, zone) + " · " + time(ms, zone)

    fun due(e: Entry, zone: ZoneId = ZoneId.systemDefault()): String? = e.dueAt?.let { if (e.dueHasTime) dayTime(it, zone) else day(it, zone) }
}

/**
 * One entry as a card: the title, and one quiet line of what matters (due, steps, status, a
 * snippet). The same on the main screen's answers and in the lists. [label] says what just happened
 * to it ("Nova tarefa"); [showKind] names what it is where kinds are mixed; [showStatus] is off where the list is already grouped by status;
 * [inToday] is for the Hoje tab, where "hoje" goes without saying.
 */
@Composable
fun EntryCard(
    entry: Entry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    terms: Collection<String> = emptyList(),
    onToggle: (() -> Unit)? = null,
    showKind: Boolean = true,
    showStatus: Boolean = true,
    inToday: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
) {
    val kind = entry.kindEnum
    val done = entry.statusEnum == Status.DONE
    val checkable = kind == Kind.TASK && onToggle != null
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .clip(shape)
            .background(FioColors.Surface)
            .stitched(shape, StitchColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = if (checkable) 6.dp else 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkable) CheckCircle(done, FioColors.Accent, onToggle!!)
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = FioColors.TextFaint, maxLines = 1)
                Spacer(Modifier.height(3.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlighted(entry.title, terms, FioColors.Text, FioColors.Accent.copy(alpha = 0.16f)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) FioColors.TextMuted else FioColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.sensitive) {
                    Spacer(Modifier.width(6.dp))
                    Icon(FioIcons.Lock, t(R.string.sensitive), tint = FioColors.TextFaint, modifier = Modifier.size(15.dp))
                }
            }
            val snippet = when {
                entry.sensitive -> null
                terms.isNotEmpty() -> Snippet.around(entry.body, terms).takeIf { it.isNotBlank() }
                kind != Kind.TASK -> entry.body.takeIf { it.isNotBlank() }
                else -> null
            }
            if (snippet != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    highlighted(snippet, terms, FioColors.Text, FioColors.Accent.copy(alpha = 0.16f)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FioColors.TextMuted,
                    maxLines = if (terms.isNotEmpty()) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = meta(entry, named = showKind && label == null && !checkable, withStatus = showStatus, inToday = inToday)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                val steps = remember(entry.steps) { Steps.read(entry.steps) }
                val stepsText = if (steps.isEmpty()) null else "${steps.count { it.done }}/${steps.size}"
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    meta.forEach { (text, strong) ->
                        if (text == stepsText && steps.size <= MAX_DOTS) StepDots(steps.count { it.done }, steps.size)
                        Text(text, style = MaterialTheme.typography.labelMedium, color = if (strong) FioColors.Accent else FioColors.TextFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The quiet line under a title; [named] starts it with what the entry is and when it was kept.
 * A day is said once: a reminder on the due day says only its time, and in the Hoje tab
 * ([inToday]) "hoje" is left out.
 */
fun meta(
    e: Entry,
    named: Boolean,
    withStatus: Boolean,
    inToday: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
): List<Pair<String, Boolean>> = buildList<Pair<String, Boolean>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    if (named) add((if (inToday && dayOf(e.createdAt) == today) kindLabel(e.kindEnum) else kindLabel(e.kindEnum) + " · " + Dates.day(e.createdAt, zone, today)) to false)
    if (e.kindEnum != Kind.TASK) return@buildList
    val status = e.statusEnum ?: Status.TODO
    if (withStatus && (status == Status.DOING || status == Status.WAITING || (named && status == Status.DONE))) add(statusLabel(status) to false)
    e.dueAt?.let { due ->
        val day = dayOf(due)
        val text = when {
            inToday && day == today -> if (e.dueHasTime) Dates.time(due, zone) else null
            e.dueHasTime -> Dates.day(due, zone, today) + " · " + Dates.time(due, zone)
            else -> Dates.day(due, zone, today)
        }
        if (text != null) add(text to (status != Status.DONE && !day.isAfter(today)))
    }
    val steps = Steps.read(e.steps)
    if (steps.isNotEmpty()) add("${steps.count { it.done }}/${steps.size}" to false)
    e.remindAt?.takeIf { it > now && !(e.dueHasTime && it == e.dueAt) }?.let { r ->
        val day = dayOf(r)
        val sameDay = e.dueAt?.let { dayOf(it) == day } == true || (inToday && day == today)
        add(t(R.string.reminder_at, if (sameDay) Dates.time(r, zone) else Dates.day(r, zone, today) + " · " + Dates.time(r, zone)) to false)
    }
}

private const val MAX_DOTS = 6

/** Steps as beads on the thread: the done ones full, the rest just the ring. */
@Composable
private fun StepDots(done: Int, total: Int) {
    val accent = FioColors.Accent
    Canvas(Modifier.size(width = (total * 12 - 4).dp, height = 8.dp)) {
        val r = 4.dp.toPx()
        for (k in 0 until total) {
            val c = androidx.compose.ui.geometry.Offset(r + k * 12.dp.toPx(), this.size.height / 2)
            if (k < done) drawCircle(accent, radius = r, center = c)
            else drawCircle(accent.copy(alpha = 0.5f), radius = r - 0.75.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FioColors.Line))
}
