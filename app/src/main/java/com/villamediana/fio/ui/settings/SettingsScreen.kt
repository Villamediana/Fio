package com.villamediana.fio.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import com.villamediana.fio.shortcut.Gesture
import com.villamediana.fio.ui.theme.Motion
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.villamediana.fio.ui.setup.LanguagePicker
import com.villamediana.fio.settings.Lang
import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.BuildConfig
import com.villamediana.fio.FioApplication
import com.villamediana.fio.backup.Backups
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.settings.FioSettings
import com.villamediana.fio.settings.Source
import com.villamediana.fio.settings.ThemeMode
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.components.Group
import com.villamediana.fio.ui.components.NoveloMark
import com.villamediana.fio.ui.components.paper
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioAccents
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.update.UpdateRow

/** The parts of Ajustes, each opened in place. */
private enum class Part { AI, SHORTCUT, THEME, BACKUP, LANGUAGE }

/**
 * Ajustes: one page. Each part is a line that says in a few words how things are (in the accent
 * when something is missing) and opens right there, one at a time; under them, what the app is.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    val graph = FioApplication.graph
    val context = LocalContext.current
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()
    val busy by graph.backups.busy.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Part?>(null) }
    var gesture by remember { mutableStateOf(Gesture.on(context)) }
    fun toggle(p: Part) { open = if (open == p) null else p }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageTop(t(R.string.settings)) { nav.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            val ai = intelligenceSummary(s, local, graph.novelo.available)
            val backup = backupSummary(s, busy)
            Fold(t(R.string.ai_title), ai.first, ai.second, open == Part.AI, { toggle(Part.AI) }) { IntelligenceSection() }
            Fold(t(R.string.shortcut), t(if (gesture) R.string.shortcut_on else R.string.shortcut_off), false, open == Part.SHORTCUT, { toggle(Part.SHORTCUT) }) {
                ShortcutSection(gesture) { on -> Gesture.set(context, on); gesture = on }
            }
            Fold(
                t(R.string.theme),
                when (s.theme) { ThemeMode.SYSTEM -> t(R.string.theme_system); ThemeMode.LIGHT -> t(R.string.theme_light); ThemeMode.DARK -> t(R.string.theme_dark) } + " · " + FioAccents.byId(s.accent).name.lowercase(),
                false, open == Part.THEME, { toggle(Part.THEME) },
            ) { ThemeSection() }
            Fold(t(R.string.backup), backup.first, backup.second, open == Part.BACKUP, { toggle(Part.BACKUP) }) { BackupSection() }
            Fold(t(R.string.language), Lang.current.label, false, open == Part.LANGUAGE, { toggle(Part.LANGUAGE) }) { LanguagePicker() }

            Spacer(Modifier.height(18.dp))
            val updateLine: (@Composable () -> Unit)? = if (graph.updates.enabled) ({ UpdateRow() }) else null
            Group(
                Modifier,
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        NoveloMark(34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Novelo", style = MaterialTheme.typography.titleLarge, color = FioColors.Text)
                            Text(t(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted)
                        }
                    }
                },
                updateLine,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * One part of Ajustes: its name and how it is, and — opened — what is in it, right under it (the
 * chevron turns down). The page scrolls to keep what opened in view.
 */
@Composable
private fun Fold(title: String, summary: String, attention: Boolean, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.soft(), label = "chevron")
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(open) { if (open) { delay(260); bring.bringIntoView() } }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).bringIntoViewRequester(bring)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(FioColors.Surface)
                .clickable(onClick = onToggle)
                .heightIn(min = 60.dp)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (open) FioColors.Accent else FioColors.Text)
                if (!open) Text(summary, style = MaterialTheme.typography.bodySmall, color = if (attention) FioColors.Accent else FioColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(FioIcons.Chevron, null, tint = if (open) FioColors.Accent else FioColors.TextFaint, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()), exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit())) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) { content() }
        }
    }
}

/** Atalho: recording with the phone's own gesture (on or off), and in one line where to choose it. */
@Composable
private fun ShortcutSection(on: Boolean, onChange: (Boolean) -> Unit) {
    Group(Modifier, {
        Line(title = t(R.string.shortcut_gesture), subtitle = if (on) t(R.string.shortcut_how) else null, onClick = { onChange(!on) }) {
            Switch(
                checked = on,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FioColors.OnAccent,
                    checkedTrackColor = FioColors.Accent,
                    checkedBorderColor = FioColors.Accent,
                    uncheckedThumbColor = FioColors.TextFaint,
                    uncheckedTrackColor = FioColors.Surface2,
                    uncheckedBorderColor = FioColors.Surface3,
                ),
            )
        }
    })
}

/** Who thinks, in a few words (hearing is always on the phone); true when something is missing. */
internal fun intelligenceSummary(s: FioSettings, local: LocalModel.State, novelo: Boolean): Pair<String, Boolean> = when {
    !(LocalEngine.available && local is LocalModel.State.Ready) -> t(R.string.local_pack_hear) + " · " + localShort(local) to true
    s.think == Source.NOVELO && !novelo -> t(R.string.need_novelo_not_in_version) to true
    s.think == Source.API && !s.apiReady -> t(R.string.ai_missing_api) to true
    s.think == Source.API && s.thinkModel.isBlank() -> t(R.string.ai_missing_model) to true
    s.think == Source.API -> t(R.string.ai_mixed_phone_hears, s.thinkModel) to false
    else -> t(R.string.ai_novelo_summary) to false
}

/** The backup in a few words; true when it needs her. */
internal fun backupSummary(s: FioSettings, busy: String?): Pair<String, Boolean> = when {
    s.backupFolder.isBlank() -> t(R.string.backup_choose_where) to true
    busy == Backups.SAVING -> t(R.string.backup_doing) to false
    s.backupFailed -> s.backupNote to true
    s.backupAt > 0 -> t(R.string.backup_daily_last, moment(s.backupAt)) to false
    else -> t(R.string.backup_daily) to false
}

/** The top of a page in Ajustes: back, and its name. */
@Composable
internal fun PageTop(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietIcon(FioIcons.Back, t(R.string.back), onBack, alpha = 0.8f)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A row of a block: the name (and a word under it) on the left, the control on the right. */
@Composable
internal fun Line(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = FioColors.TextMuted,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

