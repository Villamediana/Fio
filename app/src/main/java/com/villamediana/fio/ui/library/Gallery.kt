package com.villamediana.fio.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
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
import com.villamediana.fio.ui.components.PhotoTile
import com.villamediana.fio.ui.components.PhotoViewer
import com.villamediana.fio.ui.components.photoAspect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every photo kept, Pinterest-like: two columns, each photo in its own shape, the newest first.
 * A tap opens them big, side by side, from that one.
 */
@Composable
internal fun Gallery(photos: List<Photo>, empty: @Composable () -> Unit) {
    if (photos.isEmpty()) {
        empty()
        return
    }
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            // Its own shape, kept reasonable (a panorama or a long screenshot doesn't take the column).
            val ratio = remember(p.path) { photoAspect(p.path).coerceIn(0.56f, 1.8f) }
            Box(Modifier.animateItem()) {
                PhotoTile(
                    p.path,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { opened = i }
                        .testTag("gallery-photo-$i"),
                )
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
