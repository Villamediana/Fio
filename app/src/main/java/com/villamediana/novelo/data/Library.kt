package com.villamediana.novelo.data

import androidx.room.withTransaction
import com.villamediana.novelo.mind.Recall
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Steps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** What the screens do to entries by hand (tap the circle, change the status, edit, delete). */
class Library(private val db: NoveloDatabase, private val reminders: ReminderSync, private val clock: () -> Long = System::currentTimeMillis) {
    fun all(): Flow<List<Entry>> = db.entries().observeAll()
    fun entry(id: Long): Flow<Entry?> = db.entries().observe(id)

    suspend fun get(id: Long): Entry? = db.entries().get(id)
    suspend fun capture(id: Long): Capture? = db.captures().get(id)

    /** The photos taken in [day] (first to last ms), in the order they were taken. */
    fun photos(day: LongRange): Flow<List<com.villamediana.novelo.mind.Photo>> =
        db.captures().observePhotos(day.first, day.last).map { list -> list.mapNotNull(com.villamediana.novelo.mind.Photo::of) }

    /** The photos [entryId] holds (sent with what she said; see [Attached]), in order. */
    fun photosOf(entryId: Long): Flow<List<com.villamediana.novelo.mind.Photo>> =
        db.captures().observeAttachments()
            .map { list -> Attached.photosOf(entryId, list).filter { java.io.File(it.imagePath!!).isFile }.mapNotNull(com.villamediana.novelo.mind.Photo::of) }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** The sounds kept that go with [entry] (see [Sounds]), the oldest first. */
    fun sounds(entry: Entry): Flow<List<Capture>> =
        db.captures().observeSounds()
            .map { list -> list.filter { Sounds.goesWith(it, entry) && java.io.File(it.audioPath!!).isFile } }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** Every photo kept, the newest first. */
    fun allPhotos(): Flow<List<com.villamediana.novelo.mind.Photo>> =
        db.captures().observeAllPhotos().map { list -> list.mapNotNull(com.villamediana.novelo.mind.Photo::of) }

    /** Whether [entryId] is the day's diary (the newest of [day]) — the one that shows the day's photos. */
    suspend fun isDayDiary(entryId: Long, day: LongRange): Boolean = db.entries().diariesBetween(day.first, day.last).firstOrNull()?.id == entryId

    /**
     * One diary a day: days with more than one (from before the rule, or a model that made another)
     * become one — the first of the day, with what the others told that it didn't, their topics
     * and links. Runs on opening and after an import; a day already single is left alone.
     */
    suspend fun joinDayDiaries(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) = db.withTransaction {
        val diaries = db.entries().all().filter { it.kindEnum == Kind.DIARY }
        val days = diaries.groupBy { com.villamediana.novelo.mind.DayDiary.bounds(it.createdAt, zone).first }.values.filter { it.size > 1 }
        if (days.isEmpty()) return@withTransaction
        val ties = db.graph().allTies()
        val links = db.graph().allLinks()
        for (list in days) {
            val ordered = list.sortedWith(compareBy<Entry>({ it.createdAt }, { it.id }))
            val keep = ordered.first()
            val others = ordered.drop(1)
            var body = keep.body
            others.forEach { body = com.villamediana.novelo.mind.DayDiary.merge(body, it.body) }
            val title = if (keep.body.isBlank()) others.firstOrNull { it.body.isNotBlank() }?.title ?: keep.title else keep.title
            db.entries().update(keep.copy(title = title, body = body, updatedAt = maxOf(keep.updatedAt, others.maxOf { it.updatedAt })))
            val gone = others.map { it.id }.toSet()
            ties.filter { it.entryId in gone }.forEach { db.graph().tie(EntryTopic(keep.id, it.topicId)) }
            links.filter { it.a in gone || it.b in gone }.forEach { l ->
                val a = if (l.a in gone) keep.id else l.a
                val b = if (l.b in gone) keep.id else l.b
                if (a != b) db.graph().link(Link(minOf(a, b), maxOf(a, b)))
            }
            gone.forEach { db.entries().delete(it) }
        }
    }

    /** A photo a quarter turn to the right (kept with it, the file untouched). */
    suspend fun turnPhoto(captureId: Long) {
        val c = db.captures().get(captureId) ?: return
        db.captures().update(c.copy(turn = (c.turn + 90) % 360))
    }

    /** Takes a photo out of Novelo, its file too. What was kept from it (a task, a note) stays. */
    suspend fun removePhoto(captureId: Long) {
        val c = db.captures().get(captureId)?.takeIf { it.source == "photo" } ?: return
        db.captures().delete(c.id)
        c.imagePath?.let { java.io.File(it).delete() }
    }

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
