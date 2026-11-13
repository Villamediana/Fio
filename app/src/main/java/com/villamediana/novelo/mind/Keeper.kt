package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.EntryTopic
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Link
import com.villamediana.novelo.data.Outcome
import com.villamediana.novelo.data.Progress
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.data.Topic
import androidx.room.withTransaction
import java.time.ZoneId

/** Writes a [Plan] into the database: new entries, their topics and links, and changes. */
class Keeper(
    private val db: NoveloDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What happened, in order; entries whose reminders changed are in [touched]. */
    data class Result(val outcomes: List<Outcome>, val touched: List<Long>)

    /**
     * [sentPhotos] and [sentFiles]: the photos and files she sent with it, in the order the model saw
     * them numbered (1, 2…): an item's numbers become theirs.
     */
    suspend fun apply(plan: Plan, captureId: Long?, sentPhotos: List<Long> = emptyList(), sentFiles: List<Long> = emptyList()): Result = db.withTransaction {
        /** An item's numbers → (all of them, these capture ids). */
        fun picked(nums: List<Int>, ids: List<Long>): Pair<Boolean, List<Long>> =
            if (Picks.ALL in nums) true to emptyList() else false to nums.mapNotNull { ids.getOrNull(it - 1) }.distinct()
        fun outcome(id: Long, action: String, photos: List<Int>, files: List<Int>): Outcome {
            val (allPhotos, pics) = picked(photos, sentPhotos)
            val (allFiles, docs) = picked(files, sentFiles)
            return Outcome(id, action, photos = allPhotos, files = allFiles, pics = pics, docs = docs)
        }
        val now = clock()
        val z = zone()
        val outcomes = mutableListOf<Outcome>()
        val touched = mutableListOf<Long>()
        val known = HashSet<Long>()
        // The categories there are (and the ones made here): one written another way is that one.
        val categories = db.entries().categories().toMutableList()
        fun category(name: String?): String? = Categories.pick(name, categories)?.also { if (it !in categories) categories += it }

        // Categories renamed, joined or taken off first: what is created or moved here goes by the new names.
        for (c in plan.categories) {
            val from = categories.firstOrNull { Categories.fold(it) == Categories.fold(c.name) } ?: continue
            val others = categories.filter { it != from }
            val to = Categories.pick(c.to, others)
            if (to == from) continue
            db.entries().renameCategory(from, to)
            categories.remove(from)
            if (to != null && to !in categories) categories += to
        }

        for (item in plan.create) {
            val kind = Kind.of(item.kind) ?: continue
            // One diary a day: the day's story told again goes on in the diary already there.
            if (kind == Kind.DIARY) {
                val day = DayDiary.bounds(now, z)
                val today = db.entries().diariesBetween(day.first, day.last).firstOrNull()
                if (today != null) {
                    val body = DayDiary.merge(today.body, item.body)
                    // A diary only begun by a photo (the date for a name, nothing told) takes the name told.
                    val title = if (today.body.isBlank() && item.title.isNotBlank()) item.title.trim().take(140) else today.title
                    val e = today.copy(title = title, body = body)
                    // A file or photo that lives in the day's diary is news of it too.
                    if (e != today || item.photos.isNotEmpty() || item.files.isNotEmpty()) {
                        db.entries().update(e.copy(updatedAt = now))
                        outcomes += outcome(today.id, Outcome.UPDATED, item.photos, item.files)
                    }
                    known += today.id
                    tieTopics(today.id, item.entities)
                    item.related.filter { it != today.id && db.entries().get(it) != null }.forEach { other ->
                        db.graph().link(Link(minOf(today.id, other), maxOf(today.id, other)))
                    }
                    continue
                }
            }
            val due = Brain.time(item.due, z)
            val status = if (kind == Kind.TASK) (Status.of(item.status) ?: Status.TODO) else null
            val (repeat, remindAt) = repeating(item.repeat, Brain.remindTime(item.remindAt, z)?.takeIf { it > now }, now, z)
            val entry = Entry(
                kind = kind.key,
                title = item.title.trim().take(140),
                body = item.body.trim(),
                status = status?.key,
                steps = Steps.write(item.steps.filter { it.isNotBlank() }.map { Step(it.trim()) }),
                tags = Tags.write(item.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)),
                dueAt = due?.first,
                dueHasTime = due?.second ?: false,
                remindAt = remindAt,
                repeat = repeat,
                checkInAt = if (kind == Kind.TASK) Brain.remindTime(item.checkInAt, z)?.takeIf { it > now } else null,
                sensitive = item.sensitive,
                createdAt = now,
                updatedAt = now,
                doneAt = if (status == Status.DONE) now else null,
                captureId = captureId,
                category = if (kind == Kind.TASK) category(item.category) else null,
            )
            val id = db.entries().insert(entry)
            known += id
            tieTopics(id, item.entities)
            item.related.filter { it != id && db.entries().get(it) != null }.forEach { other ->
                db.graph().link(Link(minOf(id, other), maxOf(id, other)))
            }
            outcomes += outcome(id, if (status == Status.DONE) Outcome.DONE else Outcome.CREATED, item.photos, item.files)
            if (entry.remindAt != null || entry.checkInAt != null) touched += id
        }

        for (change in plan.update) {
            val old = db.entries().get(change.id) ?: continue
            if (change.delete) {
                db.entries().delete(old.id)
                outcomes += Outcome(old.id, Outcome.DELETED)
                touched += old.id
                continue
            }
            var e = old
            val newStatus = Status.of(change.status)?.takeIf { old.kindEnum == Kind.TASK }
            if (newStatus != null && newStatus != old.statusEnum) {
                e = e.copy(status = newStatus.key, doneAt = if (newStatus == Status.DONE) now else null)
            }
            if (change.stepsDone.isNotEmpty() || change.addSteps.isNotEmpty()) {
                var steps = Steps.read(e.steps)
                steps = steps.map { s -> if (change.stepsDone.any { Steps.same(it, s.text) }) s.copy(done = true) else s }
                change.addSteps.filter { a -> a.isNotBlank() && steps.none { Steps.same(a, it.text) } }.forEach { steps = steps + Step(it.trim()) }
                e = e.copy(steps = Steps.write(steps))
            }
            change.title?.takeIf { it.isNotBlank() }?.let { e = e.copy(title = it.trim().take(140)) }
            // In another category: it goes with the ones of that one (out of where it was moved by hand).
            if (e.kindEnum == Kind.TASK) category(change.category)?.takeIf { it != e.category }?.let { e = e.copy(category = it, position = null) }
            // What she said changed what the item is: its text written again.
            change.body?.takeIf { it.isNotBlank() }?.let { e = e.copy(body = it.trim()) }
            // One text per item: what is new joins it (no history kept apart that says the same again).
            change.append?.takeIf { it.isNotBlank() }?.let { e = e.copy(body = if (e.kindEnum == Kind.DIARY) DayDiary.merge(e.body, it) else joined(e.body, it)) }
            Brain.time(change.due, z)?.let { (ms, hasTime) -> e = e.copy(dueAt = ms, dueHasTime = hasTime) }
            Brain.remindTime(change.remindAt, z)?.takeIf { it > now }?.let { e = e.copy(remindAt = it) }
            Brain.remindTime(change.checkInAt, z)?.takeIf { it > now }?.let { e = e.copy(checkInAt = it) }
            // It comes back from now on (from its reminder, or from now), or not any more.
            change.repeat?.let { ask ->
                if (ask.every.trim().lowercase() in STOP) e = e.copy(repeat = null)
                else repeating(ask, e.remindAt?.takeIf { it > now }, now, z).let { (r, at) -> if (r != null) e = e.copy(repeat = r, remindAt = at) }
            }
            // Done: nothing left to ask about — or, when it repeats, this time is done and it comes back for the next.
            if (e.statusEnum == Status.DONE && old.statusEnum != Status.DONE && com.villamediana.novelo.remind.Repeat.read(e.repeat) != null) {
                e = com.villamediana.novelo.remind.Repeat.done(e, now, z)
            } else if (e.statusEnum == Status.DONE) e = e.copy(checkInAt = null, remindAt = e.remindAt?.takeIf { old.statusEnum == Status.DONE })
            val changed = e != old
            // Photos or files joined to it are news too: it comes up as changed now.
            val joins = change.photos.isNotEmpty() || change.files.isNotEmpty()
            if (changed || joins) db.entries().update(e.copy(updatedAt = now))
            if (newStatus != null && newStatus != old.statusEnum) {
                db.progress().insert(Progress(entryId = e.id, at = now, text = "", status = newStatus.key))
            }
            tieTopics(e.id, change.entities)
            if (changed || joins) {
                outcomes += outcome(e.id, if (e.statusEnum == Status.DONE && old.statusEnum != Status.DONE) Outcome.DONE else Outcome.UPDATED, change.photos, change.files)
                if (e.remindAt != old.remindAt || e.checkInAt != old.checkInAt) touched += e.id
            }
        }
        if (plan.update.any { it.delete }) db.graph().pruneTopics()
        Result(outcomes.distinctBy { it.entryId to it.action }, touched.distinct())
    }

    /**
     * A rule the model gave (every day, week on days, month) and the reminder it comes with: the
     * rule (JSON) and its first time — the reminder said, or the next time from now at 9:00. Null
     * rule: no repeat (none asked, or one that says nothing).
     */
    private fun repeating(ask: RepeatAsk?, remind: Long?, now: Long, z: ZoneId): Pair<String?, Long?> {
        val every = ask?.every?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it !in STOP } ?: return null to remind
        val base = remind ?: java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate().atTime(9, 0).atZone(z).toInstant().toEpochMilli()
        val rule = com.villamediana.novelo.remind.Repeat.from(every, ask.days, base, z) ?: return null to remind
        // The first time is the reminder said, when it falls on the rule; otherwise the rule's next.
        val first = rule.next(maxOf(base, now + 1) - 1, z) ?: return null to remind
        return com.villamediana.novelo.remind.Repeat.write(rule) to first
    }

    private suspend fun tieTopics(entryId: Long, topics: List<NamedTopic>) {
        for (t in topics) {
            val name = t.name.trim().take(60)
            val norm = Words.of(name).joinToString(" ")
            if (norm.length < 2) continue
            val id = db.graph().topicByNorm(norm)?.id
                ?: db.graph().insertTopic(Topic(name = name, norm = norm, type = t.type.trim().lowercase().ifEmpty { "topic" })).takeIf { it > 0 }
                ?: db.graph().topicByNorm(norm)?.id
                ?: continue
            db.graph().tie(EntryTopic(entryId, id))
        }
    }
}

/** What the model says to stop a repeat. */
private val STOP = setOf("none", "never", "no", "nunca", "nenhum", "")

/** [body] and what is new about it, a blank line between them. */
fun joined(body: String, news: String): String = listOf(body.trim(), news.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

fun Steps.same(a: String, b: String): Boolean {
    val x = Words.of(a).joinToString(" ")
    val y = Words.of(b).joinToString(" ")
    return x.isNotEmpty() && (x == y || (x.length >= 6 && y.contains(x)) || (y.length >= 6 && x.contains(y)))
}
