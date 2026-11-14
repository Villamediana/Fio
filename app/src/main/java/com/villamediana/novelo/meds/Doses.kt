package com.villamediana.novelo.meds

import com.villamediana.novelo.data.Med
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * When a medicine is taken: its times of the day (on its days of the week, or every day), from when it
 * starts until it ends. Each of those times is a "slot"; medicines due at the same time are one
 * reminder together.
 */
object Doses {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())
    private val ints = ListSerializer(Int.serializer())

    fun times(m: Med): List<LocalTime> = readTimes(m.times)
    fun days(m: Med): Set<Int> = readDays(m.days)

    fun readTimes(text: String?): List<LocalTime> =
        runCatching { json.decodeFromString(strings, text ?: "[]") }.getOrDefault(emptyList()).mapNotNull(::time).distinct().sorted()

    fun writeTimes(list: Collection<LocalTime>): String = json.encodeToString(strings, list.map { it.withSecond(0).withNano(0) }.distinct().sorted().map(::hhmm))

    fun readDays(text: String?): Set<Int> =
        runCatching { json.decodeFromString(ints, text ?: "[]") }.getOrDefault(emptyList()).filter { it in 1..7 }.toSet().let { if (it.size == 7) emptySet() else it }

    /** The days of the week; all seven (or none) is every day, kept as none. */
    fun writeDays(days: Collection<Int>): String = json.encodeToString(ints, days.filter { it in 1..7 }.distinct().sorted().let { if (it.size == 7) emptyList() else it })

    fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** "08:00", "8:30", "8h", "20h30", "8" → a time of the day; null when it isn't one. */
    fun time(text: String): LocalTime? {
        val m = Regex("""^\s*(\d{1,2})(?:\s*[:hH.]\s*(\d{2})?)?\s*(?:h|hs|hrs)?\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h == 24 && min == 0) return LocalTime.MIDNIGHT
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    /** "De [hours] em [hours] horas" from [first]: the times of a day (24 h), in order. */
    fun every(first: LocalTime, hours: Int): List<LocalTime> {
        if (hours !in 1..24) return listOf(first)
        return (0 until 24 / hours).map { first.plusHours((it * hours).toLong()) }.distinct().sorted()
    }

    fun takesOn(m: Med, d: LocalDate): Boolean = days(m).let { it.isEmpty() || d.dayOfWeek.value in it }

    /** The times [m] is due between [from] and [to] (ms, both included), in order. */
    fun slots(m: Med, from: Long, to: Long, zone: ZoneId): List<Long> {
        val ts = times(m)
        val start = maxOf(from, m.startsAt)
        val end = minOf(to, m.endsAt ?: Long.MAX_VALUE)
        if (ts.isEmpty() || end < start) return emptyList()
        var d = dayOf(start, zone).minusDays(1) // a time after midnight may belong to the day before in another zone rule
        val last = dayOf(end, zone)
        val out = ArrayList<Long>()
        while (!d.isAfter(last)) {
            if (takesOn(m, d)) ts.forEach { t -> d.atTime(t).atZone(zone).toInstant().toEpochMilli().let { if (it in start..end) out += it } }
            d = d.plusDays(1)
        }
        return out.distinct().sorted()
    }

    /** The first time after [after] (ms) that any of [meds] is due; null: none (only when needed, or all ended). */
    fun next(meds: List<Med>, after: Long, zone: ZoneId): Long? = meds.mapNotNull { m ->
        val from = maxOf(after + 1, m.startsAt)
        slots(m, from, from + LOOK_AHEAD, zone).firstOrNull()
    }.minOrNull()

    /** The ones of [meds] due at [at]. */
    fun at(meds: List<Med>, at: Long, zone: ZoneId): List<Med> = meds.filter { slots(it, at, at, zone).isNotEmpty() }

    /** A time of the day and the medicines due then. */
    data class Slot(val at: Long, val meds: List<Med>)

    /** The times of [day], each with the medicines due then, in order. */
    fun day(meds: List<Med>, day: LocalDate, zone: ZoneId): List<Slot> {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return meds.flatMap { m -> slots(m, from, to, zone).map { it to m } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (at, list) -> Slot(at, list) }
    }

    /** One dose of a treatment: its time, taken or not, gone by or still to come. */
    data class Dot(val at: Long, val taken: Boolean, val past: Boolean)

    /**
     * The doses to show as beads: the whole treatment when it has an end and isn't too long ([whole]),
     * else today's; [days] groups them by day. Null: only when needed, or none today.
     */
    data class Course(val days: List<List<Dot>>, val whole: Boolean) {
        val dots: List<Dot> get() = days.flatten()
        val taken: Int get() = dots.count { it.taken }
    }

    fun course(m: Med, taken: Set<Long>, now: Long, zone: ZoneId): Course? {
        if (times(m).isEmpty()) return null
        fun dots(list: List<Long>) = list.groupBy { dayOf(it, zone) }.toSortedMap().values.map { day -> day.map { Dot(it, it in taken, it <= now) } }
        m.endsAt?.let { end ->
            val all = slots(m, m.startsAt, end, zone)
            if (all.size in 1..MAX_DOTS) return Course(dots(all), whole = true)
        }
        val today = day(listOf(m), dayOf(now, zone), zone).map { it.at }
        return if (today.isEmpty()) null else Course(dots(today), whole = false)
    }

    /** The last [n] times up to [until] (ms), looking back as far as it takes (from before it started too), in order. */
    fun lastTimes(m: Med, n: Int, until: Long, zone: ZoneId): List<Long> {
        if (n <= 0 || times(m).isEmpty()) return emptyList()
        var back = 1L
        while (true) {
            val from = dayOf(until, zone).minusDays(back).atStartOfDay(zone).toInstant().toEpochMilli()
            val found = slots(m.copy(startsAt = minOf(m.startsAt, from)), from, until, zone)
            if (found.size >= n || back >= 120) return found.takeLast(n)
            back *= 2
        }
    }

    /** The time of [m] on the day of [at] nearest it (from before it started too); null: none that day. */
    fun nearest(m: Med, at: Long, zone: ZoneId): Long? {
        val day = dayOf(at, zone)
        val whole = m.copy(startsAt = minOf(m.startsAt, day.atStartOfDay(zone).toInstant().toEpochMilli()))
        return day(listOf(whole), day, zone).minByOrNull { kotlin.math.abs(it.at - at) }?.at
    }

    /** Its last day is over. */
    fun ended(m: Med, now: Long): Boolean = m.endsAt != null && m.endsAt < now

    /** The days still to take it, today counted (its last day is 1); null: no end. */
    fun daysLeft(m: Med, now: Long, zone: ZoneId): Int? =
        m.endsAt?.let { (ChronoUnit.DAYS.between(dayOf(now, zone), dayOf(it, zone)) + 1).toInt().coerceAtLeast(0) }

    /** How far along it is, from its first day (0) to its last (1); null: no end. */
    fun progress(m: Med, now: Long, zone: ZoneId): Float? {
        val end = m.endsAt ?: return null
        val total = ChronoUnit.DAYS.between(dayOf(m.startsAt, zone), dayOf(end, zone)) + 1
        val gone = ChronoUnit.DAYS.between(dayOf(m.startsAt, zone), dayOf(now, zone))
        return (gone.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** The end of [day] (its last ms): "até dia 21" takes the whole of the 21st. */
    fun endOf(day: LocalDate, zone: ZoneId): Long = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * What one dose comes to, from how strong one of it is ("500 mg") and how much is taken
     * ("½ comprimido", "2 cápsulas", "meio"): "250 mg", "1000 mg". Null when they don't say it in
     * pieces (drops, ml of a syrup, a strength per ml).
     */
    fun amount(strength: String?, dose: String?): String? {
        val s = strength?.trim() ?: return null
        if ('/' in s) return null
        val m = Regex("""^(\d+(?:[.,]\d+)*)\s*(mcg|µg|mg|g|ui|UI|IU)\b""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val written = m.groupValues[1]
        // "1.000 mg" is a thousand (the dot between thousands), "1,5 g" and "0.125 mg" have decimals.
        val thousands = Regex("""^[1-9]\d{0,2}(\.\d{3})+$""").matches(written)
        val value = (if (thousands) written.replace(".", "") else written.replace(',', '.')).toDoubleOrNull() ?: return null
        val q = pieces(dose ?: return null) ?: return null
        if (q == 1.0) return null // the same as the strength: nothing to add
        val comma = thousands || !written.contains('.')
        return number(value * q, comma) + " " + m.groupValues[2]
    }

    /** "½ comprimido", "1/2", "meio", "1 e meio", "2 cápsulas", "uma" → how many pieces; null when it isn't pieces. */
    fun pieces(dose: String): Double? {
        var rest = dose.trim().lowercase(Locale.ROOT)
        var total = 0.0
        var found = false
        fun take(re: Regex, value: (MatchResult) -> Double?): Boolean {
            val m = re.find(rest) ?: return false
            val v = value(m) ?: return false
            total += v; found = true
            rest = rest.substring(m.range.last + 1).trimStart()
            return true
        }
        take(Regex("""^(\d+)\s*/\s*(\d+)""")) { m -> m.groupValues[2].toDouble().takeIf { it > 0 }?.let { m.groupValues[1].toDouble() / it } } ||
            take(Regex("""^(\d+(?:[.,]\d+)?)""")) { it.groupValues[1].replace(',', '.').toDoubleOrNull() } ||
            take(Regex("""^(${WORDS.keys.joinToString("|")})(?![\p{L}])""")) { WORDS[it.groupValues[1]] }
        // "1 ½", "1 e meio", "uno y medio": a half (or quarter) after the whole ones.
        if (found) take(Regex("""^(?:(?:e|y|and)\s+)?(½|¼|¾|meio|meia|medio|media|half|um quarto|un cuarto|a quarter)(?![\p{L}])""")) { FRACTIONS[it.groupValues[1]] }
        else take(Regex("""^(½|¼|¾|⅓|⅔)""")) { FRACTIONS[it.groupValues[1]] }
        if (!found || total <= 0) return null
        val unit = rest.trim()
        return if (unit.isEmpty() || PIECES.any { unit.startsWith(it) }) total else null
    }

    private fun number(v: Double, comma: Boolean): String {
        val r = Math.round(v * 100) / 100.0
        val text = if (r == Math.floor(r)) r.toLong().toString() else "%.2f".format(Locale.ROOT, r).trimEnd('0').trimEnd('.')
        return if (comma) text.replace('.', ',') else text
    }

    private val FRACTIONS = mapOf(
        "½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3,
        "meio" to 0.5, "meia" to 0.5, "medio" to 0.5, "media" to 0.5, "half" to 0.5,
        "um quarto" to 0.25, "un cuarto" to 0.25, "a quarter" to 0.25,
    )

    private val WORDS = linkedMapOf(
        "um quarto" to 0.25, "un cuarto" to 0.25, "a quarter" to 0.25,
        "meio" to 0.5, "meia" to 0.5, "medio" to 0.5, "media" to 0.5, "half" to 0.5,
        "uma" to 1.0, "um" to 1.0, "una" to 1.0, "uno" to 1.0, "un" to 1.0, "one" to 1.0,
        "duas" to 2.0, "dois" to 2.0, "dos" to 2.0, "two" to 2.0,
        "três" to 3.0, "tres" to 3.0, "three" to 3.0,
    )

    /** The words of a dose counted in pieces (pt, es, en). */
    private val PIECES = listOf("comprimido", "comp", "cp", "cápsula", "capsula", "drágea", "dragea", "pílula", "pilula", "sachê", "sache", "sobre", "unidade", "unidad", "tablete", "tableta", "tablet", "pastilla", "pill", "capsule")

    /** The most doses shown one by one for a whole treatment (longer ones show today's). */
    const val MAX_DOTS = 60

    /** How far ahead the next time is looked for (a medicine taken once a week has one in it). */
    private const val LOOK_AHEAD = 8 * 86_400_000L
}
