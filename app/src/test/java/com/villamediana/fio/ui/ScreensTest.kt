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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
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
import com.villamediana.fio.ui.home.HomeScreen
import com.villamediana.fio.ui.home.Orb
import com.villamediana.fio.ui.home.OrbMode
import com.villamediana.fio.ui.library.LibraryScreen
import com.villamediana.fio.ui.settings.SettingsScreen
import com.villamediana.fio.ui.settings.ThemeScreen
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
            """{"reply":"Anotei a ligação pro Pedro amanhã às 9 e o presente da Ana. E guardei o seu dia.","create":[{"kind":"task","title":"Ligar pro Pedro sobre o contrato","due":"$tomorrow9","remind_at":"$tomorrow9","entities":[{"name":"Pedro","type":"person"}],"related":[${ids["contract"]}]},{"kind":"diary","title":"Hoje foi um dia bom","body":"Hoje foi um dia bom."}],"update":[{"id":${ids["gift"]},"append":"Lembrar de comprar.","remind_at":"$tomorrow9"}]}""",
        )
        val nav = Nav()

        // 1. Idle.
        compose.setContent { FioTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.onNodeWithText("O que está na sua cabeça?").assertExists()
        // Next to the camera, the gallery.
        compose.onNodeWithContentDescription("Câmera").assertExists()
        compose.onNodeWithContentDescription("Galeria").assertExists()
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
        screen.value = Screen.Settings
        shot("08-settings")
        screen.value = Screen.Library(Filter.LINKS)
        repeat(8) { compose.mainClock.advanceTimeBy(250); Thread.sleep(150) }
        shot("09-graph")
        screen.value = Screen.Library(Filter.LINKS, topic = ids["topic-pedro"])
        // The touched knot lifts its entries in a card at the bottom.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dia bom no escritório").fetchSemanticsNodes().isNotEmpty() }
        repeat(4) { compose.mainClock.advanceTimeBy(250); Thread.sleep(100) }
        shot("12-graph-topic")
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
                graph.db.captures().insert(com.villamediana.fio.data.Capture(createdAt = times[i], source = "photo", imagePath = f.path, state = com.villamediana.fio.data.Capture.DONE))
            }
        }
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Entry(ids["diary"]!!))
        compose.setContent {
            FioTheme(ThemeMode.LIGHT) {
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
                graph.db.captures().insert(com.villamediana.fio.data.Capture(createdAt = now - i * day, source = "photo", imagePath = f.path, state = com.villamediana.fio.data.Capture.DONE, description = about))
            }
        }
        val nav = Nav()
        val screen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Library(Filter.GALLERY))
        compose.setContent { FioTheme(ThemeMode.LIGHT) { (screen.value as? Screen.Library)?.let { LibraryScreen(nav, it) } } }
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
    fun swipesMoveAlongTheLine() {
        seed()
        val nav = Nav()
        compose.setContent {
            FioTheme(ThemeMode.LIGHT) {
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
    fun homeComesBackToTheMiddleAfterASwipe() {
        val nav = Nav()
        // The main screen kept on (as when she comes back before its way out ended).
        compose.setContent { FioTheme(ThemeMode.LIGHT) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        compose.onRoot().performTouchInput {
            val y = height * 0.72f
            swipe(Offset(width * 0.85f, y), Offset(width * 0.15f, y), durationMillis = 250)
        }
        compose.waitUntil(3_000) { nav.top is Screen.Library }
        compose.waitForIdle()
        nav.back()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText("Hoje").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, hint.center.x, 2f)
    }

    @Test
    fun theme() {
        val nav = Nav()
        // (mode, accent, the main screen instead of Tema)
        val look = androidx.compose.runtime.mutableStateOf(Triple(ThemeMode.LIGHT, "", false))
        compose.setContent {
            val (mode, accent, home) = look.value
            FioTheme(mode, accent = accent) { if (home) HomeScreen(nav, talkAbout = null, onTalkHandled = {}) else ThemeScreen(nav) }
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
    fun backupInSettings() {
        val nav = Nav()
        compose.setContent { FioTheme(ThemeMode.LIGHT) { SettingsScreen(nav) } }
        // No folder yet: one line to choose it, and the import.
        compose.onNodeWithText("escolher onde guardar").performScrollTo().assertExists()
        assertTrue(compose.onAllNodesWithText("Automático").fetchSemanticsNodes().isEmpty())
        runBlocking {
            graph.settings.setBackupFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents/Fio")
            graph.settings.setBackupDone(System.currentTimeMillis(), "120 itens · 34 fotos")
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Documents/Fio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Importar backup").performScrollTo()
        compose.onNodeWithText("Automático").assertExists()
        compose.onNodeWithText("Diário").assertExists()
        assertTrue(compose.onAllNodesWithText("último: hoje", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.mainClock.advanceTimeBy(500)
        shot("18-settings-backup")
        runBlocking {
            graph.settings.setBackupFolder("", "")
        }
    }

    @Test
    fun settingsDark() {
        val nav = Nav()
        compose.setContent { FioTheme(ThemeMode.DARK) { SettingsScreen(nav) } }
        compose.mainClock.advanceTimeBy(500)
        shot("13-settings-dark")
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
    fun listeningByItselfHasAQuietCancel() {
        val now = System.currentTimeMillis()
        var cancelled = false
        compose.setContent {
            FioTheme(ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(FioColors.Background)) {
                    androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.Center), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        com.villamediana.fio.ui.home.ListeningText(MindState.Listening(startedAt = now, locked = true, focusId = null), inCancelZone = false, onCancel = { cancelled = true })
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
        compose.setContent { FioTheme(ThemeMode.DARK) { HomeScreen(nav, talkAbout = null, onTalkHandled = {}) } }
        shot("11-home-dark")
    }
}
