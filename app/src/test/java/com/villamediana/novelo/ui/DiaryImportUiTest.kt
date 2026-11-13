package com.villamediana.novelo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.villamediana.novelo.importer.DiaryImport
import com.villamediana.novelo.importer.Found
import com.villamediana.novelo.importer.Kept
import com.villamediana.novelo.importer.Reading
import com.villamediana.novelo.importer.Recipe
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.settings.DiaryImportBody
import com.villamediana.novelo.ui.theme.NoveloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DiaryImportUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(d: Int) = LocalDateTime.of(2024, 1, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun thePatternRecognizedShowsWhatCameOutAndImports() {
        var confirmed = 0
        var again = 0
        val ready = DiaryImport.State.Ready(
            "Journal.json",
            Recipe(app = "Day One", about = "Uma lista em entries, cada uma com a data e o texto.", shape = "json"),
            Reading(listOf(Found(at(15), "Praia", "Fui à praia com a Ana."), Found(at(16), "", "Choveu o dia inteiro.")), undated = 3),
        )
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(ready, onAgain = { again++ }, onConfirm = { confirmed++ }, onClose = {}, onSeeDiary = {}, zone = zone) } }
        compose.onNodeWithText("Day One").assertIsDisplayed()
        compose.onNodeWithText("2 entradas · 2 dias").assertIsDisplayed()
        compose.onNodeWithText("3 sem data ficam de fora").assertIsDisplayed()
        compose.onNodeWithText("Praia").assertIsDisplayed()
        compose.onNodeWithText("Reconhecer de novo").performClick()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, again)
        assertEquals(1, confirmed)
    }

    @Test fun nothingCameOutSoTheMainWayIsToRecognizeAgain() {
        var again = 0
        val ready = DiaryImport.State.Ready("x.txt", Recipe(app = "Texto", shape = "text"), Reading(emptyList()))
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(ready, onAgain = { again++ }, onConfirm = {}, onClose = {}, onSeeDiary = {}, zone = zone) } }
        compose.onNodeWithText("Nenhuma entrada saiu com esse padrão.").assertIsDisplayed()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, again)
    }

    @Test fun doneSaysWhatWentInAndThatTheBackupWent() {
        var seen = 0
        val done = DiaryImport.State.Done(Kept(added = 12, joined = 2, from = at(1), to = at(20)), backup = true)
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(done, onAgain = {}, onConfirm = {}, onClose = {}, onSeeDiary = { seen++ }, zone = zone) } }
        compose.onNodeWithText("12 dias novos no diário").assertIsDisplayed()
        compose.onNodeWithText("2 dias que já tinham diário ganharam o que faltava").assertIsDisplayed()
        compose.onNodeWithText("Backup feito.").assertIsDisplayed()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, seen)
    }
}
