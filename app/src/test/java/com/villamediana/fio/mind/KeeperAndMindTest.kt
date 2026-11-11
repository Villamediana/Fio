package com.villamediana.fio.mind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.fio.audio.Recorder
import com.villamediana.fio.audio.Wav
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Outcome
import com.villamediana.fio.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeeperAndMindTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FioDatabase
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 11, 11, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Before fun open() { db = FioDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    private fun keeper() = Keeper(db, zone = { zone }, clock = { now })

    @Test
    fun createsWithTopicsLinksAndReminders() = runBlocking {
        val old = db.entries().insert(Entry(kind = "note", title = "Telefone do Pedro", createdAt = now - 1000, updatedAt = now - 1000))
        val plan = Brain.parse(
            """{"reply":"ok","create":[{"kind":"task","title":"Ligar pro Pedro","body":"sobre o contrato","due":"2026-11-12","remind_at":"2026-11-12T09:00","check_in_at":"2026-11-14","steps":["achar contrato","ligar"],"entities":[{"name":"Pedro","type":"person"},{"name":"contrato","type":"topic"}],"related":[$old, 999]}]}""",
        )!!
        val r = keeper().apply(plan, captureId = null)
        val id = r.outcomes.single().entryId
        assertEquals(Outcome.CREATED, r.outcomes.single().action)
        assertEquals(listOf(id), r.touched)
        val e = db.entries().get(id)!!
        assertEquals(Status.TODO, e.statusEnum)
        assertEquals(2, Steps.read(e.steps).size)
        assertTrue(!e.dueHasTime)
        assertNotNull(e.remindAt)
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(e.checkInAt!!), zone))
        assertEquals(listOf("contrato", "Pedro"), db.graph().topicsOf(id).map { it.name })
        assertEquals(1, db.graph().allLinks().size) // 999 doesn't exist
    }

    @Test
    fun updatesStatusStepsAndTheText() = runBlocking {
        val id = db.entries().insert(
            Entry(kind = "task", title = "Contrato", body = "Fechar com o fornecedor novo.", status = "doing", steps = Steps.write(listOf(com.villamediana.fio.data.Step("pedir orçamento"), com.villamediana.fio.data.Step("assinar"))), checkInAt = now + 5000, createdAt = now, updatedAt = now),
        )
        val k = keeper()
        val r1 = k.apply(Brain.parse("""{"update":[{"id":$id,"steps_done":["pedir orcamento"],"append":"O fornecedor mandou o orçamento."}]}""")!!, null)
        assertEquals(Outcome.UPDATED, r1.outcomes.single().action)
        val e1 = db.entries().get(id)!!
        assertTrue(Steps.read(e1.steps)[0].done)
        // One text: the news joins it, nothing kept apart that says it again.
        assertEquals("Fechar com o fornecedor novo.\n\nO fornecedor mandou o orçamento.", e1.body)
        assertTrue(db.progress().latest(id, 5).isEmpty())

        val r2 = k.apply(Brain.parse("""{"update":[{"id":$id,"status":"done"}]}""")!!, null)
        assertEquals(Outcome.DONE, r2.outcomes.single().action)
        val e2 = db.entries().get(id)!!
        assertEquals(Status.DONE, e2.statusEnum)
        assertNull(e2.checkInAt) // nothing left to ask about
        assertEquals("done", db.progress().latest(id, 1).single().status)
        assertEquals("", db.progress().latest(id, 1).single().text)
    }

    @Test
    fun aDayToldThatIsAlsoNewsOfATaskGoesToTheTaskAndANewParagraphOfTodaysDiary() = runBlocking {
        val task = db.entries().insert(Entry(kind = "task", title = "Recibir a Alma en el aeropuerto", body = "Llega a São Paulo a las 9:00.", status = "doing", createdAt = now - 3000, updatedAt = now - 3000))
        val diary = db.entries().insert(Entry(kind = "diary", title = "Mi martes", body = "Me desperté temprano para ir al aeropuerto.", createdAt = now - 2000, updatedAt = now - 2000))
        // What the prompt asks for a story of the day that is also news: the task gets what is new
        // for it; today's diary gets the story as a paragraph of its own, nothing of it rewritten.
        val plan = Brain.parse(
            """{"reply":"Guardé en tu diario y en la tarea.","update":[{"id":$task,"append":"Ya llegué a la estación Luz; espero el Expreso Aeropuerto."},{"id":$diary,"append":"Ya llegué a la estación Luz y estoy esperando el Expreso Aeropuerto. Se sentó un señor a mi lado y comenzó a llamarme, pero por suerte estoy de audífonos."}]}""",
        )!!
        val r = keeper().apply(plan, captureId = null)
        assertEquals(listOf(Outcome.UPDATED, Outcome.UPDATED), r.outcomes.map { it.action })
        assertEquals("Llega a São Paulo a las 9:00.\n\nYa llegué a la estación Luz; espero el Expreso Aeropuerto.", db.entries().get(task)!!.body)
        val day = db.entries().get(diary)!!
        assertEquals("Mi martes", day.title)
        assertEquals(
            "Me desperté temprano para ir al aeropuerto.\n\nYa llegué a la estación Luz y estoy esperando el Expreso Aeropuerto. Se sentó un señor a mi lado y comenzó a llamarme, pero por suerte estoy de audífonos.",
            day.body,
        )
    }

    @Test
    fun theModelSeesTodaysDiaryToGoOnInIt() = runBlocking {
        db.entries().insert(Entry(kind = "diary", title = "Ayer", body = "Día largo.", createdAt = now - 86_400_000, updatedAt = now - 86_400_000))
        val today = db.entries().insert(Entry(kind = "diary", title = "Mi martes", body = "Me desperté temprano.", createdAt = now - 2000, updatedAt = now - 2000))
        val ears = object : Ears {
            override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = "Ya llegué a la estación Luz"
        }
        val thinker = object : Thinker {
            val prompts = mutableListOf<String>()
            override suspend fun seesImages() = false
            override suspend fun answer(system: String, user: String, image: ByteArray?): String {
                prompts += user
                return """{"reply":"ok","update":[{"id":$today,"append":"Ya llegué a la estación Luz."}]}"""
            }
        }
        val m = mind(ears, thinker)
        m.write("Ya llegué a la estación Luz")
        answered(m)
        val prompt = thinker.prompts.single()
        assertTrue(prompt, prompt.contains("DIÁRIO DE HOJE:\n#$today diário"))
        // Shown once, as today's diary — not again among the last things kept.
        assertEquals(prompt, 1, Regex("#$today ").findAll(prompt).count())
        assertEquals("Me desperté temprano.\n\nYa llegué a la estación Luz.", db.entries().get(today)!!.body)
    }

    @Test
    fun oldAndamentoJoinsTheText() = runBlocking {
        val task = db.entries().insert(Entry(kind = "task", title = "Recibir a Alma", body = "Voy al aeropuerto a pasar un tiempo con Alma.", status = "doing", createdAt = now, updatedAt = now))
        val bare = db.entries().insert(Entry(kind = "task", title = "Mudança", status = "todo", createdAt = now, updatedAt = now))
        db.progress().insert(com.villamediana.fio.data.Progress(entryId = task, at = now + 2, text = "Llega a São Paulo a las 9:00.", status = null))
        db.progress().insert(com.villamediana.fio.data.Progress(entryId = task, at = now + 1, text = "Vuelve antes de la misión.", status = "doing"))
        db.progress().insert(com.villamediana.fio.data.Progress(entryId = task, at = now + 3, text = "", status = "waiting"))
        db.progress().insert(com.villamediana.fio.data.Progress(entryId = bare, at = now, text = " caixas prontas ", status = null))
        com.villamediana.fio.data.FioDatabase.MIGRATION_2_3.migrate(db.openHelper.writableDatabase)
        assertEquals("Voy al aeropuerto a pasar un tiempo con Alma.\n\nVuelve antes de la misión.\n\nLlega a São Paulo a las 9:00.", db.entries().get(task)!!.body)
        assertEquals("caixas prontas", db.entries().get(bare)!!.body)
        assertTrue(db.progress().latest(task, 10).all { it.text.isEmpty() })
        assertEquals(3, db.progress().latest(task, 10).size) // the status changes stay
    }

    @Test
    fun unknownIdsAreIgnoredAndDeleteWorks() = runBlocking {
        val id = db.entries().insert(Entry(kind = "note", title = "x", createdAt = now, updatedAt = now))
        val r = keeper().apply(Brain.parse("""{"update":[{"id":12345,"status":"done"},{"id":$id,"delete":true}]}""")!!, null)
        assertEquals(listOf(Outcome(id, Outcome.DELETED)), r.outcomes)
        assertNull(db.entries().get(id))
    }

    // ------------------------------------------------------------------ the whole path

    private class FakeEars(val text: String) : Ears {
        var heard = 0
        override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String { heard++; onPartial(text.take(5)); return text }
    }

    private class FakeThinker(val answers: ArrayDeque<String>) : Thinker {
        val prompts = mutableListOf<String>()
        override suspend fun seesImages() = false
        override suspend fun answer(system: String, user: String, image: ByteArray?): String { prompts += user; return answers.removeFirst() }
    }

    private object NoMic : com.villamediana.fio.mind.MicHolder { override fun hold() {}; override fun release() {} }

    /** What the app asked the background holder, in order. */
    private class Holder : com.villamediana.fio.mind.MicHolder {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var working = false
        override fun hold() { calls += "hold" }
        override fun release() { calls += "release" }
        override fun working(on: Boolean) { working = on; calls += "working:$on" }
    }

    /** A microphone that "records" a ready WAV. */
    private class FakeRecorder(val wav: () -> File) : Recorder() {
        var starts = 0
        private var on = false
        override val isRecording: Boolean get() = on
        override fun start(target: File): Boolean { starts++; on = true; return true }
        override fun stop(): Pair<File, Long>? { if (!on) return null; on = false; return wav() to 2000L }
    }
    private class Synced : ReminderSync { val ids = mutableListOf<Long>(); override suspend fun sync(entryIds: List<Long>) { ids += entryIds } }
    private object NoPhotos : PhotoReader { override suspend fun text(file: File) = ""; override suspend fun jpeg(file: File, side: Int) = ByteArray(0) }

    private fun mind(ears: Ears, thinker: Thinker, sync: ReminderSync = Synced(), recorder: Recorder = Recorder(), mic: com.villamediana.fio.mind.MicHolder = NoMic, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), photos: PhotoReader = NoPhotos) = Mind(
        scope = scope,
        db = db, recorder = recorder, hearing = ears, thinking = thinker, keeper = keeper(), reminders = sync,
        photos = photos, mic = mic, dir = tmp.root, zone = { zone }, clock = { now },
    )

    private fun voiceFile(): File = tmp.newFile("v.wav").also { f ->
        Wav.write(f, FloatArray(Wav.RATE * 2) { (0.3 * sin(2 * PI * 200 * it / Wav.RATE)).toFloat() })
    }

    private suspend fun answered(m: Mind): MindState.Answer = withTimeout(10_000) {
        m.state.first { it is MindState.Answer || it is MindState.Problem }.let { it as? MindState.Answer ?: error("problem: $it") }
    }

    @Test
    fun oneStopIsEnoughAndTheWorkKeepsTheAppGoing() = runBlocking {
        val ears = FakeEars("lembrar de comprar pão")
        val thinker = FakeThinker(ArrayDeque(listOf("""{"reply":"Anotei.","create":[{"kind":"task","title":"Comprar pão"}]}""")))
        val holder = Holder()
        var n = 0
        val rec = FakeRecorder { voiceFile().also { n++ }.let { f -> File(tmp.root, "v$n.wav").also { f.renameTo(it) } } }
        val m = mind(ears, thinker, recorder = rec, mic = holder)

        assertTrue(m.startListening(locked = true))
        assertTrue(m.state.value is MindState.Listening)
        m.stopListening()
        // One stop: the screen leaves "ouvindo" at once (it used to stay there until a second tap,
        // hiding the progress and the answer, and a new hold then recorded nothing).
        assertTrue("${m.state.value}", m.state.value is MindState.Working)
        val a = answered(m)
        assertEquals("Anotei.", a.reply)
        // The background was held from before the microphone let go until the work was done.
        withTimeout(5_000) { while (holder.working) kotlinx.coroutines.delay(20) }
        val calls = holder.calls.toList()
        assertEquals(listOf("hold", "working:true", "release"), calls.take(3))
        assertEquals("working:false", calls.last())

        // And the button records again right away.
        assertTrue(m.startListening(locked = false))
        assertEquals(2, rec.starts)
        m.cancelListening()
    }

    @Test
    fun aSoundWorthKeepingStaysWithWhatCameOfIt() = runBlocking {
        val ears = FakeEars("lembra dessa música que eu inventei")
        val sung = """{"reply":"Guardei a música.","create":[{"kind":"note","title":"Música que eu inventei"}],"keep_audio":true}"""
        val plain = """{"reply":"Anotei.","create":[{"kind":"task","title":"Comprar pão"}]}"""
        val thinker = FakeThinker(ArrayDeque(listOf(sung, plain)))
        var n = 0
        val rec = FakeRecorder { voiceFile().also { n++ }.let { f -> File(tmp.root, "v$n.wav").also { f.renameTo(it) } } }
        val m = mind(ears, thinker, recorder = rec)

        assertTrue(m.startListening(locked = true))
        m.stopListening()
        answered(m)
        m.dismiss()
        val kept = db.backup().captures().single()
        assertTrue(kept.sound)
        assertTrue(File(kept.audioPath!!).isFile)
        val note = db.entries().all().single()
        assertEquals(listOf(kept.id), com.villamediana.fio.data.Library(db, Synced()).sounds(note).first().map { it.id })

        // Where the words are enough, the recording goes.
        assertTrue(m.startListening(locked = true))
        m.stopListening()
        withTimeout(10_000) { while (thinker.prompts.size < 2 || !m.idle) kotlinx.coroutines.delay(20) }
        val other = db.backup().captures().first { it.id != kept.id }
        assertTrue(!other.sound)
        assertNull(other.audioPath)
        assertTrue(!File(tmp.root, "v2.wav").exists())
    }

    private fun jpegFile(name: String): File = File(tmp.root, name).apply { writeBytes(ByteArray(1024) { it.toByte() }) }

    @Test
    fun photosWaitAndGoWithWhatSheSays() = runBlocking {
        val made = """{"reply":"Anotei: comprar pra Alma.","create":[{"kind":"task","title":"Comprar o tênis azul pra Alma","photos":true}],"photo_description":"tênis azul numa vitrine"}"""
        val thinker = FakeThinker(ArrayDeque(listOf(made)))
        val m = mind(FakeEars(""), thinker)
        m.stage(listOf(jpegFile("a.jpg") to now - 60_000, jpegFile("b.jpg") to now - 30_000, jpegFile("c.jpg") to now))
        assertEquals(3, m.staged.value.size)
        // One let go before sending.
        m.unstage(m.staged.value[2])
        assertEquals(2, m.staged.value.size)

        m.write("lembra de comprar isso pra Alma")
        answered(m)
        assertTrue(m.staged.value.isEmpty())
        // One look for all: what she wrote with the two photos.
        val prompt = thinker.prompts.single()
        assertTrue(prompt, prompt.contains("AS FOTOS QUE ELA MANDOU JUNTO (2, na imagem, em ordem):"))
        val said = db.backup().captures().single { it.source == Capture.TEXT }
        val sent = db.captures().attachedTo(said.id)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.description == "tênis azul numa vitrine" && it.state == Capture.DONE && File(it.imagePath!!).isFile })
        // They stay with the task, and the day's diary shows them too.
        val task = db.entries().all().single { it.kindEnum == com.villamediana.fio.data.Kind.TASK }
        val lib = com.villamediana.fio.data.Library(db, Synced())
        assertEquals(sent.map { it.id }, lib.photosOf(task.id).first().map { it.id })
        val day = DayDiary.bounds(now, zone)
        assertEquals(1, db.entries().diariesBetween(day.first, day.last).size)
    }

    @Test
    fun morePhotosJoinATaskAlreadyThere() = runBlocking {
        val id = db.entries().insert(Entry(kind = "task", title = "Presente da Alma", status = "todo", createdAt = now - 86_400_000, updatedAt = now - 86_400_000))
        val r = keeper().apply(Brain.parse("""{"update":[{"id":$id,"photos":true}]}""")!!, 7L)
        assertEquals(listOf(Outcome(id, Outcome.UPDATED, photos = true)), r.outcomes)
        assertEquals(now, db.entries().get(id)!!.updatedAt)
    }

    /** A model that hangs and doesn't listen to anything (like a native call that won't come back). */
    private class StuckThinker(val reply: String) : Thinker {
        @Volatile var stuck = true
        @Volatile var stops = 0
        override suspend fun seesImages() = false
        override suspend fun answer(system: String, user: String, image: ByteArray?): String =
            kotlinx.coroutines.withContext(Dispatchers.IO) { while (stuck) Thread.sleep(10); reply }
        override fun stop() { stops++ }
    }

    @Test
    fun pararFreesTheScreenEvenWhenTheModelHangs() = runBlocking {
        val thinker = StuckThinker("""{"reply":"Anotei.","create":[{"kind":"note","title":"Ideia"}]}""")
        val m = mind(FakeEars("uma ideia boa"), thinker)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, durationMs = 2000, state = Capture.RECORDED))
        m.retry(id)
        withTimeout(5_000) { m.state.first { it is MindState.Working && it.stage == Stage.THINKING } }

        m.stopWork()
        val p = withTimeout(2_000) { m.state.first { it is MindState.Problem } } as MindState.Problem
        assertEquals(id, p.captureId)
        assertEquals("uma ideia boa", p.said) // what was said stays, with its text
        assertEquals(1, thinker.stops)
        assertEquals(Capture.STOPPED, db.captures().get(id)!!.state)

        // The stuck job finally comes back: it says and keeps nothing.
        thinker.stuck = false
        kotlinx.coroutines.delay(300)
        assertTrue(m.state.value is MindState.Problem)
        assertEquals(0, db.entries().all().size)

        // Coming back to the app doesn't start it again on its own…
        m.retryPending()
        kotlinx.coroutines.delay(300)
        assertEquals(Capture.STOPPED, db.captures().get(id)!!.state)
        // …a hand does, and it goes through.
        m.retryPending(byHand = true)
        val a = withTimeout(10_000) { m.state.first { it is MindState.Answer } } as MindState.Answer
        assertEquals("Anotei.", a.reply)
        assertEquals(Capture.DONE, db.captures().get(id)!!.state)
    }

    @Test
    fun anErrorOfTheEngineEndsOnTheScreenNotInAnEndlessOrganizando() = runBlocking {
        val broken = object : Thinker {
            override suspend fun seesImages() = false
            override suspend fun answer(system: String, user: String, image: ByteArray?): String = throw UnsatisfiedLinkError("nativeGenerate")
        }
        val m = mind(FakeEars("comprar café"), broken)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, durationMs = 2000, state = Capture.RECORDED))
        m.retry(id)
        val p = withTimeout(5_000) { m.state.first { it is MindState.Problem } } as MindState.Problem
        assertEquals(id, p.captureId)
        assertTrue(p.message, p.message.contains("nativeGenerate"))
        assertEquals("comprar café", db.captures().get(id)!!.transcript)
    }

    /** Hears one piece, keeps it, and then the app "falls" (it never comes back). */
    private class FallingEars : Ears {
        override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String {
            onPiece(Heard("hoy fue un buen día", 1, "local:28"))
            kotlinx.coroutines.awaitCancellation()
        }
    }

    /** Goes on from what was already heard. */
    private class GoingOnEars : Ears {
        var from: Heard? = null
        override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String {
            this.from = from
            return from.text + " y fui al aeropuerto"
        }
    }

    @Test
    fun afterAFallTheHearingGoesOnFromThePieceItReached() = runBlocking {
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, durationMs = 60_000, state = Capture.RECORDED))
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m1 = mind(FallingEars(), FakeThinker(ArrayDeque()), scope = first)
        m1.retry(id)
        withTimeout(5_000) { while (db.captures().get(id)!!.heardPieces < 1) kotlinx.coroutines.delay(20) }
        first.cancel() // the app falls

        val ears = GoingOnEars()
        val m2 = mind(ears, FakeThinker(ArrayDeque(listOf("""{"reply":"Guardei seu dia.","create":[{"kind":"diary","title":"Dia bom e aeroporto"}]}"""))))
        m2.retryPending() // the app opens again
        val a = answered(m2)
        assertEquals(Heard("hoy fue un buen día", 1, "local:28"), ears.from)
        assertEquals("hoy fue un buen día y fui al aeropuerto", a.said)
        assertEquals(0, db.captures().get(id)!!.tries)
    }

    @Test
    fun whatMadeTheAppFallSeveralTimesWaitsForAHand() = runBlocking {
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, durationMs = 2000, state = Capture.TRANSCRIBING, tries = Mind.MAX_TRIES))
        val ears = FakeEars("comprar pão")
        val m = mind(ears, FakeThinker(ArrayDeque(listOf("""{"reply":"Anotei.","create":[{"kind":"task","title":"Comprar pão"}]}"""))))
        m.retryPending()
        kotlinx.coroutines.delay(400)
        assertEquals(0, ears.heard) // not on its own: it would only fall again
        m.retryPending(byHand = true)
        val a = withTimeout(10_000) { m.state.first { it is MindState.Answer } } as MindState.Answer
        assertEquals("Anotei.", a.reply)
        assertEquals(Capture.DONE, db.captures().get(id)!!.state)
    }

    @Test
    fun theModelOnThePhoneGetsItsThinkingTurnedOffAndNoThinkingShows() {
        assertTrue(Brain.chatml("s", "u").endsWith("<|im_start|>assistant\n<think></think>"))
        val asr = com.villamediana.fio.local.LocalEngine.asrPrompt("Spanish")
        assertTrue(asr.contains("Perform ASR in Spanish.") && asr.contains("<__media__>"))
        assertTrue(asr.endsWith("<|im_start|>assistant\n<think></think>"))
        val clean = com.villamediana.fio.local.LocalEngine::clean
        assertEquals("Bueno, hoy es un buen día.", clean("<think></think>Bueno, hoy es un buen día."))
        assertEquals("Harle algunas cositas", clean("<think>\n</think>Harle algunas cositas"))
        assertEquals("Bueno", clean("Bueno <think></thi")) // still coming: never shown half
        val end = com.villamediana.fio.ui.home.tail("um dois três ".repeat(40) + "o fim", max = 24)
        assertTrue(end, end.startsWith("…") && end.endsWith("o fim") && end.length <= 25 && !end.startsWith("… "))
    }

    @Test
    fun writingGoesStraightToOrganizing() = runBlocking {
        val ears = FakeEars("nunca") // must not be called: there is nothing to transcribe
        val thinker = FakeThinker(ArrayDeque(listOf("""{"reply":"Anotei.","create":[{"kind":"task","title":"Comprar pão"}]}""")))
        val m = mind(ears, thinker)
        m.write("  comprar pão amanhã  ")
        val a = withTimeout(10_000) { m.state.first { it is MindState.Answer } } as MindState.Answer
        assertEquals("Anotei.", a.reply)
        assertTrue(a.typed)
        assertEquals("comprar pão amanhã", a.said)
        assertEquals(0, ears.heard)
        assertTrue(thinker.prompts.single().contains("O QUE ELA ESCREVEU:\n\"comprar pão amanhã\""))
        val c = db.captures().get(a.captureId)!!
        assertEquals(Capture.TEXT, c.source)
        assertEquals(Capture.DONE, c.state)
    }

    @Test
    fun saysThinksKeepsAndAnswers() = runBlocking {
        val ears = FakeEars("amanhã preciso ligar pro dentista às 9")
        val thinker = FakeThinker(ArrayDeque(listOf("""{"reply":"Anotei: ligar pro dentista amanhã às 9.","create":[{"kind":"task","title":"Ligar pro dentista","due":"2026-11-12T09:00","remind_at":"2026-11-12T08:45"}]}""")))
        val sync = Synced()
        val m = mind(ears, thinker, sync)
        val wav = voiceFile()
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = wav.path, durationMs = 2000, state = Capture.RECORDED))
        m.retry(id)
        val a = answered(m)
        assertEquals("Anotei: ligar pro dentista amanhã às 9.", a.reply)
        val entry = db.entries().get(a.outcomes.single().entryId)!!
        assertEquals("Ligar pro dentista", entry.title)
        assertEquals(id, entry.captureId)
        assertEquals(listOf(entry.id), sync.ids)
        val c = db.captures().get(id)!!
        assertEquals(Capture.DONE, c.state)
        assertEquals("amanhã preciso ligar pro dentista às 9", c.transcript)
        assertTrue("audio deleted after it went through", !wav.exists())
        assertTrue(thinker.prompts.single().contains("\"amanhã preciso ligar pro dentista às 9\""))
    }

    @Test
    fun questionsCanSearchOnce() = runBlocking {
        val pw = db.entries().insert(Entry(kind = "note", title = "Wifi da casa da mãe", body = "senha: girassol42", sensitive = true, createdAt = now - 86_400_000L * 30, updatedAt = now))
        val thinker = FakeThinker(
            ArrayDeque(
                listOf(
                    """{"reply":"","search":["wifi","mãe"]}""",
                    """{"reply":"A senha do wifi da sua mãe é girassol42.","show":[$pw]}""",
                ),
            ),
        )
        val m = mind(FakeEars("qual era a senha da internet lá da minha mãe?"), thinker)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, state = Capture.RECORDED))
        m.retry(id)
        val a = answered(m)
        assertEquals(listOf(pw), a.shown)
        assertTrue(a.outcomes.isEmpty())
        assertEquals(2, thinker.prompts.size)
        assertTrue(thinker.prompts[1], thinker.prompts[1].contains("RESULTADOS DA BUSCA (wifi, mãe)"))
        assertTrue(thinker.prompts[1].contains("#$pw nota"))
    }

    @Test
    fun questionsAboutATimeLookAtThoseDays() = runBlocking {
        val day = 86_400_000L
        val diary = db.entries().insert(Entry(kind = "diary", title = "Dia bom no escritório", body = "saiu o cronograma", createdAt = now - day, updatedAt = now - day))
        val paid = db.entries().insert(Entry(kind = "task", title = "Pagar o IPVA", status = "done", doneAt = now - day, createdAt = now - 20 * day, updatedAt = now - day))
        val old = db.entries().insert(Entry(kind = "note", title = "Receita de pão", createdAt = now - 40 * day, updatedAt = now - 40 * day))
        val thinker = FakeThinker(
            ArrayDeque(
                listOf(
                    """{"reply":"","search":["academia"]}""",
                    """{"reply":"","period":{"from":"2026-11-10","to":"2026-11-10"}}""",
                    """{"reply":"Ontem você pagou o IPVA e teve um dia bom no escritório.","show":[$paid,$diary]}""",
                ),
            ),
        )
        val m = mind(FakeEars("o que eu fiz ontem?"), thinker)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, state = Capture.RECORDED))
        m.retry(id)
        val a = answered(m)
        assertEquals(listOf(paid, diary), a.shown)
        assertEquals(3, thinker.prompts.size) // two lookups, then the answer
        assertTrue(thinker.prompts[0].contains("ÚLTIMAS COISAS GUARDADAS"))
        val results = thinker.prompts[2].substringAfter("RESULTADOS DA BUSCA (academia · dia 2026-11-10)", "")
        assertTrue(thinker.prompts[2], results.contains("#$diary diário"))
        assertTrue(results.contains("#$paid tarefa[done]"))
        assertTrue(!results.contains("#$old nota"))
    }

    private class ReadsText(val text: String) : PhotoReader { override suspend fun text(file: File) = text; override suspend fun jpeg(file: File, side: Int) = ByteArray(0) }

    @Test
    fun aPhotoTakenIsTheDaysTooItsDiaryBegunOnce() = runBlocking {
        val note = """{"reply":"Guardei o recibo.","create":[{"kind":"note","title":"Recibo do almoço","body":"R$ 32,00"}]}"""
        val m = mind(FakeEars(""), FakeThinker(ArrayDeque(listOf(note, note))), photos = ReadsText("Restaurante Sol · R$ 32,00"))
        m.photo(tmp.newFile("p1.jpg"))
        answered(m)
        // The day had no diary: one is begun, named by the day, nothing written for her.
        val day = DayDiary.bounds(now, zone)
        val first = db.entries().diariesBetween(day.first, day.last)
        assertEquals(1, first.size)
        assertEquals("Quarta-feira, 11 de novembro", first.single().title)
        assertEquals("", first.single().body)
        m.dismiss()
        // Another photo the same day: the same diary.
        m.photo(tmp.newFile("p2.jpg"))
        answered(m)
        assertEquals(first.map { it.id }, db.entries().diariesBetween(day.first, day.last).map { it.id })
        assertEquals(2, db.entries().all().count { it.title == "Recibo do almoço" })
    }

    @Test
    fun aPhotoKeepsWhatItShowsAndAQuestionBringsItBack() = runBlocking {
        val described = """{"reply":"Que lindo!","photo_description":"Alma no aeroporto de Guarulhos com a mala azul"}"""
        val thinker = FakeThinker(ArrayDeque(listOf(described)))
        val m = mind(FakeEars(""), thinker, photos = ReadsText("GRU T2"))
        m.photo(tmp.newFile("alma.jpg"))
        val a = answered(m)
        val photo = db.captures().get(a.captureId)!!
        assertEquals("Alma no aeroporto de Guarulhos com a mala azul", photo.description)
        m.dismiss()

        // Later, a question: the photo goes to the model by what it shows, and comes back as the answer.
        val asked = FakeThinker(ArrayDeque(listOf("""{"reply":"Essa aqui.","show_photos":[${photo.id}, 999]}""")))
        val m2 = mind(FakeEars("mostra a foto da Alma no aeroporto"), asked)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, state = Capture.RECORDED))
        m2.retry(id)
        val answer = answered(m2)
        assertTrue(asked.prompts.single(), asked.prompts.single().contains("FOTOS QUE PODEM TER A VER:\nfoto#${photo.id} "))
        assertEquals(listOf(photo.id), answer.photos) // 999 isn't a photo
        assertEquals("[${photo.id}]", db.captures().get(id)!!.shownPhotos)
    }

    @Test
    fun aPhotoWithNothingToReadIsNotKept() = runBlocking {
        val m = mind(FakeEars(""), FakeThinker(ArrayDeque())) // this model doesn't see, and the photo has no text
        val file = tmp.newFile("p.jpg")
        m.photo(file)
        val p = withTimeout(10_000) { m.state.first { it is MindState.Problem } as MindState.Problem }
        assertNull(p.captureId)
        assertTrue(p.message, p.message.contains("foto"))
        assertTrue(!file.exists())
        assertTrue(db.captures().unfinished().isEmpty())
    }

    @Test
    fun doneByHandDropsItsReminder() = runBlocking {
        val id = db.entries().insert(Entry(kind = "task", title = "Pagar o IPVA", status = "todo", remindAt = now + 3_600_000L, checkInAt = now + 7_200_000L, createdAt = now, updatedAt = now))
        val sync = Synced()
        com.villamediana.fio.data.Library(db, sync, clock = { now }).setStatus(id, Status.DONE)
        val e = db.entries().get(id)!!
        assertNull(e.remindAt)
        assertNull(e.checkInAt)
        assertEquals(listOf(id), sync.ids) // the alarm is cancelled with it
    }

    @Test
    fun aCheckInAnswerGoesToItsTask() = runBlocking {
        val task = db.entries().insert(Entry(kind = "task", title = "Mudança de escritório", status = "doing", createdAt = now, updatedAt = now))
        val thinker = FakeThinker(ArrayDeque(listOf("""{"reply":"Boa! Anotei o andamento.","update":[{"id":$task,"append":"Caixas prontas.","check_in_at":"2026-11-13"}]}""")))
        val m = mind(FakeEars("já empacotei tudo"), thinker)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, state = Capture.RECORDED, focusEntryId = task))
        m.retry(id)
        val a = answered(m)
        assertEquals(Outcome(task, Outcome.UPDATED), a.outcomes.single())
        assertTrue(thinker.prompts.single().contains("ELA ESTÁ RESPONDENDO SOBRE ESTE ITEM"))
        assertNotNull(db.entries().get(task)!!.checkInAt)
        assertEquals("Caixas prontas.", db.entries().get(task)!!.body)
    }

    @Test
    fun nothingIsLostWhenItCantGoThrough() = runBlocking {
        val notReady = object : Ears {
            override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = throw NotReady(Need.HEAR, "configure a API em Ajustes")
        }
        val m = mind(notReady, FakeThinker(ArrayDeque()))
        val wav = voiceFile()
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = wav.path, state = Capture.RECORDED))
        m.retry(id)
        val p = withTimeout(10_000) { m.state.first { it is MindState.Problem } as MindState.Problem }
        assertEquals(Need.HEAR, p.need)
        assertEquals(id, p.captureId)
        assertTrue(wav.exists())
        assertEquals(Capture.RECORDED, db.captures().get(id)!!.state)
        assertEquals(1, withTimeout(5_000) { m.pending.first { it == 1 } })
    }

    @Test
    fun photosFromTheGalleryGoOneByOneEachOnTheDayItWasTaken() = runBlocking {
        val seen = """{"reply":"Guardei.","photo_description":"praia"}"""
        val thinker = FakeThinker(ArrayDeque(listOf(seen, seen)))
        val m = mind(FakeEars(""), thinker, photos = ReadsText("Ubatuba"))
        val lastWeek = LocalDateTime.of(2026, 11, 4, 16, 20).atZone(zone).toInstant().toEpochMilli()
        val first = tmp.newFile("g1.jpg")
        val second = tmp.newFile("g2.jpg")
        m.photos(listOf(first to lastWeek, second to now))
        // Done when nothing is going through or waiting (what an import waits for).
        withTimeout(10_000) { while (thinker.prompts.size < 2 || !m.idle || db.captures().unfinished().isNotEmpty()) kotlinx.coroutines.delay(20) }
        val kept = db.captures().allPhotos().sortedBy { it.id }
        assertEquals(listOf(first.path, second.path), kept.map { it.imagePath }) // in the order picked
        assertEquals(listOf(lastWeek, now), kept.map { it.createdAt })
        // Each one is its day's: last week's day gets its diary, and the model knows when it was taken.
        val thatDay = DayDiary.bounds(lastWeek, zone)
        assertEquals("Quarta-feira, 4 de novembro", db.entries().diariesBetween(thatDay.first, thatDay.last).single().title)
        assertTrue(thinker.prompts[0], thinker.prompts[0].contains("O QUE ESTÁ NA FOTO (TIRADA EM quarta-feira, 2026-11-04 16:20):"))
        assertTrue(thinker.prompts[1], thinker.prompts[1].contains("O QUE ESTÁ NA FOTO QUE ELA TIROU:"))
    }

    @Test
    fun theCameraDateOfAPhotoIsReadWithItsOffset() {
        val utc = ZoneId.of("UTC")
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 13, 15, 30).atZone(utc).toInstant().toEpochMilli(),
            PhotoDate.parse("2026:11:07 10:15:30", "-03:00", utc),
        )
        // No offset written: the phone's own zone.
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 10, 15, 30).atZone(zone).toInstant().toEpochMilli(),
            PhotoDate.parse("2026:11:07 10:15:30", null, zone),
        )
        assertNull(PhotoDate.parse("0000:00:00 00:00:00", null, zone))
        assertNull(PhotoDate.parse("", null, zone))
        assertNull(PhotoDate.parse(null, null, zone))
        assertNull(PhotoDate.of(File(tmp.root, "nada.jpg"), zone))
    }

    @Test
    fun anotherDiaryTheSameDayGoesOnInTheOneThereIs() = runBlocking {
        val morning = db.entries().insert(Entry(kind = "diary", title = "Una mañana interesante", body = "Me desperté temprano.", createdAt = now - 3_600_000, updatedAt = now - 3_600_000))
        // The model made a new diary with the morning again and something new.
        keeper().apply(Brain.parse("""{"create":[{"kind":"diary","title":"Una mañana interesante","body":"Me desperté temprano.\n\nAl final encontré a Alma en el aeropuerto."}]}""")!!, null)
        val day = DayDiary.bounds(now, zone)
        val diaries = db.entries().diariesBetween(day.first, day.last)
        assertEquals(listOf(morning), diaries.map { it.id })
        assertEquals("Me desperté temprano.\n\nAl final encontré a Alma en el aeropuerto.", diaries.single().body)
        // Told again from the start with more: it takes the place (nothing doubled).
        keeper().apply(Brain.parse("""{"update":[{"id":$morning,"append":"Al final encontré a Alma en el aeropuerto."}]}""")!!, null)
        assertEquals("Me desperté temprano.\n\nAl final encontré a Alma en el aeropuerto.", db.entries().get(morning)!!.body)
    }

    @Test
    fun aDiaryOnlyGrowsByWholeWords() {
        // "Sol." is not in "Girassol": the day told so far stays, the new part joins it.
        assertEquals("Sol.\n\nO girassol nasceu.", DayDiary.merge("Sol.", "O girassol nasceu."))
        assertEquals("Sol na praia.\n\nDepois choveu.", DayDiary.merge("Sol na praia.", "Sol na praia.\n\nDepois choveu."))
    }

    @Test
    fun daysLeftWithTwoDiariesBecomeOne() = runBlocking {
        val a = db.entries().insert(Entry(kind = "diary", title = "Una mañana interesante", body = "Me desperté temprano.", createdAt = now - 3_600_000, updatedAt = now - 3_600_000))
        val b = db.entries().insert(Entry(kind = "diary", title = "Una mañana interesante", body = "Me desperté temprano.\n\nAl final encontré a Alma.", createdAt = now, updatedAt = now))
        val other = db.entries().insert(Entry(kind = "diary", title = "Ontem", body = "Outro dia.", createdAt = now - 86_400_000, updatedAt = now))
        val task = db.entries().insert(Entry(kind = "task", title = "Buscar a Alma", status = "done", createdAt = now, updatedAt = now))
        val topic = db.graph().insertTopic(com.villamediana.fio.data.Topic(name = "Alma", norm = "alma", type = "person"))
        db.graph().tie(com.villamediana.fio.data.EntryTopic(b, topic))
        db.graph().link(com.villamediana.fio.data.Link(minOf(b, task), maxOf(b, task)))
        com.villamediana.fio.data.Library(db, Synced(), clock = { now }).joinDayDiaries(zone)
        assertNull(db.entries().get(b))
        val kept = db.entries().get(a)!!
        assertEquals("Me desperté temprano.\n\nAl final encontré a Alma.", kept.body)
        assertEquals(listOf("Alma"), db.graph().topicsOf(a).map { it.name })
        assertEquals(listOf(com.villamediana.fio.data.Link(minOf(a, task), maxOf(a, task))), db.graph().allLinks())
        assertNotNull(db.entries().get(other)) // another day stays as it is
    }

    @Test
    fun theLastConversationGoesAlongSoThatTaskIsKnown() = runBlocking {
        // First: a task is made.
        val made = """{"reply":"Anotei a tarefa do fluxo.","create":[{"kind":"task","title":"Criar fluxo no Power Automate"}]}"""
        val then = """{"reply":"Anotei a etapa.","update":[{"id":1,"add_steps":["Falar com o Vinícius"]}]}"""
        val thinker = FakeThinker(ArrayDeque(listOf(made, then)))
        val m = mind(FakeEars(""), thinker)
        m.write("preciso criar um fluxo no Power Automate")
        answered(m)
        m.dismiss()
        val task = db.entries().all().single { it.kindEnum == com.villamediana.fio.data.Kind.TASK }
        // Then, about "that task": the previous turn is in the context, with the task's id.
        m.write("da tarefa do fluxo, preciso falar com o Vinícius")
        withTimeout(10_000) { while (thinker.prompts.size < 2 || !m.idle) kotlinx.coroutines.delay(20) }
        val prompt = thinker.prompts[1]
        assertTrue(prompt, prompt.contains("ANTES DISSO (a última conversa, "))
        assertTrue(prompt, prompt.contains("ela: \"preciso criar um fluxo no Power Automate\""))
        assertTrue(prompt, prompt.contains("você: \"Anotei a tarefa do fluxo.\""))
        // Already in the open tasks above: there it is only pointed to, not written again.
        assertTrue(prompt, prompt.contains("criado: #${task.id} (acima)"))
        assertEquals(prompt, 1, Regex("#${task.id} tarefa\\[todo]").findAll(prompt).count())
        // The step went to the task's steps, not to its text.
        val after = db.entries().get(task.id)!!
        assertEquals(listOf("Falar com o Vinícius"), Steps.read(after.steps).map { it.text })
        assertEquals("", after.body)
        // The first thing said has nothing before it.
        assertTrue(!thinker.prompts[0].contains("ANTES DISSO"))
    }

    @Test
    fun aTaskDoneLeavesWhatTheModelGetsByItself() = runBlocking {
        val id = db.entries().insert(Entry(kind = "task", title = "Criar fluxo no Power Automate", status = "todo", createdAt = now, updatedAt = now))
        val finish = """{"reply":"Marquei o fluxo como feito.","update":[{"id":$id,"status":"done"}]}"""
        val note = """{"reply":"Anotei.","create":[{"kind":"note","title":"Vinícius sabe mexer no Power Automate"}]}"""
        val look = """{"search":["fluxo"]}"""
        val answer = """{"reply":"Você terminou o fluxo hoje."}"""
        val thinker = FakeThinker(ArrayDeque(listOf(finish, note, look, answer)))
        val m = mind(FakeEars(""), thinker)
        m.write("terminei o fluxo do Power Automate")
        answered(m)
        m.dismiss()
        assertEquals("done", db.entries().get(id)!!.status)
        // Next, something near it: the task done is in no list (not even the last conversation's).
        m.write("o Vinícius que sabe mexer no fluxo do Power Automate")
        withTimeout(10_000) { while (thinker.prompts.size < 2 || !m.idle) kotlinx.coroutines.delay(20) }
        m.dismiss()
        val second = thinker.prompts[1]
        assertTrue(second, second.contains("você: \"Marquei o fluxo como feito.\""))
        assertTrue(second, !second.contains("#$id "))
        // Asked about it, a lookup still finds it.
        m.write("o que eu fiz do fluxo?")
        withTimeout(10_000) { while (thinker.prompts.size < 4 || !m.idle) kotlinx.coroutines.delay(20) }
        assertTrue(thinker.prompts[2], !thinker.prompts[2].contains("#$id "))
        assertTrue(thinker.prompts[3], thinker.prompts[3].contains("#$id tarefa[done]"))
    }

    @Test
    fun whatChangesWhatATaskIsRewritesItsText() = runBlocking {
        val id = db.entries().insert(Entry(kind = "task", title = "Fluxo de aprovação", body = "No Power Automate.", status = "todo", createdAt = now, updatedAt = now))
        keeper().apply(Brain.parse("""{"update":[{"id":$id,"body":"No Power Apps, com tela de aprovação.","title":"App de aprovação"}]}""")!!, null)
        val e = db.entries().get(id)!!
        assertEquals("No Power Apps, com tela de aprovação.", e.body)
        assertEquals("App de aprovação", e.title)
    }
}
