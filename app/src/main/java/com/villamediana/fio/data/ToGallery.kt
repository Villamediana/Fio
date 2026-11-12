package com.villamediana.fio.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A photo kept in Novelo, copied to the phone's gallery (Pictures/Novelo) when she wants it there too:
 * as she sees it in Novelo (her turn counted) and on the day it was taken.
 */
object ToGallery {
    val FOLDER = Environment.DIRECTORY_PICTURES + "/Novelo"
    private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

    /** True when the copy is in the gallery. */
    suspend fun save(context: Context, path: String, takenAt: Long, turn: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean = withContext(Dispatchers.IO) {
        val src = File(path).takeIf { it.isFile } ?: return@withContext false
        val ext = src.extension.lowercase().ifBlank { "jpg" }
        val copy = turned(src, turn, File(context.cacheDir, "to-gallery.$ext"))
        val resolver = context.contentResolver
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Novelo " + Instant.ofEpochMilli(takenAt).atZone(zone).format(NAME) + ".$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime(ext))
                put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
            runCatching {
                (resolver.openOutputStream(uri) ?: error("sem saída")).use { out -> copy.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }.onFailure { resolver.delete(uri, null, null) }.isSuccess
        } catch (e: Exception) {
            false
        } finally {
            if (copy != src) copy.delete()
        }
    }

    /**
     * [src] itself when not turned; otherwise a copy at [out] with the turn added to its EXIF
     * orientation (the pixels as they are). [src] when the copy's EXIF can't be written (a HEIC).
     */
    fun turned(src: File, turn: Int, out: File): File {
        if (turn % 360 == 0) return src
        return runCatching {
            src.copyTo(out, overwrite = true)
            ExifInterface(out.path).apply {
                // A photo with no orientation (WhatsApp, a screenshot) is upright: rotate() only turns a known one.
                if (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) == ExifInterface.ORIENTATION_UNDEFINED) {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                }
                rotate(turn)
                saveAttributes()
            }
            out
        }.getOrElse {
            out.delete()
            src
        }
    }

    private fun mime(ext: String) = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }
}
