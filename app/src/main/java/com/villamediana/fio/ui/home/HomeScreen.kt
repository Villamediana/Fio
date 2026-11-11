package com.villamediana.fio.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.testTag
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
import androidx.compose.material3.Icon
import com.villamediana.fio.ui.components.FioIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import com.villamediana.fio.ui.sheets.FioSheet
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.villamediana.fio.ui.components.IconAction
import com.villamediana.fio.ui.components.swipeNav
import androidx.compose.runtime.mutableFloatStateOf
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
    val staged by mind.staged.collectAsStateWithLifecycle()

    var focus by remember { mutableStateOf<Entry?>(null) }
    var writing by remember { mutableStateOf(false) }
    var inCancelZone by remember { mutableStateOf(false) }
    var wantsAfterPermission by remember { mutableStateOf<Long?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val f = wantsAfterPermission
        wantsAfterPermission = null
        if (granted && f != null) mind.startListening(locked = true, focusId = f.takeIf { it > 0 })
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The phone's gallery: as many photos as she wants, each going the way of one taken here.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> graph.fromGallery(uris) }
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

    // The main screen sits between the camera (on its left) and the library (on its right):
    // a swipe to the right opens the camera, to the left the library's first tab.
    val lean = remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(FioColors.Background)
            .swipeNav(
                onLeft = { nav.go(Screen.Library(Filter.TODAY)) },
                onRight = { nav.go(Screen.Camera) },
                follow = lean,
            ),
    ) {
        Column(Modifier.fillMaxSize().graphicsLayer { translationX = lean.floatValue }.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Writing instead of talking: a light pencil, there for whoever wants it.
                if (state !is MindState.Listening) QuietIcon(FioIcons.Pencil, "Escrever", { writing = true }, alpha = 0.4f)
                Spacer(Modifier.weight(1f))
                QuietIcon(FioIcons.Tune, "Ajustes", { nav.go(Screen.Settings) }, alpha = 0.55f)
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
                        onStop = mind::stopWork,
                        onCancel = mind::cancelListening,
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
                        .clickable { mind.retryPending(byHand = true) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            // Photos taken or picked wait here: sent as they are, or with what she says or writes next.
            AnimatedVisibility(staged.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                StagedTray(staged, onRemove = mind::unstage, onSend = mind::sendStaged)
            }
            BottomBar(
                entries = entries,
                onCamera = { nav.go(Screen.Camera) },
                onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onToday = { nav.go(Screen.Library(Filter.TODAY)) },
                onSearch = { nav.go(Screen.Library(Filter.ALL, search = true)) },
            )
        }
    }

    if (writing) {
        WriteSheet(
            about = focus,
            onSend = { text -> writing = false; mind.write(text) },
            onDismiss = { writing = false },
        )
    }
}

/** The photos waiting: a row of them (× lets one go), the arrow sends them as they are. */
@Composable
private fun StagedTray(photos: List<com.villamediana.fio.mind.Staged>, onRemove: (com.villamediana.fio.mind.Staged) -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Text(
            if (photos.size == 1) "Fale ou escreva o que fazer com ela, ou envie assim" else "Fale ou escreva o que fazer com elas, ou envie assim",
            style = MaterialTheme.typography.labelMedium,
            color = FioColors.TextMuted,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.weight(1f).testTag("staged-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(photos.size, key = { photos[it].file.name }) { i ->
                    val p = photos[i]
                    Box(Modifier.size(62.dp)) {
                        com.villamediana.fio.ui.components.PhotoTile(
                            p.file.path,
                            maxPx = 200,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).testTag("staged-$i"),
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(FioColors.Background.copy(alpha = 0.86f))
                                .clickable(onClickLabel = "Tirar a foto") { onRemove(p) }
                                .semantics { contentDescription = "Tirar a foto" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(FioIcons.Close, null, tint = FioColors.Text, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            com.villamediana.fio.ui.components.IconAction(FioIcons.Send, if (photos.size == 1) "Enviar a foto" else "Enviar as fotos", onSend, primary = true, size = 48.dp)
        }
    }
}

/** Writing instead of talking: the text goes straight to organizing, nothing to transcribe. */
@Composable
private fun WriteSheet(about: Entry?, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    FioSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp).imePadding(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text("O que está na sua cabeça?", style = MaterialTheme.typography.bodyLarge, color = FioColors.TextFaint)
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = FioColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(FioColors.Accent),
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            Spacer(Modifier.width(10.dp))
            IconAction(FioIcons.Send, "Guardar", { if (text.isNotBlank()) onSend(text) }, primary = text.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun QuietIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, alpha: Float = 0.62f) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = FioColors.Text, modifier = Modifier.size(24.dp).graphicsLayer { this.alpha = alpha })
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
            Icon(FioIcons.Close, "Tirar", tint = FioColors.TextFaint, modifier = Modifier.size(16.dp))
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
    onStop: () -> Unit,
    onCancel: () -> Unit,
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
                is MindState.Listening -> ListeningText(s, inCancelZone, onCancel)
                is MindState.Working -> WorkingText(s, onStop)
                is MindState.Answer -> AnswerView(s, entries, onOpen, onToggle, onDismiss)
                is MindState.Problem -> ProblemView(s, onRetry, onDiscard, onSettings, onDismiss)
            }
        }
    }
}

@Composable
internal fun ListeningText(s: MindState.Listening, inCancelZone: Boolean, onCancel: () -> Unit) {
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
    // Listening by itself (tapped): throwing it away is one touch, without saying it — a quiet ×,
    // no circle around it, so the button stays the one thing that stands out.
    if (s.locked) {
        Spacer(Modifier.height(10.dp))
        QuietIcon(FioIcons.Close, "Cancelar", onCancel, alpha = 0.45f)
    }
}

@Composable
private fun WorkingText(s: MindState.Working, onStop: () -> Unit) {
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
        // While it comes, the newest words: the beginning is already known, the end shows it is going.
        Said(tail(s.text), maxLines = 8)
    }
    // Taking too long: a stop. What was said stays, to try again or discard.
    if (s.captureId != 0L) {
        Spacer(Modifier.height(14.dp))
        IconAction(FioIcons.Stop, "Parar", onStop, size = 40.dp)
    }
}

/** The end of a long text, from a whole word, after "…". */
fun tail(text: String, max: Int = 340): String {
    val t = text.trim()
    if (t.length <= max) return t
    val cut = t.takeLast(max)
    return "…" + cut.substring((cut.indexOf(' ') + 1).coerceAtMost(cut.length))
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
                Text(s.reply, style = MaterialTheme.typography.titleLarge, color = FioColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (showSaid && s.said.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Said(s.said, maxLines = 12)
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.said.isNotBlank()) {
                    QuietWord(if (showSaid) "esconder" else if (s.photo) "da foto" else if (s.typed) "o que você escreveu" else "o que você disse") { showSaid = !showSaid }
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss).semantics { contentDescription = "Fechar" }, contentAlignment = Alignment.Center) {
                    Icon(FioIcons.Close, null, tint = FioColors.TextFaint, modifier = Modifier.size(16.dp))
                }
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
            // The photos that answer, in the order Fio gave them; a tap opens them big.
            if (s.photos.isNotEmpty()) {
                val kept by remember { com.villamediana.fio.FioApplication.graph.library.allPhotos() }.collectAsState(initial = emptyList())
                val found = remember(kept, s.photos) { kept.associateBy { it.id }.let { m -> s.photos.mapNotNull(m::get) } }
                if (found.isNotEmpty()) AppearingCard(i++) { com.villamediana.fio.ui.entry.PhotoRow(found, side = 22.dp, height = 170.dp, tag = "answer-photo") }
            }
        }
    }
}

@Composable
private fun QuietWord(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = FioColors.TextFaint,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
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
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (s.need != null) IconAction(FioIcons.Tune, "Ajustes", onSettings, primary = true)
        if (s.captureId != null) {
            IconAction(FioIcons.Retry, "Tentar de novo", { onRetry(s.captureId) }, primary = s.need == null)
            IconAction(FioIcons.Trash, "Descartar", { onDiscard(s.captureId) })
        } else {
            IconAction(FioIcons.Check, "Ok", onDismiss)
        }
    }
    if (s.captureId != null && s.need != null) {
        Spacer(Modifier.height(10.dp))
        Text("Está guardado aqui. Assim que der, eu organizo.", style = MaterialTheme.typography.bodySmall, color = FioColors.TextFaint, textAlign = TextAlign.Center)
    }
}

/** The camera and the gallery, what is for today (the way into everything kept), the search. */
@Composable
private fun BottomBar(entries: List<Entry>, onCamera: () -> Unit, onGallery: () -> Unit, onToday: () -> Unit, onSearch: () -> Unit) {
    val today = remember(entries) { todayCount(entries) }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both sides as wide, so "Hoje" stays in the middle.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.PhotoCamera, "Câmera", onCamera)
            QuietIcon(FioIcons.Gallery, "Galeria", onGallery)
        }
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
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.Search, "Buscar", onSearch)
        }
    }
}

/** Open tasks due today or late, and reminders still to come today. */
fun todayCount(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Int {
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return entries.count { e ->
        e.kindEnum == Kind.TASK && e.statusEnum != Status.DONE && ((e.dueAt != null && e.dueAt < end) || (e.remindAt != null && e.remindAt < end))
    }
}
