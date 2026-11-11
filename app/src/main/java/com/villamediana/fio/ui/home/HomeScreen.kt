package com.villamediana.fio.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import com.villamediana.fio.ui.components.FioIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.FioApplication
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Outcome
import com.villamediana.fio.data.Status
import com.villamediana.fio.mind.MindState
import com.villamediana.fio.mind.Need
import com.villamediana.fio.mind.Stage
import com.villamediana.fio.mind.Words
import com.villamediana.fio.ui.Filter
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.EntryCard
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.pressScale
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(nav: Nav, talkAbout: Long?, onTalkHandled: () -> Unit) {
    val graph = FioApplication.graph
    val mind = graph.mind
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by mind.state.collectAsStateWithLifecycle()
    val level = mind.level.collectAsStateWithLifecycle()
    val pending by mind.pending.collectAsStateWithLifecycle()
    val settings by graph.settings.now.collectAsStateWithLifecycle()
    val entries by graph.library.all().collectAsStateWithLifecycle(initialValue = emptyList())

    var focus by remember { mutableStateOf<Entry?>(null) }
    var inCancelZone by remember { mutableStateOf(false) }
    var wantsAfterPermission by remember { mutableStateOf<Long?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val f = wantsAfterPermission
        wantsAfterPermission = null
        if (granted && f != null) mind.startListening(locked = true, focusId = f.takeIf { it > 0 })
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // "Falar" from a check-in: the microphone opens right away, about that task.
    LaunchedEffect(talkAbout) {
        val id = talkAbout ?: return@LaunchedEffect
        onTalkHandled()
        focus = graph.library.get(id)
        if (hasMic()) mind.startListening(locked = true, focusId = id) else { wantsAfterPermission = id; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    // A reminder was set: ask once for notifications (Android 13+).
    LaunchedEffect(state) {
        val a = state as? MindState.Answer ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val ids = a.outcomes.map { it.entryId }.toSet()
            if (entries.any { it.id in ids && (it.remindAt != null || it.checkInAt != null) }) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!settings.hintSeen) graph.settings.setHintSeen()
        focus = null
    }

    val mode = when (val s = state) {
        is MindState.Listening -> if (s.locked) OrbMode.LOCKED else OrbMode.HOLDING
        is MindState.Working -> OrbMode.WORKING
        else -> OrbMode.IDLE
    }
    val hasContent = state is MindState.Answer || state is MindState.Problem || state is MindState.Working
    val lift by animateFloatAsState(if (hasContent) 1f else 0f, spring(0.86f, 260f), label = "lift")

    Box(Modifier.fillMaxSize().background(FioColors.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                QuietIcon(FioIcons.Tune, "Ajustes", { nav.go(Screen.Settings) }, alpha = 0.4f)
            }

            Spacer(Modifier.weight(1f - 0.82f * lift))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Orb(
                    mode = mode,
                    level = level,
                    size = (280 - 70 * lift).dp,
                    onStart = {
                        if (!hasMic()) {
                            wantsAfterPermission = focus?.id ?: 0L
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            mind.startListening(locked = false, focusId = focus?.id)
                        }
                    },
                    onLock = mind::lock,
                    onStop = mind::stopListening,
                    onCancel = mind::cancelListening,
                    onCancelZone = { inCancelZone = it },
                )
            }

            Box(Modifier.weight(1.15f + 1.9f * lift).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    focus?.let { f ->
                        if (state is MindState.Listening || state is MindState.Idle) FocusChip(f, onClear = { focus = null })
                    }
                    StatusArea(
                        state = state,
                        hintSeen = settings.hintSeen,
                        inCancelZone = inCancelZone,
                        entries = entries,
                        onOpen = { nav.go(Screen.Entry(it)) },
                        onToggle = { id -> scope.launch { graph.library.toggleDone(id) } },
                        onDismiss = mind::dismiss,
                        onRetry = { mind.retry(it) },
                        onDiscard = { mind.discard(it) },
                        onSettings = { nav.go(Screen.Settings) },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (pending > 0 && state !is MindState.Working && state !is MindState.Listening) {
                Text(
                    if (pending == 1) "1 coisa esperando · tentar agora" else "$pending coisas esperando · tentar agora",
                    style = MaterialTheme.typography.labelMedium,
                    color = FioColors.TextMuted,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .clickable { mind.retryPending() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            BottomBar(
                entries = entries,
                onCamera = { nav.go(Screen.Camera) },
                onToday = { nav.go(Screen.Library(Filter.TODAY)) },
                onSearch = { nav.go(Screen.Library(Filter.ALL, search = true)) },
            )
        }
    }
}

@Composable
fun QuietIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, alpha: Float = 0.5f) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = FioColors.Text, modifier = Modifier.size(22.dp).graphicsLayer { this.alpha = alpha })
    }
}

@Composable
private fun FocusChip(entry: Entry, onClear: () -> Unit) {
    Row(
        Modifier.padding(bottom = 14.dp).clip(CircleShape).background(FioColors.Surface2).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sobre: " + entry.title, style = MaterialTheme.typography.labelLarge, color = FioColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Tirar", tint = FioColors.TextFaint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StatusArea(
    state: MindState,
    hintSeen: Boolean,
    inCancelZone: Boolean,
    entries: List<Entry>,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onDismiss: () -> Unit,
    onRetry: (Long) -> Unit,
    onDiscard: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    AnimatedContent(
        targetState = state,
        contentKey = { s ->
            when (s) {
                is MindState.Listening -> "listening"
                is MindState.Working -> "working"
                is MindState.Answer -> "answer-${s.captureId}"
                is MindState.Problem -> "problem-${s.captureId}-${s.message}"
                MindState.Idle -> "idle"
            }
        },
        transitionSpec = {
            (fadeIn(com.villamediana.fio.ui.theme.Motion.enter(380)) + slideInVertically(com.villamediana.fio.ui.theme.Motion.slide) { it / 6 }) togetherWith
                fadeOut(com.villamediana.fio.ui.theme.Motion.exit(160))
        },
        label = "status",
    ) { s ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (s) {
                MindState.Idle -> Text(
                    if (hintSeen) "O que está na sua cabeça?" else "Segure pra falar.\nToque pra deixar ouvindo.",
                    style = MaterialTheme.typography.titleMedium,
                    color = FioColors.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is MindState.Listening -> ListeningText(s, inCancelZone)
                is MindState.Working -> WorkingText(s)
                is MindState.Answer -> AnswerView(s, entries, onOpen, onToggle, onDismiss)
                is MindState.Problem -> ProblemView(s, onRetry, onDiscard, onSettings, onDismiss)
            }
        }
    }
}

@Composable
private fun ListeningText(s: MindState.Listening, inCancelZone: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val secs = ((now - s.startedAt) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d".format(secs / 60, secs % 60),
        style = MaterialTheme.typography.headlineMedium,
        color = if (inCancelZone) FioColors.TextMuted else FioColors.Text,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            inCancelZone -> "Solte pra descartar"
            s.locked -> "Ouvindo. Toque pra guardar."
            else -> "Solte pra guardar · arraste pra cima pra descartar"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (inCancelZone) FioColors.Danger else FioColors.TextMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun WorkingText(s: MindState.Working) {
    Text(
        when (s.stage) {
            Stage.HEARING -> "Transcrevendo…"
            Stage.READING -> "Lendo a foto…"
            Stage.THINKING -> "Organizando…"
            Stage.LOOKING -> "Procurando nas suas lembranças…"
        },
        style = MaterialTheme.typography.labelLarge,
        color = FioColors.Accent,
    )
    if (s.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Said(s.text, maxLines = 8)
    }
}

@Composable
private fun Said(text: String, maxLines: Int) {
    Text(
        "“" + text.trim() + "”",
        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Normal),
        color = FioColors.TextMuted,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AnswerView(s: MindState.Answer, entries: List<Entry>, onOpen: (Long) -> Unit, onToggle: (Long) -> Unit, onDismiss: () -> Unit) {
    var showSaid by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.reply.isNotBlank()) {
                Text(s.reply, style = MaterialTheme.typography.titleLarge, color = FioColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 34.dp))
            }
            if (s.said.isNotBlank()) {
                Text(
                    if (showSaid) "“" + s.said.trim() + "”" else if (s.photo) "da foto" else "o que você disse",
                    style = MaterialTheme.typography.bodySmall,
                    color = FioColors.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { showSaid = !showSaid }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            val byId = entries.associateBy { it.id }
            var i = 0
            s.outcomes.filter { it.action != Outcome.DELETED }.forEach { o ->
                val e = byId[o.entryId] ?: return@forEach
                AppearingCard(i++) { EntryCard(e, onClick = { onOpen(e.id) }, label = outcomeLabel(o, e), onToggle = { onToggle(e.id) }) }
            }
            val terms = remember(s.said) { Words.of(s.said).filter { it.length >= 4 } }
            s.shown.filter { id -> s.outcomes.none { it.entryId == id } }.forEach { id ->
                val e = byId[id] ?: return@forEach
                AppearingCard(i++) { EntryCard(e, onClick = { onOpen(e.id) }, terms = terms, onToggle = { onToggle(e.id) }) }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp).size(36.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Fechar", tint = FioColors.TextFaint, modifier = Modifier.size(16.dp))
        }
    }
}

/** Cards come in one after the other, a beat apart. */
@Composable
private fun AppearingCard(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(90L * index + 60L); shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(com.villamediana.fio.ui.theme.Motion.enter(360)) + slideInVertically(com.villamediana.fio.ui.theme.Motion.slide) { it / 3 },
    ) {
        Box(Modifier.padding(bottom = 10.dp)) { content() }
    }
}

private fun outcomeLabel(o: Outcome, e: Entry): String = when (o.action) {
    Outcome.CREATED -> when (e.kindEnum) {
        Kind.TASK -> "Nova tarefa"
        Kind.NOTE -> "Nota guardada"
        Kind.DIARY -> "No diário"
    }
    Outcome.DONE -> "Feita"
    else -> "Atualizada"
}

@Composable
private fun ProblemView(s: MindState.Problem, onRetry: (Long) -> Unit, onDiscard: (Long) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    Text(s.message, style = MaterialTheme.typography.titleMedium, color = FioColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    if (!s.said.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Said(s.said, maxLines = 4)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.need != null) Pill("Ajustes", selected = true, onClick = onSettings)
        if (s.captureId != null) {
            Pill("Tentar de novo", selected = s.need == null, onClick = { onRetry(s.captureId) })
            Pill("Descartar", selected = false, onClick = { onDiscard(s.captureId) })
        } else {
            Pill("Ok", selected = false, onClick = onDismiss)
        }
    }
    if (s.captureId != null && s.need != null) {
        Spacer(Modifier.height(10.dp))
        Text("Está guardado aqui. Assim que der, eu organizo.", style = MaterialTheme.typography.bodySmall, color = FioColors.TextFaint, textAlign = TextAlign.Center)
    }
}

/** Three things, no more: the camera, what is for today (the way into everything kept), the search. */
@Composable
private fun BottomBar(entries: List<Entry>, onCamera: () -> Unit, onToday: () -> Unit, onSearch: () -> Unit) {
    val today = remember(entries) { todayCount(entries) }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietIcon(FioIcons.PhotoCamera, "Câmera", onCamera)
        Spacer(Modifier.weight(1f))
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .pressScale(interaction)
                .clip(CircleShape)
                .background(FioColors.Surface)
                .clickable(interactionSource = interaction, indication = null, onClick = onToday)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hoje", style = MaterialTheme.typography.labelLarge, color = FioColors.Text)
            if (today > 0) {
                Spacer(Modifier.width(8.dp))
                Text(today.toString(), style = MaterialTheme.typography.labelLarge, color = FioColors.Accent)
            }
        }
        Spacer(Modifier.weight(1f))
        QuietIcon(Icons.Rounded.Search, "Buscar", onSearch)
    }
}

/** Open tasks due today or late, and reminders still to come today. */
fun todayCount(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Int {
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return entries.count { e ->
        e.kindEnum == Kind.TASK && e.statusEnum != Status.DONE && ((e.dueAt != null && e.dueAt < end) || (e.remindAt != null && e.remindAt < end))
    }
}
