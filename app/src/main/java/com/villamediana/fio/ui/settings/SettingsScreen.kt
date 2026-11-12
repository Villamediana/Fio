package com.villamediana.fio.ui.settings

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
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.components.Group
import com.villamediana.fio.ui.components.NoveloMark
import com.villamediana.fio.ui.components.paper
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioAccents
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.update.UpdateRow

/**
 * Ajustes: three doors and what the app is. Each door says in a few words how things are (and
 * asks, in the accent, when something is missing); the details live behind it.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    val graph = FioApplication.graph
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()
    val busy by graph.backups.busy.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding()) {
        PageTop("Ajustes") { nav.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            val ai = intelligenceSummary(s, local, graph.localModel.spec.name)
            val backup = backupSummary(s, busy)
            Group(
                Modifier,
                { Door("Inteligência", ai.first, ai.second) { nav.go(Screen.Intelligence) } },
                {
                    Door(
                        "Tema",
                        when (s.theme) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Escuro" } + " · " + FioAccents.byId(s.accent).name.lowercase(),
                    ) { nav.go(Screen.Theme) }
                },
                { Door("Backup", backup.first, backup.second) { nav.go(Screen.Backup) } },
            )

            Spacer(Modifier.height(26.dp))
            val updateLine: (@Composable () -> Unit)? = if (graph.updates.enabled) ({ UpdateRow() }) else null
            Group(
                Modifier,
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        NoveloMark(34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Novelo", style = MaterialTheme.typography.titleLarge, color = FioColors.Text)
                            Text("versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted)
                        }
                    }
                },
                updateLine,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Where hearing and thinking happen, in a few words; true when something is missing. */
internal fun intelligenceSummary(s: FioSettings, local: LocalModel.State, localName: String): Pair<String, Boolean> {
    val phone = s.hear == Source.LOCAL || s.think == Source.LOCAL
    val api = s.hear == Source.API || s.think == Source.API
    return when {
        phone && !(LocalEngine.available && local is LocalModel.State.Ready) -> "$localName · " + localShort(local) to true
        api && !s.apiReady -> "falta o endereço ou a chave da API" to true
        (s.hear == Source.API && s.hearModel.isBlank()) || (s.think == Source.API && s.thinkModel.isBlank()) -> "falta escolher o modelo" to true
        phone && api -> (if (s.hear == Source.LOCAL) "ouve no celular · pensa com ${s.thinkModel}" else "ouve com ${s.hearModel} · pensa no celular") to false
        phone -> "no celular · funciona sem internet" to false
        else -> s.thinkModel to false
    }
}

/** The backup in a few words; true when it needs her. */
internal fun backupSummary(s: FioSettings, busy: String?): Pair<String, Boolean> = when {
    s.backupFolder.isBlank() -> "escolher onde guardar" to true
    busy == Backups.SAVING -> "fazendo…" to false
    s.backupFailed -> s.backupNote to true
    s.backupAt > 0 -> "todo dia · último " + moment(s.backupAt) to false
    else -> "todo dia" to false
}

/** A way into a page: its name, how things are under it, and the chevron. */
@Composable
private fun Door(title: String, subtitle: String, attention: Boolean = false, onClick: () -> Unit) {
    Line(title = title, subtitle = subtitle, subtitleColor = if (attention) FioColors.Accent else FioColors.TextMuted, onClick = onClick) {
        Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.size(18.dp))
    }
}

/** The top of a page in Ajustes: back, and its name. */
@Composable
internal fun PageTop(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietIcon(FioIcons.Back, "Voltar", onBack, alpha = 0.8f)
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

