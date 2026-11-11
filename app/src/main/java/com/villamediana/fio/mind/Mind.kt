package com.villamediana.fio.mind

import com.villamediana.fio.audio.Recorder
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Outcome
import kotlinx.coroutines.CoroutineScope
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

/** What the main screen shows around the button. */
sealed interface MindState {
    data object Idle : MindState

    /** The microphone is open. [locked]: tapped (stays open until tapped again), not held. */
    data class Listening(val startedAt: Long, val locked: Boolean, val focusId: Long?) : MindState

    data class Working(val captureId: Long, val stage: Stage, val text: String, val photo: Boolean = false) : MindState

    data class Answer(
        val captureId: Long,
        val said: String,
        val reply: String,
        val outcomes: List<Outcome>,
        val shown: List<Long>,
        val photo: Boolean,
    ) : MindState

    data class Problem(val captureId: Long?, val need: Need?, val message: String, val said: String? = null) : MindState
}

enum class Stage { HEARING, READING, THINKING, LOOKING }

/** Turns the photo into what the model reads: its text, and the picture itself for a model that sees. */
interface PhotoReader {
    suspend fun text(file: File): String
    suspend fun jpeg(file: File): ByteArray
}

/** Keeps reminders in step with the entries (AlarmManager in the app; a fake in the tests). */
interface ReminderSync {
    suspend fun sync(entryIds: List<Long>)
}

/** Keeps the recording alive with the screen off (a foreground service in the app). */
interface MicHolder {
    fun hold()
    fun release()
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
    private val ids = Json

    init {
        scope.launch {
            for (id in queue) {
                synchronized(queued) { queued.remove(id) }
                runCatching { process(id) }
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
        return true
    }

    /** A hold that turned into a tap: the microphone stays open until the next tap. */
    fun lock() {
        val s = _state.value as? MindState.Listening ?: return
        _state.value = s.copy(locked = true)
    }

    fun stopListening() {
        val s = _state.value as? MindState.Listening ?: return
        val (file, ms) = recorder.stop() ?: run { mic.release(); _state.value = MindState.Idle; return }
        mic.release()
        if (ms < MIN_MS) {
            file.delete()
            _state.value = MindState.Idle
            return
        }
        scope.launch {
            val id = db.captures().insert(
                Capture(createdAt = clock(), source = "voice", audioPath = file.path, durationMs = ms, state = Capture.RECORDED, focusEntryId = s.focusId),
            )
            _state.value = MindState.Working(id, Stage.HEARING, "")
            enqueue(id)
        }
    }

    fun cancelListening() {
        if (_state.value !is MindState.Listening) return
        recorder.stop()?.first?.delete()
        mic.release()
        _state.value = MindState.Idle
    }

    /** A photo taken in the app's camera: read, understood and kept like something said. */
    fun photo(file: File) {
        scope.launch {
            val id = db.captures().insert(Capture(createdAt = clock(), source = "photo", imagePath = file.path, state = Capture.RECORDED))
            _state.value = MindState.Working(id, Stage.READING, "", photo = true)
            enqueue(id)
        }
    }

    fun retry(captureId: Long) {
        scope.launch {
            val c = db.captures().get(captureId) ?: return@launch
            _state.value = MindState.Working(c.id, if (c.transcript == null) (if (c.source == "photo") Stage.READING else Stage.HEARING) else Stage.THINKING, c.transcript.orEmpty(), c.source == "photo")
            enqueue(captureId)
        }
    }

    /** Everything that didn't go through yet (app opening, Ajustes saved, the "esperando" chip). */
    fun retryPending() {
        scope.launch {
            db.captures().unfinished().forEach { enqueue(it.id) }
        }
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
        val fresh = synchronized(queued) { queued.add(id) }
        if (fresh) queue.trySend(id)
    }

    private suspend fun refreshPending() {
        _pending.value = withContext(Dispatchers.IO) { db.captures().unfinished().count { it.state != Capture.TRANSCRIBING && it.state != Capture.THINKING } }
    }

    // ------------------------------------------------------------------ the path

    private suspend fun process(id: Long) {
        var c = db.captures().get(id) ?: return
        if (c.state == Capture.DONE) return
        val photo = c.source == "photo"
        try {
            var said = c.transcript
            if (said == null) {
                c = c.copy(state = Capture.TRANSCRIBING, error = null).also { db.captures().update(it) }
                if (photo) {
                    show(MindState.Working(id, Stage.READING, "", photo = true))
                    said = photos.text(File(c.imagePath!!))
                } else {
                    show(MindState.Working(id, Stage.HEARING, ""))
                    said = hearing.transcribe(File(c.audioPath!!)) { partial -> show(MindState.Working(id, Stage.HEARING, partial)) }
                }
                c = c.copy(transcript = said).also { db.captures().update(it) }
            }
            if (!photo && said.isBlank()) {
                c.audioPath?.let { File(it).delete() }
                db.captures().delete(id)
                show(MindState.Problem(null, null, "Não ouvi nada. Tente de novo, um pouco mais perto."))
                return
            }
            c = c.copy(state = Capture.THINKING).also { db.captures().update(it) }
            show(MindState.Working(id, Stage.THINKING, said, photo))
            val plan = think(c, said, photo)
            val result = keeper.apply(plan, id)
            runCatching { reminders.sync(result.touched) }
            val shown = plan.show.distinct().filter { db.entries().get(it) != null }
            db.captures().update(
                c.copy(
                    state = Capture.DONE,
                    error = null,
                    reply = plan.reply,
                    outcomes = ids.encodeToString(ListSerializer(Outcome.serializer()), result.outcomes),
                    shown = ids.encodeToString(ListSerializer(Long.serializer()), shown),
                ),
            )
            c.audioPath?.let { File(it).delete() }
            show(MindState.Answer(id, said, plan.reply, result.outcomes, shown, photo))
        } catch (e: NotReady) {
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = e.message))
            show(MindState.Problem(id, e.what, e.message ?: "", c.transcript))
        } catch (e: Exception) {
            val msg = when (e) {
                is com.villamediana.fio.ai.ApiException -> "A API respondeu ${e.code}: ${e.message}"
                is IOException -> "Sem conexão agora. Guardei; tento de novo depois."
                else -> e.message ?: e.javaClass.simpleName
            }
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = msg))
            show(MindState.Problem(id, null, msg, c.transcript))
        }
    }

    /** One look at the context; if the model asks to search, one more look with what was found. */
    private suspend fun think(c: Capture, said: String, photo: Boolean): Plan {
        val now = clock()
        val z = zone()
        val all = db.entries().all()
        val recall = Recall(all, db.graph().allTopics(), db.graph().allTies(), db.graph().allLinks())
        val open = db.entries().openTasks()
        val focus = c.focusEntryId?.let { db.entries().get(it) }
        val focusProgress = focus?.let { db.progress().latest(it.id, 5) }.orEmpty()
        val hits = recall.search(said, 12, now).map { it.entry }
        val image = if (photo && thinking.seesImages()) runCatching { photos.jpeg(File(c.imagePath!!)) }.getOrNull() else null
        val heard = if (photo && said.isBlank()) "(nenhum texto legível na foto)" else said

        val first = Brain.context(heard, now, z, open, hits, focus, focusProgress, photo = photo)
        var plan = Brain.parse(thinking.answer(Brain.SYSTEM, first, image)) ?: throw IllegalStateException("O modelo respondeu fora do formato. Tente de novo.")
        if (plan.isLookupOnly) {
            show(MindState.Working(c.id, Stage.LOOKING, said, photo))
            val found = recall.search(plan.search.joinToString(" "), 15, now).map { it.entry }
            val second = Brain.context(heard, now, z, open, found, focus, focusProgress, searched = plan.search, photo = photo)
            plan = Brain.parse(thinking.answer(Brain.SYSTEM, second, image))?.copy(search = emptyList())
                ?: plan.copy(search = emptyList(), reply = plan.reply.ifBlank { "Não achei nada sobre isso." })
        }
        return plan
    }

    private fun show(state: MindState) {
        // A new recording on screen wins over the background work's progress.
        if (_state.value is MindState.Listening) return
        _state.value = state
    }

    companion object {
        /** Shorter than this is a slip of the finger, not a thought. */
        const val MIN_MS = 700L
    }
}
