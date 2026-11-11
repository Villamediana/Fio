package com.villamediana.fio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.villamediana.fio.FioApplication
import com.villamediana.fio.audio.Wav
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.EntryTopic
import com.villamediana.fio.data.Step
import com.villamediana.fio.data.Topic
import com.villamediana.fio.mind.MindState
import com.villamediana.fio.mind.Steps
import com.villamediana.fio.settings.Source
import com.villamediana.fio.settings.ThemeMode
import com.villamediana.fio.ui.entry.EntryScreen
import com.villamediana.fio.ui.graph.GraphScreen
import com.villamediana.fio.ui.home.HomeScreen
import com.villamediana.fio.ui.home.Orb
import com.villamediana.fio.ui.home.OrbMode
import com.villamediana.fio.ui.library.LibraryScreen
import com.villamediana.fio.ui.settings.SettingsScreen
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.theme.FioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/**
 * The real app (FioApplication, Room, DataStore, the Mind) with an OpenAI-compatible API played by
 * a MockWebServer: something said goes all the way through, then every screen is drawn and saved
 * to app/build/fio-shots for a look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val graph get() = FioApplication.graph
    private val zone = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    @After fun stop() { server.close() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/fio-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed(): Map<String, Long> = runBlocking {
        val db = graph.db
        val tomorrow = LocalDate.now(zone).plusDays(1).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val today = LocalDate.now(zone).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val ids = mutableMapOf<String, Long>()
        ids["contract"] = db.entries().insert(
            Entry(
                kind = "task", title = "Fechar contrato da torre de Campinas", body = "Falta a assinatura do proprietário e a planta atualizada.", status = "doing",
                steps = Steps.write(listOf(Step("pedir planta", true), Step("revisar cláusulas", true), Step("assinatura"))), dueAt = tomorrow, dueHasTime = true,
                checkInAt = now + 2 * day, createdAt = now - 3 * day, updatedAt = now,
            ),
        )
        ids["pay"] = db.entries().insert(Entry(kind = "task", title = "Pagar o IPVA", status = "todo", dueAt = today, createdAt = now - day, updatedAt = now, remindAt = today - 3_600_000))
        ids["gift"] = db.entries().insert(Entry(kind = "task", title = "Presente de aniversário da Ana", status = "todo", createdAt = now - 2 * day, updatedAt = now))
        ids["idea"] = db.entries().insert(Entry(kind = "note", title = "Ideia: app que organiza o que eu falo", body = "Um botão só. Classifica em tarefa, nota e diário, liga as coisas num grafo.", createdAt = now - 4 * day, updatedAt = now))
        ids["wifi"] = db.entries().insert(Entry(kind = "note", title = "Wifi da casa da mãe", body = "senha: girassol42", sensitive = true, createdAt = now - 20 * day, updatedAt = now))
        ids["diary"] = db.entries().insert(Entry(kind = "diary", title = "Dia bom no escritório", body = "Reunião boa com o time, saiu o cronograma da migração. À noite corri 5 km.", createdAt = now - 6 * 3_600_000L, updatedAt = now))
        val pedro = db.graph().insertTopic(Topic(name = "Pedro", norm = "pedro", type = "person"))
        val torre = db.graph().insertTopic(Topic(name = "Torre Campinas", norm = "torre campinas", type = "project"))
        val ana = db.graph().insertTopic(Topic(name = "Ana", norm = "ana", type = "person"))
        val mae = db.graph().insertTopic(Topic(name = "Mãe", norm = "mae", type = "person"))
        val migr = db.graph().insertTopic(Topic(name = "Migração", norm = "migracao", type = "project"))
        listOf(ids["contract"]!! to pedro, ids["contract"]!! to torre, ids["gift"]!! to ana, ids["wifi"]!! to mae, ids["diary"]!! to migr, ids["diary"]!! to pedro, ids["idea"]!! to migr)
            .forEach { (e, t) -> db.graph().tie(EntryTopic(e, t)) }
        ids
    }

    private fun apiThatAnswers(planJson: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath.endsWith("/audio/transcriptions") ->
                    MockResponse.Builder().body("""{"text":"Amanhã às nove eu preciso ligar pro Pedro sobre o contrato, e lembra de comprar o presente da Ana. Ah, e hoje foi um dia bom."}""").build()
                request.url.encodedPath.endsWith("/chat/completions") ->
                    MockResponse.Builder().body("""{"choices":[{"message":{"content":${org.json.JSONObject.quote(planJson)}}}]}""").build()
                request.url.encodedPath.endsWith("/models") -> MockResponse.Builder().body("""{"data":[{"id":"gpt-6-luna"},{"id":"whisper"}]}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        runBlocking {
            val s = graph.settings
            s.setApiBase(server.url("/openai/v1").toString())
            s.setApiKey("test-key")
            s.setHear(Source.API); s.setHearModel("whisper")
            s.setThink(Source.API); s.setThinkModel("gpt-6-luna")
            s.setHintSeen()
        }
    }

    @Test
    fun everyScreen() {
        val ids = seed()
        val tomorrow9 = LocalDate.now(zone).plusDays(1).toString() + "T09:00"
        apiThatAnswers(
            """{"reply":"Anotei a ligação pro Pedro amanhã às 9 e o presente da Ana. E guardei o seu dia.","create":[{"kind":"task","title":"Ligar pro Pedro sobre o contrato","due":"$tomorrow9","remind_at":"$tomorrow9","entities":[{"name":"Pedro","type":"person"}],"related":[${ids["contract"]}]},{"kind":"diary","title":"Hoje foi um dia bom","body":"Hoje foi um dia bom."}],"update":[{"id":${ids["gift"]},"progress":"lembrar de comprar","remind_at":"$tomorrow9"}]}""",
        )
        val nav = Nav()

        // 1. Idle.
        compose.setContent { FioTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.onNodeWithText("O que está na sua cabeça?").assertExists()
        shot("01-home-idle")

        // 2. Something said goes through the API and comes back organized.
        val wav = File(graph.appContext.filesDir, "captures/voice-test.wav").apply { parentFile?.mkdirs() }
        Wav.write(wav, FloatArray(Wav.RATE * 3) { (0.3 * sin(2 * PI * 210 * it / Wav.RATE)).toFloat() })
        val id = runBlocking { graph.db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = wav.path, durationMs = 3000, state = Capture.RECORDED)) }
        graph.mind.retry(id)
        val answer = runBlocking { withTimeout(20_000) { graph.mind.state.first { it is MindState.Answer || it is MindState.Problem } } }
        assertTrue("$answer", answer is MindState.Answer)
        answer as MindState.Answer
        assertEquals(3, answer.outcomes.size)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ligar pro Pedro sobre o contrato").fetchSemanticsNodes().isNotEmpty() }
        shot("02-home-answer")
        // The request carried the context: the open tasks with their ids.
        val prompt = (1..server.requestCount).map { server.takeRequest() }.first { it.url.encodedPath.endsWith("chat/completions") }.body!!.utf8()
        assertTrue(prompt.contains("#${ids["contract"]} tarefa[doing]"))
    }

    @Test
    fun listsAndDetails() {
        val ids = seed()
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Library(Filter.TODAY))
        compose.setContent {
            FioTheme(ThemeMode.LIGHT) {
                when (val s = screen.value) {
                    is Screen.Library -> LibraryScreen(nav, s)
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    Screen.Settings -> SettingsScreen(nav)
                    is Screen.Graph -> GraphScreen(nav, s.topicId)
                    else -> Unit
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pagar o IPVA").fetchSemanticsNodes().isNotEmpty() }
        shot("03-today")
        screen.value = Screen.Library(Filter.TASKS)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Presente de aniversário da Ana").fetchSemanticsNodes().isNotEmpty() }
        shot("04-tasks")
        screen.value = Screen.Library(Filter.DIARY)
        shot("05-diary")
        screen.value = Screen.Library(Filter.ALL, search = true, query = "senha mãe")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Wifi da casa da mãe").fetchSemanticsNodes().isNotEmpty() }
        shot("06-search")
        screen.value = Screen.Entry(ids["contract"]!!)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fechar contrato da torre de Campinas").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        shot("07-entry-task")
        screen.value = Screen.Settings
        shot("08-settings")
        screen.value = Screen.Graph()
        repeat(8) { compose.mainClock.advanceTimeBy(250); Thread.sleep(150) }
        shot("09-graph")
    }

    @Test
    fun theOrbAndTheDark() {
        val level = mutableFloatStateOf(0.7f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FioTheme(ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(FioColors.Background)) {
                    Orb(OrbMode.HOLDING, level, onStart = { true }, onLock = {}, onStop = {}, onCancel = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        shot("10-orb-listening-dark")
    }

    @Test
    fun homeDark() {
        seed()
        val nav = Nav()
        compose.setContent { FioTheme(ThemeMode.DARK) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        shot("11-home-dark")
    }
}
