package com.villamediana.novelo.importer

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One file of what she picked, read as text: its path inside (with its folders) and what it says. */
data class TextFile(val name: String, val text: String, val time: Long? = null)

/**
 * A file that isn't text. A photo is set aside in [file] (named by its content), with the date the
 * camera wrote inside ([taken], EXIF "yyyy:MM:dd HH:mm:ss", plus [offset] when written); anything
 * else is only its name and size.
 */
data class OtherFile(val name: String, val size: Long, val file: File? = null, val taken: String? = null, val offset: String? = null) {
    val isPhoto: Boolean get() = file != null
}

/** An entry of the diary read from a day's folder. */
data class Found(val at: Long, val title: String, val body: String, val tags: List<String> = emptyList())

/** A photo of a day's folder, on that day. */
data class FoundPhoto(val at: Long, val file: File)

/** What came out: the entries (oldest first) and the photos. */
data class Reading(val found: List<Found>, val photos: List<FoundPhoto> = emptyList()) {
    val isEmpty: Boolean get() = found.isEmpty() && photos.isEmpty()

    /** How many days the entries fall on (the diary is one a day). */
    fun days(zone: ZoneId): Int = found.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().size
}

/**
 * The diary as a .zip or a folder of one folder a day: the folder's name starts with its date
 * (2024-12-17, or "2024-12-17 - Natal"), and inside it the day's entry as text (.txt or .md). The
 * photos in a day's folder (in its folders too: "fotos/01.jpg") go on that day. Nothing else counts:
 * a file outside a day's folder (a "diario.txt" with everything) or another kind (the same entry as
 * .html, audio, video) is left as it is.
 */
object DayFolders {
    private val DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
    val TEXTS = setOf("txt", "md")

    fun read(texts: List<TextFile>, others: List<OtherFile>, zone: ZoneId): Reading {
        val found = texts.mapNotNull { f ->
            val folders = f.name.split('/').dropLast(1)
            val day = folders.lastOrNull()?.let(::dayOf) ?: return@mapNotNull null
            if (f.name.substringAfterLast('.', "").lowercase() !in TEXTS) return@mapNotNull null
            entry(f.text, day, zone)
        }
        val photos = others.mapNotNull { o ->
            val file = o.file ?: return@mapNotNull null
            val day = o.name.split('/').dropLast(1).lastOrNull { dayOf(it) != null }?.let(::dayOf) ?: return@mapNotNull null
            FoundPhoto(noon(day, zone), file)
        }.distinctBy { it.file.name }
        return Reading(found.sortedBy { it.at }, photos.sortedBy { it.at })
    }

    /** The date a folder's name starts with, when it is one. */
    fun dayOf(folder: String): LocalDate? = DAY.find(folder)?.let { m ->
        runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    /**
     * The day's text as an entry. Its first line, when it is the day's date itself (as "23/01/2022"
     * or "2022-01-23", with the title after " — " or " - "), gives the title and leaves the text.
     */
    private fun entry(text: String, day: LocalDate, zone: ZoneId): Found? {
        var body = text.replace("\r\n", "\n").replace('\r', '\n').trim('﻿', ' ', '\n', '\t')
        var title = ""
        val first = body.substringBefore('\n').trim()
        val dates = listOf("%02d/%02d/%04d".format(day.dayOfMonth, day.monthValue, day.year), day.toString())
        dates.firstOrNull { first.startsWith(it) }?.let { date ->
            val rest = first.removePrefix(date).trim()
            title = rest.removePrefix("—").removePrefix("-").trim().takeIf { rest.isEmpty() || rest.startsWith("—") || rest.startsWith("-") } ?: return@let
            body = body.substringAfter('\n', "").trim('\n', ' ')
        }
        body = body.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (body.isEmpty() && title.isEmpty()) return null
        return Found(noon(day, zone), title.take(TITLE_MAX), body)
    }

    /** A day without a time of day sits at noon (the same day in any time zone close by). */
    private fun noon(day: LocalDate, zone: ZoneId): Long = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private const val TITLE_MAX = 120
}
