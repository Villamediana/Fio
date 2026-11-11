package com.villamediana.fio.ui.entry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.villamediana.fio.ui.components.IconAction
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
import com.villamediana.fio.ui.components.FioIcons
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
import com.villamediana.fio.FioApplication
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Status
import com.villamediana.fio.mind.Steps
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.components.CheckCircle
import com.villamediana.fio.ui.components.Dates
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.components.kindLabel
import com.villamediana.fio.ui.components.statusLabel
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun EntryScreen(nav: Nav, id: Long) {
    val graph = FioApplication.graph
    val lib = graph.library
    val scope = rememberCoroutineScope()
    val entry by lib.entry(id).collectAsStateWithLifecycle(initialValue = null)
    val capture by produceState<Capture?>(null, entry?.captureId) { value = entry?.captureId?.let { lib.capture(it) } }
    var loaded by remember { mutableStateOf(false) }

    val e = entry
    if (e == null) {
        // Deleted (or never there): back to where we came from.
        LaunchedEffect(loaded) { if (loaded) nav.back() }
        Box(Modifier.fillMaxSize().background(FioColors.Background))
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
    var menu by remember { mutableStateOf(false) }
    // Steps only take room when there are some (or one is being written); what was said, folded.
    var addingStep by remember(e.id) { mutableStateOf(false) }
    var showSaid by remember(e.id) { mutableStateOf(false) }
    val hasSteps = remember(e.steps) { Steps.read(e.steps).isNotEmpty() }
    // Saved as it is typed (a beat after), and when leaving.
    LaunchedEffect(title, body) { delay(600); lib.edit(e.id, title, body) }
    DisposableEffect(e.id) { onDispose { graph.scope.launch { lib.edit(e.id, title, body) } } }

    val kind = e.kindEnum
    Column(
        Modifier.fillMaxSize().background(FioColors.Background).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.Back, "Voltar", { nav.back() }, alpha = 0.8f)
            Spacer(Modifier.weight(1f))
            Box {
                QuietIcon(FioIcons.More, "Mais", { menu = true }, alpha = 0.7f)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = FioColors.Surface) {
                    Kind.entries.filter { it != kind }.forEach { k ->
                        DropdownMenuItem(text = { Text("Virar ${kindLabel(k).lowercase()}") }, onClick = { menu = false; scope.launch { lib.setKind(e.id, k) } })
                    }
                    DropdownMenuItem(
                        text = { Text(if (e.sensitive) "Não é sensível" else "Marcar como sensível") },
                        onClick = { menu = false; scope.launch { lib.setSensitive(e.id, !e.sensitive) } },
                    )
                    DropdownMenuItem(text = { Text("Apagar", color = FioColors.Danger) }, onClick = { menu = false; scope.launch { lib.delete(e.id) } })
                }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(kindLabel(kind) + " · " + Dates.dayTime(e.createdAt), style = MaterialTheme.typography.labelLarge, color = FioColors.TextFaint)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = if (e.statusEnum == Status.DONE) FioColors.TextMuted else FioColors.Text,
                    textDecoration = if (e.statusEnum == Status.DONE) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(FioColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind == Kind.TASK) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Status.entries.forEach { st ->
                        Pill(statusLabel(st), selected = (e.statusEnum ?: Status.TODO) == st, onClick = { scope.launch { lib.setStatus(e.id, st) } })
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth()) {
                val blurred = !revealed && Build.VERSION.SDK_INT >= 31
                Box(if (blurred) Modifier.blur(10.dp) else Modifier) {
                    if (body.isEmpty()) Text("Detalhes…", style = MaterialTheme.typography.bodyLarge, color = FioColors.TextFaint)
                    BasicTextField(
                        value = if (!revealed && Build.VERSION.SDK_INT < 31) "••••••••" else body,
                        onValueChange = { if (revealed) body = it },
                        readOnly = !revealed,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = FioColors.Text),
                        cursorBrush = SolidColor(FioColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!revealed) {
                    Text(
                        "Toque pra ver",
                        style = MaterialTheme.typography.labelLarge,
                        color = FioColors.Text,
                        modifier = Modifier.align(Alignment.Center).clip(CircleShape).background(FioColors.Surface2).clickable { revealed = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // The day's photos live in the day's diary.
            if (kind == Kind.DIARY) DayPhotos(e, side = 24.dp)

            if (kind == Kind.TASK && (hasSteps || addingStep)) {
                StepsBlock(
                    e,
                    focusNew = addingStep && !hasSteps,
                    onToggle = { i -> scope.launch { lib.toggleStep(e.id, i) } },
                    onAdd = { t -> scope.launch { lib.addStep(e.id, t) } },
                    onRemove = { i -> scope.launch { lib.removeStep(e.id, i) } },
                )
            }

            DatesBlock(
                e,
                onDue = { at, t -> scope.launch { lib.setDue(e.id, at, t) } },
                onRemind = { at -> scope.launch { lib.setReminder(e.id, at) } },
                onAddStep = if (kind == Kind.TASK && !hasSteps && !addingStep) ({ addingStep = true }) else null,
            )

            // What was said stays folded: it is the source, not the thing kept.
            capture?.transcript?.takeIf { it.isNotBlank() }?.let { said ->
                Row(
                    Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable { showSaid = !showSaid }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (capture?.source) { "photo" -> "Da foto"; com.villamediana.fio.data.Capture.TEXT -> "O que você escreveu"; else -> "O que você disse" },
                        style = MaterialTheme.typography.labelLarge,
                        color = FioColors.TextMuted,
                    )
                    Icon(
                        FioIcons.Chevron, null, tint = FioColors.TextFaint,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = if (showSaid) 90f else 0f },
                    )
                }
                AnimatedVisibility(showSaid, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        "“" + said.trim() + "”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FioColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(FioColors.Surface2).padding(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StepsBlock(e: Entry, focusNew: Boolean, onToggle: (Int) -> Unit, onAdd: (String) -> Unit, onRemove: (Int) -> Unit) {
    val steps = remember(e.steps) { Steps.read(e.steps) }
    var adding by remember { mutableStateOf("") }
    val newStep = remember { FocusRequester() }
    LaunchedEffect(focusNew) { if (focusNew) runCatching { newStep.requestFocus() } }
    val haptics = LocalHapticFeedback.current
    SectionLabel(if (steps.isEmpty()) "Etapas" else "Etapas · ${steps.count { it.done }}/${steps.size}")
    if (steps.isNotEmpty()) {
        val frac = steps.count { it.done }.toFloat() / steps.size
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(FioColors.Surface2)) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(FioColors.Accent))
        }
        Spacer(Modifier.height(6.dp))
    }
    steps.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckCircle(s.done, FioColors.Accent, onClick = { onToggle(i) }, size = 20.dp)
            // Tap ticks it; holding takes the step away.
            Text(
                s.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (s.done) FioColors.TextMuted else FioColors.Text,
                textDecoration = if (s.done) TextDecoration.LineThrough else null,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { onToggle(i) },
                        onLongClickLabel = "Tirar etapa",
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onRemove(i) },
                    )
                    .padding(vertical = 8.dp),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 2.dp)) {
        Icon(FioIcons.Plus, null, tint = FioColors.TextFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (adding.isEmpty()) Text("Nova etapa", style = MaterialTheme.typography.bodyLarge, color = FioColors.TextFaint)
            BasicTextField(
                value = adding,
                onValueChange = { v ->
                    if (v.endsWith("\n")) { onAdd(v.trim()); adding = "" } else adding = v
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = FioColors.Text),
                cursorBrush = SolidColor(FioColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(newStep),
            )
        }
        if (adding.isNotBlank()) {
            IconAction(FioIcons.Check, "Pôr etapa", { onAdd(adding); adding = "" }, primary = true, size = 36.dp)
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
                icon = FioIcons.Calendar,
                label = Dates.due(e),
                name = "Prazo",
                onClick = { pick(e.dueAt, withTime = false) { at, t -> onDue(at, t) } },
                onClear = { onDue(null, false) },
            )
        }
        val remind = e.remindAt?.takeIf { it > System.currentTimeMillis() }
        DateChip(
            icon = FioIcons.Bell,
            label = remind?.let { Dates.dayTime(it) },
            name = "Lembrete",
            onClick = { pick(remind, withTime = true) { at, _ -> onRemind(at) } },
            onClear = { onRemind(null) },
        )
        if (onAddStep != null) DateChip(icon = FioIcons.Steps, label = null, name = "Etapas", onClick = onAddStep, onClear = {})
    }
    e.checkInAt?.takeIf { it > System.currentTimeMillis() && e.isOpenTask }?.let {
        Spacer(Modifier.height(8.dp))
        Text("O Fio vai perguntar como está ${Dates.dayTime(it)}.", style = MaterialTheme.typography.bodySmall, color = FioColors.TextFaint)
    }
}

@Composable
private fun DateChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, name: String, onClick: () -> Unit, onClear: () -> Unit) {
    val set = label != null
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (set) FioColors.Surface else FioColors.Surface2)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (set) "$name: $label" else name }
            .padding(start = if (set) 12.dp else 11.dp, end = if (set) 4.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (set) FioColors.Accent else FioColors.TextMuted, modifier = Modifier.size(18.dp))
        if (set) {
            Spacer(Modifier.width(8.dp))
            Text(label!!, style = MaterialTheme.typography.labelLarge, color = FioColors.Text)
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(FioIcons.Close, "Tirar", tint = FioColors.TextFaint, modifier = Modifier.size(14.dp))
            }
        }
    }
}
