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
import com.villamediana.novelo.ui.sheets.NoveloSheet
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
import com.villamediana.novelo.ui.settings.moment
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
 * The first steps, once: the permissions (and the language), the Novelo's ears (they come only
 * when she agrees, and go on in the background), the backup folder (a must; a backup already there
 * comes back), and who thinks with her — the Novelo's own intelligence or hers. The app opens only
 * with the ears on the phone.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Steps(step, count = 4, modifier = Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally))
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
                1 -> EarsStep(onNext = { step = 2 })
                2 -> BackupStep(onNext = { step = 3 })
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
    primaryEnabled: Boolean = true,
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
            BigButton(primary, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
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
private fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction, 0.97f)
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (enabled) NoveloColors.Accent else NoveloColors.Accent.copy(alpha = 0.35f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
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
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val missing = buildList {
        if (!mic) add(Manifest.permission.RECORD_AUDIO)
        if (!notif && notifications) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!camera) add(Manifest.permission.CAMERA)
    }
    Page(
        title = t(R.string.setup_hello),
        line = t(R.string.setup_permissions_line),
        primary = t(if (missing.isEmpty()) R.string.setup_continue else R.string.setup_allow),
        onPrimary = { if (missing.isEmpty()) onNext() else ask.launch(missing.toTypedArray()) },
        secondary = if (missing.isEmpty()) null else t(R.string.setup_not_now),
        onSecondary = onNext,
        top = { LanguagePicker(Modifier.padding(bottom = 22.dp)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Permission(NoveloIcons.Mic, t(R.string.setup_mic), t(R.string.setup_mic_why), mic)
            Permission(NoveloIcons.Bell, t(R.string.setup_notifications), t(R.string.setup_notifications_why), notif)
            Permission(NoveloIcons.PhotoCamera, t(R.string.setup_camera), t(R.string.setup_camera_why), camera)
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
            // The screens follow at once (no restart): she stays right where she is.
            if (langs[i] != Lang.current) Lang.set(context, langs[i])
        },
        modifier = modifier,
    )
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
    val scope = rememberCoroutineScope()
    val s by graph.settings.now.collectAsStateWithLifecycle()
    // Looking in the folder / bringing a backup back; then what came back (when one did).
    var bringing by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            bringing = true
            back = null
            scope.launch {
                val r = runCatching { graph.backups.chooseAndBringBack(uri) }.getOrNull()
                back = r?.let { (kept, restored) ->
                    t(R.string.setup_backup_back, moment(kept.at), com.villamediana.novelo.backup.Backups.note(restored.entries, restored.photos, restored.sounds, restored.files))
                }
                bringing = false
            }
        }
    }
    val chosen = s.backupFolder.isNotBlank()
    fun choose() = pick.launch(s.backupFolder.takeIf { it.isNotBlank() }?.let(Uri::parse))
    Page(
        title = t(R.string.setup_backup_title),
        line = t(R.string.setup_backup_line),
        primary = t(if (chosen) R.string.setup_continue else R.string.setup_choose_folder),
        onPrimary = { if (chosen) onNext() else choose() },
        primaryEnabled = !bringing,
        // A folder is a must: with it nothing is lost, whatever happens to the phone.
    ) {
        Card(onClick = { if (!bringing) choose() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(NoveloColors.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(NoveloIcons.Upload, null, tint = NoveloColors.Accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t(R.string.backup_folder), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                    Text(
                        when {
                            bringing -> t(R.string.setup_backup_bringing)
                            chosen -> s.backupPlace.ifBlank { t(R.string.backup_folder_chosen) }
                            else -> t(R.string.backup_choose_where)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen || bringing) NoveloColors.TextMuted else NoveloColors.Accent,
                    )
                }
                when {
                    bringing -> androidx.compose.material3.CircularProgressIndicator(color = NoveloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    chosen -> Icon(NoveloIcons.Check, null, tint = NoveloColors.Success, modifier = Modifier.size(20.dp))
                    else -> Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
                }
            }
        }
        back?.let {
            Spacer(Modifier.height(12.dp))
            Note(it, NoveloColors.Success)
        }
        // Better in a cloud drive on the phone (one that shows in the list): the backup lives off the phone.
        if (!chosen && !bringing) {
            Spacer(Modifier.height(14.dp))
            Note(t(R.string.setup_backup_cloud), NoveloColors.TextMuted)
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
    val ears by graph.localModel.state.collectAsStateWithLifecycle()
    // The app opens only with the ears on the phone (they came down while she went on).
    val earsReady = ears == LocalModel.State.Ready || !LocalEngine.available
    val percent = NumberFormat.getPercentInstance(Lang.locale)
    // Hers when her API already thinks; the Novelo's otherwise.
    var own by rememberSaveable { mutableStateOf(s.think == Source.API) }
    // Hers goes on only when it is whole: whose, the key, connected, the model.
    var ownReady by remember { mutableStateOf(OwnReady.PROVIDER) }
    val ownOk = !own || ownReady == OwnReady.READY
    val canGo = ownOk && earsReady

    Page(
        title = t(R.string.setup_ai_title),
        line = t(R.string.setup_ai_line),
        primary = when {
            !ownOk -> t(ownReady.need)
            !earsReady -> (ears as? LocalModel.State.Downloading)?.let { t(R.string.setup_ears_waiting, percent.format(it.fraction.toDouble())) } ?: t(R.string.setup_ears_wait)
            else -> t(R.string.setup_start)
        },
        onPrimary = {
            scope.launch {
                st.setSetupDone()
                onDone()
            }
        },
        primaryEnabled = canGo,
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
            if (own) OwnSetup(onReady = { ownReady = it })
            // The ears stopped (no room, the download failed, gone) or wait for the Wi-Fi: what to do
            // is here too — the button can't go on without them, and the setup has no way back.
            val stuck = ears == LocalModel.State.Missing || ears is LocalModel.State.Failed || (ears as? LocalModel.State.Downloading)?.waiting != null
            if (LocalEngine.available && stuck) {
                Spacer(Modifier.height(8.dp))
                Text(t(R.string.setup_ears_title), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                LocalDownload(Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Where her own intelligence is in its setup, and what the button says while something is missing. */
internal enum class OwnReady(val need: Int) {
    PROVIDER(R.string.setup_need_provider),
    KEY(R.string.setup_need_key),
    CONNECTION(R.string.setup_need_connection),
    MODEL(R.string.setup_need_model),
    READY(R.string.setup_continue),
}

// ——— 4. The ears ———

/**
 * The Novelo's ears (LFM3-Omni, on the phone): without them nothing is heard. They never come by
 * themselves — she agrees (Baixar; off Wi-Fi she picks Wi-Fi or mobile data). Once they are coming
 * she goes on (it goes on in the background), and the last step's "Começar" waits for them. (A build
 * without the engine, only for tests, can't have them: it lets her go.)
 */
@Composable
private fun EarsStep(onNext: () -> Unit) {
    val graph = NoveloApplication.graph
    val state by graph.localModel.state.collectAsStateWithLifecycle()
    val agreed = !LocalEngine.available || state == LocalModel.State.Ready || state == LocalModel.State.Checking || state is LocalModel.State.Downloading
    Page(
        title = t(R.string.setup_ears_title),
        line = t(R.string.setup_ears_line, formatBytes(graph.localModel.totalBytes)),
        primary = t(if (agreed) R.string.setup_continue else R.string.setup_ears_agree),
        onPrimary = onNext,
        primaryEnabled = agreed,
    ) {
        LocalDownload()
    }
}

/**
 * The model on the phone: ready, coming, or how she wants it to come. It never starts by itself:
 * on Wi-Fi she taps Baixar (it then waits if the Wi-Fi goes, never her mobile data unasked); off
 * it, she chooses: wait for the Wi-Fi or use mobile data. It says when it waits (for the Wi-Fi, for a network), when the phone has no
 * room, and that it goes on with the app in the background.
 */
@Composable
internal fun LocalDownload(modifier: Modifier = Modifier) {
    val model = NoveloApplication.graph.localModel
    val state by model.state.collectAsStateWithLifecycle()
    val onWifi by remember { model.wifi() }.collectAsStateWithLifecycle(initialValue = remember { model.onWifi() })
    val percent = NumberFormat.getPercentInstance(Lang.locale)

    /** Off Wi-Fi: wait for it, or use mobile data now. */
    @Composable
    fun Choose() {
        Card {
            Column {
                Text(t(R.string.setup_local_no_wifi, formatBytes(model.totalBytes)), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(t(R.string.setup_local_wait_wifi), selected = true, onClick = { model.download(wifiOnly = true) })
                    Pill(t(R.string.local_use_mobile), selected = false, onClick = { model.download() })
                }
            }
        }
    }

    Column(modifier.fillMaxWidth().animateContentSize()) {
        when {
            !LocalEngine.available -> Note(t(R.string.local_not_in_version), NoveloColors.Danger)
            state == LocalModel.State.Ready -> Note(t(R.string.setup_local_ready), NoveloColors.Success)
            state is LocalModel.State.Downloading -> {
                val d = state as LocalModel.State.Downloading
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(NoveloColors.Surface2)) {
                    Box(Modifier.fillMaxWidth(d.fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(if (d.waiting != null) NoveloColors.Accent.copy(alpha = 0.45f) else NoveloColors.Accent))
                }
                Spacer(Modifier.height(8.dp))
                when (d.waiting) {
                    LocalModel.Waiting.WIFI -> {
                        Note(t(R.string.local_waiting_wifi, percent.format(d.fraction.toDouble())), NoveloColors.TextMuted)
                        Spacer(Modifier.height(10.dp))
                        // Waiting for the Wi-Fi: mobile data now, if she wants (the piece coming starts again).
                        Pill(t(R.string.local_use_mobile), selected = false, onClick = { model.useMobileData() })
                    }
                    LocalModel.Waiting.NETWORK -> Note(t(R.string.local_waiting_network, percent.format(d.fraction.toDouble())), NoveloColors.TextMuted)
                    null -> {
                        Note(t(R.string.setup_local_downloading, percent.format(d.fraction.toDouble()), formatBytes(model.totalBytes)), NoveloColors.TextMuted)
                        Note(t(R.string.local_background), NoveloColors.TextFaint)
                    }
                }
            }
            state == LocalModel.State.Checking -> Note(t(R.string.local_checking), NoveloColors.TextMuted)
            else -> {
                // Missing, or it stopped (no room, a damaged file, the download failed): what happened, and again.
                (state as? LocalModel.State.Failed)?.message?.let {
                    Note(it, NoveloColors.Danger)
                    Spacer(Modifier.height(10.dp))
                }
                if (onWifi) Pill(if (state is LocalModel.State.Failed) t(R.string.try_again) else t(R.string.local_download), selected = true, onClick = { model.download(wifiOnly = true) })
                else Choose()
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
internal fun OwnSetup(modifier: Modifier = Modifier, showModels: Boolean = true, onReady: (OwnReady) -> Unit = {}) {
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
    var choosingProvider by remember { mutableStateOf(false) }
    val provider = s.provider
    // Whose it is, once she chose (an address from before, without a provider, is "Outro").
    val chosen = s.apiProvider.isNotBlank() || s.apiBase.isNotBlank()
    val ready = when {
        !chosen -> OwnReady.PROVIDER
        !s.apiReady -> OwnReady.KEY
        status?.second != true -> OwnReady.CONNECTION
        s.thinkModel.isBlank() -> OwnReady.MODEL
        else -> OwnReady.READY
    }
    LaunchedEffect(ready) { onReady(ready) }

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
        // Whose it is: one line that opens the list (calmer than a row of names).
        ChoiceLine(t(R.string.ai_provider_title), if (chosen) providerName(provider) else null, tag = "provider-line") { choosingProvider = true }
        // The fields only once she said whose it is.
        if (!chosen) return@Column
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
        // The model only once it connected (its list comes from there).
        if (showModels && s.apiReady && status?.second == true) ModelLine(t(R.string.ai_model_thinks), s.thinkModel) { picking = true }
    }

    if (choosingProvider) {
        NoveloSheet(onDismissRequest = { choosingProvider = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(t(R.string.ai_provider_title), style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text, modifier = Modifier.padding(bottom = 10.dp))
                Providers.all.forEach { p ->
                    val on = chosen && p.id == provider.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                choosingProvider = false
                                scope.launch {
                                    st.setApiProvider(p.id)
                                    models = emptyList(); status = null
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(providerName(p), style = MaterialTheme.typography.titleMedium, color = if (on) NoveloColors.Accent else NoveloColors.Text, modifier = Modifier.weight(1f))
                        if (on) Icon(NoveloIcons.Check, null, tint = NoveloColors.Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
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
private fun ModelLine(title: String, model: String, onClick: () -> Unit) = ChoiceLine(title, model.ifBlank { null }, empty = t(R.string.ai_choose_model), onClick = onClick)

/** A choice as one line: what it is, what is chosen (or, in the accent, that it is still to choose). */
@Composable
private fun ChoiceLine(title: String, value: String?, empty: String = t(R.string.ai_provider_choose), tag: String? = null, onClick: () -> Unit) {
    Card(modifier = if (tag != null) Modifier.testTag(tag) else Modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                Text(
                    value ?: empty,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value == null) NoveloColors.Accent else NoveloColors.TextMuted,
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
