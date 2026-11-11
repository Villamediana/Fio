package com.villamediana.fio.ui.entry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import com.villamediana.fio.ui.Filter
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.CheckCircle
import com.villamediana.fio.ui.components.Dates
import com.villamediana.fio.ui.components.EntryCard
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(nav: Nav, id: Long) {
    val graph = FioApplication.graph
    val lib = graph.library
    val scope = rememberCoroutineScope()
    val entry by lib.entry(id).collectAsStateWithLifecycle(initialValue = null)
    val topics by lib.topicsOf(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by lib.progressOf(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val related by produceState(emptyList<Entry>(), entry?.updatedAt, topics.size) { value = lib.recall().related(id) }
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
    var revealed by remember(e.id) { mutableStateOf(!e.sensitive) }
    var menu by remember { mutableStateOf(false) }
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

            if (kind == Kind.TASK) StepsBlock(e, onToggle = { i -> scope.launch { lib.toggleStep(e.id, i) } }, onAdd = { t -> scope.launch { lib.addStep(e.id, t) } }, onRemove = { i -> scope.launch { lib.removeStep(e.id, i) } })

            DatesBlock(e, onDue = { at, t -> scope.launch { lib.setDue(e.id, at, t) } }, onRemind = { at -> scope.launch { lib.setReminder(e.id, at) } })

            if (progress.any { it.text.isNotBlank() || it.status != null }) {
                SectionLabel("Andamento")
                progress.forEach { p ->
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(FioColors.Surface3))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            val st = Status.of(p.status)
                            Text(
                                listOfNotNull(st?.let { statusLabel(it) }, p.text.takeIf { it.isNotBlank() }).joinToString(" — "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FioColors.Text,
                            )
                            Text(Dates.dayTime(p.at), style = MaterialTheme.typography.labelMedium, color = FioColors.TextFaint)
                        }
                    }
                }
            }

            if (topics.isNotEmpty() || related.isNotEmpty()) {
                SectionLabel("Conexões")
                if (topics.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topics.forEach { t -> Pill(t.name, selected = false, onClick = { nav.go(Screen.Library(Filter.LINKS, topic = t.id)) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                related.forEach { r ->
                    Box(Modifier.padding(bottom = 8.dp)) { EntryCard(r, onClick = { nav.go(Screen.Entry(r.id)) }) }
                }
            }

            capture?.transcript?.takeIf { it.isNotBlank() }?.let { said ->
                SectionLabel(if (capture?.source == "photo") "Da foto" else "O que você disse")
                Text(
                    "“" + said.trim() + "”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FioColors.TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(FioColors.Surface2).padding(14.dp).animateContentSize(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StepsBlock(e: Entry, onToggle: (Int) -> Unit, onAdd: (String) -> Unit, onRemove: (Int) -> Unit) {
    val steps = remember(e.steps) { Steps.read(e.steps) }
    var adding by remember { mutableStateOf("") }
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (adding.isNotBlank()) {
            Text("Pôr", style = MaterialTheme.typography.labelLarge, color = FioColors.Accent, modifier = Modifier.clip(CircleShape).clickable { onAdd(adding); adding = "" }.padding(12.dp))
        }
    }
}

@Composable
private fun DatesBlock(e: Entry, onDue: (Long?, Boolean) -> Unit, onRemind: (Long?) -> Unit) {
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
        if (e.kindEnum == Kind.TASK) {
            DateChip(
                label = Dates.due(e)?.let { "Prazo: $it" } ?: "Pôr prazo",
                set = e.dueAt != null,
                onClick = { pick(e.dueAt, withTime = false) { at, t -> onDue(at, t) } },
                onClear = { onDue(null, false) },
            )
        }
        val remind = e.remindAt?.takeIf { it > System.currentTimeMillis() }
        DateChip(
            label = remind?.let { "Lembrete: " + Dates.dayTime(it) } ?: "Lembrar",
            set = remind != null,
            onClick = { pick(remind, withTime = true) { at, _ -> onRemind(at) } },
            onClear = { onRemind(null) },
        )
    }
    e.checkInAt?.takeIf { it > System.currentTimeMillis() && e.isOpenTask }?.let {
        Spacer(Modifier.height(8.dp))
        Text("O Fio vai perguntar como está ${Dates.dayTime(it)}.", style = MaterialTheme.typography.bodySmall, color = FioColors.TextFaint)
    }
}

@Composable
private fun DateChip(label: String, set: Boolean, onClick: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (set) FioColors.Surface else FioColors.Surface2).clickable(onClick = onClick).padding(start = 14.dp, end = if (set) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (set) FioColors.Text else FioColors.TextMuted, modifier = Modifier.padding(vertical = 9.dp))
        if (set) {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(FioIcons.Close, "Tirar", tint = FioColors.TextFaint, modifier = Modifier.size(14.dp))
            }
        }
    }
}
