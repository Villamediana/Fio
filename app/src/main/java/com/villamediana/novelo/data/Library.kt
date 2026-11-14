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
        loose(db.captures().observePhotos(day.first, day.last))

    /** [photos] without those that live inside a note or a sensitive item ([Attached.keptInside]). */
    private fun loose(photos: Flow<List<Capture>>): Flow<List<com.villamediana.novelo.mind.Photo>> =
        kotlinx.coroutines.flow.combine(photos, db.captures().observeAttachments(), db.captures().observeAllPhotos(), db.entries().observeAll()) { list, attached, all, entries ->
            val inside = Attached.keptInside((attached + all).distinctBy { it.id }, entries)
            list.filter { it.id !in inside }.mapNotNull(com.villamediana.novelo.mind.Photo::of)
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    /** The photos [entryId] holds (sent with what she said; see [Attached]), in order. */
    fun photosOf(entryId: Long): Flow<List<com.villamediana.novelo.mind.Photo>> =
        kotlinx.coroutines.flow.combine(db.captures().observeAttachments(), db.captures().observeAllPhotos()) { attached, all -> (attached + all).distinctBy { it.id } }
            .map { list -> Attached.photosOf(entryId, list).filter { java.io.File(it.imagePath!!).isFile }.mapNotNull(com.villamediana.novelo.mind.Photo::of) }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** The sounds kept that go with [entry] (see [Sounds]), the oldest first. */
    fun sounds(entry: Entry): Flow<List<Capture>> =
        db.captures().observeSounds()
            .map { list -> list.filter { Sounds.goesWith(it, entry) && java.io.File(it.audioPath!!).isFile } }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * Every file kept, the newest first, with where it lives ([KeptFile.home]: its item, or null —
     * the day's diary). [withSensitive] false: those living in something sensitive are left out.
     */
    fun files(withSensitive: Boolean = true): Flow<List<KeptFile>> =
        kotlinx.coroutines.flow.combine(db.captures().observeFiles(), db.entries().observeAll()) { list, entries ->
            val byId = entries.associateBy { it.id }
            val homes = Attached.fileHomes(list, byId.keys)
            list.filter { it.filePath != null }.mapNotNull { c ->
                val home = homes[c.id]?.let(byId::get)
                if (!withSensitive && home?.sensitive == true) null else KeptFile.of(c, home)
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    /** The files [entryId] holds, in the order they came. */
    fun filesOf(entryId: Long): Flow<List<KeptFile>> =
        files().map { list -> list.filter { it.home?.id == entryId }.sortedWith(compareBy<KeptFile>({ it.at }, { it.id })) }

    /** The loose files of [day] (in no item): they live in the day's diary, in the order they came. */
    fun dayFiles(day: LongRange): Flow<List<KeptFile>> =
        files().map { list -> list.filter { it.home == null && it.at in day }.sortedWith(compareBy<KeptFile>({ it.at }, { it.id })) }

    /** Every photo kept, the newest first. */
    fun allPhotos(): Flow<List<com.villamediana.novelo.mind.Photo>> =
        db.captures().observeAllPhotos().map { list -> list.mapNotNull(com.villamediana.novelo.mind.Photo::of) }

    /** The gallery's photos (and the search's): all but those that live inside a note or a sensitive item. */
    fun loosePhotos(): Flow<List<com.villamediana.novelo.mind.Photo>> = loose(db.captures().observeAllPhotos())

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

    /** The photos and the files (capture ids) that live in [entryId] and in no other item. */
    suspend fun onlyIn(entryId: Long): Pair<List<Long>, List<Long>> {
        val all = db.backup().captures()
        val byId = all.associateBy { it.id }
        val photos = all.filter { it.source == "photo" && it.imagePath != null && Attached.homesOf(it, byId) == setOf(entryId) }.map { it.id }
        val files = all.filter { it.filePath != null && Attached.fileHomesOf(it, byId) == setOf(entryId) }.map { it.id }
        return photos to files
    }

    /** Takes a file out of Novelo — from its item, the day's diary and the Arquivos tab — and off the phone. */
    suspend fun removeFile(captureId: Long) {
        val c = db.captures().get(captureId)?.takeIf { it.filePath != null } ?: return
        db.captures().delete(c.id)
        c.filePath?.let { java.io.File(it).delete(); java.io.File(it).parentFile?.takeIf { d -> d.name.contains('-') && d.list().isNullOrEmpty() }?.delete() }
    }

    /** Takes a photo out of Novelo, its file too. What was kept from it (a task, a note) stays. */
    suspend fun removePhoto(captureId: Long) {
        val c = db.captures().get(captureId)?.takeIf { it.source == "photo" } ?: return
        db.captures().delete(c.id)
        c.imagePath?.let { java.io.File(it).delete() }
    }

    /** A task or note made by hand, still empty (its screen opens on the title); its id. */
    suspend fun create(kind: Kind): Long {
        val now = clock()
        return db.entries().insert(Entry(kind = kind.key, title = "", status = if (kind == Kind.TASK) Status.TODO.key else null, createdAt = now, updatedAt = now))
    }

    /**
     * An item made by hand, left: with nothing written it isn't kept; a diary written without a
     * title takes its day's ("Sexta-feira, 13 de novembro"), like the others.
     */
    suspend fun dropIfEmpty(id: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val e = db.entries().get(id) ?: return
        if (e.title.isBlank() && e.body.isBlank() && Steps.read(e.steps).isEmpty()) db.entries().delete(id)
        else if (e.kindEnum == Kind.DIARY && e.title.isBlank()) db.entries().update(e.copy(title = com.villamediana.novelo.mind.DayDiary.title(e.createdAt, zone)))
    }

    // The medicines (the Remédios tab), by hand.

    fun meds(): Flow<List<Med>> = db.meds().observeAll()
    fun med(id: Long): Flow<Med?> = db.meds().observe(id)

    /** The times of medicines taken in [range] (a day). */
    fun taken(range: LongRange): Flow<List<MedTaken>> = db.meds().observeTaken(range.first, range.last)

    /** Every time of a medicine taken (the doses gone of each treatment). */
    fun allTaken(): Flow<List<MedTaken>> = db.meds().observeAllTaken()

    /** A new one, made by hand (it opens on its name): from now on, no times yet. */
    suspend fun newMed(): Long {
        val now = clock()
        // From today (the whole day: a time already gone today shows, to tick).
        return db.meds().insert(Med(name = "", startsAt = com.villamediana.novelo.meds.Doses.startOfDay(now, java.time.ZoneId.systemDefault()), createdAt = now, updatedAt = now))
    }

    /**
     * Before 1.64.1 a medicine said without a day (or "hoje" with no hour) began the minute it was written,
     * and today's time already gone was lost: those begin at the start of that day. Runs on opening; one
     * already right is left alone.
     */
    suspend fun fixMedStarts(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val wrong = db.meds().all().filter { it.startsAt / 60_000L == it.createdAt / 60_000L && it.startsAt != com.villamediana.novelo.meds.Doses.startOfDay(it.startsAt, zone) }
        if (wrong.isEmpty()) return
        wrong.forEach { db.meds().update(it.copy(startsAt = com.villamediana.novelo.meds.Doses.startOfDay(it.startsAt, zone))) }
        reminders.syncMeds()
    }

    /** A medicine set by hand: kept, and its reminders go again. */
    suspend fun saveMed(m: Med) {
        val old = db.meds().get(m.id) ?: return
        if (old == m) return
        db.meds().update(m.copy(updatedAt = clock()))
        reminders.syncMeds()
    }

    suspend fun deleteMed(id: Long) {
        db.meds().delete(id)
        reminders.syncMeds()
    }

    /** Made by hand and left with no name: it isn't kept. */
    suspend fun dropMedIfEmpty(id: Long) {
        val m = db.meds().get(id) ?: return
        if (m.name.isBlank()) deleteMed(id)
    }

    /** One time of a medicine taken (or given) — or, tapped again, not. */
    suspend fun toggleTaken(medId: Long, at: Long) {
        if (db.meds().takenBetween(at, at).any { it.medId == medId }) db.meds().untake(medId, at)
        else {
            db.meds().take(MedTaken(medId, at, clock()))
            reminders.medTaken(at)
        }
    }

    /** Tasks put in this order by hand (held and dragged in the list): each keeps its place. */
    suspend fun reorder(ids: List<Long>) = db.withTransaction {
        ids.forEachIndexed { i, id -> db.entries().get(id)?.let { e -> if (e.position != i.toLong()) db.entries().update(e.copy(position = i.toLong())) } }
    }

    /** A step moved by hand from [from] to [to]. */
    suspend fun moveStep(id: Long, from: Int, to: Int) {
        val e = db.entries().get(id) ?: return
        val steps = Steps.read(e.steps).toMutableList()
        if (from !in steps.indices || to !in steps.indices || from == to) return
        steps.add(to, steps.removeAt(from))
        db.entries().update(e.copy(steps = Steps.write(steps), updatedAt = clock()))
    }

    suspend fun recall(): Recall = Recall(db.entries().all(), db.graph().allTopics(), db.graph().allTies(), db.graph().allLinks())

    /** A task in a category (one of the ones there are, written another way, is that one); null: none. */
    suspend fun setCategory(id: Long, name: String?) {
        val e = db.entries().get(id) ?: return
        val c = Categories.pick(name, db.entries().categories())
        if (c != e.category) db.entries().setCategory(id, c)
    }

    /**
     * A category renamed on every task that has it — or, when [to] is one that already exists
     * (written any way), joined into it. Null [to]: the tasks are left with none.
     */
    suspend fun renameCategory(from: String, to: String?) = db.withTransaction {
        val others = db.entries().categories().filter { it != from }
        val target = to?.let { Categories.pick(it, others) }
        if (target != from) db.entries().renameCategory(from, target)
    }

    suspend fun setStatus(id: Long, status: Status, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val e = db.entries().get(id) ?: return
        if (e.statusEnum == status) return
        val now = clock()
        db.entries().update(
            // Done: nothing left to remind — unless it repeats: then this time is done and it comes back.
            if (status == Status.DONE) com.villamediana.novelo.remind.Repeat.done(e, now, zone)
            else e.copy(status = status.key, doneAt = null, updatedAt = now),
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

    /** A step's words written again (by hand). */
    suspend fun editStep(id: Long, index: Int, text: String) {
        val e = db.entries().get(id) ?: return
        val steps = Steps.read(e.steps).toMutableList()
        val t = text.trim()
        if (index !in steps.indices || t.isEmpty() || steps[index].text == t) return
        steps[index] = steps[index].copy(text = t)
        db.entries().update(e.copy(steps = Steps.write(steps), updatedAt = clock()))
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
        // Emptied by hand stays as it was; one made by hand starts empty and is written now.
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

    /** Its reminder at [at] (null: none), coming back as [repeat] says (null: once). */
    suspend fun setReminder(id: Long, at: Long?, repeat: com.villamediana.novelo.remind.Repeat? = null) {
        val e = db.entries().get(id) ?: return
        db.entries().update(e.copy(remindAt = at, repeat = if (at == null) null else com.villamediana.novelo.remind.Repeat.write(repeat), updatedAt = clock()))
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

    /**
     * Deletes it (topics left with nothing are pruned), and the photos and files that lived only in
     * it go with it (out of the gallery, the Arquivos tab and the phone too). Returns it for "Desfazer".
     */
    suspend fun delete(id: Long): Entry? {
        val e = db.entries().get(id) ?: return null
        val topics = db.graph().topicsOf(id)
        val (photos, files) = onlyIn(id)
        db.entries().delete(id)
        photos.forEach { removePhoto(it) }
        files.forEach { removeFile(it) }
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

/** A file kept: its copy in the app, its own name, type, size, what it is (the model's words) and the item it lives in (null: the day's diary). */
data class KeptFile(
    val id: Long,
    val path: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val at: Long,
    val description: String?,
    val home: Entry?,
) {
    companion object {
        fun of(c: Capture, home: Entry?): KeptFile? = c.filePath?.let {
            KeptFile(c.id, it, c.fileName.orEmpty().ifBlank { java.io.File(it).name }, c.fileMime, c.fileSize, c.createdAt, c.description?.takeIf(String::isNotBlank), home)
        }
    }
}
