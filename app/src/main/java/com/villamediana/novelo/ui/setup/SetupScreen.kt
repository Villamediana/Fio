package com.villamediana.novelo.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.ai.Provider
import com.villamediana.novelo.ai.Providers
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.remind.Reminders
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.AppLang
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.Segmented
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.formatBytes
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.pressScale
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.home.drawLooseEnd
import com.villamediana.novelo.ui.home.drawStitchRing
import com.villamediana.novelo.ui.home.drawYarn
import com.villamediana.novelo.ui.settings.ModelPicker
import com.villamediana.novelo.ui.settings.providerName
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * The first steps, once: the permissions, where the backup goes, and who thinks with her — the
 * Novelo's own intelligence on the phone, or hers (a key from OpenAI, Anthropic, Kimi…). Each step
 * shows how things already are (so going through again is a few touches) and can be left for later.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Steps(step, count = 3, modifier = Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 6 else it / 6 } + fadeOut())
            },
            label = "setup",
            modifier = Modifier.weight(1f),
        ) { at ->
            when (at) {
                0 -> PermissionsStep(onNext = { step = 1 })
                1 -> BackupStep(onNext = { step = 2 })
                else -> IntelligenceStep(onDone = onDone)
            }
        }
    }
}

/** Where she is: a stitch per step, the one she is on in the accent. */
@Composable
private fun Steps(at: Int, count: Int, modifier: Modifier = Modifier) {
    val accent = NoveloColors.Accent
    val faint = NoveloColors.Accent.copy(alpha = 0.25f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.width(if (i == at) 22.dp else 10.dp).height(4.dp).clip(CircleShape).background(if (i <= at) accent else faint))
        }
    }
}

/** A page of the setup: a small ball of yarn, a title, a line, what to do, and the way on. */
@Composable
private fun Page(
    title: String,
    line: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    top: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            top()
            Yarn(Modifier.size(84.dp))
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = NoveloColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(line, style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextMuted)
            Spacer(Modifier.height(22.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BigButton(primary, onPrimary, Modifier.fillMaxWidth())
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelLarge,
                    color = NoveloColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable(onClick = onSecondary).padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** The ball of yarn, small (the button's own drawing). */
@Composable
private fun Yarn(modifier: Modifier = Modifier) {
    val accent = NoveloColors.Accent
    val on = NoveloColors.OnAccent
    Canvas(modifier) {
        val r = size.minDimension * 0.30f
        drawStitchRing(center, r, accent, 1f)
        drawLooseEnd(center, r, accent)
        drawCircle(accent, radius = r)
        drawYarn(center, r, on.copy(alpha = 0.24f))
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction, 0.97f)
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(NoveloColors.Accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = NoveloColors.OnAccent)
    }
}

/** A stitched card on the paper. */
@Composable
private fun Card(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) NoveloColors.Accent.copy(alpha = 0.10f) else NoveloColors.Surface)
            .stitched(shape, if (selected) NoveloColors.Accent else StitchColor, width = if (selected) 1.6.dp else 1.3.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).semantics { role = Role.RadioButton; this.selected = selected } else Modifier)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .animateContentSize(),
    ) { content() }
}

/** A choice with nothing under its name: a roomy card, the name in the middle of it. */
@Composable
private fun ChoiceTitle(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text)
    }
}

// ——— 1. Permissions (and the language, first of all) ———

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val notifications = Build.VERSION.SDK_INT >= 33
    var tick by remember { mutableIntStateOf(0) }
    val mic = remember(tick) { granted(context, Manifest.permission.RECORD_AUDIO) }
    val notif = remember(tick) { !notifications || granted(context, Manifest.permission.POST_NOTIFICATIONS) }
    val camera = remember(tick) { granted(context, Manifest.permission.CAMERA) }
    // Reminders at the very minute: not a question Android asks in the app, but a switch on a system
    // page ("Alarmes e lembretes"); only shown when this phone needs it (the GitHub build never does).
    val onTime = remember(tick) { Reminders.onTime(context) }
    val asksOnTime = remember { !Reminders.onTime(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val openOnTime = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val missing = buildList {
        if (!mic) add(Manifest.permission.RECORD_AUDIO)
        if (!notif && notifications) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!camera) add(Manifest.permission.CAMERA)
    }
    val allSet = missing.isEmpty() && onTime
    Page(
        title = t(R.string.setup_hello),
        line = t(R.string.setup_permissions_line),
        primary = t(if (allSet) R.string.setup_continue else R.string.setup_allow),
        onPrimary = {
            when {
                missing.isNotEmpty() -> ask.launch(missing.toTypedArray())
                !onTime -> runCatching { openOnTime.launch(Reminders.onTimeSettings(context)) }.onFailure { onNext() }
                else -> onNext()
            }
        },
        secondary = if (allSet) null else t(R.string.setup_not_now),
        onSecondary = onNext,
        top = { LanguagePicker(Modifier.padding(bottom = 22.dp)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Permission(NoveloIcons.Mic, t(R.string.setup_mic), t(R.string.setup_mic_why), mic)
            Permission(NoveloIcons.Bell, t(R.string.setup_notifications), t(R.string.setup_notifications_why), notif)
            Permission(NoveloIcons.PhotoCamera, t(R.string.setup_camera), t(R.string.setup_camera_why), camera)
            if (asksOnTime) Permission(NoveloIcons.Clock, t(R.string.setup_alarms), t(R.string.setup_alarms_why), onTime)
        }
    }
}

/** The app's language, right at the start: choosing it makes the screen again in it. */
@Composable
internal fun LanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val langs = AppLang.entries
    Segmented(
        langs.map { it.label },
        selected = langs.indexOf(Lang.current),
        onSelect = { i ->
            if (langs[i] != Lang.current) {
                Lang.set(context, langs[i])
                (context as? Activity)?.recreate() ?: context.findActivity()?.recreate()
            }
        },
        modifier = modifier,
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Permission(icon: ImageVector, title: String, why: String, ok: Boolean) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(NoveloColors.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = NoveloColors.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                Text(why, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
            }
            if (ok) Icon(NoveloIcons.Check, t(R.string.setup_allowed), tint = NoveloColors.Success, modifier = Modifier.size(20.dp))
        }
    }
}

// ——— 2. The backup ———

@Composable
private fun BackupStep(onNext: () -> Unit) {
    val graph = NoveloApplication.graph
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) graph.backups.choose(uri)
    }
    val chosen = s.backupFolder.isNotBlank()
    fun choose() = pick.launch(s.backupFolder.takeIf { it.isNotBlank() }?.let(Uri::parse))
    Page(
        title = t(R.string.setup_backup_title),
        line = t(R.string.setup_backup_line),
        primary = t(if (chosen) R.string.setup_continue else R.string.setup_choose_folder),
        onPrimary = { if (chosen) onNext() else choose() },
        secondary = if (chosen) null else t(R.string.setup_later),
        onSecondary = onNext,
    ) {
        Card(onClick = { choose() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(NoveloColors.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(NoveloIcons.Upload, null, tint = NoveloColors.Accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t(R.string.backup_folder), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                    Text(
                        if (chosen) s.backupPlace.ifBlank { t(R.string.backup_folder_chosen) } else t(R.string.backup_choose_where),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) NoveloColors.TextMuted else NoveloColors.Accent,
                    )
                }
                if (chosen) Icon(NoveloIcons.Check, null, tint = NoveloColors.Success, modifier = Modifier.size(20.dp))
                else Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ——— 3. Who thinks ———

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntelligenceStep(onDone: () -> Unit) {
    val graph = NoveloApplication.graph
    val st = graph.settings
    val scope = rememberCoroutineScope()
    val s by st.now.collectAsStateWithLifecycle()
    // Hers when her API already thinks; the Novelo's otherwise.
    var own by rememberSaveable { mutableStateOf(s.think == Source.API) }

    fun finish() = scope.launch {
        st.setSetupDone()
        onDone()
    }

    Page(
        title = t(R.string.setup_ai_title),
        line = t(R.string.setup_ai_line),
        primary = t(R.string.setup_start),
        onPrimary = { finish() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(selected = !own, onClick = {
                own = false
                scope.launch { st.setThink(Source.NOVELO) }
            }) {
                ChoiceTitle(t(R.string.setup_ai_novelo))
            }
            Card(selected = own, onClick = {
                own = true
                scope.launch { st.setThink(Source.API) }
            }) {
                ChoiceTitle(t(R.string.setup_ai_own))
            }
            Spacer(Modifier.height(6.dp))
            if (own) OwnSetup()
            // Hearing is on the phone, whoever thinks: its ears come down (just its name and size).
            Text(t(R.string.local_pack_hear) + " · " + formatBytes(graph.localModel.totalBytes), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
            LocalDownload()
        }
    }
}

/** The model on the phone: ready, coming, or how she wants it to come (Wi-Fi or now). */
@Composable
internal fun LocalDownload(modifier: Modifier = Modifier) {
    val model = NoveloApplication.graph.localModel
    val state by model.state.collectAsStateWithLifecycle()
    var waitingWifi by rememberSaveable { mutableStateOf(false) }
    val onWifi = remember { model.onWifi() }
    // On Wi-Fi it simply starts (also when the choice changes what has to come).
    LaunchedEffect(state == LocalModel.State.Missing) { if (LocalEngine.available && onWifi && state == LocalModel.State.Missing) model.download() }
    val percent = NumberFormat.getPercentInstance(Lang.locale)
    Column(modifier.fillMaxWidth().animateContentSize()) {
        when {
            !LocalEngine.available -> Note(t(R.string.local_not_in_version), NoveloColors.Danger)
            state == LocalModel.State.Ready -> Note(t(R.string.setup_local_ready), NoveloColors.Success)
            state is LocalModel.State.Downloading -> {
                val d = state as LocalModel.State.Downloading
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(NoveloColors.Surface2)) {
                    Box(Modifier.fillMaxWidth(d.fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(NoveloColors.Accent))
                }
                Spacer(Modifier.height(8.dp))
                Note(
                    if (waitingWifi && d.bytes == 0L) t(R.string.setup_local_waiting_wifi)
                    else t(R.string.setup_local_downloading, percent.format(d.fraction.toDouble()), formatBytes(model.totalBytes)),
                    NoveloColors.TextMuted,
                )
            }
            state == LocalModel.State.Checking -> Note(t(R.string.local_checking), NoveloColors.TextMuted)
            else -> {
                val failed = (state as? LocalModel.State.Failed)?.message
                if (failed != null) Note(failed, NoveloColors.Danger)
                if (!onWifi) {
                    Card {
                        Column {
                            Text(t(R.string.setup_local_no_wifi, formatBytes(model.totalBytes)), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(t(R.string.setup_local_wait_wifi), selected = true, onClick = { waitingWifi = true; model.download(wifiOnly = true) })
                                Pill(t(R.string.setup_local_download_anyway), selected = false, onClick = { model.download() })
                            }
                        }
                    }
                } else {
                    Pill(t(R.string.local_download), selected = true, onClick = { model.download() })
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * Her own: whose (a chip each), the key (and the address when that provider has none of its
 * own), tested by itself; then the model that thinks. Hearing is always on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OwnSetup(modifier: Modifier = Modifier, showModels: Boolean = true) {
    val graph = NoveloApplication.graph
    val st = graph.settings
    val scope = rememberCoroutineScope()
    val s by st.now.collectAsStateWithLifecycle()
    var base by remember { mutableStateOf(s.apiBase) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<Pair<String, Boolean?>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val provider = s.provider

    suspend fun connect() {
        if (base.isNotBlank() && base.trim().trimEnd('/') != s.apiBase) st.setApiBase(base)
        if (key.isNotBlank()) { st.setApiKey(key); key = "" }
        val now = st.current()
        val k = st.apiKey()
        if (!now.apiReady || k.isNullOrBlank()) { status = null; return }
        loading = true
        runCatching { withContext(Dispatchers.IO) { graph.api.models(now.endpoint(k)) } }
            .onSuccess { models = it; status = (if (it.isEmpty()) t(R.string.api_connected) else t(R.string.api_connected_models, it.size)) to true }
            .onFailure { status = t(R.string.api_not_connected, it.message.orEmpty()) to false }
        loading = false
        graph.mind.retryPending()
    }

    LaunchedEffect(provider.id, base, key) {
        if (key.isBlank() && base.trim().trimEnd('/') == s.apiBase && !s.apiReady) return@LaunchedEffect
        delay(if (key.isBlank() && base.trim().trimEnd('/') == s.apiBase) 0 else 900)
        connect()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Providers.all.forEach { p ->
                Pill(providerName(p), selected = p.id == provider.id && (s.apiProvider.isNotBlank() || p == Providers.OTHER), onClick = {
                    scope.launch {
                        st.setApiProvider(p.id)
                        models = emptyList(); status = null
                    }
                })
            }
        }
        if (provider.base == null) SetupField(t(R.string.api_address), base, { base = it }, provider.baseHint, KeyboardType.Uri)
        SetupField(
            t(R.string.api_key),
            key,
            { key = it },
            if (s.hasApiKey) t(R.string.api_key_saved) else t(R.string.api_key_paste),
            secret = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(12.dp), color = NoveloColors.Accent, strokeWidth = 1.5.dp)
                Text(t(R.string.api_testing), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(start = 8.dp))
            } else status?.let { (text, ok) ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok == true) NoveloColors.Success else NoveloColors.Danger, modifier = Modifier.padding(start = if (loading) 8.dp else 0.dp))
            }
        }
        if (showModels && s.apiReady) ModelLine(t(R.string.ai_model_thinks), s.thinkModel) { picking = true }
    }

    if (picking) {
        ModelPicker(
            title = t(R.string.ai_model_thinks_title),
            current = s.thinkModel,
            models = models,
            settings = s,
            onLoad = { scope.launch { connect() } },
            onPick = { m ->
                scope.launch {
                    st.setThinkModel(m); st.setThink(Source.API)
                    graph.mind.retryPending()
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ModelLine(title: String, model: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                Text(
                    model.ifBlank { t(R.string.ai_choose_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.isBlank()) NoveloColors.Accent else NoveloColors.TextMuted,
                )
            }
            Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SetupField(label: String, value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    Card {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
                    cursorBrush = SolidColor(NoveloColors.Accent),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag(if (secret) "setup-key" else "setup-address"),
                )
            }
        }
    }
}
