package com.villamediana.fio.ui.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.FioApplication
import com.villamediana.fio.data.Entry
import com.villamediana.fio.mind.DayDiary
import com.villamediana.fio.mind.Photo
import com.villamediana.fio.ui.components.PhotoTile
import com.villamediana.fio.ui.components.PhotoViewer
import com.villamediana.fio.ui.components.bleed
import com.villamediana.fio.ui.components.photoAspect
import java.io.File
import java.time.ZoneId

/**
 * The photos taken on the day of a diary, under its text (only on the day's diary — the newest of
 * that day — so a day's photos show once): a row that slides sideways, each photo in its own shape,
 * edge to edge. A tap opens them big.
 */
@Composable
internal fun DayPhotos(entry: Entry, side: Dp, modifier: Modifier = Modifier) {
    val lib = FioApplication.graph.library
    val day = remember(entry.createdAt) { DayDiary.bounds(entry.createdAt, ZoneId.systemDefault()) }
    val isDay by produceState(false, entry.id, day) { value = lib.isDayDiary(entry.id, day) }
    val all by remember(day) { lib.photos(day) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (!isDay) return
    val photos = remember(all) { all.filter { File(it.path).isFile } }
    if (photos.isEmpty()) return
    PhotoRow(photos, side, modifier.padding(top = 22.dp))
}

/** A sideways row of [photos] at [height], each as wide as its shape asks; [side] = the page's own margin. */
@Composable
internal fun PhotoRow(photos: List<Photo>, side: Dp, modifier: Modifier = Modifier, height: Dp = 210.dp, tag: String = "day-photo") {
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    LazyRow(
        modifier.bleed(side).fillMaxWidth().testTag("$tag-row"),
        contentPadding = PaddingValues(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            val ratio = remember(p.path) { photoAspect(p.path).coerceIn(0.62f, 1.6f) }
            PhotoTile(
                p.path,
                Modifier
                    .height(height)
                    .width(height * ratio)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { opened = i }
                    .testTag("$tag-$i"),
            )
        }
    }
    opened?.let { start -> PhotoViewer(photos, start, onClose = { opened = null }) }
}
