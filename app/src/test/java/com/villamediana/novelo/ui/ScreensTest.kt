package com.villamediana.novelo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.geometry.Offset
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.EntryTopic
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.data.Topic
import com.villamediana.novelo.mind.MindState
import com.villamediana.novelo.mind.Steps
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.entry.EntryScreen
import com.villamediana.novelo.ui.home.HomeScreen
import com.villamediana.novelo.ui.home.Orb
import com.villamediana.novelo.ui.home.OrbMode
import com.villamediana.novelo.ui.library.LibraryScreen
import com.villamediana.novelo.ui.settings.SettingsScreen
import com.villamediana.novelo.ui.settings.ThemeSection
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
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
 * The real app (NoveloApplication, Room, DataStore, the Mind) with an OpenAI-compatible API played by
 * a MockWebServer: something said goes all the way through, then every screen is drawn and saved
 * to app/build/novelo-shots for a look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val graph get() = NoveloApplication.graph
    private val zone = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    @After fun stop() { server.close() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/novelo-shots").apply { mkdirs() }
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
        ids["topic-pedro"] = pedro
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
            s.setThink(Source.API); s.setThinkModel("gpt-6-luna")
            s.setHintSeen()
        }
    }

    @Test
    fun everyScreen() {
        val ids = seed()
        val tomorrow9 = LocalDate.now(zone).plusDays(1).toString() + "T09:00"
        apiThatAnswers(
            """{"reply":"Anotei a ligação pro Pedro amanhã às 9 e o presente da Ana. E guardei o seu dia.","create":[{"kind":"task","title":"Ligar pro Pedro sobre o contrato","due":"$tomorrow9","remind_at":"$tomorrow9","entities":[{"name":"Pedro","type":"person"}],"related":[${ids["contract"]}]},{"kind":"diary","title":"Hoje foi um dia bom","body":"Hoje foi um dia bom."}],"update":[{"id":${ids["gift"]},"append":"Lembrar de comprar.","remind_at":"$tomorrow9"}]}""",
        )
        val nav = Nav()

        // 1. Idle.
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.onNodeWithText("O que está na sua cabeça?").assertExists()
        // Next to the camera, the gallery.
        compose.onNodeWithContentDescription("Câmera").assertExists()
        compose.onNodeWithContentDescription("Galeria").assertExists()
        shot("01-home-idle")

        // 2. Something written goes through the API (hearing is always on the phone) and comes back organized.
        graph.mind.write("Amanhã às nove eu preciso ligar pro Pedro sobre o contrato, e lembra de comprar o presente da Ana. Ah, e hoje foi um dia bom.")
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
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = screen.value) {
                    is Screen.Library -> LibraryScreen(nav, s)
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    is Screen.Settings -> SettingsScreen(nav)
                    else -> Unit
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pagar o IPVA").fetchSemanticsNodes().isNotEmpty() }
        shot("03-today")
        // The Hoje tab doesn't say "hoje" again on each card.
        assertTrue(compose.onAllNodesWithText("hoje").fetchSemanticsNodes().isEmpty())
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
        // One text per item: no "Andamento" apart, no "Conexões" repeating what the text says.
        assertTrue(compose.onAllNodesWithText("Andamento").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Conexões").fetchSemanticsNodes().isEmpty())
        screen.value = Screen.Settings()
        shot("08-settings")
        // Conexões and Tudo are hidden for now, Arquivos is gone: the last tab is Galeria.
        screen.value = Screen.Library(Filter.TODAY)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Galeria").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Conexões").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Tudo").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Arquivos").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theDaysDiaryShowsTheDaysPhotos() {
        val ids = seed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        // Three photos today, one yesterday (that one belongs to yesterday's diary).
        val colors = listOf(0xFFE8A87C, 0xFF85CDCA, 0xFFC38D9E, 0xFF41B3A3).map { it.toInt() }
        val times = listOf(now - 3_600_000L, now - 1_800_000L, now - 600_000L, now - day)
        runBlocking {
            colors.forEachIndexed { i, color ->
                val f = File(dir, "photo-test-$i.jpg")
                val b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                graph.db.captures().insert(com.villamediana.novelo.data.Capture(createdAt = times[i], source = "photo", imagePath = f.path, state = com.villamediana.novelo.data.Capture.DONE))
            }
        }
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Entry(ids["diary"]!!))
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = screen.value) {
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    is Screen.Library -> LibraryScreen(nav, s)
                    else -> Unit
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("day-photo-0").fetchSemanticsNodes().isNotEmpty() }
        // Today's three slide sideways (yesterday's belongs to yesterday's diary).
        val row = compose.onNodeWithTag("day-photo-row").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo]
        assertEquals(3, row.columnCount)
        compose.mainClock.advanceTimeBy(1_000)
        shot("20-diary-photos")
        compose.onNodeWithTag("day-photo-1").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 / 3").assertExists()
        compose.mainClock.advanceTimeBy(500)
        shot("21-photo-viewer")
        compose.onNodeWithContentDescription("Fechar").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isEmpty() }
        // On the diary's card, the day's photos in a strip of different widths.
        screen.value = Screen.Library(Filter.DIARY)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dia bom no escritório").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        shot("25-diary-card-photos")
    }

    @Test
    fun aSoundKeptPlaysInItsItem() {
        seed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val wav = File(File(context.filesDir, "captures").apply { mkdirs() }, "voice-sung.wav")
        com.villamediana.novelo.audio.Wav.write(wav, FloatArray(com.villamediana.novelo.audio.Wav.RATE * 3) { i ->
            (kotlin.math.sin(i / 9.0) * (0.2 + 0.6 * kotlin.math.abs(kotlin.math.sin(i / 4000.0)))).toFloat()
        })
        val note = runBlocking {
            val id = graph.db.entries().insert(com.villamediana.novelo.data.Entry(kind = "note", title = "Música que eu inventei", body = "A do caminho de casa.", createdAt = now, updatedAt = now))
            val c = graph.db.captures().insert(
                com.villamediana.novelo.data.Capture(
                    createdAt = now, source = "voice", audioPath = wav.path, sound = true, durationMs = 3000, transcript = "lá lá lá",
                    state = com.villamediana.novelo.data.Capture.DONE, outcomes = """[{"entryId":$id,"action":"created"}]""",
                ),
            )
            graph.db.entries().update(graph.db.entries().get(id)!!.copy(captureId = c))
            id
        }
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { com.villamediana.novelo.ui.entry.EntryScreen(nav, note) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Ouvir o áudio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0:03").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        shot("27-entry-sound")
    }

    @Test
    fun photosWaitOnTheMainScreenAndATaskKeepsThem() {
        seed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val colors = listOf(0xFF85CDCA, 0xFFE8A87C, 0xFFC38D9E).map { it.toInt() }
        val files = colors.mapIndexed { i, color ->
            File(context.cacheDir, "wait-$i.jpg").also { f ->
                val b = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        graph.mind.stage(files.map { it to now })
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Home)
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = screen.value) {
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    else -> HomeScreen(nav, null) {}
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("staged-2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Enviar as fotos").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        shot("28-home-photos-waiting")
        // One let go.
        compose.onAllNodesWithContentDescription("Tirar a foto")[2].performClick()
        compose.waitUntil(3_000) { graph.mind.staged.value.size == 2 }

        // A task holding photos shows them under its text.
        val task = runBlocking {
            val id = graph.db.entries().insert(com.villamediana.novelo.data.Entry(kind = "task", title = "Comprar o tênis azul pra Alma", status = "todo", createdAt = now, updatedAt = now))
            val said = graph.db.captures().insert(
                com.villamediana.novelo.data.Capture(createdAt = now, source = "text", transcript = "comprar isso pra Alma", state = com.villamediana.novelo.data.Capture.DONE, outcomes = """[{"entryId":$id,"action":"created","photos":true}]"""),
            )
            graph.mind.staged.value.forEach { s ->
                val f = File(context.filesDir, s.file.name).also { s.file.copyTo(it, overwrite = true) }
                graph.db.captures().insert(com.villamediana.novelo.data.Capture(createdAt = now, source = "photo", imagePath = f.path, state = com.villamediana.novelo.data.Capture.DONE, partOf = said))
            }
            id
        }
        screen.value = Screen.Entry(task)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-photo-1").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        shot("29-task-photos")
    }

    @Test
    fun theGalleryHasEveryPhotoAndTheSearchFindsThemByWhatTheyShow() {
        seed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        // Different shapes: the gallery keeps each one's.
        val shots = listOf(
            Triple(0xFFE8A87C.toInt(), 60 to 80, "Pôr do sol na praia de Itacaré, céu laranja"),
            Triple(0xFF85CDCA.toInt(), 80 to 50, "Alma no aeroporto de Guarulhos com a mala azul"),
            Triple(0xFFC38D9E.toInt(), 60 to 60, "Café da manhã com pão de queijo"),
            Triple(0xFF41B3A3.toInt(), 50 to 90, null),
        )
        runBlocking {
            shots.forEachIndexed { i, (color, size, about) ->
                val f = File(dir, "gallery-test-$i.jpg")
                val b = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                graph.db.captures().insert(com.villamediana.novelo.data.Capture(createdAt = now - i * day, source = "photo", imagePath = f.path, state = com.villamediana.novelo.data.Capture.DONE, description = about))
            }
        }
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Library(Filter.GALLERY))
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { (screen.value as? Screen.Library)?.let { LibraryScreen(nav, it) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("gallery-photo-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Galeria").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        shot("22-gallery")
        compose.onNodeWithTag("gallery-photo-0").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isNotEmpty() }
        // What it shows is only for the search: never under the photo.
        assertTrue(compose.onAllNodesWithText("Pôr do sol na praia de Itacaré, céu laranja").fetchSemanticsNodes().isEmpty())
        // The light menu under it turns the photo, and the turn stays with it.
        compose.onAllNodesWithContentDescription("Girar")[0].performClick() // the page next to it is drawn too
        compose.mainClock.advanceTimeBy(600)
        val turned = runBlocking { withTimeout(5_000) { graph.db.captures().allPhotos().first { it.imagePath!!.endsWith("gallery-test-0.jpg") }.let { c -> while (graph.db.captures().get(c.id)!!.turn != 90) kotlinx.coroutines.delay(20); graph.db.captures().get(c.id)!! } } }
        assertEquals(90, turned.turn)
        shot("24-viewer-turned")
        assertTrue(compose.onAllNodesWithContentDescription("Baixar").fetchSemanticsNodes().isNotEmpty())
        // Taking it out of Novelo asks once more; then it is gone, its file too.
        compose.onAllNodesWithContentDescription("Remover")[0].performClick()
        compose.mainClock.advanceTimeBy(400)
        shot("26-viewer-remove")
        compose.onNodeWithContentDescription("Remover a foto").performClick()
        runBlocking { withTimeout(5_000) { while (graph.db.captures().get(turned.id) != null) kotlinx.coroutines.delay(20) } }
        assertTrue(!File(turned.imagePath!!).exists())
        compose.waitUntil(3_000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Fechar").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isEmpty() }

        // The search finds a photo by what it shows, even by the start of a word.
        screen.value = Screen.Library(Filter.ALL, search = true, query = "aeroport")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("found-photo-0").fetchSemanticsNodes().isNotEmpty() }
        val found = compose.onNodeWithTag("found-photo-row").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo]
        assertEquals(1, found.columnCount)
        shot("23-search-photo")
    }

    @Test
    fun aTurnedPhotoIsRedrawnWhereItShows() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(context.cacheDir, "turn-test.jpg")
        Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF85CDCA.toInt()) }
            .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val turn = androidx.compose.runtime.mutableIntStateOf(0)
        var size = 0 to 0
        compose.setContent {
            val b = com.villamediana.novelo.ui.components.rememberPhoto(f.path, 200, turn.intValue)
            size = (b?.width ?: 0) to (b?.height ?: 0)
        }
        compose.waitUntil(5_000) { size == 80 to 40 }
        // Turned (from the viewer), the same photo on screen follows without leaving it.
        turn.intValue = 90
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        runCatching { compose.waitUntil(5_000) { size == 40 to 80 } }.onFailure { throw AssertionError("still $size") }
    }

    @Test
    fun swipesMoveAlongTheLine() {
        seed()
        val nav = Nav()
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = nav.top) {
                    Screen.Home -> HomeScreen(nav, talkAbout = null, onTalkHandled = {})
                    is Screen.Library -> LibraryScreen(nav, s)
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
        fun slide(toLeft: Boolean) { compose.onRoot().performTouchInput {
            val y = height * 0.72f
            val (a, b) = if (toLeft) width * 0.85f to width * 0.15f else width * 0.15f to width * 0.85f
            swipe(Offset(a, y), Offset(b, y), durationMillis = 250)
        } }
        // Main screen: to the right is the camera, to the left the library's first tab.
        slide(toLeft = false)
        compose.waitUntil(3_000) { nav.top == Screen.Camera }
        nav.back()
        compose.waitForIdle()
        slide(toLeft = true)
        compose.waitUntil(3_000) { (nav.top as? Screen.Library)?.filter == Filter.TODAY }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pagar o IPVA").fetchSemanticsNodes().isNotEmpty() }
        // In the library a swipe to the left is the next tab, to the right the one before.
        slide(toLeft = true)
        compose.waitUntil(3_000) { (nav.top as? Screen.Library)?.filter == Filter.TASKS }
        slide(toLeft = false)
        compose.waitUntil(3_000) { (nav.top as? Screen.Library)?.filter == Filter.TODAY }
        // On the first tab, back to the right is home.
        slide(toLeft = false)
        compose.waitUntil(3_000) { nav.top == Screen.Home }
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun aThumbSwipeOnHomeGoesEvenWhenItIsNotStraight() {
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.waitForIdle()
        // Under the button there is a part that scrolls up and down: a thumb's swipe is never
        // straight, and its little bit of up/down must not take the swipe away.
        val scrolls = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().first().boundsInRoot
        compose.onRoot().performTouchInput {
            val y = scrolls.top + scrolls.height * 0.3f
            swipe(Offset(width * 0.9f, y), Offset(width * 0.1f, y + scrolls.height * 0.6f), durationMillis = 250)
        }
        compose.waitUntil(3_000) { (nav.top as? Screen.Library)?.filter == Filter.TODAY }
    }

    @Test
    fun homeGoesAlongWithTheFinger() {
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.waitForIdle()
        val before = compose.onNodeWithText("Hoje").fetchSemanticsNode().boundsInRoot.center.x
        // A little push back, not much: the screen goes 70% of the finger's way.
        val dp = compose.density.density
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.85f, height * 0.72f))
            moveBy(Offset(-20 * dp, 0f))
            moveBy(Offset(-30 * dp, 0f))
        }
        compose.waitForIdle()
        // 50 dp of finger, less the touch slop (16 dp here): 0.3 leaned ~10 dp, 1 ~34 dp, 0.6 ~20 dp, 0.7 ~24 dp.
        val leaned = (before - compose.onNodeWithText("Hoje").fetchSemanticsNode().boundsInRoot.center.x) / dp
        assertTrue("leaned $leaned dp", leaned in 21.5f..27f)
        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun homeComesBackToTheMiddleAfterASwipe() {
        val nav = Nav()
        // The main screen kept on (as when she comes back before its way out ended).
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.waitForIdle()
        val before = compose.onNodeWithText("Hoje").fetchSemanticsNode().boundsInRoot.center.x
        compose.onRoot().performTouchInput {
            val y = height * 0.72f
            swipe(Offset(width * 0.85f, y), Offset(width * 0.15f, y), durationMillis = 250)
        }
        compose.waitUntil(3_000) { nav.top is Screen.Library }
        compose.waitForIdle()
        nav.back()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val after = compose.onNodeWithText("Hoje").fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(before, after, 2f)
    }

    @Test
    fun theLibraryComesBackToTheMiddleAfterASwipeHome() {
        seed()
        val nav = Nav().apply { go(Screen.Library(Filter.TODAY)) }
        // The library kept on (as when she comes right back before its way out ended).
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { LibraryScreen(nav, Screen.Library(Filter.TODAY)) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pagar o IPVA").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onAllNodesWithText("Pagar o IPVA")[0].fetchSemanticsNode().boundsInRoot.left
        compose.onRoot().performTouchInput {
            val y = height * 0.72f
            swipe(Offset(width * 0.15f, y), Offset(width * 0.85f, y), durationMillis = 120)
        }
        compose.waitUntil(3_000) { nav.top == Screen.Home }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val after = compose.onAllNodesWithText("Pagar o IPVA")[0].fetchSemanticsNode().boundsInRoot.left
        assertEquals(before, after, 2f)
    }

    @Test
    fun aPhotoZoomsInAndThenHoldsTheFinger() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val photos = (0..1).map { i ->
            val f = File(context.filesDir, "zoom-$i.jpg")
            Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(if (i == 0) 0xFFE8A87C.toInt() else 0xFF7CA8E8.toInt()) }
                .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            com.villamediana.novelo.mind.Photo(i + 1L, f.path, now - i * day, null, null)
        }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { com.villamediana.novelo.ui.components.PhotoViewer(photos, 0, onClose = {}) } }
        compose.waitUntil(3_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
        fun swipe() = compose.onAllNodesWithContentDescription("photo-viewer")[0].performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.4f), Offset(width * 0.2f, height * 0.4f), durationMillis = 200)
        }
        // Two taps: zoomed in — a swipe now moves around in the photo, not to the next one.
        compose.onAllNodesWithContentDescription("photo-viewer")[0].performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(600)
        swipe()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("1 / 2").assertExists()
        // Two taps again: whole again, and a swipe is the next photo.
        compose.onAllNodesWithContentDescription("photo-viewer")[0].performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(600)
        swipe()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aSensitiveItemHidesItsPhotosToo() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = runBlocking {
            val e = graph.db.entries().insert(Entry(kind = "note", title = "RG e CPF", body = "RG 12.345.678-9", sensitive = true, createdAt = now, updatedAt = now))
            val said = graph.db.captures().insert(Capture(createdAt = now, source = "text", transcript = "meus documentos", state = Capture.DONE, outcomes = """[{"entryId":$e,"action":"created","photos":true}]"""))
            val f = File(context.filesDir, "rg.jpg")
            Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF7CA8E8.toInt()) }.let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            graph.db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = f.path, state = Capture.DONE, partOf = said))
            e
        }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        // Hidden: the text and its photo (a tap on the photo asks like the text's).
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-photo-hidden-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Toque pra ver").assertExists()
        compose.mainClock.advanceTimeBy(600)
        shot("50-sensitive-hidden")
        compose.onNodeWithTag("entry-photo-hidden-0").performClick()
        // (No lock asked here: she didn't turn it on.) Shown: the photo opens.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-photo-0").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Toque pra ver").fetchSemanticsNodes().isEmpty())
    }

    /** The ⋮ menu has what makes sense for what the item is; deleting asks once, the phone's way. */
    @Test
    fun theMenuOffersWhatMakesSense() {
        val ids = runBlocking {
            listOf("task", "note", "diary").associateWith { k -> graph.db.entries().insert(Entry(kind = k, title = "Um $k", body = "Texto.", status = if (k == "task") "todo" else null, createdAt = now, updatedAt = now)) }
        }
        val open = androidx.compose.runtime.mutableStateOf(ids.getValue("task"))
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { androidx.compose.runtime.key(open.value) { EntryScreen(Nav(), open.value) } } }
        // Another item opens in a new screen (its menu closed).
        fun menu(id: Long): List<String> {
            open.value = id
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mais").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Mais").performClick()
            compose.waitUntil(3_000) { compose.onAllNodesWithTag("menu-delete").fetchSemanticsNodes().isNotEmpty() }
            val shown = listOf("menu-share", "menu-sensitive", "menu-delete").filter { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
            return shown
        }
        assertEquals(listOf("menu-delete"), menu(ids.getValue("task")))
        assertEquals(listOf("menu-sensitive", "menu-delete"), menu(ids.getValue("diary")))
        assertEquals(listOf("menu-share", "menu-sensitive", "menu-delete"), menu(ids.getValue("note")))
        compose.mainClock.advanceTimeBy(400)
        shot("56-note-menu")
        // Deleting: a dialog in the middle, and only "Apagar" takes it away.
        compose.onNodeWithTag("menu-delete").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("confirm-yes").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(400)
        shot("57-delete-dialog")
        compose.onNodeWithTag("confirm-yes").performClick()
        runBlocking { withTimeout(5_000) { while (graph.db.entries().get(ids.getValue("note")) != null) kotlinx.coroutines.delay(20) } }
    }

    /** Hidden, a file's preview is there blurred like the text (consistent), not left out. */
    @Test
    fun aHiddenItemBlursItsFilesPreview() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = runBlocking {
            val e = graph.db.entries().insert(Entry(kind = "note", title = "Senhas", body = "As do banco.", sensitive = true, createdAt = now, updatedAt = now))
            val f = File(File(context.filesDir, "captures/files/7-7").apply { mkdirs() }, "senhas.txt").apply { writeText("banco 1234\ncartão 9876") }
            graph.db.captures().insert(
                Capture(createdAt = now, source = Capture.FILE, filePath = f.path, fileName = f.name, fileMime = "text/plain", fileSize = f.length(), state = Capture.DONE, outcomes = """[{"entryId":$e,"action":"created","files":true}]"""),
            )
            e
        }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-hidden-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-preview-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Toque pra ver").assertExists()
        compose.mainClock.advanceTimeBy(600)
        shot("58-hidden-file-preview")
    }

    @Test
    fun filesLiveInTheirItemAndTheSearchFindsThem() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = runBlocking {
            val e = graph.db.entries().insert(Entry(kind = "note", title = "Meu currículo", body = "A versão de 2026.", createdAt = now, updatedAt = now))
            val f = File(File(context.filesDir, "captures/files/1-1").apply { mkdirs() }, "CV Miguel 2026.pdf").apply { writeBytes(ByteArray(240_000)) }
            graph.db.captures().insert(
                Capture(
                    createdAt = now, source = Capture.FILE, filePath = f.path, fileName = "CV Miguel 2026.pdf", fileMime = "application/pdf", fileSize = f.length(),
                    description = "currículo do Miguel", state = Capture.DONE, outcomes = """[{"entryId":$e,"action":"created","files":true}]""",
                ),
            )
            e
        }
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Entry(id))
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = screen.value) {
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    is Screen.Library -> LibraryScreen(nav, s)
                    else -> {}
                }
            }
        }
        // In its note: one line with its name, kind and size.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CV Miguel 2026.pdf").assertExists()
        compose.onNodeWithText("PDF · 234 KB").assertExists()
        compose.mainClock.advanceTimeBy(600)
        shot("52-entry-file")
        // The search finds it by what it is, saying where it lives.
        screen.value = Screen.Library(Filter.ALL, search = true, query = "curriculo")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("found-file-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PDF · 234 KB · Meu currículo").assertExists()
        compose.mainClock.advanceTimeBy(600)
        shot("54-files-search")
    }

    /** A file in its item shows what it looks like: a photo, the first words of a text (a PDF's first page: on the phone, Robolectric draws no PDF). */
    @Test
    fun filesShowWhatTheyLookLike() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "captures/files/9-9").apply { mkdirs() }
        val photo = File(dir, "Planta da sala.png").apply {
            val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF85CDCA.toInt()) }
            outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val text = File(dir, "Lista da mudança.txt").apply { writeText("Caixas da cozinha\nLivros da estante\nRoupas de inverno") }
        val id = runBlocking {
            val e = graph.db.entries().insert(Entry(kind = "note", title = "Mudança", body = "O que vai na mudança.", createdAt = now, updatedAt = now))
            listOf(Triple(photo, "image/png", 0), Triple(text, "text/plain", 1)).forEach { (f, mime, k) ->
                graph.db.captures().insert(
                    Capture(
                        createdAt = now + k, source = Capture.FILE, filePath = f.path, fileName = f.name, fileMime = mime, fileSize = f.length(),
                        state = Capture.DONE, outcomes = """[{"entryId":$e,"action":"created","files":true}]""",
                    ),
                )
            }
            e
        }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        // The card is one tap (its parts merged): the previews are looked for inside it.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-preview-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-preview-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Caixas da cozinha", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(800)
        shot("55-file-previews")
    }

    /** A file held: open it, share it, or delete it (asked once). */
    @Test
    fun aFileHeldOffersWhatMakesSense() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(File(context.filesDir, "captures/files/8-8").apply { mkdirs() }, "recibo.txt").apply { writeText("Recibo do aluguel") }
        val (id, fileId) = runBlocking {
            val e = graph.db.entries().insert(Entry(kind = "note", title = "Aluguel", body = "O recibo de novembro.", createdAt = now, updatedAt = now))
            e to graph.db.captures().insert(
                Capture(createdAt = now, source = Capture.FILE, filePath = f.path, fileName = f.name, fileMime = "text/plain", fileSize = f.length(), state = Capture.DONE, outcomes = """[{"entryId":$e,"action":"created","files":true}]"""),
            )
        }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-file-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entry-file-0").performTouchInput { longClick() }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("file-menu-delete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file-menu-open").assertExists()
        compose.onNodeWithTag("file-menu-share").assertExists()
        compose.mainClock.advanceTimeBy(400)
        shot("59-file-menu")
        compose.onNodeWithTag("file-menu-delete").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("confirm-yes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirm-yes").performClick()
        runBlocking { withTimeout(5_000) { while (graph.db.captures().get(fileId)?.filePath != null && f.exists()) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun theMainScreenAttachesFiles() {
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { com.villamediana.novelo.ui.home.HomeScreen(Nav(), null) {} } }
        compose.onNodeWithContentDescription("Anexar arquivo").assertExists()
        // A file waiting shows as a small sheet with its kind, and goes with what she says next.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(context.cacheDir, "picked-x").apply { writeBytes(ByteArray(100)) }
        graph.mind.stageFiles(listOf(f to "Contrato aluguel.pdf"))
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("staged-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fale ou escreva o que é esse arquivo, ou envie assim").assertExists()
            compose.onNodeWithText("PDF").assertExists()
            compose.mainClock.advanceTimeBy(600)
            shot("55-staged-file")
        } finally {
            graph.mind.staged.value.forEach(graph.mind::unstage)
        }
    }

    @Test
    fun theWorkSaysWhereItIs() {
        fun w(stage: com.villamediana.novelo.mind.Stage, step: Int = 0, of: Int = 0) = com.villamediana.novelo.ui.home.stageText(MindState.Working(1, stage, "", step = step, of = of))
        assertEquals("Transcrevendo… parte 2 de 4", w(com.villamediana.novelo.mind.Stage.HEARING, 2, 4))
        assertEquals("Transcrevendo…", w(com.villamediana.novelo.mind.Stage.HEARING, 1, 1))
        assertEquals("Olhando as fotos… 1 de 3", w(com.villamediana.novelo.mind.Stage.SEEING, 1, 3))
        assertEquals("Olhando a foto…", w(com.villamediana.novelo.mind.Stage.SEEING))
        assertEquals("Vendo o que é o arquivo…", w(com.villamediana.novelo.mind.Stage.FILES, 1, 1))
        assertEquals("Organizando…", w(com.villamediana.novelo.mind.Stage.THINKING))
    }

    @Test
    fun aTaskMadeByHandOpensOnItsTitle() {
        val nav = Nav().apply { go(Screen.Library(Filter.TASKS)) }
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = nav.top) {
                    is Screen.Library -> LibraryScreen(nav, s)
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    else -> {}
                }
            }
        }
        compose.onNodeWithContentDescription("Nova tarefa").performClick()
        compose.waitUntil(5_000) { nav.top is Screen.Entry }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Título").fetchSemanticsNodes().isNotEmpty() }
        // The quiet chips say what they are.
        compose.onNodeWithText("Lembrete").assertExists()
        compose.onNodeWithText("Prazo").assertExists()
        compose.mainClock.advanceTimeBy(600)
        shot("56-new-task")
        val id = (nav.top as Screen.Entry).id
        // Left with nothing written: not kept.
        nav.back()
        compose.waitForIdle()
        runBlocking { withTimeout(5_000) { while (graph.db.entries().get(id) != null) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun theWordsShowWhileHeardNotWhileOrganizing() {
        val state = androidx.compose.runtime.mutableStateOf(MindState.Working(7, com.villamediana.novelo.mind.Stage.HEARING, "fui correr de manhã", step = 2, of = 3))
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    com.villamediana.novelo.ui.home.WorkingText(state.value) {}
                }
            }
        }
        compose.onNodeWithText("Transcrevendo… parte 2 de 3").assertExists()
        compose.onNodeWithText("fui correr de manhã", substring = true).assertExists()
        // It can be minimized or closed: it says so.
        compose.onNodeWithText("Pode minimizar ou fechar o app", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(400)
        shot("57-working-hearing")
        state.value = MindState.Working(7, com.villamediana.novelo.mind.Stage.THINKING, "fui correr de manhã")
        compose.waitForIdle()
        compose.onNodeWithText("Organizando…").assertExists()
        assertTrue(compose.onAllNodesWithText("fui correr de manhã", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aNoteHasNoReminderBellATaskHas() {
        val (note, task) = runBlocking {
            graph.db.entries().insert(Entry(kind = "note", title = "Senha do wifi", createdAt = now, updatedAt = now)) to
                graph.db.entries().insert(Entry(kind = "task", title = "Ligar pro Pedro", status = "todo", createdAt = now, updatedAt = now))
        }
        val screen = androidx.compose.runtime.mutableStateOf(note)
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { androidx.compose.runtime.key(screen.value) { EntryScreen(Nav(), screen.value) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Senha do wifi").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Lembrete").fetchSemanticsNodes().isEmpty())
        screen.value = task
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ligar pro Pedro").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Lembrete").assertExists()
    }

    @Test
    fun aReminderThatComesBackSetByHand() {
        val id = runBlocking { graph.db.entries().insert(Entry(kind = "task", title = "Academia", status = "todo", createdAt = now, updatedAt = now)) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Academia").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Lembrete").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dias da semana").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dias da semana").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("repeat-day-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("repeat-day-1").performClick()
        compose.onNodeWithTag("repeat-day-3").performClick()
        compose.mainClock.advanceTimeBy(500)
        shot("58-reminder-repeat")
        compose.onNodeWithTag("reminder-save").performClick()
        val rule = runBlocking { withTimeout(5_000) { var r: com.villamediana.novelo.remind.Repeat? = null; while (r == null) { r = com.villamediana.novelo.remind.Repeat.read(graph.db.entries().get(id)!!.repeat); if (r == null) kotlinx.coroutines.delay(20) }; r } }
        assertEquals(com.villamediana.novelo.remind.Repeat.WEEK, rule.every)
        assertTrue("${rule.days}", 1 in rule.days && 3 in rule.days)
        assertTrue(runBlocking { graph.db.entries().get(id)!!.remindAt!! } > System.currentTimeMillis())
    }

    /** A finger held on [node] (as long as a long press) and then moved down, [steps] times by [by] of its height. */
    private fun holdAndDrag(node: androidx.compose.ui.test.SemanticsNodeInteraction, steps: Int, by: Float) {
        compose.mainClock.autoAdvance = false
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        repeat(steps) {
            node.performTouchInput { moveBy(Offset(0f, height * by)) }
            compose.mainClock.advanceTimeBy(48)
        }
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun stepsTickByTheCircleHoldToMoveTapToWrite() {
        val id = runBlocking {
            graph.db.entries().insert(Entry(kind = "task", title = "Mudança", status = "todo", steps = com.villamediana.novelo.mind.Steps.write(listOf(com.villamediana.novelo.data.Step("caixas"), com.villamediana.novelo.data.Step("fita"), com.villamediana.novelo.data.Step("caminhão"))), createdAt = now, updatedAt = now))
        }
        fun steps() = runBlocking { com.villamediana.novelo.mind.Steps.read(graph.db.entries().get(id)!!.steps) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { EntryScreen(Nav(), id) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("caixas").fetchSemanticsNodes().isNotEmpty() }
        // Held: it neither ticks nor goes; dragged down, it moves.
        holdAndDrag(compose.onNodeWithText("caixas"), steps = 12, by = 0.25f)
        compose.waitUntil(5_000) { steps().map { it.text } != listOf("caixas", "fita", "caminhão") }
        assertEquals(listOf("fita", "caminhão", "caixas"), steps().map { it.text })
        assertTrue(steps().none { it.done })
        // A tap on the words writes them again; emptied, the step goes.
        compose.onNodeWithText("fita").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("step-editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("step-editor").performTextReplacement("")
        compose.onNodeWithTag("step-editor").performImeAction()
        compose.waitUntil(5_000) { steps().size == 2 }
        assertEquals(listOf("caminhão", "caixas"), steps().map { it.text })
    }

    @Test
    fun theDiaryIsCrossedByItsEdgeAndKeepsItsPlace() {
        // Two hundred days of diary: the first one far down the list.
        val start = LocalDate.now(zone).minusDays(200)
        runBlocking {
            for (i in 0 until 200) {
                val at = start.plusDays(i.toLong()).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
                graph.db.entries().insert(Entry(kind = "diary", title = if (i == 0) "O primeiro dia" else "Dia $i", body = "O que foi o dia $i.", createdAt = at, updatedAt = at))
            }
        }
        val nav = Nav().apply { go(Screen.Library(Filter.DIARY)) }
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                val kept = rememberLibraryKept(nav)
                when (val s = nav.top) {
                    is Screen.Library -> kept.SaveableStateProvider(LIBRARY_KEPT) { LibraryScreen(nav, s) }
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    else -> Unit
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dia 199").fetchSemanticsNodes().isNotEmpty() }
        // The list moves: the handle slides in. Still a moment, it goes (nothing on the edge); the
        // list moves again, it comes back.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("entries").performTouchInput { swipe(Offset(centerX, bottom - 100f), Offset(centerX, bottom - 500f), 200) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue(compose.onAllNodesWithTag("fast-scroll").fetchSemanticsNodes().isNotEmpty())
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue(compose.onAllNodesWithTag("fast-scroll").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("entries").performTouchInput { swipe(Offset(centerX, bottom - 100f), Offset(centerX, bottom - 300f), 200) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(compose.onAllNodesWithTag("fast-scroll").fetchSemanticsNodes().isNotEmpty())
        // Taken anywhere along the edge (here far below the handle) and down to the end: the month
        // there beside the finger.
        compose.onNodeWithTag("fast-scroll-track").performTouchInput { down(Offset(centerX, height * 0.6f)); moveBy(Offset(0f, 60f)); moveTo(Offset(centerX, bottom - 1f)) }
        compose.mainClock.advanceTimeBy(300)
        val first = start.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, com.villamediana.novelo.ui.components.PT)
            .replaceFirstChar { it.titlecase(com.villamediana.novelo.ui.components.PT) } + " " + start.year
        compose.onNodeWithTag("fast-scroll-label").assertTextEquals(first)
        shot("26-diary-fast-scroll")
        compose.onNodeWithTag("fast-scroll-track").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { compose.onAllNodesWithText("O primeiro dia").fetchSemanticsNodes().isNotEmpty() }
        // Opened and back: the list is where it was, not at the top.
        compose.onNodeWithText("O primeiro dia").performClick()
        compose.waitUntil(5_000) { nav.top is Screen.Entry }
        compose.waitForIdle()
        nav.back()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("O primeiro dia").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Dia 199").fetchSemanticsNodes().isEmpty())
        // Home and in again: from the top.
        nav.back()
        compose.waitForIdle()
        nav.go(Screen.Library(Filter.DIARY))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dia 199").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Every tab has the handle on the edge: Notas says the month, Tarefas the group under the finger. */
    @Test
    fun everyTabIsCrossedByItsEdge() {
        val start = LocalDate.now(zone).minusDays(120)
        runBlocking {
            for (i in 0 until 120) {
                val at = start.plusDays(i.toLong()).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                graph.db.entries().insert(Entry(kind = "note", title = if (i == 0) "A nota mais antiga" else "Nota $i", body = "Uma ideia.", createdAt = at, updatedAt = at))
            }
            for (i in 0 until 40) graph.db.entries().insert(Entry(kind = "task", title = "Tarefa $i", status = "todo", position = i.toLong(), createdAt = now - i * 60_000L, updatedAt = now))
        }
        val nav = Nav().apply { go(Screen.Library(Filter.NOTES)) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { (nav.top as? Screen.Library)?.let { LibraryScreen(nav, it) } } }
        fun crossToTheEnd(expect: String) {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("entries").performTouchInput { swipe(Offset(centerX, bottom - 100f), Offset(centerX, bottom - 400f), 200) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("fast-scroll-track").performTouchInput { down(Offset(centerX, height * 0.5f)); moveBy(Offset(0f, 60f)); moveTo(Offset(centerX, bottom - 1f)) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("fast-scroll-label").assertTextEquals(expect)
            compose.onNodeWithTag("fast-scroll-track").performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nota 119").fetchSemanticsNodes().isNotEmpty() }
        crossToTheEnd(start.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, com.villamediana.novelo.ui.components.PT).replaceFirstChar { it.titlecase(com.villamediana.novelo.ui.components.PT) } + " " + start.year)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A nota mais antiga").fetchSemanticsNodes().isNotEmpty() }
        nav.go(Screen.Library(Filter.TASKS))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tarefa 0").fetchSemanticsNodes().isNotEmpty() }
        crossToTheEnd("A fazer")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tarefa 39").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aTaskHeldAndDraggedMovesInItsGroup() {
        val ids = runBlocking {
            listOf("Primeira", "Segunda", "Terceira").mapIndexed { i, title -> graph.db.entries().insert(Entry(kind = "task", title = title, status = "todo", position = i.toLong(), createdAt = now, updatedAt = now)) }
        }
        val nav = Nav().apply { go(Screen.Library(Filter.TASKS)) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { LibraryScreen(nav, Screen.Library(Filter.TASKS)) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Primeira").fetchSemanticsNodes().isNotEmpty() }
        holdAndDrag(compose.onNodeWithText("Primeira"), steps = 16, by = 0.3f)
        val order = runBlocking {
            withTimeout(5_000) {
                var o: List<Long>
                while (true) {
                    val pos = ids.associateWith { graph.db.entries().get(it)!!.position }
                    o = ids.sortedBy { pos[it] }
                    if (o.first() != ids[0]) break
                    kotlinx.coroutines.delay(20)
                }
                o
            }
        }
        assertEquals(ids[1], order.first())
    }

    /** Something to hear or think with missing: the button is locked (a lock, nothing records) and a card says what, with the way to it. */
    @Test
    fun theButtonIsLockedUntilItCanHearAndThink() {
        val st = graph.settings
        runBlocking {
            st.setHear(Source.API)
            st.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "")
            st.setApiKey(com.villamediana.novelo.settings.Job.HEAR, "")
            st.setModel(com.villamediana.novelo.settings.Job.HEAR, "")
            st.setThink(Source.NOVELO)
            st.setHintSeen()
        }
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("ready-card").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Falta configurar quem ouve.").assertExists()
            compose.onNodeWithText("Configurar").assertExists()
            assertTrue(compose.onAllNodesWithText("O que está na sua cabeça?").fetchSemanticsNodes().isEmpty())
            // A tap on the button records nothing.
            compose.onNodeWithContentDescription("Falar").performClick()
            compose.mainClock.advanceTimeBy(500)
            assertTrue(graph.mind.state.value !is MindState.Listening)
            shot("64-home-locked")
            // Set up: the card goes, the button is itself again.
            server.start()
            runBlocking {
                st.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "custom")
                st.setApiBase(com.villamediana.novelo.settings.Job.HEAR, server.url("/v1").toString())
                st.setApiKey(com.villamediana.novelo.settings.Job.HEAR, "k")
                st.setModel(com.villamediana.novelo.settings.Job.HEAR, "whisper-1")
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("ready-card").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("O que está na sua cabeça?").assertExists()
        } finally {
            runBlocking {
                st.setHear(Source.NOVELO)
                st.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "")
                st.setApiBase(com.villamediana.novelo.settings.Job.HEAR, "")
                st.setApiKey(com.villamediana.novelo.settings.Job.HEAR, "")
                st.setModel(com.villamediana.novelo.settings.Job.HEAR, "")
            }
        }
    }

    /** The card when the ears are missing: the download right there, or her API instead (in Ajustes). */
    @Test
    fun theEarsCanComeFromTheMainScreen() {
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(NoveloColors.Background).padding(22.dp)) {
                    com.villamediana.novelo.ui.home.ReadyCard(listOf(com.villamediana.novelo.ui.home.Missing.EARS, com.villamediana.novelo.ui.home.Missing.THINK_API), nudge = 0, onSettings = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("ou ouvir pela sua API").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Falta configurar quem pensa.").assertExists()
        compose.mainClock.advanceTimeBy(500)
        shot("64b-ready-card-ears")
    }

    /** Tarefas: the categories as filters on top, each card says its own; in the task, a chip to change it. */
    @Test
    fun tasksGoByCategory() {
        val ids = runBlocking {
            listOf("Mandar o relatório" to "Trabalho", "Consertar a pia" to "Casa", "Ligar pro cliente" to "Trabalho", "Comprar ração" to "Casa", "Marcar dentista" to "Saúde")
                .mapIndexed { i, (title, c) -> graph.db.entries().insert(Entry(kind = "task", title = title, status = "todo", category = c, createdAt = now - i, updatedAt = now)) }
        }
        val nav = Nav().apply { go(Screen.Library(Filter.TASKS)) }
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Library(Filter.TASKS))
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val sc = screen.value) {
                    is Screen.Library -> LibraryScreen(nav, sc)
                    is Screen.Entry -> EntryScreen(nav, sc.id)
                    else -> Unit
                }
            }
        }
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("category-filter").fetchSemanticsNodes().isNotEmpty() }
            // All, then the ones with the most open tasks; each card with its category.
            for (c in listOf("Todas", "Trabalho", "Casa", "Saúde")) compose.onAllNodesWithText(c)[0].assertExists()
            compose.mainClock.advanceTimeBy(800)
            shot("62-tasks-categories")
            // One category: only its tasks.
            compose.onAllNodesWithText("Casa")[0].performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Mandar o relatório").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Consertar a pia").assertExists()
            compose.onNodeWithText("Comprar ração").assertExists()
            compose.mainClock.advanceTimeBy(800)
            shot("62b-tasks-one-category")
            // In the task: its category as a chip; a tap opens them, another puts it in another.
            screen.value = Screen.Entry(ids[1])
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-category").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(500)
            shot("63-task-category")
            compose.onNodeWithTag("entry-category").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("category-new").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(500)
            shot("63b-task-category-sheet")
            compose.onAllNodesWithText("Trabalho")[0].performClick()
            compose.waitUntil(5_000) { runBlocking { graph.db.entries().get(ids[1])!!.category } == "Trabalho" }
            // The sheet closed, and the chip in the task says the new one.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("category-new").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Trabalho", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText("Casa", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        } finally {
            runBlocking { ids.forEach { graph.db.entries().delete(it) } }
        }
    }

    /** Notas: one list in the order she sets — the newest on top, a note held and dragged moves; each card says its day. */
    @Test
    fun aNoteHeldAndDraggedMoves() {
        val ids = runBlocking {
            listOf("Primeira", "Segunda", "Terceira").mapIndexed { i, title -> graph.db.entries().insert(Entry(kind = "note", title = title, body = "Ideia $i.", createdAt = now - (3 - i) * day, updatedAt = now)) }
        }
        val nav = Nav().apply { go(Screen.Library(Filter.NOTES)) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { LibraryScreen(nav, Screen.Library(Filter.NOTES)) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Terceira").fetchSemanticsNodes().isNotEmpty() }
        // The newest first, no day labels; the day on the card.
        fun top(t: String) = compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("Terceira") < top("Segunda") && top("Segunda") < top("Primeira"))
        assertTrue(compose.onAllNodesWithText("Hoje").fetchSemanticsNodes().size <= 1) // only the tab, no day label
        compose.mainClock.advanceTimeBy(400)
        shot("60-notes-by-hand")
        holdAndDrag(compose.onNodeWithText("Terceira"), steps = 16, by = 0.3f)
        val order = runBlocking {
            withTimeout(5_000) {
                var o: List<Long>
                while (true) {
                    val pos = ids.associateWith { graph.db.entries().get(it)!!.position }
                    o = ids.filter { pos[it] != null }.sortedBy { pos[it] }
                    if (o.size == 3) break
                    kotlinx.coroutines.delay(20)
                }
                o
            }
        }
        // Terceira went down: Segunda is on top now.
        assertEquals(ids[1], order.first())
    }

    /** Diário: the day's photos slide sideways on its card; a tap opens the photo big (not the entry), the day's side by side. */
    @Test
    fun theDiaryCardsPhotosAreACarousel() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val colors = listOf(0xFFE8A87C, 0xFF85CDCA, 0xFFC38D9E, 0xFF41B3A3, 0xFFE27D60, 0xFF659DBD).map { it.toInt() }
        runBlocking {
            graph.db.entries().insert(Entry(kind = "diary", title = "Dia de praia", body = "Fomos à praia.", createdAt = now - 3_600_000L, updatedAt = now))
            colors.forEachIndexed { i, color ->
                val f = File(dir, "beach-$i.jpg")
                val b = Bitmap.createBitmap(if (i % 2 == 0) 80 else 48, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                graph.db.captures().insert(com.villamediana.novelo.data.Capture(createdAt = now - 3_000_000L + i * 60_000L, source = "photo", imagePath = f.path, state = com.villamediana.novelo.data.Capture.DONE))
            }
        }
        val nav = Nav().apply { go(Screen.Library(Filter.DIARY)) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { (nav.top as? Screen.Library)?.let { LibraryScreen(nav, it) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("diary-photo-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("6 fotos", substring = true, useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(800)
        shot("61-diary-carousel")
        compose.onNodeWithTag("diary-photo-1", useUnmergedTree = true).performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 / 6").assertExists()
        // Still on the list (the entry didn't open); closed, back where it was.
        assertTrue(nav.top is Screen.Library)
        compose.onNodeWithContentDescription("Fechar").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription("photo-viewer").fetchSemanticsNodes().isEmpty() }
        assertTrue(nav.top is Screen.Library)
        // Swiped on to its end and past it, again and again: the carousel stops there, the tab stays Diário.
        repeat(4) {
            compose.onNodeWithTag("diary-photos", useUnmergedTree = true).performTouchInput { swipe(Offset(right - 10f, centerY), Offset(left + 10f, centerY), 150) }
            compose.mainClock.advanceTimeBy(600)
        }
        compose.waitForIdle()
        assertEquals(Filter.DIARY, (nav.top as Screen.Library).filter)
        compose.onNodeWithText("Dia de praia").assertExists()
    }

    /** Diário: a + writes today's by hand while today has none; once today has one, no +. */
    @Test
    fun theDiaryPlusWritesTodayOnly() {
        runBlocking { graph.db.entries().insert(Entry(kind = "diary", title = "Ontem", body = "Foi bom.", createdAt = now - day, updatedAt = now)) }
        val nav = Nav().apply { go(Screen.Library(Filter.DIARY)) }
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                when (val s = nav.top) {
                    is Screen.Library -> LibraryScreen(nav, s)
                    is Screen.Entry -> EntryScreen(nav, s.id)
                    else -> Unit
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Diário de hoje").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Diário de hoje").performClick()
        compose.waitUntil(5_000) { nav.top is Screen.Entry }
        val made = runBlocking { graph.db.entries().get((nav.top as Screen.Entry).id)!! }
        assertEquals("diary", made.kind)
        // Written without a title and left: kept, with its day's title.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entry-body").performTextInput("Hoje escrevi à mão.")
        compose.mainClock.advanceTimeBy(300)
        nav.back()
        compose.waitUntil(5_000) { runBlocking { graph.db.entries().get(made.id)?.title } == com.villamediana.novelo.mind.DayDiary.title(made.createdAt, zone) }
        // Today has one now: no +.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hoje escrevi à mão.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithContentDescription("Diário de hoje").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun privacyInSettings() {
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(Nav()) } }
        compose.onNodeWithText("Privacidade").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Biometria para dados sensíveis").assertExists()
        // No line explaining what biometrics is (she knows).
        assertTrue(compose.onAllNodesWithText("Digital, rosto", substring = true).fetchSemanticsNodes().isEmpty())
        shot("51-settings-privacy")
    }

    @Test
    fun theme() {
        val nav = Nav()
        // (mode, accent, the main screen instead of Tema)
        val look = androidx.compose.runtime.mutableStateOf(Triple(ThemeMode.LIGHT, "", false))
        compose.setContent {
            val (mode, accent, home) = look.value
            NoveloTheme(mode, accent = accent) {
                if (home) HomeScreen(nav, talkAbout = null, onTalkHandled = {})
                else Box(Modifier.fillMaxSize().background(NoveloColors.Background).padding(16.dp)) { ThemeSection() }
            }
        }
        shot("14-theme")
        runBlocking { graph.settings.setAccent("green"); graph.settings.setTheme(ThemeMode.DARK) }
        look.value = Triple(ThemeMode.DARK, "green", false)
        compose.mainClock.advanceTimeBy(600)
        shot("15-theme-dark-green")
        look.value = Triple(ThemeMode.DARK, "graphite", true)
        compose.mainClock.advanceTimeBy(600)
        shot("16-home-dark-graphite")
    }

    @Test
    fun settingsIsOnePageThatOpensInPlace() {
        runBlocking { graph.settings.setBackupFolder("", "") }
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(nav) } }
        // One page: each part a line, closed, saying how it is.
        for (part in listOf("Inteligência", "Tema", "Backup", "Idioma")) compose.onNodeWithText(part).assertExists()
        assertTrue(compose.onAllNodesWithText("Quem pensa").fetchSemanticsNodes().isEmpty())
        // No folder yet: its line says so.
        compose.onNodeWithText("escolher onde guardar").assertExists()
        shot("12-settings")
        // A part opens right there (no other page), one at a time.
        compose.onNodeWithText("Inteligência").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Quem pensa").assertExists()
        assertEquals(1, nav.stack.size) // nothing was opened on top of it
        shot("12b-settings-intelligence-open")
        compose.onNodeWithText("Backup").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600)
        // Backup is only the folder now (it goes by itself; a folder with a backup brings it back).
        compose.onNodeWithText("Pasta").assertExists()
        assertTrue(compose.onAllNodesWithText("Quem pensa").fetchSemanticsNodes().isEmpty())
        shot("12c-settings-backup-open")
    }

    @Test
    fun theIntelligencePageShowsOnlyWhatTheChoiceNeeds() {
        runBlocking { graph.settings.setThink(Source.API); graph.settings.setHear(Source.NOVELO) }
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().background(NoveloColors.Background).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) { com.villamediana.novelo.ui.settings.IntelligenceSection() } } }
        try {
            // Who hears and who thinks, each the Novelo or her API.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Quem pensa").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Quem ouve").assertExists()
            // The Novelo hears: its ears are the download here (no API that hears to fill).
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Ouvido do Novelo").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText("API de quem ouve").fetchSemanticsNodes().isEmpty())
            // Her API thinks: its provider and key, no button to test (it tests by itself).
            compose.onNodeWithText("API de quem pensa").assertExists()
            assertTrue(compose.onAllNodesWithText("Conexão").fetchSemanticsNodes().isEmpty())
            compose.mainClock.advanceTimeBy(500)
            shot("30-settings-intelligence")
            // Her API hears: its own provider (only those that hear), key and model; no download.
            runBlocking { graph.settings.setHear(Source.API); graph.settings.setThink(Source.NOVELO) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("API de quem ouve").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Ouvido do Novelo").fetchSemanticsNodes().isEmpty() }
            assertTrue(compose.onAllNodesWithText("API de quem pensa").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("provider-line-hear").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("OpenAI").fetchSemanticsNodes().isNotEmpty() }
            for (deaf in listOf("Anthropic", "OpenRouter", "Kimi")) assertTrue(deaf, compose.onAllNodesWithText(deaf).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("OpenAI").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Chave").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(500)
            shot("30b-settings-intelligence-hears-api")
            // Both the Novelo's: nothing to fill, only the ears.
            runBlocking { graph.settings.setHear(Source.NOVELO) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Chave").fetchSemanticsNodes().isEmpty() }
            compose.mainClock.advanceTimeBy(500)
            shot("30c-settings-intelligence-novelo")
        } finally {
            runBlocking { graph.settings.setHear(Source.NOVELO); graph.settings.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "") }
        }
    }

    @Test
    fun backupInSettings() {
        val nav = Nav()
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(NoveloColors.Background).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
                    com.villamediana.novelo.ui.settings.BackupSection()
                }
            }
        }
        try {
            // No folder yet: the one line, to choose it.
            compose.onNodeWithText("escolher onde guardar").performScrollTo().assertExists()
            runBlocking {
                graph.settings.setBackupFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents/Novelo backup.zip")
                graph.settings.setBackupDone(System.currentTimeMillis(), "120 itens · 34 fotos")
            }
            // The folder, and when the last backup went (it goes by itself after every change): nothing
            // to do by hand, nothing to import (choosing a folder with a backup brings it back).
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Documents/Novelo backup.zip").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText("último: hoje", substring = true).fetchSemanticsNodes().isNotEmpty())
            for (gone in listOf("Importar backup", "Fazer agora", "Automático")) assertTrue(gone, compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty())
            compose.mainClock.advanceTimeBy(500)
            shot("18-settings-backup")
        } finally {
            runBlocking { graph.settings.setBackupFolder("", "") }
        }
    }

    @Test
    fun theOpeningIsTheButton() {
        // The opening's icon box is 288dp: its ball has to be the button's (168dp) and whole (inside the 192dp mask).
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(NoveloColors.Background), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.villamediana.novelo.R.drawable.novelo_splash_vermilion),
                        contentDescription = null,
                        modifier = Modifier.size(androidx.compose.ui.unit.Dp(288f)),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        shot("31-opening")
    }

    @Test
    fun theFirstStepsGoOneByOne() {
        // A new phone: nothing of her own intelligence set up yet.
        runBlocking {
            graph.settings.setApiProvider("")
            graph.settings.setApiBase("")
            graph.settings.setApiKey("")
            graph.settings.setThinkModel("")
            graph.settings.setBackupFolder("", "")
            graph.settings.setThink(com.villamediana.novelo.settings.Source.NOVELO)
            // …nor of the one that hears.
            graph.settings.setHear(com.villamediana.novelo.settings.Source.NOVELO)
            graph.settings.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "")
            graph.settings.setApiBase(com.villamediana.novelo.settings.Job.HEAR, "")
            graph.settings.setApiKey(com.villamediana.novelo.settings.Job.HEAR, "")
            graph.settings.setModel(com.villamediana.novelo.settings.Job.HEAR, "")
        }
        var done = false
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { com.villamediana.novelo.ui.setup.SetupScreen(onDone = { done = true }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Microfone").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        shot("32-setup-permissions")
        compose.onNodeWithText("Agora não").performClick()
        // Right after the permissions, who hears her: the Novelo's ears (they come only when she agrees,
        // Baixar, and go on in the background) or her own API (nothing to download). (This build has
        // no engine: it can't have the ears, so it lets her go; with the engine, Continuar waits for her Baixar.)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Quem vai ouvir você?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("O ouvido do Novelo").assertExists()
        compose.mainClock.advanceTimeBy(800)
        shot("33a-setup-hear")
        // Her API: whose (only those that hear), then the key; it goes on only when it is whole.
        compose.onNodeWithText("Sua própria API").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("provider-line-hear").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Escolha de quem é a IA").assertExists()
        assertTrue(compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("provider-line-hear").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("OpenAI").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Anthropic").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("OpenAI").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Coloque a chave").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
        shot("33b-setup-hear-own")
        // Back to the Novelo's ears.
        compose.onNodeWithText("O ouvido do Novelo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continuar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Onde guardar seu backup?").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
        shot("33-setup-backup")
        // The folder is a must (nothing to skip), with the tip to pick a cloud drive.
        assertTrue(compose.onAllNodesWithText("Depois").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Dica:", substring = true).assertExists()
        runBlocking { graph.settings.setBackupFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents/Novelo backup.zip") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continuar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sua própria inteligência").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
        shot("34-setup-intelligence")
        compose.onNodeWithText("Sua própria inteligência").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("provider-line-think").fetchSemanticsNodes().isNotEmpty() }
        // Her own goes on only when it is whole, and the button says what is missing: whose it is…
        compose.onNodeWithText("Escolha de quem é a IA").assertExists()
        assertTrue(compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isEmpty())
        // (one line that opens the list, not a row of names)
        compose.onNodeWithTag("provider-line-think").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Anthropic").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        shot("34b-setup-provider")
        compose.onNodeWithText("Anthropic").performClick()
        // …then the key (the fields only once she chose whose).
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Coloque a chave").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
        shot("35-setup-own")
        // Back to the Novelo's: nothing to fill. The last step: Começar (with the engine, it waits
        // for the ears to be on the phone).
        compose.onNodeWithText("Inteligência do Novelo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Começar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Começar").performClick()
        compose.waitUntil(5_000) { done }
        runBlocking {
            graph.settings.setBackupFolder("", "")
            graph.settings.setApiProvider("")
            graph.settings.setApiProvider(com.villamediana.novelo.settings.Job.HEAR, "")
            graph.settings.setHear(com.villamediana.novelo.settings.Source.NOVELO)
            graph.settings.setThink(com.villamediana.novelo.settings.Source.API)
        }
    }

    @Test
    fun theWordsFollowTheChosenLanguage() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.villamediana.novelo.settings.Lang.set(context, com.villamediana.novelo.settings.AppLang.EN)
        try {
            val nav = Nav()
            compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(nav) } }
            compose.onNodeWithText("Settings").assertExists()
            compose.onNodeWithText("Intelligence").assertExists()
            compose.onNodeWithText("Language").assertExists()
            compose.onNodeWithText("English").assertExists()
            compose.mainClock.advanceTimeBy(500)
            shot("36-settings-english")
        } finally {
            com.villamediana.novelo.settings.Lang.set(context, com.villamediana.novelo.settings.AppLang.PT)
        }
    }

    @Test
    fun thePickerMovesWithTheLanguage() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(Nav()) } }
            compose.onNodeWithText("Idioma").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("English").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("Language").assertExists()
            compose.onNodeWithText("English").assertIsSelected()
            compose.onNodeWithText("Português").assertIsNotSelected()
        } finally {
            com.villamediana.novelo.settings.Lang.set(context, com.villamediana.novelo.settings.AppLang.PT)
        }
    }

    @Test
    fun settingsDark() {
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.DARK) { SettingsScreen(nav) } }
        compose.mainClock.advanceTimeBy(500)
        shot("13-settings-dark")
    }

    @Test
    fun theOrbAndTheDark() {
        val level = mutableFloatStateOf(0.7f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NoveloTheme(ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(NoveloColors.Background)) {
                    Orb(OrbMode.HOLDING, level, onStart = { true }, onLock = {}, onStop = {}, onCancel = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        shot("10-orb-listening-dark")
    }

    @Test
    fun listeningByItselfHasAQuietCancel() {
        val now = System.currentTimeMillis()
        var cancelled = false
        compose.setContent {
            NoveloTheme(ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(NoveloColors.Background)) {
                    androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.Center), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        com.villamediana.novelo.ui.home.ListeningText(MindState.Listening(startedAt = now, locked = true, focusId = null), inCancelZone = false, onCancel = { cancelled = true })
                    }
                }
            }
        }
        shot("17-listening-cancel")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Cancelar")).performClick()
        assertTrue(cancelled)
    }

    @Test
    fun homeDark() {
        seed()
        val nav = Nav()
        compose.setContent { NoveloTheme(ThemeMode.DARK) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        shot("11-home-dark")
    }
}
