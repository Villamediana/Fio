package com.villamediana.novelo.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.importer.DiaryImport
import com.villamediana.novelo.importer.Found
import com.villamediana.novelo.importer.FoundNote
import com.villamediana.novelo.importer.FoundPhoto
import com.villamediana.novelo.importer.Kept
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.settings.tn
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.PhotoTile
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "Importar diário" line of Ajustes › Backup: picks the file, and while an import goes (or waits
 * for her), says how it is and opens its sheet again.
 */
@Composable
internal fun DiaryImportLine(onSeeDiary: () -> Unit) {
    val importer = NoveloApplication.graph.diaryImport
    val state by importer.state.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "diário"
            importer.open(name) { resolver.openInputStream(uri) }
            showing = true
        }
    }
    val busy = state is DiaryImport.State.Opening || state is DiaryImport.State.Recognizing || state is DiaryImport.State.Saving
    val (subtitle, attention) = when (val s = state) {
        DiaryImport.State.Idle -> t(R.string.import_line) to false
        is DiaryImport.State.Ready -> t(R.string.import_status_ready) to true
        is DiaryImport.State.Failed -> t(R.string.import_status_failed) to true
        is DiaryImport.State.Done -> t(R.string.import_status_done, summary(s.kept)) to false
        else -> t(R.string.import_status_busy) to false
    }
    Line(
        title = t(R.string.import_title),
        subtitle = subtitle,
        subtitleColor = if (attention) NoveloColors.Accent else NoveloColors.TextMuted,
        onClick = {
            if (state == DiaryImport.State.Idle || state is DiaryImport.State.Done) pick.launch(arrayOf("*/*")) else showing = true
        },
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = NoveloColors.Accent, strokeWidth = 2.dp)
        else Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp))
    }

    if (showing && state != DiaryImport.State.Idle) {
        NoveloSheet(onDismissRequest = { showing = false }) {
            DiaryImportBody(
                state = state,
                onAgain = importer::again,
                onConfirm = importer::confirm,
                onClose = { showing = false; importer.close() },
                onSeeDiary = { showing = false; importer.close(); onSeeDiary() },
            )
        }
    }
}

/** What the sheet holds, by where the import is (apart from the sheet, so the tests can draw it). */
@Composable
internal fun DiaryImportBody(
    state: DiaryImport.State,
    onAgain: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onSeeDiary: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it.javaClass }, label = "import") { s ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            when (s) {
                DiaryImport.State.Idle -> {}
                is DiaryImport.State.Opening -> Working(t(R.string.import_opening), s.name)
                is DiaryImport.State.Recognizing -> Working(t(R.string.import_recognizing), s.name, t(R.string.import_recognizing_line))
                is DiaryImport.State.Saving -> Working(
                    if (s.backingUp) t(R.string.import_backing) else t(R.string.import_saving),
                    listOfNotNull(
                        s.count.takeIf { it > 0 }?.let { tn(R.plurals.import_entries, it) },
                        s.notes.takeIf { it > 0 }?.let { tn(R.plurals.import_notes, it) },
                        s.photos.takeIf { it > 0 }?.let { tn(R.plurals.import_photos, it) },
                    ).joinToString(" · "),
                )
                is DiaryImport.State.Ready -> Recognized(s, zone, onAgain, onConfirm, onClose)
                is DiaryImport.State.Failed -> {
                    Head(t(R.string.import_title), s.name)
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Danger, modifier = Modifier.padding(top = 4.dp))
                    Buttons(
                        quiet = t(R.string.close) to onClose,
                        main = if (s.canRetry) t(R.string.try_again) to onAgain else null,
                    )
                }
                is DiaryImport.State.Done -> {
                    Head(t(R.string.import_done), if (s.kept.from > 0) range(s.kept.from, s.kept.to, zone) else null)
                    val lines = listOfNotNull(
                        s.kept.added.takeIf { it > 0 }?.let { tn(R.plurals.import_new, it) },
                        s.kept.joined.takeIf { it > 0 }?.let { tn(R.plurals.import_joined, it) },
                        s.kept.notes.takeIf { it > 0 }?.let { tn(R.plurals.import_notes_new, it) },
                        s.kept.photos.takeIf { it > 0 }?.let { tn(R.plurals.import_photos_new, it) },
                    )
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text, modifier = Modifier.padding(top = 4.dp)) }
                    val (said, bad) = when (s.backup) {
                        true -> t(R.string.import_backup_done) to false
                        false -> t(R.string.import_backup_failed, s.problem.orEmpty()) to true
                        null -> t(R.string.import_backup_none) to true
                    }
                    Text(said, style = MaterialTheme.typography.bodySmall, color = if (bad) NoveloColors.Accent else NoveloColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
                    Buttons(quiet = t(R.string.close) to onClose, main = t(R.string.import_see) to onSeeDiary)
                }
            }
        }
    }
}

/** The pattern the model saw, what came out of the whole file, and a few entries, photos and notes as they will be. */
@Composable
private fun Recognized(s: DiaryImport.State.Ready, zone: ZoneId, onAgain: () -> Unit, onConfirm: () -> Unit, onClose: () -> Unit) {
    val r = s.reading
    val found = r.found
    Head(s.recipe.app.ifBlank { t(R.string.import_title) }, s.name)
    if (s.recipe.about.isNotBlank()) {
        Text(s.recipe.about, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.padding(top = 2.dp))
    }
    // Not the pattern: the model sees what its recipe gave and writes another.
    if (!r.isEmpty) {
        Text(
            t(R.string.import_again),
            style = MaterialTheme.typography.labelLarge,
            color = NoveloColors.Accent,
            modifier = Modifier.padding(top = 4.dp).clip(CircleShape).clickable(onClick = onAgain).padding(vertical = 8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    if (r.isEmpty) {
        Text(t(R.string.import_none), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Accent)
    } else {
        val counts = listOfNotNull(
            found.takeIf { it.isNotEmpty() }?.let { tn(R.plurals.import_entries, it.size) + " · " + tn(R.plurals.import_days, r.days(zone)) },
            listOfNotNull(
                r.notes.size.takeIf { it > 0 }?.let { tn(R.plurals.import_notes, it) },
                r.photos.size.takeIf { it > 0 }?.let { tn(R.plurals.import_photos, it) },
            ).takeIf { it.isNotEmpty() }?.joinToString(" · "),
        )
        counts.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text) }
        val dated = found.map { it.at } + r.photos.map { it.at }
        if (dated.isNotEmpty()) Text(range(dated.min(), dated.max(), zone), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
    }
    val left = listOfNotNull(
        r.undated.takeIf { it > 0 }?.let { tn(R.plurals.import_undated, it) },
        r.unread.takeIf { it > 0 }?.let { tn(R.plurals.import_unread, it) },
        r.undatedPhotos.takeIf { it > 0 }?.let { tn(R.plurals.import_photos_undated, it) },
    )
    if (left.isNotEmpty()) Text(left.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = NoveloColors.Accent, modifier = Modifier.padding(top = 2.dp))

    if (!r.isEmpty) {
        Text(t(R.string.import_preview), style = NoveloText.Section, color = NoveloColors.TextMuted, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (found.isNotEmpty()) listOf(0, found.size / 2, found.size - 1).distinct().forEach { Page(found[it], zone) }
            if (r.photos.isNotEmpty()) Photos(r.photos)
            r.notes.take(2).forEach { NotePage(it) }
        }
    }
    Buttons(
        quiet = t(R.string.cancel) to onClose,
        main = if (!r.isEmpty) t(R.string.import_go) to onConfirm else t(R.string.import_again) to onAgain,
    )
}

/** A few of the photos, spread over the whole set, square; the last says how many more there are. */
@Composable
private fun Photos(photos: List<FoundPhoto>) {
    val n = minOf(photos.size, PHOTOS_SHOWN)
    val shown = (0 until n).map { photos[it * (photos.size - 1) / maxOf(n - 1, 1)] }.distinct()
    val more = photos.size - shown.size
    Row(Modifier.fillMaxWidth().testTag("import-photos"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEachIndexed { i, p ->
            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
                PhotoTile(p.file.path, Modifier.matchParentSize(), maxPx = 240)
                if (i == shown.lastIndex && more > 0) {
                    Box(Modifier.matchParentSize().background(NoveloColors.Text.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Text("+$more", style = MaterialTheme.typography.titleMedium, color = NoveloColors.OnAccent)
                    }
                }
            }
        }
        repeat(PHOTOS_SHOWN - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

private const val PHOTOS_SHOWN = 5

/** A note as it will be: "Nota" by hand, its title, the start of its text. */
@Composable
private fun NotePage(n: FoundNote) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NoveloColors.Surface2).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(t(R.string.import_note), style = NoveloText.Hand, color = NoveloColors.TextMuted)
        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** What the last import left, in a few words: the days, else the notes, else the photos. */
private fun summary(k: Kept): String = when {
    k.days > 0 -> tn(R.plurals.import_days, k.days)
    k.notes > 0 -> tn(R.plurals.import_notes, k.notes)
    else -> tn(R.plurals.import_photos, k.photos)
}

/** One entry as the diary will show it: the day by hand, the title, the start of the text in the diary's serif. */
@Composable
private fun Page(e: Found, zone: ZoneId) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NoveloColors.Surface2).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(day(e.at, zone), style = NoveloText.Hand, color = NoveloColors.TextMuted)
        if (e.title.isNotBlank()) Text(e.title, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        if (e.body.isNotBlank()) Text(e.body, style = NoveloText.Diary, color = NoveloColors.Text.copy(alpha = 0.82f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Head(title: String, line: String?) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text)
    if (!line.isNullOrBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
}

/** Something going: what, the file, and a line on what happens. */
@Composable
private fun Working(what: String, name: String, line: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 26.dp), verticalAlignment = Alignment.Top) {
        CircularProgressIndicator(Modifier.padding(top = 4.dp).size(22.dp), color = NoveloColors.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(what, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
            Text(name, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The ways out, on the right: the quiet ones as words, the main one in the accent. */
@Composable
private fun Buttons(quiet: Pair<String, () -> Unit>, main: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).clickable(onClick = quiet.second).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(quiet.first, style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
        }
        if (main != null) {
            Box(Modifier.padding(start = 4.dp).clip(CircleShape).background(NoveloColors.Accent).clickable(onClick = main.second).padding(horizontal = 20.dp, vertical = 12.dp).testTag("import-main")) {
                Text(main.first, style = MaterialTheme.typography.labelLarge, color = NoveloColors.OnAccent)
            }
        }
    }
}

private fun day(ms: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(t(R.string.fmt_weekday_day_month_year), Lang.locale)).replaceFirstChar { it.titlecase(Lang.locale) }

private fun range(from: Long, to: Long, zone: ZoneId): String {
    val f = DateTimeFormatter.ofPattern(t(R.string.fmt_day_month_year), Lang.locale)
    val a = Instant.ofEpochMilli(from).atZone(zone).format(f).replace(".", "")
    val b = Instant.ofEpochMilli(to).atZone(zone).format(f).replace(".", "")
    return if (a == b) a else t(R.string.import_range, a, b)
}
