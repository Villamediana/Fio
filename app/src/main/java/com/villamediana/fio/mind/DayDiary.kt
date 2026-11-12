package com.villamediana.fio.mind

import com.villamediana.fio.R
import com.villamediana.fio.settings.Lang
import com.villamediana.fio.settings.t
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The diary is one per day: what is told goes on in it, and the photos taken that day are in it.
 * Pure helpers for the day around a moment.
 */
object DayDiary {
    /** "Quarta-feira, 11 de novembro", in the app's language (a diary made today keeps today's words). */
    private val TITLE: DateTimeFormatter get() = DateTimeFormatter.ofPattern(t(R.string.fmt_weekday_day_month), Lang.locale)

    /** The whole day (in [zone]) that [ms] falls in, first to last millisecond. */
    fun bounds(ms: Long, zone: ZoneId): LongRange {
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until next
    }

    /**
     * The day's diary told some more: only what isn't in it yet joins it, as new paragraphs at the
     * end. A [more] that already holds the whole of [body] (the day told again, from the start, with
     * something new) takes its place.
     */
    fun merge(body: String, more: String): String {
        val had = Words.of(body).joinToString(" ")
        val all = Words.of(more).joinToString(" ")
        if (all.isEmpty()) return body
        // Whole words only ("sol" is not in "girassol").
        if (had.isEmpty() || " $all ".contains(" $had ")) return more.trim()
        val fresh = more.trim().split(Regex("""\n\s*\n""")).map { it.trim() }.filter { p ->
            val words = Words.of(p).joinToString(" ")
            words.isNotEmpty() && !" $had ".contains(" $words ")
        }
        return fresh.fold(body) { b, p -> joined(b, p) }
    }

    /** The name of a diary started by a photo, before anything was told: the day ("Terça-feira, 11 de novembro"). */
    fun title(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(TITLE).replaceFirstChar { it.titlecase(Lang.locale) }
}
