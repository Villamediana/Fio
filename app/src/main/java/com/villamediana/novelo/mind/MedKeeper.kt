package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.meds.Doses
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The medicines of a [Plan] written down: new ones, changes, the time of now taken, one taken off. */
internal object MedKeeper {
    /** The medicines made or changed ([meds]); [changed]: anything at all (their reminders go again). */
    data class Done(val meds: List<Long>, val changed: Boolean)

    /**
     * [sentPhotos]: the photos sent with what she said, numbered as the model saw them; [photo]: the
     * photo she sent alone (what she photographed is the medicine: the box, the prescription).
     */
    suspend fun apply(db: NoveloDatabase, asks: List<MedAsk>, captureId: Long?, sentPhotos: List<Long>, photo: Long?, now: Long, z: ZoneId): Done {
        if (asks.isEmpty()) return Done(emptyList(), false)
        val dao = db.meds()
        // The people there are: "cooper" is Cooper.
        val people = dao.all().mapNotNull { it.person }.distinct().toMutableList()
        fun person(name: String): String? = Categories.pick(name, people)?.also { if (it !in people) people += it }
        val pool = if (sentPhotos.isEmpty() && photo != null) listOf(photo) else sentPhotos
        fun picked(nums: List<Int>): Long? = if (Picks.ALL in nums) pool.firstOrNull() else nums.firstNotNullOfOrNull { pool.getOrNull(it - 1) }

        val ids = ArrayList<Long>()
        var changed = false
        for (ask in asks) {
            val old = ask.id?.let { dao.get(it) }
            if (ask.id != null && old == null) continue
            if (ask.delete) {
                if (old != null) { dao.delete(old.id); changed = true }
                continue
            }
            val name = ask.name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.take(80)
            // Said as new but already there (the same name, for the same person, not over): that one changes.
            if (old == null && name != null) {
                val who = ask.person?.let { Categories.clean(it) }?.let(Categories::fold)
                val same = dao.all().firstOrNull { m0 ->
                    Categories.fold(m0.name) == Categories.fold(name) && m0.person?.let(Categories::fold) == who && !Doses.ended(m0, now)
                }
                if (same != null) {
                    apply(db, listOf(ask.copy(id = same.id, name = null)), captureId, sentPhotos, photo, now, z).let { d -> ids += d.meds; changed = changed || d.changed }
                    continue
                }
            }
            var m = old ?: Med(name = name ?: continue, startsAt = start(ask.start, now, z) ?: (now / 60_000L * 60_000L), captureId = captureId, createdAt = now, updatedAt = now)
            if (old != null) {
                name?.let { m = m.copy(name = it) }
                ask.start?.let { s -> start(s, now, z)?.let { m = m.copy(startsAt = it) } }
            }
            ask.strength?.let { m = m.copy(strength = it.trim().take(40).ifEmpty { null }) }
            ask.dose?.let { m = m.copy(dose = it.trim().take(40).ifEmpty { null }) }
            ask.amount?.let { m = m.copy(amount = it.trim().take(40).ifEmpty { null }) }
            // A strength or a dose that changed with no sum said: worked out again from the two.
            if (ask.amount == null && (ask.strength != null || ask.dose != null)) m = m.copy(amount = Doses.amount(m.strength, m.dose))
            ask.person?.let { m = m.copy(person = person(it)) }
            ask.end?.let { e -> m = m.copy(endsAt = if (e.trim().lowercase() in NO_END) null else end(e, z) ?: m.endsAt) }
            ask.notes?.let { m = m.copy(notes = it.trim().take(300)) }
            ask.days?.let { m = m.copy(days = Doses.writeDays(it)) }
            m = times(m, ask, z)
            (picked(ask.photos) ?: photo.takeIf { old == null })?.let { m = m.copy(photoId = it) }

            val id = if (old == null) dao.insert(m).also { changed = true } else {
                if (m != old) { dao.update(m.copy(updatedAt = now)); changed = true }
                m.id
            }
            if (ask.taken) {
                take(db, dao.get(id) ?: continue, now, z)
                changed = true
            }
            if (old == null || m != old || ask.taken) ids += id
        }
        return Done(ids.distinct(), changed)
    }

    /**
     * Its times: the ones said; "every N hours" from the first of them (or of the ones it had). Times
     * said without the hours that don't fall every N hours aren't that any more.
     */
    private fun times(m: Med, ask: MedAsk, z: ZoneId): Med {
        val said = ask.times.mapNotNull(Doses::time)
        if (said.isEmpty() && ask.everyHours == null) return m
        val every = when {
            ask.everyHours != null -> ask.everyHours.takeIf { it in 1..24 }
            else -> m.everyHours?.takeIf { h -> Doses.every(said.first(), h) == said.distinct().sorted() }
        }
        val base = said.ifEmpty { Doses.times(m) }
        val times = if (every != null) {
            Doses.every(base.firstOrNull() ?: Instant.ofEpochMilli(m.startsAt).atZone(z).toLocalTime().truncatedTo(ChronoUnit.MINUTES), every)
        } else base
        return m.copy(times = Doses.writeTimes(times), everyHours = every)
    }

    /**
     * The time of now taken (or given): today's time nearest now — even one from before it was written
     * down (then it starts there, so today shows it ticked); one taken only when needed, now.
     */
    suspend fun take(db: NoveloDatabase, m: Med, now: Long, z: ZoneId) {
        val day = Doses.dayOf(now, z)
        val whole = m.copy(startsAt = minOf(m.startsAt, day.atStartOfDay(z).toInstant().toEpochMilli()))
        val slot = Doses.day(listOf(whole), day, z).minByOrNull { kotlin.math.abs(it.at - now) }?.at
        if (slot != null && slot < m.startsAt) db.meds().update(m.copy(startsAt = slot))
        db.meds().take(MedTaken(m.id, slot ?: (now / 60_000L * 60_000L), now))
    }

    /** When it starts: a day only — today is from now on, another day from its start; a time is that time. */
    private fun start(text: String?, now: Long, z: ZoneId): Long? {
        val (ms, hasTime) = Brain.time(text, z) ?: return null
        if (hasTime) return ms
        // Today, from this minute on (a time of this very minute counts).
        return if (Doses.dayOf(ms, z) == Doses.dayOf(now, z)) now / 60_000L * 60_000L else ms
    }

    /** Until when: a day is the whole of it; a time is that time. */
    private fun end(text: String, z: ZoneId): Long? {
        val (ms, hasTime) = Brain.time(text, z) ?: return null
        return if (hasTime) ms else Doses.endOf(Doses.dayOf(ms, z), z)
    }

    /** What the model says to take the end off (taken for good). */
    private val NO_END = setOf("none", "never", "nunca", "null", "", "contínuo", "continuo")
}
