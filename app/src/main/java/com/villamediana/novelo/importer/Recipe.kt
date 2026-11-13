package com.villamediana.novelo.importer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalQueries
import java.text.ParsePosition
import java.util.Locale

/**
 * How to read a diary exported by another app, written by the model from a sample of the file
 * (see [Importing]): where each entry starts, where its date, title and text are, and how the date
 * is written. The app applies it to the whole file ([Recipes.apply]); nothing the model writes runs
 * as code — only these fields (paths, regular expressions, a date pattern).
 */
@Serializable
data class Recipe(
    /** The app or format recognized ("Day One", "Texto com datas"). */
    val app: String = "",
    /** The pattern in a sentence or two, for her. */
    val about: String = "",
    /** "json", "text" or "csv". */
    val shape: String = "",
    /** In a .zip: the files that hold entries (a regex on the name); null: every text file. */
    val files: String? = null,
    /** json: the path to the list of entries ("" = the root; a root object = one entry). */
    val entries: String? = null,
    /** json: path in each entry; csv: the column (name or number from 0). */
    val date: String? = null,
    val title: String? = null,
    val body: String? = null,
    val tags: String? = null,
    /** text: the line that starts an entry, with (?<date>…) and (?<title>…) when they are on it. */
    val start: String? = null,
    /** text: a header inside the entry with (?<date>…); what matches leaves the text. */
    @SerialName("date_regex") val dateRegex: String? = null,
    /** text: a header inside the entry with (?<title>…); what matches leaves the text. */
    @SerialName("title_regex") val titleRegex: String? = null,
    /** csv: ",", ";" or "\t". */
    val delimiter: String? = null,
    /** csv: the first line has the names of the columns. */
    val header: Boolean = true,
    /** The date in the file's name (one file a day), with (?<date>…). */
    @SerialName("date_from_name") val dateFromName: String? = null,
    /** "iso", "epoch_s", "epoch_ms" or a java.time pattern ("dd/MM/yyyy"). */
    @SerialName("date_format") val dateFormat: String = ISO,
    /** The language of month and day names ("pt-BR", "en"). */
    @SerialName("date_locale") val dateLocale: String? = null,
    /** The text's first line is its title. */
    @SerialName("first_line_title") val firstLineTitle: Boolean = false,
    /** The text comes as HTML. */
    val html: Boolean = false,
    /** What to take out of the text (image references and the like), as regexes. */
    val clean: List<String> = emptyList(),
) {
    companion object {
        const val ISO = "iso"
        const val EPOCH_S = "epoch_s"
        const val EPOCH_MS = "epoch_ms"
        val SHAPES = setOf("json", "text", "csv")
    }
}

/** One file of what she picked (the file itself, or each text file in a .zip). */
data class TextFile(val name: String, val text: String)

/** An entry read from the file. */
data class Found(val at: Long, val title: String, val body: String, val tags: List<String> = emptyList())

/** What a recipe got out of the files: the entries, and what had to stay out. */
data class Reading(
    val found: List<Found>,
    /** Entries with text but no date the recipe could read. */
    val undated: Int = 0,
    /** Files the recipe couldn't read at all (not JSON, say). */
    val unread: Int = 0,
) {
    /** How many days the entries fall on (the diary is one a day). */
    fun days(zone: ZoneId): Int = found.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().size
}

object Recipes {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * The model's answer → a [Recipe] that can run: a known shape, regexes that compile, a date
     * format that is one. Null otherwise (the answer is asked again of another model).
     */
    fun parse(answer: String): Recipe? {
        val text = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val from = text.indexOf('{')
        val to = text.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        val r = runCatching { json.decodeFromString(Recipe.serializer(), text.substring(from, to + 1)) }.getOrNull() ?: return null
        if (r.shape !in Recipe.SHAPES) return null
        val regexes = listOfNotNull(r.files, r.start, r.dateRegex, r.titleRegex, r.dateFromName) + r.clean
        if (regexes.any { runCatching { Regex(it) }.isFailure }) return null
        if (formatter(r.dateFormat, r.dateLocale) == null && r.dateFormat !in setOf(Recipe.ISO, Recipe.EPOCH_S, Recipe.EPOCH_MS)) return null
        return r
    }

    /** The recipe as the model can read it again (when she asks to recognize once more). */
    fun write(r: Recipe): String = Json { encodeDefaults = false; explicitNulls = false }.encodeToString(Recipe.serializer(), r)

    /** [r] applied to every file it is for: the entries with a date, oldest first. */
    fun apply(r: Recipe, files: List<TextFile>, zone: ZoneId): Reading {
        val which = r.files?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val chosen = files.filter { f -> which == null || which.containsMatchIn(f.name) }
        val found = ArrayList<Found>()
        var undated = 0
        var unread = 0
        val clean = r.clean.map { Regex(it, RegexOption.MULTILINE) }
        for (f in chosen) {
            val raw = when (r.shape) {
                "json" -> fromJson(r, f) ?: run { unread++; null }
                "csv" -> fromCsv(r, f)
                else -> fromText(r, f)
            } ?: continue
            val fromName = r.dateFromName?.let { Regex(it).find(f.name)?.let(::dateGroup) }
            for (e in raw) {
                var body = e.body.replace("\r\n", "\n").replace('\r', '\n')
                if (r.html) body = plain(body)
                clean.forEach { body = it.replace(body, "") }
                var title = e.title.trim()
                if (title.isEmpty() && r.firstLineTitle) {
                    val lines = body.trim().lines()
                    val first = lines.firstOrNull { it.isNotBlank() }
                    if (first != null) {
                        title = first.trim().trimStart('#', '*', ' ').trimEnd('*', ' ')
                        body = lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n")
                    }
                }
                body = tidy(body)
                title = title.take(TITLE_MAX)
                if (body.isEmpty() && title.isEmpty()) continue
                val at = (e.date ?: fromName)?.let { date(it, r.dateFormat, r.dateLocale, zone) }
                if (at == null) {
                    undated++
                    continue
                }
                found += Found(at, title, body, e.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            }
        }
        return Reading(found.sortedBy { it.at }, undated, unread)
    }

    /** One entry as it is in the file, before the date is read. */
    private data class Raw(val date: String?, val title: String, val body: String, val tags: List<String> = emptyList())

    private fun fromJson(r: Recipe, f: TextFile): List<Raw>? {
        val root = runCatching { json.parseToJsonElement(f.text.trim()) }.getOrNull() ?: return null
        val list = at(root, r.entries)
        val items = when (list) {
            is JsonArray -> list.toList()
            // The root, when it is one entry; an object of entries (id → entry) otherwise.
            is JsonObject -> if (r.entries.isNullOrBlank() || r.entries == ".") listOf(list) else list.values.toList()
            else -> return emptyList()
        }
        return items.filterIsInstance<JsonObject>().map { item ->
            Raw(
                date = r.date?.let { text(at(item, it)) },
                title = r.title?.let { text(at(item, it)) }.orEmpty(),
                body = r.body?.let { text(at(item, it)) }.orEmpty(),
                tags = r.tags?.let { words(at(item, it)) }.orEmpty(),
            )
        }
    }

    private fun fromText(r: Recipe, f: TextFile): List<Raw> {
        val lines = f.text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val blocks = ArrayList<Raw>()
        val start = r.start?.let { Regex(it) }
        if (start == null) {
            blocks += Raw(null, "", lines.joinToString("\n"))
        } else {
            var head: MatchResult? = null
            val body = StringBuilder()
            fun close() {
                val h = head ?: return
                // The date on the start line; a line that only marks the start (no date group) leaves it to date_regex.
                val date = group(h, "date") ?: if (r.dateRegex == null) dateGroup(h) else null
                blocks += Raw(date, group(h, "title").orEmpty(), body.toString())
            }
            for (line in lines) {
                val m = start.find(line)
                if (m != null) {
                    close()
                    head = m
                    body.setLength(0)
                } else if (head != null) {
                    body.append(line).append('\n')
                }
            }
            close()
        }
        val dateIn = r.dateRegex?.let { Regex(it, RegexOption.MULTILINE) }
        val titleIn = r.titleRegex?.let { Regex(it, RegexOption.MULTILINE) }
        return blocks.map { b ->
            var body = b.body
            var date = b.date
            var title = b.title
            if (date == null && dateIn != null) dateIn.find(body)?.let { m -> date = dateGroup(m); body = body.removeRange(m.range) }
            if (title.isBlank() && titleIn != null) titleIn.find(body)?.let { m -> title = group(m, "title").orEmpty(); body = body.removeRange(m.range) }
            Raw(date, title, body)
        }
    }

    private fun fromCsv(r: Recipe, f: TextFile): List<Raw> {
        val sep = when (r.delimiter) { null, "" -> ','; "\\t", "tab" -> '\t'; else -> r.delimiter.first() }
        val rows = csv(f.text, sep)
        if (rows.isEmpty()) return emptyList()
        val names = if (r.header) rows.first().map { it.trim().lowercase() } else emptyList()
        fun col(name: String?): Int? {
            if (name.isNullOrBlank()) return null
            name.trim().toIntOrNull()?.let { return it }
            return names.indexOf(name.trim().lowercase()).takeIf { it >= 0 }
        }
        val d = col(r.date); val t = col(r.title); val b = col(r.body); val g = col(r.tags)
        return rows.drop(if (r.header) 1 else 0).map { row ->
            fun cell(i: Int?) = i?.let { row.getOrNull(it) }
            Raw(cell(d), cell(t).orEmpty(), cell(b).orEmpty(), cell(g)?.split(',', ';', '|').orEmpty())
        }
    }

    /** A dotted path ("location.placeName", "photos.0.md5") inside [e]; blank: [e] itself. */
    private fun at(e: JsonElement, path: String?): JsonElement? {
        if (path.isNullOrBlank() || path == ".") return e
        var cur: JsonElement? = e
        for (key in path.trim().removePrefix("$.").split('.')) {
            cur = when (val c = cur) {
                is JsonObject -> c[key]
                is JsonArray -> key.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    /** A value as text: a list of texts becomes one, a line each. */
    private fun text(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.mapNotNull { text(it) }.joinToString("\n").ifEmpty { null }
        is JsonObject -> null
    }

    private fun words(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> e.contentOrNull?.split(',', ';').orEmpty()
        else -> emptyList()
    }

    private fun group(m: MatchResult, name: String): String? = runCatching { m.groups[name]?.value }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** The (?<date>…) of a match; a regex without it: its first group, else all it matched. */
    private fun dateGroup(m: MatchResult): String? =
        group(m, "date") ?: m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: m.value.trim().takeIf { it.isNotEmpty() }

    /**
     * A date as the file writes it → the moment. A day without a time of day is placed at noon
     * (the same day in any time zone close by).
     */
    fun date(text: String, format: String, locale: String?, zone: ZoneId): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return runCatching {
            when (format) {
                Recipe.EPOCH_S -> (s.toDouble() * 1000).toLong()
                Recipe.EPOCH_MS -> s.toDouble().toLong()
                Recipe.ISO -> iso(s, zone)
                else -> pattern(s, formatter(format, locale) ?: return null, zone)
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun iso(s: String, zone: ZoneId): Long? {
        val t = if (Regex("""^\d{4}-\d{2}-\d{2} \d""").containsMatchIn(s)) s.replaceFirst(' ', 'T') else s
        runCatching { return Instant.parse(t).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(t.take(10)).atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    private fun formatter(pattern: String, locale: String?): DateTimeFormatter? = runCatching {
        DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(pattern)
            .toFormatter(locale?.let { Locale.forLanguageTag(it) } ?: Locale.ROOT)
    }.getOrNull()

    /** Text after the date (" às 10h", a weekday) is let be: the date is what comes first. */
    private fun pattern(s: String, f: DateTimeFormatter, zone: ZoneId): Long? {
        val p: TemporalAccessor = f.parse(s, ParsePosition(0))
        val day = p.query(TemporalQueries.localDate()) ?: return null
        val time = p.query(TemporalQueries.localTime())
        val offset = p.query(TemporalQueries.offset())
        val z = p.query(TemporalQueries.zone())
        return when {
            time == null -> day.atTime(12, 0).atZone(zone)
            offset != null -> day.atTime(time).atOffset(offset).atZoneSameInstant(zone)
            z != null -> day.atTime(time).atZone(z)
            else -> day.atTime(time).atZone(zone)
        }.toInstant().toEpochMilli()
    }

    /** HTML → its text: blocks and line breaks as lines, tags out, the usual entities back. */
    fun plain(html: String): String {
        var s = html.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), "")
        s = s.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|li|h[1-6]|blockquote|tr)>"""), "\n\n")
            .replace(Regex("""<[^>]+>"""), "")
        s = s.replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return s
    }

    /** Spaces at the ends of lines out, at most one empty line between paragraphs. */
    private fun tidy(s: String): String = s.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** CSV as spreadsheets write it: quotes around a cell with the separator, line breaks or quotes ("" inside). */
    fun csv(text: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                sep -> { row += cell.toString(); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.setLength(0); if (row.any { it.isNotBlank() }) rows += row; row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    private const val TITLE_MAX = 120
}
