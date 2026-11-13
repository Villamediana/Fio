package com.villamediana.novelo.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.data.KeptFile
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.entry.FileRow
import com.villamediana.novelo.ui.entry.openFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Arquivos: every file kept (but those inside something sensitive), the newest first, by the day
 * it came. Each says where it lives (its item, or the day's diary); a tap opens the file.
 */
@Composable
internal fun FilesTab(files: List<KeptFile>, empty: @Composable () -> Unit) {
    if (files.isEmpty()) {
        empty()
        return
    }
    val context = LocalContext.current
    var removing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<KeptFile?>(null) }
    removing?.let { f -> com.villamediana.novelo.ui.entry.RemoveFile(f) { removing = null } }
    val zone = ZoneId.systemDefault()
    val days = remember(files) {
        val today = LocalDate.now(zone)
        files.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .map { (d, list) -> Dates.day(d.atStartOfDay(zone).toInstant().toEpochMilli(), zone, today).replaceFirstChar { it.titlecase(Locale.getDefault()) } to list }
    }
    // The handle on the edge: the month of each line (a day's label with its day).
    val months = remember(days) { days.flatMap { (_, list) -> listOf(month(list.first().at)) + list.map { month(it.at) } } }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("files-list"), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            days.forEach { (label, list) ->
                item(key = "label-$label") { SectionLabel(label, Modifier.animateItem()) }
                itemsIndexed(list, key = { _, f -> f.id }) { _, f ->
                    Box(Modifier.animateItem().padding(bottom = 8.dp)) {
                        FileRow(f, onClick = { openFile(context, f) }, where = where(f), onLongClick = { removing = f })
                    }
                }
            }
        }
        FastScroll(state, labelAt = { months.getOrNull(it) })
    }
}

/** Where a file lives, in a few words: its item's title, or the day's diary. */
internal fun where(f: KeptFile): String = f.home?.title?.take(40) ?: t(R.string.file_in_diary)
