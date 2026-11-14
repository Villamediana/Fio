package com.villamediana.novelo.ui.meds

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.meds.MedText
import com.villamediana.novelo.mind.DayDiary
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.CategoryChip
import com.villamediana.novelo.ui.components.CheckCircle
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.entry.ItemMenu
import com.villamediana.novelo.ui.entry.MenuAction
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/** How its times go: every N hours, at set hours, or only when needed. */
private enum class Mode { EVERY, FIXED, NEEDED }

/**
 * A medicine, set by hand — all of it on one page, kept as it changes: the name, for whom, the dose,
 * the times (every N hours, set hours or when needed), the days, from when until when, how to take it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedScreen(nav: Nav, id: Long) {
    val graph = NoveloApplication.graph
    val lib = graph.library
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val med by remember(id) { lib.med(id) }.collectAsStateWithLifecycle(initialValue = null)
    val all by lib.meds().collectAsStateWithLifecycle(initialValue = emptyList())
    var loaded by remember { mutableStateOf(false) }
    // What is on the page: changed here and kept a beat after (and when leaving).
    var draft by remember(id) { mutableStateOf<Med?>(null) }
    // The last one read: changed elsewhere (the model, a dose said) while nothing is being changed here, the page follows it.
    var seen by remember(id) { mutableStateOf<Med?>(null) }
    LaunchedEffect(med) {
        val m = med
        if (m == null) { if (loaded) nav.back(); return@LaunchedEffect }
        if (draft == null || draft?.copy(updatedAt = 0) == seen?.copy(updatedAt = 0)) draft = m
        seen = m
        loaded = true
    }
    val d = draft
    if (d == null) {
        Box(Modifier.fillMaxSize().paper())
        return
    }
    val fresh = remember(id) { d.name.isBlank() }
    LaunchedEffect(d) { delay(400); lib.saveMed(d) }
    DisposableEffect(id) { onDispose { graph.scope.launch { draft?.let { lib.saveMed(it) }; if (fresh) lib.dropMedIfEmpty(id) } } }
    fun set(change: (Med) -> Med) { draft = draft?.let(change) }

    val now = rememberNow()
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        com.villamediana.novelo.ui.sheets.ConfirmDialog(
            title = t(R.string.med_delete_title),
            line = t(R.string.med_delete_line),
            yes = t(R.string.delete),
            onYes = { askDelete = false; draft = null; scope.launch { lib.deleteMed(id) } },
            onDismiss = { askDelete = false },
        )
    }
    val nameField = remember { FocusRequester() }
    LaunchedEffect(id) { if (fresh) runCatching { nameField.requestFocus() } }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(NoveloIcons.Back, t(R.string.back), { nav.back() }, alpha = 0.8f)
            Spacer(Modifier.weight(1f))
            Box {
                QuietIcon(NoveloIcons.More, t(R.string.more), { menu = true }, alpha = 0.7f)
                ItemMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    actions = listOf(MenuAction(NoveloIcons.Trash, t(R.string.delete), "menu-delete", danger = true) { askDelete = true }),
                    offset = DpOffset((-8).dp, 0.dp),
                )
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Text(t(R.string.med_kind) + " · " + t(if (d.startsAt > now) R.string.med_starts else R.string.med_since, Dates.day(d.startsAt, zone)), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextFaint)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (d.name.isEmpty()) Text(t(R.string.med_name_hint), style = MaterialTheme.typography.headlineMedium, color = NoveloColors.TextFaint)
                    BasicTextField(
                        value = d.name,
                        onValueChange = { v -> set { it.copy(name = v.take(80)) } },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = NoveloColors.Text),
                        cursorBrush = SolidColor(NoveloColors.Accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(nameField).testTag("med-name"),
                    )
                }
                if (d.photoId != null) {
                    Spacer(Modifier.width(12.dp))
                    MedAvatar(d, 64.dp)
                }
            }

            // Today's times of it, to tick here too.
            val today = remember(d, now / 60_000) { Doses.day(listOf(d), Doses.dayOf(now, zone), zone) }
            if (today.isNotEmpty() && d.name.isNotBlank()) {
                val range = DayDiary.bounds(now, zone)
                val taken by remember(range.first) { lib.taken(range) }.collectAsStateWithLifecycle(initialValue = emptyList())
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    today.forEach { s ->
                        val ok = taken.any { it.medId == d.id && it.at == s.at }
                        Row(
                            Modifier.clip(CircleShape).background(NoveloColors.Surface2).clickable { graph.scope.launch { lib.toggleTaken(d.id, s.at) } }
                                .padding(start = 2.dp, end = 14.dp).testTag("med-today-${s.at}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckCircle(ok, personColor(d.person), { graph.scope.launch { lib.toggleTaken(d.id, s.at) } }, size = 18.dp)
                            Text(Doses.hhmm(Instant.ofEpochMilli(s.at).atZone(zone).toLocalTime()), style = MaterialTheme.typography.labelLarge, color = if (ok) NoveloColors.TextMuted else NoveloColors.Text)
                        }
                    }
                }
            }

            // For whom.
            Label(t(R.string.med_for))
            val people = remember(all) { all.mapNotNull { it.person }.distinctBy(Categories::fold).sortedBy(Categories::fold) }
            var adding by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(t(R.string.med_me), null, selected = d.person == null, onClick = { set { it.copy(person = null) } })
                (people + listOfNotNull(d.person)).distinctBy(Categories::fold).forEach { p ->
                    CategoryChip(p, p, selected = d.person == p, onClick = { set { it.copy(person = p) } })
                }
                if (adding) NameChip(onDone = { n -> adding = false; Categories.pick(n, people)?.let { p -> set { it.copy(person = p) } } })
                else Row(
                    Modifier.clip(CircleShape).background(NoveloColors.Surface2).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 7.dp).testTag("med-person-add"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NoveloIcons.Plus, null, tint = NoveloColors.TextMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(t(R.string.med_person_new), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
                }
            }

            // The dose: how strong one is, how much each time, and what that comes to.
            Label(t(R.string.med_dose))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(t(R.string.med_strength), d.strength.orEmpty(), "500 mg", Modifier.weight(1f).testTag("med-strength")) { v ->
                    set { it.copy(strength = v.ifBlank { null }, amount = Doses.amount(v, it.dose)) }
                }
                Field(t(R.string.med_each_time), d.dose.orEmpty(), t(R.string.med_each_time_hint), Modifier.weight(1.3f).testTag("med-each")) { v ->
                    set { it.copy(dose = v.ifBlank { null }, amount = Doses.amount(it.strength, v)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // A tap for the usual amounts: the number changes, the word stays ("comprimido" when there is none).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("¼", "½", "1", "2").forEach { q ->
                    val cur = d.dose.orEmpty()
                    val word = cur.replace(Regex("""^\s*([\d.,/]+|[½¼¾⅓⅔]|meio|meia|uma|um|duas|dois|una|uno|un|media|medio|half|one|two)(?![\p{L}])\s*""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { t(R.string.med_tablet) }
                    val made = "$q ${if (q == "2") plural(word) else singular(word)}"
                    Pill(q, selected = Categories.fold(cur) == Categories.fold(made), onClick = { set { it.copy(dose = made, amount = Doses.amount(it.strength, made)) } })
                }
                MedText.amount(d)?.let { a ->
                    Spacer(Modifier.width(6.dp))
                    Text("= " + t(R.string.med_each_time_is, a), style = MaterialTheme.typography.labelLarge, color = NoveloColors.Accent, modifier = Modifier.testTag("med-amount"))
                }
            }

            // The times.
            Label(t(R.string.med_times))
            val times = Doses.times(d)
            // With times, it reminds: notifications are asked for (once, Android 13+), or none would come.
            val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
            val reminds = times.isNotEmpty()
            LaunchedEffect(reminds) {
                if (reminds && android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val mode = when { d.everyHours != null && times.isNotEmpty() -> Mode.EVERY; times.isNotEmpty() -> Mode.FIXED; else -> Mode.NEEDED }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t(R.string.med_mode_every), mode == Mode.EVERY, {
                    val h = d.everyHours ?: 8
                    set { it.copy(everyHours = h, times = Doses.writeTimes(Doses.every(anchor(times), h))) }
                })
                Pill(t(R.string.med_mode_fixed), mode == Mode.FIXED, { set { it.copy(everyHours = null, times = Doses.writeTimes(times.ifEmpty { listOf(LocalTime.of(8, 0)) })) } })
                Pill(t(R.string.med_mode_needed), mode == Mode.NEEDED, { set { it.copy(everyHours = null, times = "[]") } })
            }
            fun pickTime(start: LocalTime, done: (LocalTime) -> Unit) {
                TimePickerDialog(context, { _, h, min -> done(LocalTime.of(h, min)) }, start.hour, start.minute, true).show()
            }
            Spacer(Modifier.height(12.dp))
            when (mode) {
                Mode.EVERY -> {
                    val h = d.everyHours ?: 8
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6, 8, 12, 24).forEach { n ->
                            Pill(t(R.string.med_hours, n), h == n, { set { it.copy(everyHours = n, times = Doses.writeTimes(Doses.every(anchor(times), n))) } })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TimeChip(t(R.string.med_first_dose, Doses.hhmm(anchor(times)))) { pickTime(anchor(times)) { t0 -> set { it.copy(times = Doses.writeTimes(Doses.every(t0, h))) } } }
                    Spacer(Modifier.height(8.dp))
                    Text(times.joinToString(" · ") { Doses.hhmm(it) }, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted)
                }
                Mode.FIXED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    times.forEach { at ->
                        TimeChip(Doses.hhmm(at), onRemove = if (times.size > 1) ({ set { it.copy(times = Doses.writeTimes(times - at)) } }) else null) {
                            pickTime(at) { t0 -> set { it.copy(times = Doses.writeTimes(times - at + t0)) } }
                        }
                    }
                    TimeChip("+ " + t(R.string.med_time_add), tag = "med-time-add") {
                        pickTime(times.lastOrNull()?.plusHours(4) ?: LocalTime.of(8, 0)) { t0 -> set { it.copy(times = Doses.writeTimes(times + t0)) } }
                    }
                }
                Mode.NEEDED -> Text(t(R.string.med_needed_line), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted)
            }

            // The days of the week (all of them: every day).
            if (mode != Mode.NEEDED) {
                Label(t(R.string.med_days))
                val days = Doses.days(d).ifEmpty { (1..7).toSet() }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { n ->
                        val on = n in days
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (on) NoveloColors.Accent.copy(alpha = 0.13f) else NoveloColors.Surface2)
                                .clickable { val next = if (on) days - n else days + n; if (next.isNotEmpty()) set { it.copy(days = Doses.writeDays(next)) } }
                                .semantics { contentDescription = DayOfWeek.of(n).getDisplayName(TextStyle.FULL, Lang.locale); selected = on }
                                .testTag("med-day-$n"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(DayOfWeek.of(n).getDisplayName(TextStyle.NARROW, Lang.locale).uppercase(Lang.locale), style = MaterialTheme.typography.labelLarge, color = if (on) NoveloColors.Accent else NoveloColors.TextFaint)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (days.size == 7) t(R.string.med_every_day) else MedText.dayNames(days), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted)
            }

            // From when, until when.
            Label(t(R.string.med_period))
            fun pickDay(start: Long, done: (LocalDate) -> Unit) {
                val s = Doses.dayOf(start, zone)
                DatePickerDialog(context, { _, y, m, day -> done(LocalDate.of(y, m + 1, day)) }, s.year, s.monthValue - 1, s.dayOfMonth).show()
            }
            val startDay = Doses.dayOf(d.startsAt, zone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(R.string.med_starts_on), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.width(72.dp))
                TimeChip(Dates.day(d.startsAt, zone), icon = true) {
                    pickDay(d.startsAt) { day ->
                        // Today: from now on (the times already gone today don't count); another day: from its start.
                        val at = if (day == Doses.dayOf(System.currentTimeMillis(), zone)) System.currentTimeMillis() else day.atStartOfDay(zone).toInstant().toEpochMilli()
                        set { it.copy(startsAt = at, endsAt = it.endsAt?.takeIf { e -> e >= at }) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(t(R.string.med_ends_on), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.width(72.dp).padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(t(R.string.med_no_end), d.endsAt == null, { set { it.copy(endsAt = null) } })
                    listOf(5, 7, 10, 14, 30).forEach { n ->
                        val end = Doses.endOf(startDay.plusDays(n - 1L), zone)
                        Pill(t(R.string.med_n_days, n), d.endsAt == end, { set { it.copy(endsAt = end) } })
                    }
                    val custom = d.endsAt?.takeIf { e -> listOf(5, 7, 10, 14, 30).none { Doses.endOf(startDay.plusDays(it - 1L), zone) == e } }
                    TimeChip(custom?.let { t(R.string.med_until, Dates.day(it, zone)) } ?: t(R.string.med_pick_day), icon = true, selected = custom != null) {
                        pickDay(d.endsAt ?: d.startsAt) { day -> set { it.copy(endsAt = Doses.endOf(day, zone).coerceAtLeast(it.startsAt)) } }
                    }
                }
            }
            MedText.period(d, now, zone)?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Accent)
            }
            // The doses of the treatment, given and to come.
            val given by remember { lib.allTaken() }.collectAsStateWithLifecycle(initialValue = emptyList())
            val mine = remember(given, d.id) { given.filter { it.medId == d.id }.map { it.at }.toSet() }
            Doses.course(d, mine, now, zone)?.takeIf { it.whole && d.name.isNotBlank() }?.let { c ->
                Spacer(Modifier.height(14.dp))
                DoseDots(c, personColor(d.person))
                Spacer(Modifier.height(6.dp))
                Text(dosesText(c), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted)
            }

            // How to take it.
            Label(t(R.string.med_notes))
            Field(null, d.notes, t(R.string.med_notes_hint), Modifier.fillMaxWidth().testTag("med-notes"), single = false) { v -> set { it.copy(notes = v) } }
        }
    }
}

/** Which of every-N-hours times reads as the first dose (one from the morning on); 08:00 when there are none. */
private fun anchor(times: List<LocalTime>): LocalTime = times.firstOrNull { it.hour >= 6 } ?: times.firstOrNull() ?: LocalTime.of(8, 0)

/** "comprimidos" → "comprimido" (and back), for the amounts tapped. */
private fun singular(word: String): String = if (word.endsWith("s") && word.length > 3) word.dropLast(1) else word
private fun plural(word: String): String = singular(word) + "s"

/** A quiet word over a part of the page. */
@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(26.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
    Spacer(Modifier.height(10.dp))
}

/** A soft box to write in, its name small on top. */
@Composable
private fun Field(label: String?, value: String, hint: String, modifier: Modifier = Modifier, single: Boolean = true, onChange: (String) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(NoveloColors.Surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NoveloColors.TextFaint)
            Spacer(Modifier.height(2.dp))
        }
        Box {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(if (single) 40 else 300)) },
                singleLine = single,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
                cursorBrush = SolidColor(NoveloColors.Accent),
                keyboardOptions = KeyboardOptions(capitalization = if (single) KeyboardCapitalization.None else KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A chip of a time or a day: a tap changes it; [onRemove] puts a × on it. */
@Composable
private fun TimeChip(text: String, onRemove: (() -> Unit)? = null, icon: Boolean = false, selected: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(CircleShape).background(if (selected) NoveloColors.Accent.copy(alpha = 0.13f) else NoveloColors.Surface2).clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onRemove != null) 4.dp else 14.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(NoveloIcons.Calendar, null, tint = NoveloColors.Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) NoveloColors.Accent else NoveloColors.Text)
        if (onRemove != null) {
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(NoveloIcons.Close, t(R.string.clear), tint = NoveloColors.TextFaint, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** A name written in place of the "+": done (or let go) puts it there. */
@Composable
private fun NameChip(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.width(150.dp).clip(CircleShape).background(NoveloColors.Surface2).padding(horizontal = 14.dp, vertical = 8.dp)) {
        if (name.isEmpty()) Text(t(R.string.med_person_hint), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextFaint)
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(Categories.MAX) },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = NoveloColors.Text),
            cursorBrush = SolidColor(NoveloColors.Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(name) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("med-person-name"),
        )
    }
}
