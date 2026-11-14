package com.villamediana.novelo.meds

import com.villamediana.novelo.R
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.settings.tn
import com.villamediana.novelo.ui.components.Dates
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle

/** A medicine in words, in the app's language: its dose, its times, how long it goes on. */
object MedText {
    /** What a dose comes to: the model's, or worked out from the strength and the dose. */
    fun amount(m: Med): String? = m.amount?.takeIf { it.isNotBlank() } ?: Doses.amount(m.strength, m.dose)

    /** "½ comprimido · 250 mg (de 500 mg)", "500 mg", "10 gotas"; null when nothing was said. */
    fun dose(m: Med): String? {
        val amount = amount(m)
        val strength = m.strength?.takeIf { it.isNotBlank() }
        val parts = listOfNotNull(m.dose?.takeIf { it.isNotBlank() }, amount ?: strength).distinctBy(Categories::fold)
        if (parts.isEmpty()) return null
        val of = if (amount != null && strength != null && Categories.fold(amount) != Categories.fold(strength)) " (" + t(R.string.med_of, strength) + ")" else ""
        return parts.joinToString(" · ") + of
    }

    /** "de 8 em 8 h · 08:00 · 16:00 · 00:00", "seg, qua e sex · 09:00", "quando precisar". */
    fun schedule(m: Med): String {
        val ts = Doses.times(m)
        if (ts.isEmpty()) return t(R.string.med_when_needed)
        val days = Doses.days(m)
        return buildList {
            if (days.isNotEmpty()) add(dayNames(days))
            if (m.everyHours != null && ts.size > 1) add(t(R.string.med_every_hours, m.everyHours))
            add(ts.joinToString(" · ") { Doses.hhmm(it) })
        }.joinToString(" · ")
    }

    /** "seg, qua e sex". */
    fun dayNames(days: Set<Int>): String {
        val names = days.filter { it in 1..7 }.sorted().map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Lang.locale).removeSuffix(".") }
        return if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " " + t(R.string.and) + " " + names.last()
    }

    /** "começa amanhã", "até 21 de nov · faltam 7 dias", "último dia", "terminou ontem"; null: taken for good, already started. */
    fun period(m: Med, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val today = Doses.dayOf(now, zone)
        if (m.startsAt > now && Doses.dayOf(m.startsAt, zone).isAfter(today)) return t(R.string.med_starts, Dates.day(m.startsAt, zone, today))
        val end = m.endsAt ?: return null
        if (end < now) return t(R.string.med_ended_on, Dates.day(end, zone, today))
        val left = Doses.daysLeft(m, now, zone) ?: return null
        if (left <= 1) return t(R.string.med_last_day)
        return t(R.string.med_until, Dates.day(end, zone, today)) + " · " + tn(R.plurals.med_days_left, left)
    }

    /** Who takes it: the name, or "você" when it is hers. */
    fun who(m: Med): String = m.person ?: t(R.string.med_me)
}
