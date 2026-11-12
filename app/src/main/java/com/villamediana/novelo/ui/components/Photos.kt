package com.villamediana.novelo.ui.components

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalContext
import com.villamediana.novelo.data.ToGallery
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
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
import com.villamediana.novelo.mind.Photo
import com.villamediana.novelo.mind.uprightBitmap
import com.villamediana.novelo.ui.theme.NoveloColors
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
    Box(modifier.background(NoveloColors.Surface2)) {
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

private val WHEN: DateTimeFormatter get() = DateTimeFormatter.ofPattern(t(R.string.fmt_photo_when), PT)

/**
 * Photos big, on black, one beside the other: swipe between them; under each, when it was taken
 * and a light menu to turn it, save it to the phone's gallery or take it out of Novelo (what it
 * shows, in the model's words, is only for the search — never on screen). × or back closes.
 */
@Composable
fun PhotoViewer(photos: List<Photo>, start: Int, onClose: () -> Unit) {
    if (photos.isEmpty()) {
        // The last one taken out: nothing left to see.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val graph = com.villamediana.novelo.NoveloApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "photo-viewer" }) {
            // A photo zoomed in keeps the finger to itself (moving around in it); not zoomed, a swipe is the next one.
            var zoomedIn by remember { mutableStateOf<Long?>(null) }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { photos[it].id }, userScrollEnabled = zoomedIn == null) { page ->
                val p = photos[page]
                // Pinched (or double-tapped) in: how much, and where in it. Back to whole on another page.
                var zoom by remember(p.id) { mutableFloatStateOf(1f) }
                var pan by remember(p.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                LaunchedEffect(zoom > 1f) { zoomedIn = if (zoom > 1f) p.id else zoomedIn.takeIf { it != p.id } }
                LaunchedEffect(pager.currentPage) { if (pager.currentPage != page) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero } }
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
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(p.id) {
                                    // One place for all of it, so nothing else takes the touches: two
                                    // fingers pinch; zoomed in, one finger moves around; two quick taps
                                    // zoom in where she tapped, or back to whole. Not zoomed, one finger
                                    // moving is left to the pager (the next photo).
                                    var lastTap = 0L
                                    var lastAt = androidx.compose.ui.geometry.Offset.Zero
                                    val slop = viewConfiguration.touchSlop
                                    val window = viewConfiguration.doubleTapTimeoutMillis
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var many = false
                                        var moved = false
                                        var upAt = down.uptimeMillis
                                        do {
                                            val event = awaitPointerEvent()
                                            val fingers = event.changes.count { it.pressed }
                                            if (fingers >= 2) many = true
                                            event.changes.firstOrNull { it.id == down.id }?.let { c ->
                                                if ((c.position - down.position).getDistance() > slop) moved = true
                                                if (!c.pressed) upAt = c.uptimeMillis
                                            }
                                            if (fingers >= 2 || zoom > 1f) {
                                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                                pan = if (zoom <= 1f) androidx.compose.ui.geometry.Offset.Zero
                                                else clampPan(pan + event.calculatePan(), zoom, size.width.toFloat(), size.height.toFloat())
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                        // A tap (one finger, still): the second one close in time and place is a double tap.
                                        if (many || moved) {
                                            lastTap = 0L
                                        } else if (lastTap != 0L && down.uptimeMillis - lastTap <= window && (down.position - lastAt).getDistance() <= slop * 4) {
                                            lastTap = 0L
                                            if (zoom > 1f) {
                                                zoom = 1f
                                                pan = androidx.compose.ui.geometry.Offset.Zero
                                            } else {
                                                zoom = DOUBLE_TAP_ZOOM
                                                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                                pan = clampPan((center - down.position) * (DOUBLE_TAP_ZOOM - 1f), zoom, size.width.toFloat(), size.height.toFloat())
                                            }
                                        } else {
                                            lastTap = upAt
                                            lastAt = down.position
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    rotationZ = shown
                                    scaleX = scale * zoom
                                    scaleY = scale * zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                },
                        )
                    }
                    // Under the photo: when it was taken, and a light menu to turn it, save it, take it out.
                    var asking by remember(p.id) { mutableStateOf(false) }
                    var note by remember(p.id) { mutableStateOf<String?>(null) }
                    LaunchedEffect(asking) { if (asking) { delay(3_000); asking = false } }
                    LaunchedEffect(note) { if (note != null) { delay(2_400); note = null } }
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
                            note ?: Instant.ofEpochMilli(p.takenAt).atZone(ZoneId.systemDefault()).format(WHEN).replaceFirstChar { it.titlecase(PT) },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = if (note != null) 0.92f else 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(4.dp).animateContentSize(tween(220)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ViewerAction(NoveloIcons.Rotate, t(R.string.photo_rotate)) {
                                asking = false
                                angle += 90f
                                graph.scope.launch { graph.library.turnPhoto(p.id) }
                            }
                            ViewerAction(NoveloIcons.Download, t(R.string.photo_download)) {
                                asking = false
                                // As it is seen here: the turn on screen may be ahead of the one saved.
                                val turn = angle.toInt() % 360
                                scope.launch {
                                    note = if (ToGallery.save(context, p.path, p.takenAt, turn)) t(R.string.photo_saved) else t(R.string.photo_not_saved)
                                }
                            }
                            if (!asking) {
                                ViewerAction(NoveloIcons.Trash, t(R.string.photo_remove)) { asking = true }
                            } else {
                                // Asked once: one more tap takes it out of Novelo.
                                Row(
                                    Modifier
                                        .height(44.dp)
                                        .clip(CircleShape)
                                        .background(REMOVE.copy(alpha = 0.18f))
                                        .clickable(onClickLabel = t(R.string.photo_remove_long)) {
                                            asking = false
                                            if (photos.size == 1) onClose()
                                            graph.scope.launch { graph.library.removePhoto(p.id) }
                                        }
                                        .semantics { contentDescription = t(R.string.photo_remove_long) }
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(NoveloIcons.Trash, null, tint = REMOVE, modifier = Modifier.size(20.dp))
                                    Text(t(R.string.photo_remove), style = MaterialTheme.typography.labelLarge, color = REMOVE, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // White on the black, whatever the theme.
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = t(R.string.close), onClick = onClose).semantics { contentDescription = t(R.string.close) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NoveloIcons.Close, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
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

/** One round button of the viewer's menu, white on the black. */
@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(22.dp))
    }
}

/** Taking a photo out, on the black whatever the theme. */
private val REMOVE = Color(0xFFFF8A80)

private const val FULL_PX = 2048

/** As far in as a pinch goes, and where two taps go. */
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** [pan] kept so the zoomed photo never leaves the screen's edges. */
private fun clampPan(pan: androidx.compose.ui.geometry.Offset, zoom: Float, w: Float, h: Float): androidx.compose.ui.geometry.Offset {
    val maxX = w * (zoom - 1f) / 2f
    val maxY = h * (zoom - 1f) / 2f
    return androidx.compose.ui.geometry.Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
