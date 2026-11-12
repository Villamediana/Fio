package com.villamediana.novelo.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.villamediana.novelo.ui.theme.LocalPalette
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import com.villamediana.novelo.ui.theme.Motion
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.villamediana.novelo.ui.setup.LanguagePicker
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
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
import com.villamediana.novelo.BuildConfig
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.backup.Backups
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.NoveloMark
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloAccents
import com.villamediana.novelo.ui.theme.NoveloColors

/** The parts of Ajustes, each opened in place. */
private enum class Part { AI, THEME, BACKUP, LANGUAGE }

/**
 * Ajustes: one page. Each part is a line that says in a few words how things are (in the accent
 * when something is missing) and opens right there, one at a time; under them, what the app is.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    val graph = NoveloApplication.graph
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()
    val busy by graph.backups.busy.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Part?>(null) }
    fun toggle(p: Part) { open = if (open == p) null else p }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageTop(t(R.string.settings)) { nav.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            val ai = intelligenceSummary(s, local, graph.novelo.available)
            val backup = backupSummary(s, busy)
            Fold(t(R.string.ai_title), ai.first, ai.second, open == Part.AI, { toggle(Part.AI) }) { IntelligenceSection() }
            Fold(
                t(R.string.theme),
                when (s.theme) { ThemeMode.SYSTEM -> t(R.string.theme_system); ThemeMode.LIGHT -> t(R.string.theme_light); ThemeMode.DARK -> t(R.string.theme_dark) } + " · " + NoveloAccents.byId(s.accent).name.lowercase(),
                false, open == Part.THEME, { toggle(Part.THEME) },
            ) { ThemeSection() }
            Fold(t(R.string.backup), backup.first, backup.second, open == Part.BACKUP, { toggle(Part.BACKUP) }) { BackupSection() }
            Fold(t(R.string.language), Lang.current.label, false, open == Part.LANGUAGE, { toggle(Part.LANGUAGE) }) { LanguagePicker() }

            Spacer(Modifier.height(18.dp))
            val updateLine: (@Composable () -> Unit)? = com.villamediana.novelo.Store.settingsLine
            Group(
                Modifier,
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        NoveloMark(34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Novelo", style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text)
                            Text(t(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
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
 * One part of Ajustes: its name and how it is; opened, what is in it, inside the same card (its own
 * cards take the page's color there, so they read as inside it, not as more lines of the menu). The
 * page scrolls to keep what opened in view.
 */
@Composable
private fun Fold(title: String, summary: String, attention: Boolean, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.soft(), label = "chevron")
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(open) { if (open) { delay(260); bring.bringIntoView() } }
    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = 8.dp)
            .bringIntoViewRequester(bring)
            .clip(RoundedCornerShape(20.dp))
            .background(NoveloColors.Surface),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 60.dp)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (open) NoveloColors.Accent else NoveloColors.Text)
                if (!open) Text(summary, style = MaterialTheme.typography.bodySmall, color = if (attention) NoveloColors.Accent else NoveloColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(NoveloIcons.Chevron, null, tint = if (open) NoveloColors.Accent else NoveloColors.TextFaint, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()), exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit())) {
            val p = NoveloColors.palette
            CompositionLocalProvider(LocalPalette provides p.copy(surface = p.background)) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 14.dp)) { content() }
            }
        }
    }
}

/** Who thinks, in a few words (hearing is always on the phone); true when something is missing. */
internal fun intelligenceSummary(s: NoveloSettings, local: LocalModel.State, novelo: Boolean): Pair<String, Boolean> = when {
    !(LocalEngine.available && local is LocalModel.State.Ready) -> t(R.string.local_pack_hear) + " · " + localShort(local) to true
    s.think == Source.NOVELO && !novelo -> t(R.string.need_novelo_not_in_version) to true
    s.think == Source.API && !s.apiReady -> t(R.string.ai_missing_api) to true
    s.think == Source.API && s.thinkModel.isBlank() -> t(R.string.ai_missing_model) to true
    s.think == Source.API -> t(R.string.ai_mixed_phone_hears, s.thinkModel) to false
    else -> t(R.string.ai_novelo_summary) to false
}

/** The backup in a few words; true when it needs her. */
internal fun backupSummary(s: NoveloSettings, busy: String?): Pair<String, Boolean> = when {
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
        QuietIcon(NoveloIcons.Back, t(R.string.back), onBack, alpha = 0.8f)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = NoveloColors.Text, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A row of a block: the name (and a word under it) on the left, the control on the right. */
@Composable
internal fun Line(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = NoveloColors.TextMuted,
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

