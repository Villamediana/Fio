package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.EntryTopic
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Link
import com.villamediana.fio.data.Outcome
import com.villamediana.fio.data.Progress
import com.villamediana.fio.data.Status
import com.villamediana.fio.data.Step
import com.villamediana.fio.data.Topic
import androidx.room.withTransaction
import java.time.ZoneId

/** Writes a [Plan] into the database: new entries, their topics and links, and changes. */
class Keeper(
    private val db: FioDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What happened, in order; entries whose reminders changed are in [touched]. */
    data class Result(val outcomes: List<Outcome>, val touched: List<Long>)

    suspend fun apply(plan: Plan, captureId: Long?): Result = db.withTransaction {
        val now = clock()
        val z = zone()
        val outcomes = mutableListOf<Outcome>()
        val touched = mutableListOf<Long>()
        val known = HashSet<Long>()

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
                    if (e != today) {
                        db.entries().update(e.copy(updatedAt = now))
                        outcomes += Outcome(today.id, Outcome.UPDATED)
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
            val entry = Entry(
                kind = kind.key,
                title = item.title.trim().take(140),
                body = item.body.trim(),
                status = status?.key,
                steps = Steps.write(item.steps.filter { it.isNotBlank() }.map { Step(it.trim()) }),
                tags = Tags.write(item.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)),
                dueAt = due?.first,
                dueHasTime = due?.second ?: false,
                remindAt = Brain.remindTime(item.remindAt, z)?.takeIf { it > now },
                checkInAt = if (kind == Kind.TASK) Brain.remindTime(item.checkInAt, z)?.takeIf { it > now } else null,
                sensitive = item.sensitive,
                createdAt = now,
                updatedAt = now,
                doneAt = if (status == Status.DONE) now else null,
                captureId = captureId,
            )
            val id = db.entries().insert(entry)
            known += id
            tieTopics(id, item.entities)
            item.related.filter { it != id && db.entries().get(it) != null }.forEach { other ->
                db.graph().link(Link(minOf(id, other), maxOf(id, other)))
            }
            outcomes += Outcome(id, if (status == Status.DONE) Outcome.DONE else Outcome.CREATED)
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
            // What she said changed what the item is: its text written again.
            change.body?.takeIf { it.isNotBlank() }?.let { e = e.copy(body = it.trim()) }
            // One text per item: what is new joins it (no history kept apart that says the same again).
            change.append?.takeIf { it.isNotBlank() }?.let { e = e.copy(body = if (e.kindEnum == Kind.DIARY) DayDiary.merge(e.body, it) else joined(e.body, it)) }
            Brain.time(change.due, z)?.let { (ms, hasTime) -> e = e.copy(dueAt = ms, dueHasTime = hasTime) }
            Brain.remindTime(change.remindAt, z)?.takeIf { it > now }?.let { e = e.copy(remindAt = it) }
            Brain.remindTime(change.checkInAt, z)?.takeIf { it > now }?.let { e = e.copy(checkInAt = it) }
            // Done: nothing left to ask about.
            if (e.statusEnum == Status.DONE) e = e.copy(checkInAt = null, remindAt = e.remindAt?.takeIf { old.statusEnum == Status.DONE })
            val changed = e != old
            if (changed) db.entries().update(e.copy(updatedAt = now))
            if (newStatus != null && newStatus != old.statusEnum) {
                db.progress().insert(Progress(entryId = e.id, at = now, text = "", status = newStatus.key))
            }
            tieTopics(e.id, change.entities)
            if (changed) {
                outcomes += Outcome(e.id, if (e.statusEnum == Status.DONE && old.statusEnum != Status.DONE) Outcome.DONE else Outcome.UPDATED)
                if (e.remindAt != old.remindAt || e.checkInAt != old.checkInAt) touched += e.id
            }
        }
        if (plan.update.any { it.delete }) db.graph().pruneTopics()
        Result(outcomes.distinctBy { it.entryId to it.action }, touched.distinct())
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

/** [body] and what is new about it, a blank line between them. */
fun joined(body: String, news: String): String = listOf(body.trim(), news.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

fun Steps.same(a: String, b: String): Boolean {
    val x = Words.of(a).joinToString(" ")
    val y = Words.of(b).joinToString(" ")
    return x.isNotEmpty() && (x == y || (x.length >= 6 && y.contains(x)) || (y.length >= 6 && x.contains(y)))
}
