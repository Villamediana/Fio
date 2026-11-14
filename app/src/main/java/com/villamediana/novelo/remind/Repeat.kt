package com.villamediana.novelo.remind

import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/**
 * How a reminder (and its task) comes back: every day, on some days of the week, or every month on
 * one day — at [time], or at each of [times] when it is more than once a day ("10:00, 14:00 e 20:00").
 * Kept with the entry as JSON ([Entry.repeat]).
 */
@Serializable
data class Repeat(
    /** [DAY], [WEEK] or [MONTH]. */
    val every: String,
    /** For [WEEK]: the days, 1 = Monday … 7 = Sunday. */
    val days: List<Int> = emptyList(),
    /** "HH:MM". */
    val time: String = "09:00",
    /** For [MONTH]: the day of the month (a short month takes its last day). */
    val day: Int? = null,
    /** More than one time a day ("HH:MM", in order); empty: only [time]. */
    val times: List<String> = emptyList(),
) {
    /** Each of its times of the day, in order. */
    val allTimes: List<LocalTime>
        get() = times.ifEmpty { listOf(time) }.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.distinct().sorted().ifEmpty { listOf(LocalTime.of(9, 0)) }

    /** The first time it comes after [after] (ms); null when the rule says nothing. */
    fun next(after: Long, zone: ZoneId): Long? {
        val from = Instant.ofEpochMilli(after).atZone(zone)
        val start = from.toLocalDate()
        val ats = allTimes
        fun ms(d: LocalDate) = ats.map { d.atTime(it).atZone(zone).toInstant().toEpochMilli() }
        return when (every) {
            DAY -> (0..1).map { start.plusDays(it.toLong()) }.flatMap(::ms).firstOrNull { it > after }
            WEEK -> {
                val on = days.filter { it in 1..7 }.toSet().ifEmpty { return null }
                (0..7).map { start.plusDays(it.toLong()) }.filter { it.dayOfWeek.value in on }.flatMap(::ms).firstOrNull { it > after }
            }
            MONTH -> {
                val dom = (day ?: start.dayOfMonth).coerceIn(1, 31)
                (0..12).map { start.withDayOfMonth(1).plusMonths(it.toLong()) }
                    .map { m -> m.withDayOfMonth(minOf(dom, m.lengthOfMonth())) }
                    .flatMap(::ms).firstOrNull { it > after }
            }
            else -> null
        }
    }

    /** "todo dia às 08:00", "todo dia às 10:00, 14:00 e 20:00", "seg e qua às 08:00", "todo mês no dia 10 às 08:00" — in the app's language. */
    fun describe(): String {
        val at = joinAnd(allTimes.map { "%02d:%02d".format(it.hour, it.minute) })
        return when (every) {
            DAY -> t(R.string.repeat_daily_at, at)
            WEEK -> {
                val names = days.filter { it in 1..7 }.sorted().map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Lang.locale).removeSuffix(".") }
                if (names.size == 7) t(R.string.repeat_daily_at, at) else t(R.string.repeat_days_at, joinAnd(names), at)
            }
            MONTH -> t(R.string.repeat_monthly_at, day ?: 1, at)
            else -> ""
        }
    }

    private fun joinAnd(names: List<String>): String =
        if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " " + t(R.string.and) + " " + names.last()

    companion object {
        const val DAY = "day"
        const val WEEK = "week"
        const val MONTH = "month"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun read(text: String?): Repeat? = text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.every in setOf(DAY, WEEK, MONTH) && (it.every != WEEK || it.days.any { d -> d in 1..7 }) }

        fun write(r: Repeat?): String? = r?.let { json.encodeToString(serializer(), it) }

        /**
         * A rule said by the model or chosen on screen, made whole from the first time it comes
         * ([first], ms): its hour, its day of the week or of the month when none was given. [times]:
         * more than one time a day ("10:00", "14h"…) — then those are its hours.
         */
        fun from(every: String?, days: List<Int>, first: Long, zone: ZoneId, times: List<String> = emptyList()): Repeat? {
            val at = Instant.ofEpochMilli(first).atZone(zone)
            val said = times.mapNotNull { com.villamediana.novelo.meds.Doses.time(it) }.distinct().sorted().map { "%02d:%02d".format(it.hour, it.minute) }
            val time = said.firstOrNull() ?: "%02d:%02d".format(at.hour, at.minute)
            val more = if (said.size > 1) said else emptyList()
            return when (every?.trim()?.lowercase()) {
                DAY -> Repeat(DAY, time = time, times = more)
                WEEK -> Repeat(WEEK, days.filter { it in 1..7 }.distinct().sorted().ifEmpty { listOf(at.dayOfWeek.value) }, time, times = more)
                MONTH -> Repeat(MONTH, time = time, day = at.dayOfMonth, times = more)
                else -> null
            }
        }

        /**
         * A task that repeats, done now: this time is done, and it comes back for the next — open
         * again, its reminder (and its due day, when it has one) on the next time after [now].
         * Anything else is just done.
         */
        fun done(e: Entry, now: Long, zone: ZoneId): Entry {
            val rule = read(e.repeat)
            if (rule == null || e.kindEnum != Kind.TASK) {
                return e.copy(status = Status.DONE.key, doneAt = now, checkInAt = null, remindAt = null, updatedAt = now)
            }
            // The time still to come is the one done (done ahead of it): the next one after it.
            val from = maxOf(now, e.remindAt ?: now)
            val next = rule.next(from, zone) ?: return e.copy(status = Status.DONE.key, doneAt = now, checkInAt = null, remindAt = null, updatedAt = now)
            val due = e.dueAt?.let {
                val day = Instant.ofEpochMilli(next).atZone(zone).toLocalDate()
                if (e.dueHasTime) next else day.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            // Its steps start over for the next time.
            val steps = com.villamediana.novelo.mind.Steps.read(e.steps).map { it.copy(done = false) }
            return e.copy(status = Status.TODO.key, doneAt = null, remindAt = next, dueAt = due, checkInAt = null, steps = com.villamediana.novelo.mind.Steps.write(steps), updatedAt = now)
        }
    }
}
