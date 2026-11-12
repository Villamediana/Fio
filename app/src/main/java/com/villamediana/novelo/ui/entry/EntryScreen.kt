package com.villamediana.novelo.ui.entry

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import com.villamediana.novelo.ui.lock.screen

import com.villamediana.novelo.ui.lock.Unlock

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.theme.NoveloText
import androidx.compose.ui.unit.sp
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.villamediana.novelo.ui.components.IconAction
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.villamediana.novelo.ui.components.NoveloIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.mind.Steps
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.CheckCircle
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.components.kindLabel
import com.villamediana.novelo.ui.components.statusLabel
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun EntryScreen(nav: Nav, id: Long) {
    val graph = NoveloApplication.graph
    val lib = graph.library
    val scope = rememberCoroutineScope()
    val entry by lib.entry(id).collectAsStateWithLifecycle(initialValue = null)
    val capture by produceState<Capture?>(null, entry?.captureId) { value = entry?.captureId?.let { lib.capture(it) } }
    var loaded by remember { mutableStateOf(false) }

    val e = entry
    if (e == null) {
        // Deleted (or never there): back to where we came from.
        LaunchedEffect(loaded) { if (loaded) nav.back() }
        Box(Modifier.fillMaxSize().paper())
        return
    }
    LaunchedEffect(Unit) { loaded = true }

    var title by remember(e.id) { mutableStateOf(e.title) }
    var body by remember(e.id) { mutableStateOf(e.body) }
    // The text can grow from elsewhere (news the model joins to it): follow it, unless it is being typed.
    var seenTitle by remember(e.id) { mutableStateOf(e.title) }
    var seenBody by remember(e.id) { mutableStateOf(e.body) }
    LaunchedEffect(e.title, e.body) {
        if (title == seenTitle) title = e.title
        if (body == seenBody) body = e.body
        seenTitle = e.title
        seenBody = e.body
    }
    var revealed by remember(e.id) { mutableStateOf(!e.sensitive) }
    val here = androidx.compose.ui.platform.LocalContext.current
    val prefs by graph.settings.now.collectAsStateWithLifecycle()
    // Toque para ver: with the phone's lock first, when she asked for it (Ajustes › Privacidade).
    val reveal: () -> Unit = {
        val activity = here.screen()
        if (!prefs.lockSensitive || activity == null || !Unlock.available(here)) revealed = true
        else scope.launch { if (Unlock.ask(activity)) revealed = true }
    }
    var menu by remember { mutableStateOf(false) }
    // Deleting asks once more, saying what goes with it (the photos and files that live only in it).
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        val inside by produceState<Pair<List<Long>, List<Long>>?>(null, e.id) { value = lib.onlyIn(e.id) }
        com.villamediana.novelo.ui.sheets.ConfirmSheet(
            title = t(R.string.delete_item_title),
            line = inside?.takeIf { (p, f) -> p.isNotEmpty() || f.isNotEmpty() }?.let { t(R.string.delete_item_with) },
            yes = t(R.string.delete),
            onYes = { askDelete = false; scope.launch { lib.delete(e.id) } },
            onDismiss = { askDelete = false },
        )
    }
    // Steps only take room when there are some (or one is being written); what was said, folded.
    var addingStep by remember(e.id) { mutableStateOf(false) }
    var showSaid by remember(e.id) { mutableStateOf(false) }
    val hasSteps = remember(e.steps) { Steps.read(e.steps).isNotEmpty() }
    // Saved as it is typed (a beat after), and when leaving.
    LaunchedEffect(title, body) { delay(600); lib.edit(e.id, title, body) }
    // Made by hand (opened empty): the title is where she starts; left with nothing written, it isn't kept.
    val fresh = remember(e.id) { e.title.isBlank() && e.body.isBlank() }
    val titleField = remember { FocusRequester() }
    LaunchedEffect(e.id) { if (fresh) runCatching { titleField.requestFocus() } }
    DisposableEffect(e.id) { onDispose { graph.scope.launch { lib.edit(e.id, title, body); if (fresh) lib.dropIfEmpty(e.id) } } }

    val kind = e.kindEnum
    Column(
        Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(NoveloIcons.Back, t(R.string.back), { nav.back() }, alpha = 0.8f)
            Spacer(Modifier.weight(1f))
            Box {
                QuietIcon(NoveloIcons.More, t(R.string.more), { menu = true }, alpha = 0.7f)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = NoveloColors.Surface) {
                    Kind.entries.filter { it != kind }.forEach { k ->
                        DropdownMenuItem(text = { Text(t(R.string.turn_into, kindLabel(k).lowercase())) }, onClick = { menu = false; scope.launch { lib.setKind(e.id, k) } })
                    }
                    DropdownMenuItem(
                        text = { Text(if (e.sensitive) t(R.string.not_sensitive) else t(R.string.mark_sensitive)) },
                        onClick = { menu = false; scope.launch { lib.setSensitive(e.id, !e.sensitive) } },
                    )
                    DropdownMenuItem(text = { Text(t(R.string.delete), color = NoveloColors.Danger) }, onClick = { menu = false; askDelete = true })
                }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            val diary = kind == Kind.DIARY
            // The diary's day written by hand; the rest says what it is and when it was kept.
            if (diary) Text(diaryDay(e.createdAt), style = NoveloText.Hand.copy(fontSize = 22.sp, lineHeight = 26.sp), color = NoveloColors.TextMuted)
            else Text(kindLabel(kind) + " · " + Dates.dayTime(e.createdAt), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextFaint)
            Spacer(Modifier.height(if (diary) 6.dp else 10.dp))
            Box {
                if (title.isEmpty()) Text(t(R.string.title_hint), style = MaterialTheme.typography.headlineMedium, color = NoveloColors.TextFaint)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = if (e.statusEnum == Status.DONE) NoveloColors.TextMuted else NoveloColors.Text,
                        textDecoration = if (e.statusEnum == Status.DONE) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(NoveloColors.Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleField).testTag("entry-title"),
                )
            }

            if (kind == Kind.TASK) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Status.entries.forEach { st ->
                        Pill(statusLabel(st), selected = (e.statusEnum ?: Status.TODO) == st, onClick = { scope.launch { lib.setStatus(e.id, st) } })
                    }
                }
            }

            // The diary: the day's photos first, then its text on a stitched page in the serif.
            if (diary) DayPhotos(e, side = 24.dp)
            Spacer(Modifier.height(18.dp))
            val page = RoundedCornerShape(24.dp)
            val bodyStyle = if (diary) NoveloText.Diary else MaterialTheme.typography.bodyLarge
            // Something sensitive not shown yet: the text and what goes with it blurred together, and
            // one "Toque pra ver" in the middle of all of it (the whole of it is the place to tap).
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().then(
                            if (diary) Modifier.clip(page).background(NoveloColors.Surface).stitched(page, StitchColor, inset = 8.dp).padding(horizontal = 22.dp, vertical = 22.dp)
                            else Modifier,
                        ),
                    ) {
                        val blurred = !revealed && Build.VERSION.SDK_INT >= 31
                        Box(if (blurred) Modifier.blur(10.dp) else Modifier) {
                            if (body.isEmpty()) Text(t(R.string.details_hint), style = bodyStyle, color = NoveloColors.TextFaint)
                            BasicTextField(
                                value = if (!revealed && Build.VERSION.SDK_INT < 31) "••••••••" else body,
                                onValueChange = { if (revealed) body = it },
                                readOnly = !revealed,
                                textStyle = bodyStyle.copy(color = NoveloColors.Text),
                                cursorBrush = SolidColor(NoveloColors.Accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // The day's photos live in the day's diary (above its text); an item's, under its text.
                    if (!diary) EntryPhotos(e, side = 24.dp, hidden = !revealed, onReveal = reveal)
                    // Its files (on the day's diary, the day's too): a line each, a tap opens it.
                    EntryFiles(e, hidden = !revealed, onReveal = reveal)
                    // A sound kept with it (a melody she sang): to hear again.
                    EntrySounds(e)
                }
                if (!revealed) {
                    Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = reveal))
                    TapToSee(Modifier.align(Alignment.Center), reveal)
                }
            }

            if (kind == Kind.TASK && (hasSteps || addingStep)) {
                StepsBlock(
                    e,
                    focusNew = addingStep && !hasSteps,
                    onToggle = { i -> scope.launch { lib.toggleStep(e.id, i) } },
                    onAdd = { t -> scope.launch { lib.addStep(e.id, t) } },
                    onRemove = { i -> scope.launch { lib.removeStep(e.id, i) } },
                    onMove = { from, to -> scope.launch { lib.moveStep(e.id, from, to) } },
                )
            }

            DatesBlock(
                e,
                onDue = { at, t -> scope.launch { lib.setDue(e.id, at, t) } },
                onRemind = { at -> scope.launch { lib.setReminder(e.id, at) } },
                onAddStep = if (kind == Kind.TASK && !hasSteps && !addingStep) ({ addingStep = true }) else null,
            )

            // What was said stays folded: it is the source, not the thing kept.
            capture?.takeIf { it.source != com.villamediana.novelo.data.Capture.FILE }?.transcript?.takeIf { it.isNotBlank() }?.let { said ->
                Row(
                    Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable { showSaid = !showSaid }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (capture?.source) { "photo" -> t(R.string.said_photo); com.villamediana.novelo.data.Capture.TEXT -> t(R.string.said_typed); else -> t(R.string.said_spoken) },
                        style = MaterialTheme.typography.labelLarge,
                        color = NoveloColors.TextMuted,
                    )
                    Icon(
                        NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = if (showSaid) 90f else 0f },
                    )
                }
                AnimatedVisibility(showSaid, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        "“" + said.trim() + "”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoveloColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(NoveloColors.Surface2).padding(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StepsBlock(e: Entry, focusNew: Boolean, onToggle: (Int) -> Unit, onAdd: (String) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit = { _, _ -> }) {
    val steps = remember(e.steps) { Steps.read(e.steps) }
    var adding by remember { mutableStateOf("") }
    val newStep = remember { FocusRequester() }
    LaunchedEffect(focusNew) { if (focusNew) runCatching { newStep.requestFocus() } }
    val haptics = LocalHapticFeedback.current
    SectionLabel(if (steps.isEmpty()) t(R.string.steps) else t(R.string.steps_count, steps.count { it.done }, steps.size))
    if (steps.isNotEmpty()) {
        val frac = steps.count { it.done }.toFloat() / steps.size
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(NoveloColors.Surface2)) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(NoveloColors.Accent))
        }
        Spacer(Modifier.height(6.dp))
    }
    // Dragged by its handle: the step follows the finger and lands where it is let go.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val heights = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    fun target(from: Int): Int {
        var to = from
        var left = dragY
        while (left > 0 && to < steps.lastIndex) { val h = heights[to + 1] ?: break; if (left < h / 2f) break; left -= h; to++ }
        while (left < 0 && to > 0) { val h = heights[to - 1] ?: break; if (-left < h / 2f) break; left += h; to-- }
        return to
    }
    steps.forEachIndexed { i, s ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onSizeChanged { heights[i] = it.height }
                .zIndex(if (dragging == i) 1f else 0f)
                .graphicsLayer { if (dragging == i) { translationY = dragY; scaleX = 1.02f; scaleY = 1.02f } }
                .then(if (dragging == i) Modifier.clip(RoundedCornerShape(12.dp)).background(NoveloColors.Surface) else Modifier),
        ) {
            CheckCircle(s.done, NoveloColors.Accent, onClick = { onToggle(i) }, size = 20.dp)
            // Tap ticks it; holding takes the step away.
            Text(
                s.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (s.done) NoveloColors.TextMuted else NoveloColors.Text,
                textDecoration = if (s.done) TextDecoration.LineThrough else null,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { onToggle(i) },
                        onLongClickLabel = t(R.string.step_remove),
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onRemove(i) },
                    )
                    .padding(vertical = 8.dp),
            )
            if (steps.size > 1) Box(
                Modifier
                    .size(36.dp)
                    .semantics { contentDescription = t(R.string.step_move) }
                    .pointerInput(i, steps.size) {
                        detectDragGestures(
                            onDragStart = { dragging = i; dragY = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = { val to = target(i); dragging = null; dragY = 0f; if (to != i) onMove(i, to) },
                            onDragCancel = { dragging = null; dragY = 0f },
                        ) { change, amount -> change.consume(); dragY += amount.y }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(NoveloIcons.Drag, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 2.dp)) {
        Icon(NoveloIcons.Plus, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (adding.isEmpty()) Text(t(R.string.step_new), style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint)
            BasicTextField(
                value = adding,
                onValueChange = { v ->
                    if (v.endsWith("\n")) { onAdd(v.trim()); adding = "" } else adding = v
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
                cursorBrush = SolidColor(NoveloColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(newStep),
            )
        }
        if (adding.isNotBlank()) {
            IconAction(NoveloIcons.Check, t(R.string.step_add), { onAdd(adding); adding = "" }, primary = true, size = 36.dp)
        }
    }
}

@Composable
private fun DatesBlock(e: Entry, onDue: (Long?, Boolean) -> Unit, onRemind: (Long?) -> Unit, onAddStep: (() -> Unit)?) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    fun pick(initial: Long?, withTime: Boolean, done: (Long, Boolean) -> Unit) {
        val start = initial?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: java.time.ZonedDateTime.now(zone).plusHours(1)
        DatePickerDialog(context, { _, y, m, d ->
            val date = LocalDate.of(y, m + 1, d)
            if (!withTime) {
                done(date.atStartOfDay(zone).toInstant().toEpochMilli(), false)
            } else {
                TimePickerDialog(context, { _, h, min ->
                    done(date.atTime(LocalTime.of(h, min)).atZone(zone).toInstant().toEpochMilli(), true)
                }, start.hour, start.minute, true).show()
            }
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    Spacer(Modifier.height(24.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Unset: just the icon (calendar, bell, list). Set: the icon and when, with an × to take it off.
        if (e.kindEnum == Kind.TASK) {
            DateChip(
                icon = NoveloIcons.Calendar,
                label = Dates.due(e),
                name = t(R.string.due),
                onClick = { pick(e.dueAt, withTime = false) { at, t -> onDue(at, t) } },
                onClear = { onDue(null, false) },
            )
        }
        val remind = e.remindAt?.takeIf { it > System.currentTimeMillis() }
        DateChip(
            icon = NoveloIcons.Bell,
            label = remind?.let { Dates.dayTime(it) },
            name = t(R.string.reminder),
            onClick = { pick(remind, withTime = true) { at, _ -> onRemind(at) } },
            onClear = { onRemind(null) },
        )
        if (onAddStep != null) DateChip(icon = NoveloIcons.Steps, label = null, name = t(R.string.steps), onClick = onAddStep, onClear = {})
    }
    e.checkInAt?.takeIf { it > System.currentTimeMillis() && e.isOpenTask }?.let {
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.checkin_will_ask, Dates.dayTime(it)), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextFaint)
    }
}

@Composable
private fun DateChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, name: String, onClick: () -> Unit, onClear: () -> Unit) {
    val set = label != null
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (set) NoveloColors.Surface else NoveloColors.Surface2)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (set) "$name: $label" else name }
            .padding(start = if (set) 12.dp else 11.dp, end = if (set) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (set) NoveloColors.Accent else NoveloColors.TextMuted, modifier = Modifier.size(18.dp))
        // Not set yet: its name beside the icon, quiet (a bell alone said nothing).
        if (!set) {
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
        }
        if (set) {
            Spacer(Modifier.width(8.dp))
            Text(label!!, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Text)
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(NoveloIcons.Close, t(R.string.take_out), tint = NoveloColors.TextFaint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** "quarta-feira, 11 de novembro" (and the year when it is not this one). */
private fun diaryDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val pattern = t(if (d.year == LocalDate.now(zone).year) R.string.fmt_weekday_day_month else R.string.fmt_weekday_day_month_year)
    return d.format(java.time.format.DateTimeFormatter.ofPattern(pattern, com.villamediana.novelo.ui.components.PT))
}

/** "Toque pra ver": one pill in the middle of what is hidden, the lock and the words in the accent, a seam around. */
@Composable
private fun TapToSee(modifier: Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, NoveloColors.Accent.copy(alpha = 0.55f), inset = 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "tap-to-see" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NoveloIcons.Lock, null, tint = NoveloColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(t(R.string.tap_to_see), style = MaterialTheme.typography.labelLarge, color = NoveloColors.Accent)
    }
}
