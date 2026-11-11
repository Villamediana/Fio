package com.villamediana.fio.data

import com.villamediana.fio.mind.Recall
import com.villamediana.fio.mind.ReminderSync
import com.villamediana.fio.mind.Steps
import kotlinx.coroutines.flow.Flow

/** What the screens do to entries by hand (tap the circle, change the status, edit, delete). */
class Library(private val db: FioDatabase, private val reminders: ReminderSync, private val clock: () -> Long = System::currentTimeMillis) {
    fun all(): Flow<List<Entry>> = db.entries().observeAll()
    fun entry(id: Long): Flow<Entry?> = db.entries().observe(id)
    fun topicsOf(id: Long): Flow<List<Topic>> = db.graph().observeTopicsOf(id)
    fun progressOf(id: Long): Flow<List<Progress>> = db.progress().observe(id)

    suspend fun get(id: Long): Entry? = db.entries().get(id)
    suspend fun capture(id: Long): Capture? = db.captures().get(id)

    suspend fun recall(): Recall = Recall(db.entries().all(), db.graph().allTopics(), db.graph().allTies(), db.graph().allLinks())

    suspend fun setStatus(id: Long, status: Status) {
        val e = db.entries().get(id) ?: return
        if (e.statusEnum == status) return
        val now = clock()
        db.entries().update(
            e.copy(
                status = status.key,
                doneAt = if (status == Status.DONE) now else null,
                checkInAt = if (status == Status.DONE) null else e.checkInAt,
                remindAt = if (status == Status.DONE) null else e.remindAt, // done: nothing left to remind
                updatedAt = now,
            ),
        )
        db.progress().insert(Progress(entryId = id, at = now, text = "", status = status.key))
        reminders.sync(listOf(id))
    }

    suspend fun toggleDone(id: Long) {
        val e = db.entries().get(id) ?: return
        setStatus(id, if (e.statusEnum == Status.DONE) Status.TODO else Status.DONE)
    }

    suspend fun toggleStep(id: Long, index: Int) {
        val e = db.entries().get(id) ?: return
        val steps = Steps.read(e.steps).toMutableList()
        if (index !in steps.indices) return
        steps[index] = steps[index].copy(done = !steps[index].done)
        db.entries().update(e.copy(steps = Steps.write(steps), updatedAt = clock()))
    }

    suspend fun addStep(id: Long, text: String) {
        val e = db.entries().get(id) ?: return
        val t = text.trim().takeIf { it.isNotEmpty() } ?: return
        db.entries().update(e.copy(steps = Steps.write(Steps.read(e.steps) + Step(t)), updatedAt = clock()))
    }

    suspend fun removeStep(id: Long, index: Int) {
        val e = db.entries().get(id) ?: return
        val steps = Steps.read(e.steps).toMutableList()
        if (index !in steps.indices) return
        steps.removeAt(index)
        db.entries().update(e.copy(steps = Steps.write(steps), updatedAt = clock()))
    }

    suspend fun edit(id: Long, title: String, body: String) {
        val e = db.entries().get(id) ?: return
        val t = title.trim().ifEmpty { e.title }
        if (t == e.title && body == e.body) return
        db.entries().update(e.copy(title = t, body = body, updatedAt = clock()))
    }

    suspend fun setKind(id: Long, kind: Kind) {
        val e = db.entries().get(id) ?: return
        if (e.kindEnum == kind) return
        db.entries().update(
            e.copy(
                kind = kind.key,
                status = if (kind == Kind.TASK) (e.status ?: Status.TODO.key) else null,
                checkInAt = if (kind == Kind.TASK) e.checkInAt else null,
                updatedAt = clock(),
            ),
        )
        reminders.sync(listOf(id))
    }

    suspend fun setReminder(id: Long, at: Long?) {
        val e = db.entries().get(id) ?: return
        db.entries().update(e.copy(remindAt = at, updatedAt = clock()))
        reminders.sync(listOf(id))
    }

    suspend fun setDue(id: Long, at: Long?, hasTime: Boolean) {
        val e = db.entries().get(id) ?: return
        db.entries().update(e.copy(dueAt = at, dueHasTime = at != null && hasTime, updatedAt = clock()))
    }

    suspend fun setSensitive(id: Long, sensitive: Boolean) {
        val e = db.entries().get(id) ?: return
        db.entries().update(e.copy(sensitive = sensitive, updatedAt = clock()))
    }

    /** Deletes it (topics left with nothing are pruned). Returns it for "Desfazer". */
    suspend fun delete(id: Long): Entry? {
        val e = db.entries().get(id) ?: return null
        val topics = db.graph().topicsOf(id)
        db.entries().delete(id)
        db.graph().pruneTopics()
        reminders.sync(listOf(id))
        deleted[id] = e to topics
        return e
    }

    private val deleted = HashMap<Long, Pair<Entry, List<Topic>>>()

    suspend fun undoDelete(id: Long) {
        val (e, topics) = deleted.remove(id) ?: return
        val newId = db.entries().insert(e.copy(id = 0))
        topics.forEach { t ->
            val tid = db.graph().topicByNorm(t.norm)?.id ?: db.graph().insertTopic(t.copy(id = 0))
            if (tid > 0) db.graph().tie(EntryTopic(newId, tid))
        }
        reminders.sync(listOf(newId))
    }

    fun topTopics(limit: Int): Flow<List<TopicCount>> = db.graph().observeTopTopics(limit)
    fun topicPairs(): Flow<List<TopicPair>> = db.graph().observePairs()
    suspend fun entriesOfTopic(topicId: Long): List<Entry> = db.entries().getAll(db.graph().entriesOfTopic(topicId))
    suspend fun topic(id: Long): Topic? = db.graph().topic(id)
}
