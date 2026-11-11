package com.villamediana.fio.mind

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Text on the phone (ML Kit, offline) and a lighter JPEG for a model that sees. */
class MlKitPhotoReader(private val context: Context) : PhotoReader {
    override suspend fun text(file: File): String = withContext(Dispatchers.Default) {
        val bitmap = load(file, 2048) ?: return@withContext ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it.text.trim()) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    override suspend fun jpeg(file: File, side: Int): ByteArray = withContext(Dispatchers.Default) {
        val bitmap = load(file, side) ?: return@withContext file.readBytes()
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    override suspend fun jpeg(files: List<File>, side: Int): ByteArray {
        if (files.size == 1) return jpeg(files.first(), side)
        return withContext(Dispatchers.Default) {
            // A grid, in order: each photo whole in its square, a thin line between them.
            val cols = kotlin.math.ceil(kotlin.math.sqrt(files.size.toDouble())).toInt()
            val rows = (files.size + cols - 1) / cols
            val cell = side / cols
            val gap = 6
            val grid = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(grid).apply { drawColor(android.graphics.Color.WHITE) }
            files.forEachIndexed { i, f ->
                val b = load(f, cell) ?: return@forEachIndexed
                val box = cell - gap
                val scale = minOf(box.toFloat() / b.width, box.toFloat() / b.height)
                val w = (b.width * scale).toInt()
                val h = (b.height * scale).toInt()
                val x = (i % cols) * cell + (cell - w) / 2
                val y = (i / cols) * cell + (cell - h) / 2
                canvas.drawBitmap(b, null, android.graphics.Rect(x, y, x + w, y + h), null)
            }
            ByteArrayOutputStream().use { out ->
                grid.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    }

    private fun load(file: File, max: Int): Bitmap? = uprightBitmap(file, max)
}

/** A photo decoded at most [max] px on the long side, upright (its EXIF turn applied, and [turn] degrees more to the right); null if unreadable. */
fun uprightBitmap(file: File, max: Int, turn: Int = 0): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val degrees = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f) + turn
    val scale = (max.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
    if (degrees % 360f == 0f && scale == 1f) return raw
    val m = Matrix().apply { postRotate(degrees); postScale(scale, scale) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
