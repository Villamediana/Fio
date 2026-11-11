package com.villamediana.fio.mind

import com.villamediana.fio.audio.Recorder
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Outcome
import com.villamediana.fio.data.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** What the main screen shows around the button. */
sealed interface MindState {
    data object Idle : MindState

    /** The microphone is open. [locked]: tapped (stays open until tapped again), not held. */
    data class Listening(val startedAt: Long, val locked: Boolean, val focusId: Long?) : MindState

    data class Working(val captureId: Long, val stage: Stage, val text: String, val photo: Boolean = false, val typed: Boolean = false) : MindState

    data class Answer(
        val captureId: Long,
        val said: String,
        val reply: String,
        val outcomes: List<Outcome>,
        val shown: List<Long>,
        val photo: Boolean,
        /** Written instead of said. */
        val typed: Boolean = false,
        /** Photos that answer (capture ids). */
        val photos: List<Long> = emptyList(),
    ) : MindState

    data class Problem(val captureId: Long?, val need: Need?, val message: String, val said: String? = null) : MindState
}

enum class Stage { HEARING, READING, THINKING, LOOKING }

/** Turns the photo into what the model reads: its text, and the picture itself for a model that sees. */
interface PhotoReader {
    suspend fun text(file: File): String

    /** The photo as a JPEG at most [side] px on its long side. */
    suspend fun jpeg(file: File, side: Int = 1600): ByteArray
}

/** Keeps reminders in step with the entries (AlarmManager in the app; a fake in the tests). */
interface ReminderSync {
    suspend fun sync(entryIds: List<Long>)
}

/**
 * Keeps the app alive with the screen off or in the background (a foreground service in the app):
 * the microphone while recording ([hold]/[release]), and the work while something said is being
 * transcribed and organized ([working]), so minimizing the app never stops it halfway.
 */
interface MicHolder {
    fun hold()
    fun release()
    fun working(on: Boolean) {}
}

/**
 * The path of a thought: the button records → the speech becomes text → the model decides what
 * it is (task, note, diary, news about something, a question) → it is kept, reminders are set →
 * the answer goes back to the screen. One capture at a time, in order; nothing said is lost: a
 * capture that couldn't go through (no network, Ajustes missing) stays and is tried again.
 */
class Mind(
    private val scope: CoroutineScope,
    private val db: FioDatabase,
    private val recorder: Recorder,
    private val hearing: Ears,
    private val thinking: Thinker,
    private val keeper: Keeper,
    private val reminders: ReminderSync,
    private val photos: PhotoReader,
    private val mic: MicHolder,
    private val dir: File,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<MindState>(MindState.Idle)
    val state: StateFlow<MindState> = _state

    private val _pending = MutableStateFlow(0)
    /** Captures waiting to go through (shown as a quiet chip; a tap tries them again). */
    val pending: StateFlow<Int> = _pending

    val level: StateFlow<Float> get() = recorder.level

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val queued = HashSet<Long>()
    @Volatile private var current: Long? = null
    private val ids = Json

    /** Captures on their way (being saved, waiting in line, or going through): while > 0 the app keeps working in the background. */
    private val work = AtomicInteger(0)

    private fun begin() { if (work.getAndIncrement() == 0) mic.working(true) }

    private fun end() { if (work.decrementAndGet() == 0) mic.working(false) }

    /** The capture going through now, as a job the "parar" can cancel… */
    @Volatile private var job: Job? = null

    /** …and the stop itself: the line moves on at once, without waiting for the job to notice. */
    @Volatile private var stop: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            for (id in queue) {
                current = id
                synchronized(queued) { queued.remove(id) }
                try {
                    val j = scope.launch { runCatching { process(id) } }
                    val halt = CompletableDeferred<Unit>()
                    job = j
                    stop = halt
                    select<Unit> {
                        j.onJoin {}
                        halt.onAwait {}
                    }
                    if (halt.isCompleted || j.isCancelled) stopped(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    job = null
                    stop = null
                    current = null
                    end()
                }
                refreshPending()
            }
        }
        scope.launch { refreshPending() }
    }

    // ------------------------------------------------------------------ the button

    fun startListening(locked: Boolean, focusId: Long? = null): Boolean {
        if (_state.value is MindState.Listening) return true
        val file = File(dir, "voice-${clock()}.wav")
        if (!recorder.start(file)) {
            _state.value = MindState.Problem(null, null, "Não consegui abrir o microfone.")
            return false
        }
        mic.hold()
        _state.value = MindState.Listening(clock(), locked, focusId)
        // The model on the phone loads while she talks: when she stops, the work starts at once.
        scope.launch {
            runCatching { hearing.warm() }
            runCatching { thinking.warm() }
        }
        return true
    }

    /** A hold that turned into a tap: the microphone stays open until the next tap. */
    fun lock() {
        val s = _state.value as? MindState.Listening ?: return
        _state.value = s.copy(locked = true)
    }

    fun stopListening() {
        val s = _state.value as? MindState.Listening ?: return
        val stopped = recorder.stop()
        if (stopped == null || stopped.second < MIN_MS) {
            stopped?.first?.delete()
            mic.release()
            _state.value = MindState.Idle
            return
        }
        val (file, ms) = stopped
        // From here it is work: the app keeps running for it before the microphone lets go (so the
        // background never has a gap), and the screen leaves "ouvindo" right now — the button is
        // free again and the progress shows (while it said "ouvindo", nothing else could show).
        begin()
        mic.release()
        _state.value = MindState.Working(0L, Stage.HEARING, "")
        scope.launch {
            try {
                val id = db.captures().insert(
                    Capture(createdAt = clock(), source = "voice", audioPath = file.path, durationMs = ms, state = Capture.RECORDED, focusEntryId = s.focusId),
                )
                show(MindState.Working(id, Stage.HEARING, ""))
                enqueue(id)
            } finally {
                end()
            }
        }
    }

    fun cancelListening() {
        if (_state.value !is MindState.Listening) return
        recorder.stop()?.first?.delete()
        mic.release()
        _state.value = MindState.Idle
    }

    /** Something written instead of said: it is already text, so it goes straight to organizing. */
    fun write(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        begin()
        show(MindState.Working(0L, Stage.THINKING, said, typed = true))
        scope.launch {
            try {
                val id = db.captures().insert(Capture(createdAt = clock(), source = Capture.TEXT, transcript = said, state = Capture.RECORDED))
                show(MindState.Working(id, Stage.THINKING, said, typed = true))
                enqueue(id)
            } finally {
                end()
            }
        }
    }

    /** A photo taken in the app's camera: read, understood and kept like something said. */
    fun photo(file: File) = photos(listOf(file to clock()))

    /**
     * Photos ([file] to when it was taken): the camera's one, or many from the gallery — each goes
     * the way of a photo taken here, one after the other, in the order given.
     */
    fun photos(files: List<Pair<File, Long>>) {
        if (files.isEmpty()) return
        begin()
        scope.launch {
            try {
                files.forEachIndexed { i, (file, at) ->
                    val id = db.captures().insert(Capture(createdAt = at, source = "photo", imagePath = file.path, state = Capture.RECORDED))
                    if (i == 0) show(MindState.Working(id, Stage.READING, "", photo = true))
                    enqueue(id)
                }
            } finally {
                end()
            }
        }
    }

    /** Nothing going through or waiting in line, the microphone closed: an import can replace everything. */
    val idle: Boolean
        get() = current == null && synchronized(queued) { queued.isEmpty() } && _state.value !is MindState.Listening && work.get() == 0

    /** Everything was replaced by an import: the screen starts clean and what came unfinished goes on. */
    fun restored() {
        _state.value = MindState.Idle
        retryPending()
        scope.launch { refreshPending() }
    }

    fun retry(captureId: Long) {
        scope.launch {
            var c = db.captures().get(captureId) ?: return@launch
            if (c.tries > 0 && !busy(c.id)) c = c.copy(tries = 0).also { db.captures().update(it) }
            show(MindState.Working(c.id, if (c.transcript == null) (if (c.source == "photo") Stage.READING else Stage.HEARING) else Stage.THINKING, c.transcript ?: c.heard.orEmpty(), c.source == "photo", c.source == Capture.TEXT))
            enqueue(captureId)
        }
    }

    private fun busy(id: Long) = current == id || synchronized(queued) { id in queued }

    /**
     * Everything that didn't go through yet (app opening, Ajustes saved). What was stopped by hand
     * waits for a hand too, and so does what made the app fall several times in a row (going again
     * on its own would only make it fall again): only [byHand] (the "esperando · tentar agora"
     * chip) takes those.
     */
    fun retryPending(byHand: Boolean = false) {
        scope.launch {
            db.captures().unfinished().forEach { c ->
                val waitsForHand = c.state == Capture.STOPPED || c.tries >= MAX_TRIES
                if (waitsForHand && !byHand) return@forEach
                if (byHand && c.tries > 0 && !busy(c.id)) db.captures().update(c.copy(tries = 0))
                enqueue(c.id)
            }
        }
    }

    /**
     * The "parar" under the progress: lets go of the model or the request at once and cancels the
     * work. What was said stays (with its text, if it got that far), to try again or discard.
     */
    fun stopWork() {
        val j = job ?: return
        hearing.stop()
        thinking.stop()
        j.cancel()
        stop?.complete(Unit)
    }

    private suspend fun stopped(id: Long) {
        val c = db.captures().get(id)
        if (c != null && c.state != Capture.DONE) db.captures().update(c.copy(state = Capture.STOPPED, error = STOPPED_MESSAGE, tries = 0))
        if (c?.state == Capture.DONE) return
        // What was heard before the stop stays (and a "Tentar de novo" goes on from there).
        show(MindState.Problem(id, null, STOPPED_MESSAGE, c?.transcript ?: c?.heard))
    }

    /** Drops a capture that couldn't go through (and its audio or photo). */
    fun discard(captureId: Long) {
        scope.launch {
            db.captures().get(captureId)?.let { c ->
                c.audioPath?.let { File(it).delete() }
                c.imagePath?.let { File(it).delete() }
                db.captures().delete(c.id)
            }
            if ((_state.value as? MindState.Problem)?.captureId == captureId) _state.value = MindState.Idle
            refreshPending()
        }
    }

    fun dismiss() {
        if (_state.value is MindState.Answer || _state.value is MindState.Problem) _state.value = MindState.Idle
    }

    private fun enqueue(id: Long) {
        if (current == id) return // already going through
        val fresh = synchronized(queued) { queued.add(id) }
        if (fresh) {
            begin()
            queue.trySend(id)
        }
    }

    /**
     * Waiting = not done and not being handled now. Its saved state doesn't tell: a capture left
     * "transcribing" by an app that was closed halfway is waiting too.
     */
    private suspend fun refreshPending() {
        val busy = synchronized(queued) { queued.toSet() } + listOfNotNull(current)
        _pending.value = withContext(Dispatchers.IO) { db.captures().unfinished().count { it.id !in busy } }
    }

    // ------------------------------------------------------------------ the path

    private suspend fun process(id: Long) {
        // A job stopped by hand may still be finishing something: from then on it says nothing.
        val me = currentCoroutineContext().job
        fun say(st: MindState) { if (me.isActive) show(st) }
        var c = db.captures().get(id) ?: return
        if (c.state == Capture.DONE) return
        val photo = c.source == "photo"
        val typed = c.source == Capture.TEXT
        // A run that starts counts until it ends by itself (an answer, a problem on the screen, a
        // stop); one that never ends is an app that closed or fell in the middle.
        c = c.copy(tries = c.tries + 1).also { db.captures().update(it) }
        try {
            var said = c.transcript
            if (said == null) {
                c = c.copy(state = Capture.TRANSCRIBING, error = null).also { db.captures().update(it) }
                if (photo) {
                    say(MindState.Working(id, Stage.READING, "", photo = true))
                    said = photos.text(File(c.imagePath!!))
                } else {
                    say(MindState.Working(id, Stage.HEARING, c.heard.orEmpty()))
                    // Each piece heard is kept: after a fall it goes on from there, not from the start.
                    said = hearing.transcribe(
                        File(c.audioPath!!),
                        from = Heard(c.heard.orEmpty(), c.heardPieces, c.heardBy),
                        onPiece = { h -> c = c.copy(heard = h.text, heardPieces = h.pieces, heardBy = h.by).also { db.captures().update(it) } },
                    ) { partial -> say(MindState.Working(id, Stage.HEARING, partial)) }
                }
                c = c.copy(transcript = said).also { db.captures().update(it) }
            }
            if (!photo && said.isBlank()) {
                c.audioPath?.let { File(it).delete() }
                db.captures().delete(id)
                say(MindState.Problem(null, null, "Não ouvi nada. Tente de novo, um pouco mais perto."))
                return
            }
            if (photo && said.isBlank() && !thinking.seesImages()) {
                // Nothing to read and a model that can't look: there is nothing to keep.
                c.imagePath?.let { File(it).delete() }
                db.captures().delete(id)
                say(MindState.Problem(null, null, "Não consegui ler nada na foto. Tente de mais perto, com mais luz."))
                return
            }
            c = c.copy(state = Capture.THINKING).also { db.captures().update(it) }
            say(MindState.Working(id, Stage.THINKING, said, photo, typed))
            val plan = think(c, said, photo, typed)
            val result = keeper.apply(plan, id)
            // A photo is the day's too: it shows in the day's diary (begun now when there is none).
            if (photo) diaryFor(c.createdAt)
            runCatching { reminders.sync(result.touched) }
            val shown = plan.show.distinct().filter { db.entries().get(it) != null }
            val shownPhotos = plan.showPhotos.distinct().filter { p -> p != id && db.captures().get(p)?.let { it.source == "photo" && it.imagePath != null } == true }
            // The recording goes, unless the model saw its sound is what she wants kept.
            val keep = plan.keepAudio && c.source == "voice" && c.audioPath?.let { File(it).isFile } == true
            db.captures().update(
                c.copy(
                    state = Capture.DONE,
                    tries = 0,
                    error = null,
                    reply = plan.reply,
                    outcomes = ids.encodeToString(ListSerializer(Outcome.serializer()), result.outcomes),
                    shown = ids.encodeToString(ListSerializer(Long.serializer()), shown),
                    shownPhotos = ids.encodeToString(ListSerializer(Long.serializer()), shownPhotos),
                    // What the photo shows, in her words: what the search finds it by.
                    description = if (photo) plan.photoDescription?.trim()?.takeIf { it.isNotEmpty() } ?: c.description else c.description,
                    audioPath = if (keep) c.audioPath else null,
                    sound = keep,
                ),
            )
            if (!keep) c.audioPath?.let { File(it).delete() }
            say(MindState.Answer(id, said, plan.reply, result.outcomes, shown, photo, typed, shownPhotos))
        } catch (e: NotReady) {
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = e.message, tries = 0))
            say(MindState.Problem(id, e.what, e.message ?: "", c.transcript ?: c.heard))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Anything at all (an error of the model on the phone too) ends here, on the screen:
            // the progress never stays up with nothing going on behind it.
            val msg = when (e) {
                is com.villamediana.fio.ai.ApiException -> "A API respondeu ${e.code}: ${e.message}"
                is IOException -> "Sem conexão agora. Guardei; tento de novo depois."
                else -> e.message ?: e.javaClass.simpleName
            }
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = msg, tries = 0))
            say(MindState.Problem(id, null, msg, c.transcript ?: c.heard))
        }
    }

    /**
     * One look at the context; while the model asks to look something up (words, a stretch of
     * days), another look with everything found so far — [MAX_LOOKUPS] times at most.
     */
    private suspend fun think(c: Capture, said: String, photo: Boolean, typed: Boolean = false): Plan {
        val now = clock()
        val z = zone()
        val all = db.entries().all()
        val topics = db.graph().allTopics()
        val ties = db.graph().allTies()
        val links = db.graph().allLinks()
        // A task already done is over: the model doesn't get it by itself (nothing to change in it,
        // no words spent on it). Only a lookup it asks for, to answer about the past, brings it back.
        val live = all.filterNot { it.kindEnum == Kind.TASK && it.statusEnum == Status.DONE }
        val recall = Recall(all, topics, ties, links)
        val open = db.entries().openTasks()
        val focus = c.focusEntryId?.let { db.entries().get(it) }
        val hits = Recall(live, topics, ties, links).search(said, 12, now).map { it.entry }
        val image = if (photo && thinking.seesImages()) runCatching { photos.jpeg(File(c.imagePath!!), thinking.imageSide()) }.getOrNull() else null
        val heard = if (photo && said.isBlank()) "(nenhum texto legível na foto)" else said

        val recent = live.take(RECENT) // all() comes newest first
        val diary = Brain.todaysDiary(all, now, z)
        // The photos kept, found by what they show — only for what is said or written (a photo
        // being described needs no others), a few at first; more when the model asks to look.
        val pictures = PhotoRecall(db.captures().allPhotos().filter { it.id != c.id }.mapNotNull(Photo::of))
        val photoHits = if (photo) emptyList() else pictures.search(said, FIRST_PHOTO_HITS)

        val taken = if (photo) c.createdAt else null
        val before = before(c, live)
        val first = Brain.context(heard, now, z, open, hits, focus, recent, photo = photo, typed = typed, diary = diary, photos = photoHits, takenAt = taken, before = before)
        var plan = Brain.parse(thinking.answer(Brain.SYSTEM, first, image)) ?: throw IllegalStateException("O modelo respondeu fora do formato. Tente de novo.")
        var found = emptyList<Entry>()
        var foundPhotos = emptyList<Photo>()
        val looked = ArrayList<String>()
        var lookups = 0
        while (plan.isLookupOnly && lookups < MAX_LOOKUPS) {
            lookups++
            if (currentCoroutineContext().isActive) show(MindState.Working(c.id, Stage.LOOKING, said, photo))
            val byWords = if (plan.search.isEmpty()) emptyList() else recall.search(plan.search.joinToString(" "), 15, now).map { it.entry }
            val days = Brain.range(plan.period, z)
            val byTime = if (days == null) emptyList() else all.filter { e -> e.createdAt in days || e.doneAt?.let { it in days } == true || e.dueAt?.let { it in days } == true }
            found = (found + byWords + byTime).distinctBy { it.id }
            foundPhotos = (foundPhotos + (if (plan.search.isEmpty()) emptyList() else pictures.search(plan.search.joinToString(" "), MAX_PHOTO_HITS)) +
                (if (days == null) emptyList() else pictures.during(days, MAX_PHOTO_HITS))).distinctBy { it.id }
            looked += Brain.looked(plan)
            val next = Brain.context(heard, now, z, open, found, focus, recent, looked = looked.joinToString(" · "), photo = photo, typed = typed, diary = diary, photos = foundPhotos, takenAt = taken, before = before)
            plan = Brain.parse(thinking.answer(Brain.SYSTEM, next, image))
                ?: plan.copy(search = emptyList(), period = null, reply = plan.reply.ifBlank { NOTHING_FOUND })
        }
        // Still asking after the last look: it answers with what it has.
        if (plan.isLookupOnly) plan = plan.copy(reply = plan.reply.ifBlank { NOTHING_FOUND })
        return plan.copy(search = emptyList(), period = null)
    }

    /** The previous turn of the conversation: what she said then, the answer, and the items it touched (as they are now, among [all]). */
    private suspend fun before(c: Capture, all: List<Entry>): Before? {
        val last = db.captures().lastDone(c.id) ?: return null
        val byId = all.associateBy { it.id }
        val items = runCatching { ids.decodeFromString(ListSerializer(Outcome.serializer()), last.outcomes) }.getOrDefault(emptyList())
            .filter { it.action != Outcome.DELETED }
            .mapNotNull { o -> byId[o.entryId]?.let { it to WHAT[o.action].orEmpty().ifEmpty { "mudou" } } }
        return Before(last.createdAt, last.transcript.orEmpty(), last.reply.orEmpty(), photo = last.source == "photo", items = items)
    }

    /** The diary of the day [at] falls in, made (named by the date, nothing written yet) when the day has none. */
    private suspend fun diaryFor(at: Long) {
        val day = DayDiary.bounds(at, zone())
        if (db.entries().diariesBetween(day.first, day.last).isNotEmpty()) return
        db.entries().insert(Entry(kind = Kind.DIARY.key, title = DayDiary.title(at, zone()), createdAt = at, updatedAt = clock()))
    }

    private fun show(state: MindState) {
        // A new recording on screen wins over the background work's progress.
        if (_state.value is MindState.Listening) return
        _state.value = state
    }

    companion object {
        /** Shorter than this is a slip of the finger, not a thought. */
        const val MIN_MS = 700L

        /** Runs that never ended (the app fell in the middle) before it waits for a hand. */
        const val MAX_TRIES = 3

        /** How many times the model may ask to look something up for one thing said. */
        const val MAX_LOOKUPS = 2

        /** Photos found by what they show that go to the model at most: on the first look, and when it asks to look. */
        const val FIRST_PHOTO_HITS = 4
        const val MAX_PHOTO_HITS = 8

        /** The last things kept, shown to the model so "aquilo que acabei de falar" has a face. */
        const val RECENT = 5

        private const val NOTHING_FOUND = "Não achei nada sobre isso."

        private val WHAT = mapOf(Outcome.CREATED to "criado", Outcome.UPDATED to "mudou", Outcome.DONE to "feito")

        private const val STOPPED_MESSAGE = "Parei. O que você disse ficou guardado."
    }
}
