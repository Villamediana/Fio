package com.villamediana.fio.ui.settings

import com.villamediana.fio.settings.Lang
import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.FioApplication
import com.villamediana.fio.backup.Backup
import com.villamediana.fio.backup.Backups
import com.villamediana.fio.backup.Snapshot
import com.villamediana.fio.ui.Nav
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.villamediana.fio.ui.components.paper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.components.Group
import com.villamediana.fio.ui.components.IconAction
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.components.Segmented
import com.villamediana.fio.ui.sheets.FioSheet
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ajustes › Backup: the folder (chosen in the phone's picker), the last one (it goes by itself every
 * day; a backup now is one touch away), and the import back from that folder.
 */
@Composable
fun BackupScreen(nav: Nav) {
    val graph = FioApplication.graph
    val backups = graph.backups
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val busy by backups.busy.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var importAfterPick by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Job?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            choosing = backups.choose(uri)
            if (importAfterPick) importing = true
        }
        importAfterPick = false
    }
    val hasFolder = s.backupFolder.isNotBlank()
    fun choose() = pick.launch(s.backupFolder.takeIf { it.isNotBlank() }?.let(Uri::parse))

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding()) {
    PageTop(t(R.string.backup)) { nav.back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Spacer(Modifier.height(10.dp))
    Group(
        Modifier.padding(horizontal = 16.dp),
        {
            Line(
                title = t(R.string.backup_folder),
                subtitle = if (hasFolder) s.backupPlace.ifBlank { t(R.string.backup_folder_chosen) } else t(R.string.backup_choose_where),
                subtitleColor = if (hasFolder) FioColors.TextMuted else FioColors.Accent,
                onClick = { choose() },
            ) {
                Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        },
        if (!hasFolder) null else ({
            Line(
                title = t(R.string.backup_now),
                subtitle = when {
                    busy == Backups.SAVING -> t(R.string.backup_doing)
                    s.backupFailed -> s.backupNote
                    s.backupAt > 0 -> t(R.string.backup_last, moment(s.backupAt)) + (if (s.backupNote.isNotBlank()) " · " + s.backupNote else "")
                    else -> t(R.string.backup_by_itself)
                },
                subtitleColor = if (s.backupFailed && busy == null) FioColors.Danger else FioColors.TextMuted,
                onClick = { if (busy == null) backups.now() },
            ) {
                if (busy == Backups.SAVING) CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                else IconAction(FioIcons.Upload, t(R.string.backup_now_long), { if (busy == null) backups.now() }, primary = true, size = 40.dp)
            }
        }),
        {
            Line(
                title = t(R.string.backup_import),
                onClick = { if (hasFolder) importing = true else { importAfterPick = true; choose() } },
            ) {
                IconAction(FioIcons.Download, t(R.string.backup_import), { if (hasFolder) importing = true else { importAfterPick = true; choose() } }, size = 40.dp)
            }
        },
    )

    }
    }

    if (importing) ImportSheet(waitFor = choosing, onDismiss = { importing = false })
}

/** The backups in the folder (the newest first); one tapped, what it holds and the import. */
@Composable
private fun ImportSheet(waitFor: Job?, onDismiss: () -> Unit) {
    val graph = FioApplication.graph
    val backups = graph.backups
    val busy by backups.busy.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<Backup.Kept>?>(null) }
    var chosen by remember { mutableStateOf<Backup.Kept?>(null) }
    var holds by remember { mutableStateOf<Snapshot?>(null) }
    var done by remember { mutableStateOf<Backup.Restored?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        waitFor?.join()
        runCatching { backups.kept() }.onSuccess { list = it }.onFailure { problem = it.message; list = emptyList() }
    }
    LaunchedEffect(chosen) {
        holds = null
        val k = chosen ?: return@LaunchedEffect
        problem = null
        runCatching { backups.read(k.name) }.onSuccess { holds = it }.onFailure { problem = it.message }
    }

    FioSheet(onDismissRequest = { if (busy != Backups.IMPORTING) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(t(R.string.backup_import), style = MaterialTheme.typography.headlineSmall, color = FioColors.Text)
            Spacer(Modifier.height(14.dp))
            val k = chosen
            val finished = done
            when {
                finished != null -> {
                    Text(t(R.string.backup_restored, Backups.note(finished.entries, finished.photos, finished.sounds)), style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
                    Spacer(Modifier.height(18.dp))
                    IconAction(FioIcons.Check, t(R.string.ok), onDismiss, primary = true)
                }
                busy == Backups.IMPORTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                    Text(t(R.string.backup_importing), style = MaterialTheme.typography.bodyLarge, color = FioColors.TextMuted, modifier = Modifier.padding(start = 12.dp))
                }
                k != null -> {
                    Text(moment(k.at), style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
                    Text(
                        holds?.let { Backups.note(it.entries.size, it.photoCount, it.soundCount) } ?: t(R.string.reading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FioColors.TextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t(R.string.backup_replaces),
                        style = MaterialTheme.typography.bodySmall,
                        color = FioColors.TextMuted,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconAction(FioIcons.Back, t(R.string.back), { chosen = null })
                        if (holds != null) {
                            IconAction(FioIcons.Check, t(R.string.import_action), {
                                problem = null
                                // On the app's scope: closing the sheet never leaves an import halfway.
                                graph.scope.launch {
                                    backups.restore(k.name).onSuccess { done = it }.onFailure { problem = it.message }
                                }
                            }, primary = true)
                        }
                    }
                }
                list == null -> CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                list!!.isEmpty() && problem == null -> Text(t(R.string.backup_none), style = MaterialTheme.typography.bodyLarge, color = FioColors.TextMuted)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(list!!, key = { _, it -> it.name }) { i, item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = item }.padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(moment(item.at), style = MaterialTheme.typography.bodyLarge, color = FioColors.Text, modifier = Modifier.weight(1f))
                            if (i == 0) Text(t(R.string.newest), style = MaterialTheme.typography.bodySmall, color = FioColors.Accent, modifier = Modifier.padding(end = 6.dp))
                            Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            problem?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = FioColors.Danger)
            }
        }
    }
}

private val HOUR: DateTimeFormatter get() = DateTimeFormatter.ofPattern("HH:mm", Lang.locale)
private val THIS_YEAR: DateTimeFormatter get() = DateTimeFormatter.ofPattern(t(R.string.fmt_day_month_time), Lang.locale)
private val OTHER_YEAR: DateTimeFormatter get() = DateTimeFormatter.ofPattern(t(R.string.fmt_day_month_year_time), Lang.locale)

/** A moment as people say it: "hoje, 14:32", "ontem, 09:10", "7 de nov, 14:32". */
internal fun moment(ms: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    return when (at.toLocalDate()) {
        today -> t(R.string.moment_today, at.format(HOUR))
        today.minusDays(1) -> t(R.string.moment_yesterday, at.format(HOUR))
        else -> at.format(if (at.year == today.year) THIS_YEAR else OTHER_YEAR).replace(".", "")
    }
}
