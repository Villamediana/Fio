package com.villamediana.novelo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.villamediana.novelo.importer.DiaryImport
import com.villamediana.novelo.importer.Found
import com.villamediana.novelo.importer.FoundPhoto
import com.villamediana.novelo.importer.Kept
import com.villamediana.novelo.importer.Reading
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.settings.DiaryImportBody
import com.villamediana.novelo.ui.settings.ImportChoice
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

    @Test fun aZipOrAFolder() {
        var file = 0
        var folder = 0
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { ImportChoice(onFile = { file++ }, onFolder = { folder++ }) } }
        compose.onNodeWithText("Um ZIP").assertIsDisplayed()
        compose.onNodeWithText("Uma pasta").assertIsDisplayed()
        compose.onNodeWithTag("import-folder").performClick()
        compose.onNodeWithTag("import-file").performClick()
        assertEquals(1, file)
        assertEquals(1, folder)
    }

    @Test fun whatWasReadShowsAndImports() {
        var confirmed = 0
        val photos = (1..8).map { FoundPhoto(at(it), java.io.File("/nao/existe/$it.jpg")) }
        val ready = DiaryImport.State.Ready(
            "diario-2026-11-13.zip",
            Reading(listOf(Found(at(15), "Praia", "Fui à praia com a Ana."), Found(at(16), "", "Choveu o dia inteiro.")), photos),
        )
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(ready, onConfirm = { confirmed++ }, onClose = {}, onSeeDiary = {}, zone = zone) } }
        compose.onNodeWithText("2 entradas · 2 dias").assertIsDisplayed()
        compose.onNodeWithText("8 fotos").assertIsDisplayed()
        compose.onNodeWithText("Praia").assertIsDisplayed()
        compose.onNodeWithTag("import-photos").assertExists()
        compose.onNodeWithText("+3").assertExists()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun doneSaysWhatWentInAndThatTheBackupWent() {
        var seen = 0
        val done = DiaryImport.State.Done(Kept(added = 12, joined = 2, from = at(1), to = at(20), photos = 40), backup = true)
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { DiaryImportBody(done, onConfirm = {}, onClose = {}, onSeeDiary = { seen++ }, zone = zone) } }
        compose.onNodeWithText("12 dias novos no diário").assertIsDisplayed()
        compose.onNodeWithText("2 dias que já tinham diário ganharam o que faltava").assertIsDisplayed()
        compose.onNodeWithText("40 fotos novas na galeria, cada uma no seu dia").assertIsDisplayed()
        compose.onNodeWithText("Backup feito.").assertIsDisplayed()
        compose.onNodeWithTag("import-main").performClick()
        assertEquals(1, seen)
    }
}
