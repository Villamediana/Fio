package com.villamediana.novelo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.villamediana.novelo.importer.DiaryImport
import com.villamediana.novelo.importer.Found
import com.villamediana.novelo.importer.FoundNote
import com.villamediana.novelo.importer.FoundPhoto
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
        compose.onNodeWithText("Nada saiu com esse padrão.").assertIsDisplayed()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, again)
    }

    @Test fun aZipShowsItsNotesAndPhotosToo() {
        val photos = (1..8).map { FoundPhoto(at(it), java.io.File("/nao/existe/$it.jpg")) }
        val ready = DiaryImport.State.Ready(
            "diario.zip",
            Recipe(app = "Meu Diário", about = "Os dias em dias/, as notas em notas/, as fotos em fotos/.", shape = "text"),
            Reading(listOf(Found(at(15), "", "Choveu.")), notes = listOf(FoundNote(null, "Ideias", "Um livro sobre o mar.")), photos = photos, undatedPhotos = 2),
        )
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(ready, onAgain = {}, onConfirm = {}, onClose = {}, onSeeDiary = {}, zone = zone) } }
        compose.onNodeWithText("1 entrada · 1 dia").assertIsDisplayed()
        compose.onNodeWithText("1 nota · 8 fotos").assertIsDisplayed()
        compose.onNodeWithText("2 fotos sem data ficam de fora").assertIsDisplayed()
        compose.onNodeWithTag("import-photos").assertExists()
        compose.onNodeWithText("+3").assertExists()
        compose.onNodeWithText("Ideias").assertExists()
    }

    @Test fun doneSaysWhatWentInAndThatTheBackupWent() {
        var seen = 0
        val done = DiaryImport.State.Done(Kept(added = 12, joined = 2, from = at(1), to = at(20), notes = 3, photos = 40), backup = true)
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(done, onAgain = {}, onConfirm = {}, onClose = {}, onSeeDiary = { seen++ }, zone = zone) } }
        compose.onNodeWithText("12 dias novos no diário").assertIsDisplayed()
        compose.onNodeWithText("2 dias que já tinham diário ganharam o que faltava").assertIsDisplayed()
        compose.onNodeWithText("3 notas novas").assertIsDisplayed()
        compose.onNodeWithText("40 fotos novas na galeria, cada uma no seu dia").assertIsDisplayed()
        compose.onNodeWithText("Backup feito.").assertIsDisplayed()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, seen)
    }
}
