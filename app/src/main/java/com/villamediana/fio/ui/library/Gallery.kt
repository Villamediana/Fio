package com.villamediana.fio.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.mind.Photo
import com.villamediana.fio.ui.components.Mosaic
import com.villamediana.fio.ui.components.PhotoTile
import com.villamediana.fio.ui.components.PhotoViewer
import com.villamediana.fio.ui.components.rememberAspects
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every photo kept as a mosaic: rows edge to edge, the photos of a row of different widths
 * (see [Mosaic]), the newest first. A tap opens them big, side by side, from that one.
 */
@Composable
internal fun Gallery(photos: List<Photo>, empty: @Composable () -> Unit) {
    if (photos.isEmpty()) {
        empty()
        return
    }
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    // Each photo's own shape, read off the main thread; the mosaic lays out once it knows them (no jumping tiles).
    val aspects = rememberAspects(photos, min = Mosaic.MIN, max = Mosaic.MAX) ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = 16.dp
        val gap = 8.dp
        val width = maxWidth - side * 2
        val rows = remember(photos, aspects, width) {
            val shapes = photos.mapIndexed { i, p -> Mosaic.shape(i, aspects[p.id] ?: 1f) }
            Mosaic.rows(shapes, width.value, gap.value, target = 220f).map { r -> r to r.items.map { shapes[it] } }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 6.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(rows, key = { (r, _) -> photos[r.items.first()].id }) { (row, shapes) ->
                Row(
                    Modifier.animateItem().then(if (row.full) Modifier.fillMaxWidth() else Modifier).height(row.height.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.items.forEachIndexed { k, i ->
                        val p = photos[i]
                        PhotoTile(
                            p.path,
                            (if (row.full) Modifier.weight(shapes[k]) else Modifier.width((row.height * shapes[k]).dp))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { opened = i }
                                .testTag("gallery-photo-$i"),
                        )
                    }
                }
            }
        }
    }
    opened?.let { start -> PhotoViewer(photos, start, onClose = { opened = null }) }
}

/** Which photos go on which diary card: a day's photos on that day's diary (the newest of the day), once. */
internal class DayPhotosIndex private constructor(private val byDiary: Map<Long, List<Photo>>) {
    fun of(diaryId: Long): List<Photo> = byDiary[diaryId].orEmpty()

    companion object {
        val EMPTY = DayPhotosIndex(emptyMap())

        fun of(entries: List<Entry>, photos: List<Photo>, zone: ZoneId): DayPhotosIndex {
            if (photos.isEmpty()) return EMPTY
            fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val byDay = photos.groupBy { day(it.takenAt) }.mapValues { (_, list) -> list.sortedBy { it.takenAt } }
            val diaryOfDay = entries.filter { it.kindEnum == Kind.DIARY }
                .groupBy { day(it.createdAt) }
                .mapValues { (_, list) -> list.maxWith(compareBy<Entry>({ it.createdAt }, { it.id })).id }
            return DayPhotosIndex(diaryOfDay.mapNotNull { (d, id) -> byDay[d]?.let { id to it } }.toMap())
        }
    }
}
