package com.villamediana.fio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val PT: Locale = Locale.forLanguageTag("pt-BR")

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

/** The mark: one thread that loops once and runs on (a thought kept). */
@Composable
fun FioMark(size: Dp = 40.dp, color: Color = FioColors.Accent, background: Color = FioColors.Text) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(background)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.14f, w * 0.64f)
            cubicTo(w * 0.34f, w * 0.64f, w * 0.50f, w * 0.62f, w * 0.58f, w * 0.50f)
            cubicTo(w * 0.66f, w * 0.38f, w * 0.61f, w * 0.24f, w * 0.50f, w * 0.24f)
            cubicTo(w * 0.39f, w * 0.24f, w * 0.34f, w * 0.38f, w * 0.42f, w * 0.50f)
            cubicTo(w * 0.50f, w * 0.62f, w * 0.66f, w * 0.64f, w * 0.86f, w * 0.64f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))
    }
}

@Composable
fun KindDot(kind: Kind, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(FioColors.of(kind)))
}

fun kindLabel(kind: Kind): String = when (kind) {
    Kind.TASK -> "Tarefa"
    Kind.NOTE -> "Nota"
    Kind.DIARY -> "Diário"
}

fun statusLabel(status: Status): String = when (status) {
    Status.TODO -> "A fazer"
    Status.DOING -> "Fazendo"
    Status.WAITING -> "Esperando"
    Status.DONE -> "Feito"
}

/** A rounded word that can be chosen (filters, statuses). */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    val bg by animateColorAsState(if (selected) FioColors.Text else FioColors.Surface2, label = "pillBg")
    val fg by animateColorAsState(if (selected) FioColors.Background else FioColors.TextMuted, label = "pillFg")
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(PT),
        style = MaterialTheme.typography.labelSmall,
        color = FioColors.TextFaint,
        modifier = modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

/** [text] with the matched words in ink and bold (search results, answers). */
fun highlighted(text: String, terms: Collection<String>, color: Color): AnnotatedString = buildAnnotatedString {
    val ranges = Snippet.ranges(text, terms)
    var at = 0
    ranges.forEach { r ->
        if (r.first > at) append(text.substring(at, r.first))
        pushStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, background = color.copy(alpha = 0.10f)))
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
        if (done) Icon(Icons.Rounded.Check, null, tint = FioColors.Background, modifier = Modifier.size(size * 0.62f).graphicsLayer { alpha = fill })
    }
}

object Dates {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return when (d) {
            today -> "hoje"
            today.plusDays(1) -> "amanhã"
            today.minusDays(1) -> "ontem"
            else -> if (d.year == today.year && d.isAfter(today.minusDays(6)) && d.isBefore(today.plusDays(7)))
                d.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
            else d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d 'de' MMM" else "d 'de' MMM yyyy", PT))
        }
    }

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).format(TIME)

    fun dayTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = day(ms, zone) + " · " + time(ms, zone)

    fun due(e: Entry, zone: ZoneId = ZoneId.systemDefault()): String? = e.dueAt?.let { if (e.dueHasTime) dayTime(it, zone) else day(it, zone) }

    /** "terça, 11 de novembro" for the top of the main screen. */
    fun header(zone: ZoneId = ZoneId.systemDefault()): String {
        val d = LocalDate.now(zone)
        return d.dayOfWeek.getDisplayName(TextStyle.FULL, PT).substringBefore("-") + ", " + d.format(DateTimeFormatter.ofPattern("d 'de' MMMM", PT))
    }
}

/**
 * One entry as a card: kind dot, title, a line of what matters (due, steps, status, a snippet) —
 * the same look on the main screen's answers and in the lists.
 */
@Composable
fun EntryCard(
    entry: Entry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    terms: Collection<String> = emptyList(),
    onToggle: (() -> Unit)? = null,
) {
    val kind = entry.kindEnum
    val done = entry.statusEnum == Status.DONE
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .clip(RoundedCornerShape(20.dp))
            .background(FioColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = if (kind == Kind.TASK && onToggle != null) 4.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == Kind.TASK && onToggle != null) {
            CheckCircle(done, FioColors.of(kind), onToggle)
        }
        Column(Modifier.weight(1f)) {
            if (label != null || kind != Kind.TASK || onToggle == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindDot(kind, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (label ?: kindLabel(kind)).uppercase(PT) + "  ·  " + Dates.day(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = FioColors.TextFaint,
                        maxLines = 1,
                    )
                    if (entry.sensitive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Lock, null, tint = FioColors.TextFaint, modifier = Modifier.size(11.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                highlighted(entry.title, terms, FioColors.Text),
                style = MaterialTheme.typography.titleMedium,
                color = if (done) FioColors.TextMuted else FioColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val meta = meta(entry)
            val snippet = when {
                entry.sensitive -> null
                terms.isNotEmpty() -> Snippet.around(entry.body, terms).takeIf { it.isNotBlank() }
                kind != Kind.TASK -> entry.body.takeIf { it.isNotBlank() }
                else -> null
            }
            if (snippet != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    highlighted(snippet, terms, FioColors.Text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FioColors.TextMuted,
                    maxLines = if (terms.isNotEmpty()) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    meta.forEach { (text, strong) ->
                        Text(text, style = MaterialTheme.typography.labelMedium, color = if (strong) FioColors.Accent else FioColors.TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun meta(e: Entry): List<Pair<String, Boolean>> = buildList<Pair<String, Boolean>> {
    if (e.kindEnum != Kind.TASK) return@buildList
    val status = e.statusEnum ?: Status.TODO
    if (status == Status.DOING || status == Status.WAITING) add(statusLabel(status) to false)
    Dates.due(e)?.let { d ->
        val late = e.dueAt != null && status != Status.DONE && e.dueAt < LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        add(d to (late || d.startsWith("hoje")))
    }
    val steps = Steps.read(e.steps)
    if (steps.isNotEmpty()) add("${steps.count { it.done }}/${steps.size}" to false)
    e.remindAt?.takeIf { it > System.currentTimeMillis() && !(e.dueHasTime && it == e.dueAt) }?.let { add(("lembrete " + Dates.dayTime(it)) to false) }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FioColors.Line))
}
