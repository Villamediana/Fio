package com.villamediana.fio.mind

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The diary is one per day: what is told goes on in it, and the photos taken that day are in it.
 * Pure helpers for the day around a moment.
 */
object DayDiary {
    private val PT = Locale.forLanguageTag("pt-BR")
    private val TITLE = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT)

    /** The whole day (in [zone]) that [ms] falls in, first to last millisecond. */
    fun bounds(ms: Long, zone: ZoneId): LongRange {
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until next
    }

    /** The name of a diary started by a photo, before anything was told: the day ("Terça-feira, 11 de novembro"). */
    fun title(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(TITLE).replaceFirstChar { it.titlecase(PT) }
}
