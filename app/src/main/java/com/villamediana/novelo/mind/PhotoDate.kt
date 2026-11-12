package com.villamediana.novelo.mind

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** When a photo was taken, as the camera wrote it in the file (EXIF). */
object PhotoDate {
    private val EXIF = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * EXIF's date ("2026:11:07 10:15:30") at its offset ("-03:00") when the camera wrote one, else
     * in [zone] (the camera's clock is the phone's); null when it isn't there or isn't a date.
     */
    fun parse(dateTime: String?, offset: String?, zone: ZoneId): Long? {
        val text = dateTime?.trim()?.takeIf { it.length >= 19 } ?: return null
        val local = runCatching { LocalDateTime.parse(text.take(19), EXIF) }.getOrNull() ?: return null
        val off = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        return (if (off != null) local.atOffset(off).toInstant() else local.atZone(zone).toInstant()).toEpochMilli()
    }

    fun of(file: File, zone: ZoneId): Long? = runCatching {
        val exif = ExifInterface(file.path)
        val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (original != null) parse(original, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), zone)
        else parse(exif.getAttribute(ExifInterface.TAG_DATETIME), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME), zone)
    }.getOrNull()
}
