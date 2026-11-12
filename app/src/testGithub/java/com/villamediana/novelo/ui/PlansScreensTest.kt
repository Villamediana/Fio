package com.villamediana.novelo.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.activity.ComponentActivity
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.plans.PlansSheet
import com.villamediana.novelo.ui.settings.SettingsScreen
import com.villamediana.novelo.ui.theme.NoveloTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The GitHub build's plans, all the way: the trial in Ajustes with its bars, the plans sheet, and a
 * (simulated) purchase that makes it Novelo+ — the Novelo's server played by a MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlansScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val graph get() = NoveloApplication.graph
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private var plan = "trial"
    private val purchases = mutableListOf<String>()

    private fun status(): String {
        val plus = plan == "plus"
        val (talkLimit, talkWeek, photoLimit, photoWeek) = if (plus) listOf(60, 250, 20, 80) else listOf(15, 60, 5, 20)
        return """{"plan":"$plan","trial_days":7,"trial_ends":${now + 5 * day},"sub_until":${if (plus) now + 30 * day else "null"},"sub_store":${if (plus) "\"test\"" else "null"},""" +
            """"talk":{"day":{"used":${if (plus) 2 else 9},"limit":$talkLimit},"week":{"used":${if (plus) 2 else 31},"limit":$talkWeek}},""" +
            """"photo":{"day":{"used":1,"limit":$photoLimit},"week":{"used":4,"limit":$photoWeek}},"day_resets":${now + day / 3},"week_resets":${now + 5 * day},""" +
            """"offer":{"product":"novelo_plus","base_plan":"monthly","test_purchases":true,"limits":{"talk":{"day":60,"week":250},"photo":{"day":20,"week":80}}}}"""
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/v1/session" -> MockResponse.Builder().body("""{"token":"v1.t","status":${status()}}""").build()
                "/v1/me" -> MockResponse.Builder().body("""{"status":${status()}}""").build()
                "/v1/purchase" -> {
                    purchases += request.body!!.utf8()
                    plan = "plus"
                    MockResponse.Builder().body("""{"status":${status()}}""").build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        graph.noveloServer = server.url("/").toString()
        runBlocking { graph.settings.setThink(Source.NOVELO) }
    }

    @After fun stop() { server.close() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/novelo-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theTrialItsBarsAndASimulatedPurchase() {
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(Nav()) } }
        compose.onNodeWithText("Inteligência").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("plan-card").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(900)
        // The trial: days left, today's and the trial's bars.
        compose.onNodeWithText("Teste grátis").assertExists()
        compose.onNodeWithText("Faltam 5 dias do teste grátis").assertExists()
        compose.onNodeWithText("9 de 15").assertExists()
        compose.onNodeWithText("31 de 60").assertExists()
        compose.onNodeWithText("No teste").assertExists()
        shot("40-plan-trial")

        compose.onNodeWithText("Assinar o Novelo+").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("plan-monthly").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("R$ 14,90").assertExists()
        compose.onNodeWithText("60 falas e 20 fotos por dia").assertExists()
        compose.onNodeWithText("Versão de teste: a assinatura é simulada, nada é cobrado.").assertExists()
        compose.onNodeWithText("Sua própria inteligência").assertExists()
        shot("41-plans-sheet")

        compose.onNodeWithText("Assinar").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Pronto: você tem o Novelo+.").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Não deu pra assinar agora. Nada foi cobrado.").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("purchase failed: ${purchases.size} sent", compose.onAllNodesWithText("Pronto: você tem o Novelo+.").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, purchases.size)
        assertTrue(purchases[0], purchases[0].contains("\"store\":\"test\""))
        compose.mainClock.advanceTimeBy(600)
        shot("42-plans-bought")
        assertEquals("plus", graph.novelo.status.value!!.plan)
    }

    @Test
    fun novelosPlusInAjustes() {
        plan = "plus"
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SettingsScreen(Nav()) } }
        compose.onNodeWithText("Inteligência").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Esta semana").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Ativo").assertExists()
        compose.onNodeWithText("2 de 60").assertExists()
        assertTrue(compose.onAllNodesWithText("Assinar o Novelo+").fetchSemanticsNodes().isEmpty())
        shot("43-plan-plus")
    }

    @Test
    fun thePlansSheetInTheDark() {
        compose.setContent { NoveloTheme(ThemeMode.DARK) { PlansSheet(onDismiss = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("plan-monthly").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(900)
        shot("44-plans-sheet-dark")
    }
}
