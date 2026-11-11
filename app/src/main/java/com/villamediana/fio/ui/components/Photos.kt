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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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

/** The shapes of [photos] (id → width over height, kept within [min]..[max]), read off the main thread; null until read. */
@Composable
fun rememberAspects(photos: List<Photo>, min: Float, max: Float): Map<Long, Float>? {
    val aspects by produceState<Map<Long, Float>?>(null, photos) {
        value = withContext(Dispatchers.IO) {
            photos.associate { p -> p.id to photoAspect(p.path).let { r -> if (p.turn % 180 == 90) 1f / r else r }.coerceIn(min, max) }
        }
    }
    return aspects
}

/**
 * The photo at [path], at most [maxPx] on its long side, upright ([turn] more to the right); null
 * while it loads (or if it can't). Turned, it keeps showing as it was until the turned one is ready.
 */
@Composable
fun rememberPhoto(path: String, maxPx: Int, turn: Int = 0): Bitmap? {
    val key = "$path@$maxPx@$turn"
    val bitmap = remember(path) { mutableStateOf(PhotoCache.get(key)) }
    LaunchedEffect(key) {
        val ready = PhotoCache.get(key)
            ?: withContext(Dispatchers.IO) { runCatching { uprightBitmap(File(path), maxPx, turn) }.getOrNull() }?.also { PhotoCache.put(key, it) }
        if (ready != null) bitmap.value = ready
    }
    return bitmap.value
}

/** A photo filling its box (cropped), coming in softly once it is decoded. */
@Composable
fun PhotoTile(path: String, modifier: Modifier = Modifier, maxPx: Int = 480, turn: Int = 0) {
    val bitmap = rememberPhoto(path, maxPx, turn)
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
 * Photos big, on black, one beside the other: swipe between them; under each, when it was taken
 * and a light menu to turn it (what it shows, in the model's words, is only for the search —
 * never on screen). × or back closes.
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
                // Drawn as it stands in the file and turned on screen, so a turn moves smoothly
                // (and forward: 270 → 360, never back); the turn is kept with the photo.
                val bitmap = rememberPhoto(p.path, FULL_PX)
                var angle by remember(p.id) { mutableFloatStateOf(p.turn.toFloat()) }
                val shown by animateFloatAsState(angle, tween(320), label = "turn")
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    bitmap?.let { b ->
                        val boxW = constraints.maxWidth.toFloat()
                        val boxH = constraints.maxHeight.toFloat()
                        val straight = min(boxW / b.width, boxH / b.height)
                        val sideways = min(boxW / b.height, boxH / b.width)
                        // Lying on its side it fits the screen another way: the size follows the turn.
                        val t = abs(sin(Math.toRadians(shown.toDouble()))).toFloat()
                        val scale = 1f + (sideways / straight - 1f) * t
                        Image(
                            b.asImageBitmap(),
                            null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = shown; scaleX = scale; scaleY = scale },
                        )
                    }
                    // Under the photo: when it was taken, and a light menu to turn it.
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                            .navigationBarsPadding()
                            .padding(top = 40.dp, bottom = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            Instant.ofEpochMilli(p.takenAt).atZone(ZoneId.systemDefault()).format(WHEN).replaceFirstChar { it.titlecase(PT) },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = "Girar") {
                                        angle += 90f
                                        val lib = com.villamediana.fio.FioApplication.graph
                                        lib.scope.launch { lib.library.turnPhoto(p.id) }
                                    }
                                    .semantics { contentDescription = "Girar" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(FioIcons.Rotate, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(22.dp))
                            }
                        }
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
