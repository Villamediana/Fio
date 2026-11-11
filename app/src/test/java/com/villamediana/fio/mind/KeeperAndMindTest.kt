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
    fun updatesStatusStepsAndProgress() = runBlocking {
        val id = db.entries().insert(
            Entry(kind = "task", title = "Contrato", status = "doing", steps = Steps.write(listOf(com.villamediana.fio.data.Step("pedir orçamento"), com.villamediana.fio.data.Step("assinar"))), checkInAt = now + 5000, createdAt = now, updatedAt = now),
        )
        val k = keeper()
        val r1 = k.apply(Brain.parse("""{"update":[{"id":$id,"steps_done":["pedir orcamento"],"progress":"o fornecedor mandou o orçamento"}]}""")!!, null)
        assertEquals(Outcome.UPDATED, r1.outcomes.single().action)
        val e1 = db.entries().get(id)!!
        assertTrue(Steps.read(e1.steps)[0].done)
        assertEquals("o fornecedor mandou o orçamento", db.progress().latest(id, 5).single().text)

        val r2 = k.apply(Brain.parse("""{"update":[{"id":$id,"status":"done"}]}""")!!, null)
        assertEquals(Outcome.DONE, r2.outcomes.single().action)
        val e2 = db.entries().get(id)!!
        assertEquals(Status.DONE, e2.statusEnum)
        assertNull(e2.checkInAt) // nothing left to ask about
        assertEquals("done", db.progress().latest(id, 1).single().status)
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
        override suspend fun transcribe(wav: File, onPartial: (String) -> Unit): String { heard++; onPartial(text.take(5)); return text }
    }

    private class FakeThinker(val answers: ArrayDeque<String>) : Thinker {
        val prompts = mutableListOf<String>()
        override suspend fun seesImages() = false
        override suspend fun answer(system: String, user: String, image: ByteArray?): String { prompts += user; return answers.removeFirst() }
    }

    private object NoMic : com.villamediana.fio.mind.MicHolder { override fun hold() {}; override fun release() {} }
    private class Synced : ReminderSync { val ids = mutableListOf<Long>(); override suspend fun sync(entryIds: List<Long>) { ids += entryIds } }
    private object NoPhotos : PhotoReader { override suspend fun text(file: File) = ""; override suspend fun jpeg(file: File) = ByteArray(0) }

    private fun mind(ears: Ears, thinker: Thinker, sync: ReminderSync = Synced()) = Mind(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        db = db, recorder = Recorder(), hearing = ears, thinking = thinker, keeper = keeper(), reminders = sync,
        photos = NoPhotos, mic = NoMic, dir = tmp.root, zone = { zone }, clock = { now },
    )

    private fun voiceFile(): File = tmp.newFile("v.wav").also { f ->
        Wav.write(f, FloatArray(Wav.RATE * 2) { (0.3 * sin(2 * PI * 200 * it / Wav.RATE)).toFloat() })
    }

    private suspend fun answered(m: Mind): MindState.Answer = withTimeout(10_000) {
        m.state.first { it is MindState.Answer || it is MindState.Problem }.let { it as? MindState.Answer ?: error("problem: $it") }
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
        val thinker = FakeThinker(ArrayDeque(listOf("""{"reply":"Boa! Anotei o andamento.","update":[{"id":$task,"progress":"caixas prontas","check_in_at":"2026-11-13"}]}""")))
        val m = mind(FakeEars("já empacotei tudo"), thinker)
        val id = db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = voiceFile().path, state = Capture.RECORDED, focusEntryId = task))
        m.retry(id)
        val a = answered(m)
        assertEquals(Outcome(task, Outcome.UPDATED), a.outcomes.single())
        assertTrue(thinker.prompts.single().contains("ELA ESTÁ RESPONDENDO SOBRE ESTE ITEM"))
        assertNotNull(db.entries().get(task)!!.checkInAt)
    }

    @Test
    fun nothingIsLostWhenItCantGoThrough() = runBlocking {
        val notReady = object : Ears {
            override suspend fun transcribe(wav: File, onPartial: (String) -> Unit): String = throw NotReady(Need.HEAR, "configure a API em Ajustes")
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
}
