package com.villamediana.novelo.ui.settings

import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
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
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.backup.Backup
import com.villamediana.novelo.backup.Backups
import com.villamediana.novelo.backup.Snapshot
import com.villamediana.novelo.ui.Nav
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.villamediana.novelo.ui.components.paper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.components.Segmented
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ajustes › Backup: only the folder. The backup goes by itself after every change (and once a day),
 * so there is nothing to do by hand; choosing a folder that already has a Novelo backup brings it
 * back (what the app had stays beside it, dated), and a new folder starts getting the backups.
 * Under the folder: when the last one went (or that it is going, or what went wrong).
 */
@Composable
fun BackupSection() {
    val graph = NoveloApplication.graph
    val backups = graph.backups
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val busy by backups.busy.collectAsStateWithLifecycle()
    var back by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            back = null
            // On the app's scope: leaving the screen never leaves a bringing-back halfway.
            graph.scope.launch {
                back = backups.chooseAndBringBack(uri)?.let { (kept, restored) ->
                    t(R.string.setup_backup_back, moment(kept.at), Backups.note(restored.entries, restored.photos, restored.sounds))
                }
            }
        }
    }
    val hasFolder = s.backupFolder.isNotBlank()
    fun choose() = pick.launch(s.backupFolder.takeIf { it.isNotBlank() }?.let(Uri::parse))
    val (status, problem) = when {
        !hasFolder -> t(R.string.backup_choose_where) to true
        busy == Backups.IMPORTING -> t(R.string.backup_importing) to false
        busy == Backups.SAVING -> t(R.string.backup_doing) to false
        back != null -> back!! to false
        s.backupFailed -> s.backupNote to true
        s.backupAt > 0 -> t(R.string.backup_last, moment(s.backupAt)) to false
        else -> t(R.string.backup_by_itself) to false
    }

    Group(
        Modifier,
        {
            Line(
                title = if (hasFolder) s.backupPlace.ifBlank { t(R.string.backup_folder_chosen) } else t(R.string.backup_folder),
                subtitle = status,
                subtitleColor = if (problem) (if (hasFolder) NoveloColors.Danger else NoveloColors.Accent) else NoveloColors.TextMuted,
                onClick = { if (busy == null) choose() },
            ) {
                if (busy != null) CircularProgressIndicator(Modifier.size(18.dp), color = NoveloColors.Accent, strokeWidth = 2.dp)
                else Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        },
    )
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
