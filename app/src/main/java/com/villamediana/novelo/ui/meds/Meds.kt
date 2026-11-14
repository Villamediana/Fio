package com.villamediana.novelo.ui.meds

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.meds.MedText
import com.villamediana.novelo.mind.DayDiary
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.CategoryChip
import com.villamediana.novelo.ui.components.CategoryColors
import com.villamediana.novelo.ui.components.CategoryTag
import com.villamediana.novelo.ui.components.CheckCircle
import com.villamediana.novelo.ui.components.GroupGap
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.PhotoTile
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.groupShape
import com.villamediana.novelo.ui.components.pressScale
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Now, again every half minute: what is due "agora" moves on by itself. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

/** Today's medicines (their times, each to tick) and the times taken, kept fresh. */
class Today(val meds: List<Med>, val slots: List<Doses.Slot>, val taken: Set<Pair<Long, Long>>)

@Composable
fun rememberToday(now: Long, zone: ZoneId = ZoneId.systemDefault()): Today? {
    val graph = NoveloApplication.graph
    // Kept in memory from the app's start (AppGraph): here at once, not coming in a beat after the screen.
    val meds by graph.medsNow.collectAsStateWithLifecycle()
    val all by graph.takenNow.collectAsStateWithLifecycle()
    val day = DayDiary.bounds(now, zone)
    val list = meds ?: return null
    val taken = all ?: return null
    val slots = remember(list, day.first) { Doses.day(list.filter { it.name.isNotBlank() }, Doses.dayOf(now, zone), zone) }
    return Today(list, slots, remember(taken, day.first) { taken.filter { it.at in day }.map { it.medId to it.at }.toSet() })
}

/**
 * Remédios: the list (Miguel, 1.66.1) — the medicines in use, by person when there are some (Você,
 * Cooper, Aurora…), each with its doses as beads; folded, the ones that ended. The times of the day
 * (what is due now, the next one) are Hoje's.
 */
@Composable
fun MedsPage(onOpen: (Long) -> Unit, empty: @Composable () -> Unit) {
    val graph = NoveloApplication.graph
    val now = rememberNow()
    val zone = ZoneId.systemDefault()
    val today = rememberToday(now, zone) ?: return
    var who by rememberSaveable { mutableStateOf<String?>(null) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    val named = today.meds.filter { it.name.isNotBlank() }
    // The people of the medicines in use (her first, "Você"), when there is more than one.
    val people = remember(named, now / 60_000) { named.filterNot { Doses.ended(it, now) }.map { it.person }.distinctBy { it?.let(Categories::fold) }.sortedWith(compareBy<String?>({ it != null }, { it?.let(Categories::fold) })) }
    val chosen = who?.takeIf { w -> people.any { (it ?: ME) == w } }
    fun mine(m: Med) = chosen == null || (m.person ?: ME) == chosen
    val shown = named.filter(::mine)
    val (ended, inUse) = shown.partition { Doses.ended(it, now) }
    // Every dose given, by medicine: the beads of each treatment.
    val allTaken by graph.takenNow.collectAsStateWithLifecycle()
    val takenBy = remember(allTaken) { allTaken.orEmpty().groupBy({ it.medId }, { it.at }).mapValues { it.value.toSet() } }

    Column(Modifier.fillMaxSize()) {
        if (people.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 6.dp).testTag("med-people"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryChip(t(R.string.med_all), null, selected = chosen == null, onClick = { who = null })
                people.forEach { p ->
                    val key = p ?: ME
                    CategoryChip(p ?: t(R.string.med_me), p, selected = chosen == key, onClick = { who = if (chosen == key) null else key })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("meds"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            if (named.isEmpty()) item(key = "empty") { empty() }
            // In use: one group, or one per person (her first) when there are more and none is chosen.
            val groups = if (people.size > 1 && chosen == null) people.map { p -> (p ?: t(R.string.med_me)) to inUse.filter { it.person == p } }.filter { it.second.isNotEmpty() }
            else listOf(t(R.string.med_in_use) to inUse).filter { it.second.isNotEmpty() }
            groups.forEach { (label, list) -> medGroup(label, list, now, onOpen, showPerson = false, takenBy = takenBy) }
            if (ended.isNotEmpty()) {
                item(key = "ended-toggle") {
                    Text(
                        if (showEnded) t(R.string.med_ended_hide) else t(R.string.med_ended_show, ended.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = NoveloColors.TextMuted,
                        modifier = Modifier.animateItem().padding(top = 10.dp).clip(CircleShape).clickable { showEnded = !showEnded }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (showEnded) medGroup(t(R.string.med_ended), ended.sortedByDescending { it.endsAt }, now, onOpen, showPerson = people.size > 1, faded = true, takenBy = takenBy)
            }
        }
    }
}

/** A group of medicine cards under its label, touching like the tasks. */
private fun LazyListScope.medGroup(label: String, list: List<Med>, now: Long, onOpen: (Long) -> Unit, showPerson: Boolean, faded: Boolean = false, takenBy: Map<Long, Set<Long>> = emptyMap()) {
    item(key = "label-$label-$faded") { SectionLabel(label, Modifier.animateItem()) }
    itemsIndexed(list, key = { _, m -> "med-${m.id}" }) { i, m ->
        Box(Modifier.animateItem().padding(bottom = if (i == list.lastIndex) 8.dp else GroupGap)) {
            MedCard(m, now, onClick = { onOpen(m.id) }, shape = groupShape(i, list.size), showPerson = showPerson, faded = faded, taken = takenBy[m.id].orEmpty(), modifier = Modifier.testTag("med-$i"))
        }
    }
}

/** Hoje's medicines: the times of the last [back] (taken or not) and the next one to come. */
fun nearTimes(slots: List<Doses.Slot>, now: Long, back: Long = 2 * 60 * 60_000L): List<Doses.Slot> {
    val gone = slots.filter { it.at in (now - back)..now }
    val next = slots.firstOrNull { it.at > now }
    return gone + listOfNotNull(next)
}

/** "Você" in the people filter (a medicine with no person is hers). */
private const val ME = "\u0000me"

/** Someone's color (the same as a category of that name would have); hers is the accent. */
@Composable
fun personColor(person: String?): Color = person?.let { CategoryColors.of(it, NoveloColors.palette.dark) } ?: NoveloColors.Accent

/**
 * The day's times, one card each touching the next: the hour on the left (the one that is due now
 * or next in the accent), the medicines of that hour on the right, each with its circle to tick.
 */
@Composable
fun TodayDoses(
    slots: List<Doses.Slot>,
    taken: Set<Pair<Long, Long>>,
    now: Long,
    onToggle: (Long, Long) -> Unit,
    onOpen: (Long) -> Unit,
    showPeople: Boolean,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    // The time to look at: the first not all taken that isn't long gone ("agora" near it, "próximo" before it).
    val focus = slots.firstOrNull { s -> s.at >= now - FOCUS_BEFORE_MS && s.meds.any { (it.id to s.at) !in taken } }?.at
    Column(modifier.fillMaxWidth().testTag("today-doses")) {
        slots.forEachIndexed { i, s ->
            val shape = groupShape(i, slots.size)
            val done = s.meds.all { (it.id to s.at) in taken }
            val here = s.at == focus
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(NoveloColors.Surface)
                    .stitched(shape, StitchColor)
                    .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                    .animateContentSize(),
            ) {
                Column(Modifier.width(62.dp).padding(top = 10.dp)) {
                    Text(
                        Doses.hhmm(Instant.ofEpochMilli(s.at).atZone(zone).toLocalTime()),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = when { done -> NoveloColors.TextFaint; here -> NoveloColors.Accent; else -> NoveloColors.Text },
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                    )
                    if (here) Text(t(if (s.at <= now) R.string.med_now else R.string.med_next), style = MaterialTheme.typography.labelSmall, color = NoveloColors.Accent)
                }
                Column(Modifier.weight(1f)) {
                    s.meds.forEach { m ->
                        val ok = (m.id to s.at) in taken
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(m.id) }.testTag("dose-${m.id}-${s.at}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.testTag("tick-${m.id}-${s.at}")) { CheckCircle(ok, personColor(m.person), { onToggle(m.id, s.at) }, size = 22.dp) }
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(
                                    m.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = if (ok) NoveloColors.TextMuted else NoveloColors.Text,
                                    textDecoration = if (ok) TextDecoration.LineThrough else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = listOfNotNull(m.dose?.takeIf { it.isNotBlank() } ?: MedText.amount(m) ?: m.strength, m.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = NoveloColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (showPeople && m.person != null) CategoryTag(m.person, Modifier.padding(start = 8.dp, end = 6.dp))
                        }
                    }
                }
            }
            if (i < slots.lastIndex) Spacer(Modifier.height(GroupGap))
        }
    }
}

/** A time stays "agora" this long after it, while not taken. */
private const val FOCUS_BEFORE_MS = 90 * 60_000L

/**
 * One medicine as a card: its photo (or a capsule in its person's color), the name, the dose, the times,
 * and — when it has an end — the thread of the treatment, the part gone full and the rest stitched.
 */
@Composable
fun MedCard(
    m: Med,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    label: String? = null,
    showPerson: Boolean = true,
    faded: Boolean = false,
    /** The times of it taken: its doses as beads. */
    taken: Set<Long> = emptySet(),
) {
    val zone = ZoneId.systemDefault()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, StitchColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
    ) {
        MedAvatar(m, 46.dp, faded)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = NoveloColors.TextFaint, maxLines = 1)
                Spacer(Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name.ifBlank { t(R.string.med_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (faded) NoveloColors.TextMuted else NoveloColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showPerson && m.person != null) CategoryTag(m.person, Modifier.padding(start = 10.dp))
            }
            MedText.dose(m)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (Doses.times(m).isEmpty()) NoveloIcons.Pill else NoveloIcons.Bell, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(MedText.schedule(m), style = MaterialTheme.typography.labelMedium, color = NoveloColors.TextFaint, maxLines = 2)
            }
            val period = MedText.period(m, now, zone)
            // The doses as beads (the whole treatment, or today's); otherwise the thread of its days.
            val course = remember(m, taken, now / 60_000) { Doses.course(m, taken, now, zone) }?.takeIf { !faded || it.whole }
            val progress = Doses.progress(m, now, zone).takeIf { !faded && m.startsAt <= now && course == null }
            if (course != null) {
                Spacer(Modifier.height(10.dp))
                DoseDots(course, personColor(m.person).let { if (faded) it.copy(alpha = 0.55f) else it })
            } else if (progress != null) {
                Spacer(Modifier.height(9.dp))
                Thread(progress, personColor(m.person))
            }
            val line = listOfNotNull(course?.let(::dosesText), period)
            if (line.isNotEmpty()) {
                Spacer(Modifier.height(if (course != null || progress != null) 6.dp else 4.dp))
                val last = (Doses.daysLeft(m, now, zone) ?: 2) <= 1 && !faded
                Text(line.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = if (last) NoveloColors.Accent else NoveloColors.TextFaint, maxLines = 2)
            }
        }
    }
}

/**
 * A treatment's doses as beads, a little cluster a day: the ones given full in the person's color, the
 * ones to come a ring of it, the ones gone by without a tick a quiet gray ring.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoseDots(course: Doses.Course, color: Color, modifier: Modifier = Modifier) {
    val gone = NoveloColors.TextFaint.copy(alpha = 0.6f)
    FlowRow(modifier.testTag("dose-dots"), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        course.days.forEach { day ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                day.forEach { d ->
                    Box(
                        Modifier.size(9.dp).clip(CircleShape).then(
                            if (d.taken) Modifier.background(color)
                            else Modifier.border(1.4.dp, if (d.past) gone else color.copy(alpha = 0.55f), CircleShape),
                        ),
                    )
                }
            }
        }
    }
}

/** "8 de 21 doses" (the whole treatment), "hoje: 1 de 3". */
fun dosesText(c: Doses.Course): String = t(if (c.whole) R.string.med_doses_course else R.string.med_doses_today, c.taken, c.dots.size)

/** How far the treatment went: the part gone a full thread, the rest a running stitch. */
@Composable
private fun Thread(progress: Float, color: Color) {
    val rest = NoveloColors.TextFaint.copy(alpha = 0.5f)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val y = size.height / 2
        val w = 2.4.dp.toPx()
        val at = (size.width * progress).coerceIn(0f, size.width)
        if (at < size.width) drawLine(rest, Offset(at, y), Offset(size.width, y), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        if (at > 0f) drawLine(color, Offset(w / 2, y), Offset(maxOf(at, w / 2), y), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(color, radius = 3.4.dp.toPx(), center = Offset(at.coerceIn(3.4.dp.toPx(), size.width - 3.4.dp.toPx()), y))
    }
}

/** Its photo (the box, the prescription), or a capsule in a soft circle of its person's color. */
@Composable
fun MedAvatar(m: Med, size: Dp, faded: Boolean = false) {
    val graph = NoveloApplication.graph
    val path by produceState<String?>(null, m.photoId) { value = m.photoId?.let { graph.library.capture(it)?.imagePath }?.takeIf { java.io.File(it).isFile } }
    val color = personColor(m.person).let { if (faded) it.copy(alpha = 0.5f) else it }
    val p = path
    if (p != null) {
        PhotoTile(p, Modifier.size(size).clip(RoundedCornerShape(14.dp)), maxPx = 240)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(NoveloIcons.Pill, null, tint = color, modifier = Modifier.size(size * 0.5f))
        }
    }
}
