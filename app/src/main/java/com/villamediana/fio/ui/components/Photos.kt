package com.villamediana.fio.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.villamediana.fio.mind.Photo
import com.villamediana.fio.mind.uprightBitmap
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Photos decoded once for a size and kept a while (lists scroll back and forth over the same ones). */
private object PhotoCache : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(48 * 1024 * 1024)) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private val ratios = HashMap<String, Float>()

/** Width over height of the photo as it stands (its EXIF turn counted); 1 when it can't be read. */
fun photoAspect(path: String): Float = synchronized(ratios) { ratios[path] } ?: run {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val turned = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in
            setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
    }.getOrDefault(false)
    val w = if (turned) bounds.outHeight else bounds.outWidth
    val h = if (turned) bounds.outWidth else bounds.outHeight
    val r = if (w > 0 && h > 0) w.toFloat() / h else 1f
    synchronized(ratios) { ratios[path] = r }
    r
}

/** The photo at [path], at most [maxPx] on its long side, upright; null while it loads (or if it can't). */
@Composable
fun rememberPhoto(path: String, maxPx: Int): Bitmap? {
    val key = "$path@$maxPx"
    val bitmap by produceState(PhotoCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { runCatching { uprightBitmap(File(path), maxPx) }.getOrNull() }?.also { PhotoCache.put(key, it) }
        }
    }
    return bitmap
}

/** A photo filling its box (cropped), coming in softly once it is decoded. */
@Composable
fun PhotoTile(path: String, modifier: Modifier = Modifier, maxPx: Int = 480) {
    val bitmap = rememberPhoto(path, maxPx)
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(260), label = "photo")
    Box(modifier.background(FioColors.Surface2)) {
        bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(shown)) }
    }
}

/** Lets a row reach past its column's side padding ([by] on each side): a carousel edge to edge. */
fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wide = constraints.copy(
        minWidth = (constraints.minWidth + 2 * extra).coerceAtLeast(0),
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
    )
    val placeable = measurable.measure(wide)
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

private val WHEN = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM · HH:mm", PT)

/**
 * Photos big, on black, one beside the other: swipe between them; under each, what it shows (in
 * her words) and when it was taken. × or back closes.
 */
@Composable
fun PhotoViewer(photos: List<Photo>, start: Int, onClose: () -> Unit) {
    if (photos.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "photo-viewer" }) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { photos[it].id }) { page ->
                val p = photos[page]
                val bitmap = rememberPhoto(p.path, FULL_PX)
                Box(Modifier.fillMaxSize()) {
                    bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                            .navigationBarsPadding()
                            .padding(start = 22.dp, end = 22.dp, top = 40.dp, bottom = 22.dp),
                    ) {
                        p.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            Instant.ofEpochMilli(p.takenAt).atZone(ZoneId.systemDefault()).format(WHEN).replaceFirstChar { it.titlecase(PT) },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // White on the black, whatever the theme.
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Fechar", onClick = onClose).semantics { contentDescription = "Fechar" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FioIcons.Close, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                }
                if (photos.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${photos.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

private const val FULL_PX = 2048
