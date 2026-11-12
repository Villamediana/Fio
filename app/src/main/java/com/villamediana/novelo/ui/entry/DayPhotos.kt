package com.villamediana.novelo.ui.entry

import com.villamediana.novelo.settings.t

import com.villamediana.novelo.R

import com.villamediana.novelo.ui.theme.NoveloColors

import com.villamediana.novelo.ui.components.NoveloIcons

import androidx.compose.ui.Alignment

import androidx.compose.material3.Icon

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.Box

import androidx.compose.ui.draw.blur

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
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.mind.DayDiary
import com.villamediana.novelo.mind.Photo
import com.villamediana.novelo.ui.components.Mosaic
import com.villamediana.novelo.ui.components.PhotoTile
import com.villamediana.novelo.ui.components.PhotoViewer
import com.villamediana.novelo.ui.components.bleed
import com.villamediana.novelo.ui.components.rememberAspects
import com.villamediana.novelo.ui.components.stitched
import java.io.File
import java.time.ZoneId

/**
 * The photos taken on the day of a diary, under its text (only on the day's diary — the newest of
 * that day — so a day's photos show once): a row that slides sideways, each photo in its own shape,
 * edge to edge. A tap opens them big.
 */
@Composable
internal fun DayPhotos(entry: Entry, side: Dp, modifier: Modifier = Modifier) {
    val lib = NoveloApplication.graph.library
    val day = remember(entry.createdAt) { DayDiary.bounds(entry.createdAt, ZoneId.systemDefault()) }
    val isDay by produceState(false, entry.id, day) { value = lib.isDayDiary(entry.id, day) }
    val all by remember(day) { lib.photos(day) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (!isDay) return
    val photos = remember(all) { all.filter { File(it.path).isFile } }
    if (photos.isEmpty()) return
    PhotoRow(photos, side, modifier.padding(top = 22.dp))
}

/** The photos an item holds (a task, a note: sent with what she said), under its text. */
@Composable
internal fun EntryPhotos(entry: Entry, side: Dp, modifier: Modifier = Modifier, hidden: Boolean = false, onReveal: () -> Unit = {}) {
    val lib = NoveloApplication.graph.library
    val photos by remember(entry.id) { lib.photosOf(entry.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (photos.isEmpty()) return
    PhotoRow(photos, side, modifier.padding(top = 22.dp), tag = "entry-photo", hidden = hidden, onReveal = onReveal)
}

/** The seam on a photo: light, like thread on fabric. */
private val StitchOnPhoto = androidx.compose.ui.graphics.Color(0xCCFBF6EC)

/** A sideways row of [photos] at [height], of different widths (see [Mosaic]); [side] = the page's own margin. */
@Composable
/**
 * [hidden]: something sensitive not shown yet — the photos are blurred (or, where blur isn't there,
 * covered), with a lock; a tap asks to see ([onReveal]) instead of opening them.
 */
internal fun PhotoRow(photos: List<Photo>, side: Dp, modifier: Modifier = Modifier, height: Dp = 210.dp, tag: String = "day-photo", hidden: Boolean = false, onReveal: () -> Unit = {}) {
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    val aspects = rememberAspects(photos, min = Mosaic.MIN, max = Mosaic.MAX) ?: return
    LazyRow(
        modifier.bleed(side).fillMaxWidth().testTag("$tag-row"),
        contentPadding = PaddingValues(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            // Different widths, even for photos all of one shape.
            val ratio = Mosaic.shape(i, aspects[p.id] ?: 1f)
            Box(
                Modifier
                    .height(height)
                    .width(height * ratio)
                    .clip(RoundedCornerShape(20.dp))
                    .stitched(RoundedCornerShape(20.dp), StitchOnPhoto)
                    .clickable { if (hidden) onReveal() else opened = i }
                    .testTag(if (hidden) "$tag-hidden-$i" else "$tag-$i"),
            ) {
                // Hidden: the photo itself, blurred (decoded tiny and stretched, which blurs it on any
                // Android; softer still where blur exists) — it is there, it can't be read.
                PhotoTile(
                    p.path,
                    turn = p.turn,
                    maxPx = if (hidden) 28 else 480,
                    modifier = Modifier.fillMaxSize().then(if (hidden && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier),
                )
            }
        }
    }
    opened?.let { start -> PhotoViewer(photos, start, onClose = { opened = null }) }
}
