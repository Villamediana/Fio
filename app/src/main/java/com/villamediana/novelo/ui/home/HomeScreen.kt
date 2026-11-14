package com.villamediana.novelo.ui.home

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
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
import com.villamediana.novelo.ui.components.NoveloIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import com.villamediana.novelo.ui.sheets.NoveloSheet
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.villamediana.novelo.ui.components.IconAction
import androidx.compose.runtime.DisposableEffect
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.components.swipeNav
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
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Outcome
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.mind.MindState
import com.villamediana.novelo.mind.Need
import com.villamediana.novelo.mind.Stage
import com.villamediana.novelo.mind.Words
import com.villamediana.novelo.ui.Filter
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.Screen
import com.villamediana.novelo.ui.components.EntryCard
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.pressScale
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(nav: Nav, talkAbout: Long?, onTalkHandled: () -> Unit) {
    val graph = NoveloApplication.graph
    val mind = graph.mind
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by mind.state.collectAsStateWithLifecycle()
    val level = mind.level.collectAsStateWithLifecycle()
    val pending by mind.pending.collectAsStateWithLifecycle()
    val settings by graph.settings.now.collectAsStateWithLifecycle()
    val entries by graph.library.all().collectAsStateWithLifecycle(initialValue = emptyList())
    val staged by mind.staged.collectAsStateWithLifecycle()
    val ears by graph.localModel.state.collectAsStateWithLifecycle()
    // What is still missing to hear and think (the ears, her APIs): the button is locked until it is there.
    val missing = remember(settings, ears) { missing(settings, ears, com.villamediana.novelo.local.LocalEngine.available) }
    val locked = missing.isNotEmpty()
    var nudge by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    SideEffect { OrbSpot.locked = locked }

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
    // Files of any type: they wait with the photos, and go alone or with what she says.
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> graph.fromFiles(uris) }
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
            val meds = if (a.meds.isEmpty()) emptyList() else graph.db.meds().all().filter { it.id in a.meds }
            if (entries.any { it.id in ids && (it.remindAt != null || it.checkInAt != null) } || meds.any { com.villamediana.novelo.meds.Doses.times(it).isNotEmpty() }) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            .paper()
            .swipeNav(
                onLeft = { nav.go(Screen.Library(Filter.TODAY)) },
                onRight = { nav.go(Screen.Camera) },
                follow = lean,
            ),
    ) {
        Column(Modifier.fillMaxSize().graphicsLayer { translationX = lean.floatValue }.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Writing instead of talking: a light pencil, there for whoever wants it.
                if (state !is MindState.Listening) QuietIcon(NoveloIcons.Pencil, t(R.string.write), { writing = true }, alpha = 0.4f)
                Spacer(Modifier.weight(1f))
                QuietIcon(NoveloIcons.Tune, t(R.string.settings), { nav.go(Screen.Settings()) }, alpha = 0.55f)
            }

            Spacer(Modifier.weight(1f - 0.82f * lift))
            // The opening's ball lands where this one is (see Opening).
            DisposableEffect(Unit) { onDispose { OrbSpot.center = null; OrbSpot.locked = false } }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Orb(
                    modifier = Modifier.orbSpot(),
                    mode = mode,
                    level = level,
                    size = (280 - 70 * lift).dp,
                    locked = locked,
                    onStart = {
                        if (locked) {
                            // Locked: nothing records; the card under it says what is missing.
                            nudge++
                            false
                        } else if (!hasMic()) {
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
                        onOpenMed = { nav.go(Screen.Med(it)) },
                        onToggle = { id -> scope.launch { graph.library.toggleDone(id) } },
                        onDismiss = mind::dismiss,
                        onRetry = { mind.retry(it) },
                        onDiscard = { mind.discard(it) },
                        onSettings = { nav.go(Screen.Settings(ai = true)) },
                        missing = missing,
                        nudge = nudge,
                        onStop = mind::stopWork,
                        onCancel = mind::cancelListening,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (pending > 0 && state !is MindState.Working && state !is MindState.Listening) {
                Text(
                    if (pending == 1) t(R.string.pending_one) else t(R.string.pending_many, pending),
                    style = MaterialTheme.typography.labelMedium,
                    color = NoveloColors.TextMuted,
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
                onFile = { runCatching { documents.launch(arrayOf("*/*")) } },
                onToday = { nav.go(Screen.Library(Filter.TODAY)) },
                onSearch = { nav.go(Screen.Library(Filter.ALL, search = true)) },
            )
        }
    }

    if (writing) {
        WriteSheet(
            about = focus,
            onSend = { text -> writing = false; graph.draft.clear(); mind.write(text) },
            onDismiss = { writing = false },
        )
    }
}

/** The photos waiting: a row of them (× lets one go), the arrow sends them as they are. */
@Composable
private fun StagedTray(photos: List<com.villamediana.novelo.mind.Staged>, onRemove: (com.villamediana.novelo.mind.Staged) -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        val onlyFiles = photos.all { it.isFile }
        Text(
            when {
                photos.size == 1 && onlyFiles -> t(R.string.staged_file_one)
                photos.size == 1 -> t(R.string.staged_one)
                onlyFiles -> t(R.string.staged_file_many)
                else -> t(R.string.staged_many)
            },
            style = MaterialTheme.typography.labelMedium,
            color = NoveloColors.TextMuted,
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
                        if (p.isFile) StagedFile(p, Modifier.fillMaxSize().testTag("staged-$i"))
                        else com.villamediana.novelo.ui.components.PhotoTile(
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
                                .background(NoveloColors.Background.copy(alpha = 0.86f))
                                .clickable(onClickLabel = t(if (p.isFile) R.string.file_take_out else R.string.photo_take_out)) { onRemove(p) }
                                .semantics { contentDescription = t(if (p.isFile) R.string.file_take_out else R.string.photo_take_out) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(NoveloIcons.Close, null, tint = NoveloColors.Text, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            com.villamediana.novelo.ui.components.IconAction(
                NoveloIcons.Send,
                when {
                    onlyFiles -> if (photos.size == 1) t(R.string.send_file) else t(R.string.send_files)
                    else -> if (photos.size == 1) t(R.string.send_photo) else t(R.string.send_photos)
                },
                onSend,
                primary = true,
                size = 48.dp,
            )
        }
    }
}

/** A file waiting: its kind ("PDF") over the start of its name, on a small sheet. */
@Composable
private fun StagedFile(p: com.villamediana.novelo.mind.Staged, modifier: Modifier = Modifier) {
    val name = p.name.orEmpty()
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(NoveloColors.Surface2).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NoveloIcons.File, null, tint = NoveloColors.Accent, modifier = Modifier.size(20.dp))
        Text(
            com.villamediana.novelo.mind.FileKind.label(name, null) ?: name,
            style = MaterialTheme.typography.labelSmall,
            color = NoveloColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Writing instead of talking: the text goes straight to organizing, nothing to transcribe. What
 * she writes is kept as she goes (a day): closed by a stray touch, it is all there when she opens it again.
 */
@Composable
private fun WriteSheet(about: Entry?, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val draft = NoveloApplication.graph.draft
    var value by remember { mutableStateOf(draft.read().let { androidx.compose.ui.text.input.TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) }) }
    val text = value.text
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    NoveloSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp).imePadding(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text(t(R.string.invite), style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint)
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = { v -> if (v.text != value.text) draft.write(v.text); value = v },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NoveloColors.Accent),
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            Spacer(Modifier.width(10.dp))
            IconAction(NoveloIcons.Send, t(R.string.keep), { if (text.isNotBlank()) onSend(text) }, primary = text.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun QuietIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, alpha: Float = 0.62f, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = NoveloColors.Text, modifier = Modifier.size(24.dp).graphicsLayer { this.alpha = alpha })
    }
}

@Composable
private fun FocusChip(entry: Entry, onClear: () -> Unit) {
    Row(
        Modifier.padding(bottom = 14.dp).clip(CircleShape).background(NoveloColors.Surface2).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t(R.string.about_entry, entry.title), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
            Icon(NoveloIcons.Close, t(R.string.take_out), tint = NoveloColors.TextFaint, modifier = Modifier.size(16.dp))
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
    onOpenMed: (Long) -> Unit = {},
    onDiscard: (Long) -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    missing: List<Missing> = emptyList(),
    nudge: Int = 0,
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
            (fadeIn(com.villamediana.novelo.ui.theme.Motion.enter(380)) + slideInVertically(com.villamediana.novelo.ui.theme.Motion.slide) { it / 6 }) togetherWith
                fadeOut(com.villamediana.novelo.ui.theme.Motion.exit(160))
        },
        label = "status",
    ) { s ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (s) {
                MindState.Idle -> if (missing.isNotEmpty()) ReadyCard(missing, nudge, onSettings) else Text(
                    if (hintSeen) t(R.string.invite) else t(R.string.hint_first),
                    style = com.villamediana.novelo.ui.theme.NoveloText.Invite,
                    color = NoveloColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is MindState.Listening -> ListeningText(s, inCancelZone, onCancel)
                is MindState.Working -> WorkingText(s, onStop)
                is MindState.Answer -> AnswerView(s, entries, onOpen, onToggle, onDismiss, onOpenMed)
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
        color = if (inCancelZone) NoveloColors.TextMuted else NoveloColors.Text,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            inCancelZone -> t(R.string.release_to_discard)
            s.locked -> t(R.string.listening_tap_keep)
            else -> t(R.string.release_to_keep)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (inCancelZone) NoveloColors.Danger else NoveloColors.TextMuted,
        textAlign = TextAlign.Center,
    )
    // Listening by itself (tapped): throwing it away is one touch, without saying it — a quiet ×,
    // no circle around it, so the button stays the one thing that stands out.
    if (s.locked) {
        Spacer(Modifier.height(10.dp))
        QuietIcon(NoveloIcons.Close, t(R.string.cancel), onCancel, alpha = 0.45f)
    }
}

@Composable
internal fun WorkingText(s: MindState.Working, onStop: () -> Unit) {
    Text(stageText(s), style = MaterialTheme.typography.labelLarge, color = NoveloColors.Accent)
    // Where it is in it (a piece of the recording, a photo, a file): a stitch each, the done ones filled.
    if (s.of in 2..MAX_STEP_DOTS) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(s.of) { i ->
                val on = i < s.step
                Box(
                    Modifier
                        .width(if (i == s.step - 1) 16.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (on) NoveloColors.Accent else NoveloColors.Accent.copy(alpha = 0.22f)),
                )
            }
        }
    }
    // The words only while they are being heard (the newest ones: the start is known, the end shows
    // it is going); once it organizes, the screen is only what it is doing.
    if (s.stage == Stage.HEARING && s.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Said(tail(s.text), maxLines = 8)
    }
    // Taking too long: a stop. What was said stays, to try again or discard.
    if (s.captureId != 0L) {
        Spacer(Modifier.height(14.dp))
        StopPill(onStop)
        // It goes on with the app minimized or closed (and says when it is done).
        Spacer(Modifier.height(12.dp))
        Text(
            t(R.string.work_away),
            style = MaterialTheme.typography.bodySmall,
            color = NoveloColors.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** "Parar": a quiet pill — a small solid square and the word, never an empty box on its own. */
@Composable
private fun StopPill(onStop: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, 0.95f)
            .clip(CircleShape)
            .background(NoveloColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = t(R.string.stop), onClick = onStop)
            .semantics { contentDescription = t(R.string.stop) }
            .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(NoveloColors.TextMuted))
        Spacer(Modifier.width(8.dp))
        Text(t(R.string.stop), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
    }
}

/** What it is doing, in words: the stage, and which piece, photo or file of how many. */
internal fun stageText(s: MindState.Working): String {
    val counted = s.of > 1 && s.step in 1..s.of
    return when (s.stage) {
        Stage.HEARING -> if (counted) t(R.string.stage_hearing_part, s.step, s.of) else t(R.string.stage_hearing)
        Stage.READING -> t(R.string.stage_reading)
        Stage.SEEING -> if (counted) t(R.string.stage_seeing_many, s.step, s.of) else t(R.string.stage_seeing)
        Stage.FILES -> if (counted) t(R.string.stage_files_many, s.step, s.of) else t(R.string.stage_files)
        Stage.THINKING -> t(R.string.stage_thinking)
        Stage.LOOKING -> t(R.string.stage_looking)
    }
}

/** More than this many and the stitches would crowd the line: only the words say it. */
private const val MAX_STEP_DOTS = 9

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
        color = NoveloColors.TextMuted,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AnswerView(s: MindState.Answer, entries: List<Entry>, onOpen: (Long) -> Unit, onToggle: (Long) -> Unit, onDismiss: () -> Unit, onOpenMed: (Long) -> Unit = {}) {
    var showSaid by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.reply.isNotBlank()) {
                Text(s.reply, style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (showSaid && s.said.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Said(s.said, maxLines = 12)
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.said.isNotBlank()) {
                    QuietWord(if (showSaid) t(R.string.hide) else if (s.photo) t(R.string.said_photo_low) else if (s.typed) t(R.string.said_typed_low) else t(R.string.said_spoken_low)) { showSaid = !showSaid }
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss).semantics { contentDescription = t(R.string.close) }, contentAlignment = Alignment.Center) {
                    Icon(NoveloIcons.Close, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            val byId = entries.associateBy { it.id }
            var i = 0
            s.outcomes.filter { it.action != Outcome.DELETED }.forEach { o ->
                val e = byId[o.entryId] ?: return@forEach
                AppearingCard(i++) { EntryCard(e, onClick = { onOpen(e.id) }, label = outcomeLabel(o, e), onToggle = { onToggle(e.id) }) }
            }
            // The medicines made or changed: their own cards (a tap opens one).
            if (s.meds.isNotEmpty()) {
                val meds by remember { com.villamediana.novelo.NoveloApplication.graph.library.meds() }.collectAsState(initial = emptyList())
                val taken by remember { com.villamediana.novelo.NoveloApplication.graph.library.allTaken() }.collectAsState(initial = emptyList())
                val now = remember { System.currentTimeMillis() }
                s.meds.forEach { id ->
                    val m = meds.firstOrNull { it.id == id } ?: return@forEach
                    AppearingCard(i++) {
                        com.villamediana.novelo.ui.meds.MedCard(
                            m, now, onClick = { onOpenMed(m.id) }, label = t(if (m.createdAt >= now - 120_000L) R.string.outcome_med_new else R.string.outcome_med_changed),
                            taken = taken.filter { it.medId == m.id }.map { it.at }.toSet(),
                        )
                    }
                }
            }
            val terms = remember(s.said) { Words.of(s.said).filter { it.length >= 4 } }
            s.shown.filter { id -> s.outcomes.none { it.entryId == id } }.forEach { id ->
                val e = byId[id] ?: return@forEach
                AppearingCard(i++) { EntryCard(e, onClick = { onOpen(e.id) }, terms = terms, onToggle = { onToggle(e.id) }) }
            }
            // The photos that answer, in the order Novelo gave them; a tap opens them big.
            if (s.photos.isNotEmpty()) {
                val kept by remember { com.villamediana.novelo.NoveloApplication.graph.library.allPhotos() }.collectAsState(initial = emptyList())
                val found = remember(kept, s.photos) { kept.associateBy { it.id }.let { m -> s.photos.mapNotNull(m::get) } }
                if (found.isNotEmpty()) AppearingCard(i++) { com.villamediana.novelo.ui.entry.PhotoRow(found, side = 22.dp, height = 170.dp, tag = "answer-photo") }
            }
        }
    }
}

@Composable
private fun QuietWord(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = NoveloColors.TextFaint,
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
        enter = fadeIn(com.villamediana.novelo.ui.theme.Motion.enter(360)) + slideInVertically(com.villamediana.novelo.ui.theme.Motion.slide) { it / 3 },
    ) {
        Box(Modifier.padding(bottom = 10.dp)) { content() }
    }
}

private fun outcomeLabel(o: Outcome, e: Entry): String = when (o.action) {
    Outcome.CREATED -> when (e.kindEnum) {
        Kind.TASK -> t(R.string.outcome_new_task)
        Kind.NOTE -> t(R.string.outcome_note_kept)
        Kind.DIARY -> t(R.string.outcome_in_diary)
    }
    Outcome.DONE -> t(R.string.outcome_done)
    else -> t(R.string.outcome_updated)
}

@Composable
private fun ProblemView(s: MindState.Problem, onRetry: (Long) -> Unit, onDiscard: (Long) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    Text(s.message, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    if (!s.said.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Said(s.said, maxLines = 4)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (s.need != null) IconAction(NoveloIcons.Tune, t(R.string.settings), onSettings, primary = true)
        if (s.captureId != null) {
            IconAction(NoveloIcons.Retry, t(R.string.try_again), { onRetry(s.captureId) }, primary = s.need == null)
            IconAction(NoveloIcons.Trash, t(R.string.discard), { onDiscard(s.captureId) })
        } else {
            IconAction(NoveloIcons.Check, t(R.string.ok), onDismiss)
        }
    }
    if (s.captureId != null && s.need != null) {
        Spacer(Modifier.height(10.dp))
        Text(t(R.string.kept_here), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextFaint, textAlign = TextAlign.Center)
    }
}

/** The camera and the gallery, what is for today (the way into everything kept), the search. */
@Composable
private fun BottomBar(entries: List<Entry>, onCamera: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit, onToday: () -> Unit, onSearch: () -> Unit) {
    val today = remember(entries) { todayCount(entries) }
    val gap = 6.dp
    // What brings things in on the left (camera, gallery, files), the search on the right, and "Hoje"
    // in the middle — moved over only as much as it takes not to touch the left ones on a narrow phone.
    androidx.compose.ui.layout.Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A bit narrower than elsewhere, so three fit beside "Hoje" without pushing it off the middle.
                QuietIcon(NoveloIcons.PhotoCamera, t(R.string.camera), onCamera, size = BAR_ICON)
                QuietIcon(NoveloIcons.Gallery, t(R.string.gallery), onGallery, size = BAR_ICON)
                QuietIcon(NoveloIcons.File, t(R.string.attach_file), onFile, size = BAR_ICON)
            }
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .pressScale(interaction)
                    .clip(CircleShape)
                    .background(NoveloColors.Surface)
                    .stitched(CircleShape, StitchColor, inset = 5.dp)
                    .clickable(interactionSource = interaction, indication = null, onClick = onToday)
                    .padding(start = 24.dp, end = if (today > 0) 14.dp else 24.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t(R.string.today), style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text)
                if (today > 0) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(NoveloColors.Accent), contentAlignment = Alignment.Center) {
                        Text(today.toString(), style = MaterialTheme.typography.labelMedium, color = NoveloColors.OnAccent)
                    }
                }
            }
            QuietIcon(NoveloIcons.Search, t(R.string.search), onSearch, size = BAR_ICON)
        },
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
    ) { parts, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = parts[0].measure(loose)
        val middle = parts[1].measure(loose)
        val right = parts[2].measure(loose)
        val w = constraints.maxWidth
        val h = maxOf(left.height, middle.height, right.height)
        val space = gap.roundToPx()
        val x = ((w - middle.width) / 2)
            .coerceAtMost(w - right.width - space - middle.width)
            .coerceAtLeast(left.width + space)
        layout(w, h) {
            left.place(0, (h - left.height) / 2)
            middle.place(x, (h - middle.height) / 2)
            right.place(w - right.width, (h - right.height) / 2)
        }
    }
}

/** The bottom bar's icons: room enough for a finger, three of them beside "Hoje". */
private val BAR_ICON = 44.dp

/** Open tasks due today or late, and reminders still to come today. */
fun todayCount(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Int {
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return entries.count { e ->
        e.kindEnum == Kind.TASK && e.statusEnum != Status.DONE && ((e.dueAt != null && e.dueAt < end) || (e.remindAt != null && e.remindAt < end))
    }
}
