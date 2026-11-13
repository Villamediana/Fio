package com.villamediana.novelo.importer

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Library
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiaryImportTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private lateinit var db: NoveloDatabase

    @Before fun open() { db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    private fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun recipe(json: String) = Recipes.parse(json) ?: error("no recipe: $json")

    // ---- the recipe the model writes ----

    @Test fun aRecipeComesOutOfTheAnswerEvenWithFencesAround() {
        val r = Recipes.parse("```json\n{\"app\": \"Day One\", \"about\": \"x\", \"shape\": \"json\", \"entries\": \"entries\", \"body\": \"text\"}\n```")
        assertEquals("Day One", r?.app)
        assertEquals("entries", r?.entries)
    }

    @Test fun aRecipeThatCantRunIsNoRecipe() {
        assertNull(Recipes.parse("""{"app": "x", "shape": "xml"}"""))
        assertNull(Recipes.parse("""{"app": "x", "shape": "text", "start": "^(unclosed"}"""))
        assertNull(Recipes.parse("not json"))
    }

    // ---- applied to the whole file ----

    @Test fun dayOneJsonWithTitleOnTheFirstLineAndImagesTakenOut() {
        val file = """
            {"metadata": {"version": "1.0"}, "entries": [
              {"creationDate": "2024-01-15T13:30:00Z", "text": "# Praia\n\nFui à praia com a Ana. ![](dayone-moment://ABC123)\n\nO mar estava calmo.", "tags": ["férias", "praia"]},
              {"creationDate": "2024-01-16T22:10:00Z", "text": "Dia de trabalho\nReunião longa."},
              {"text": "sem data"}
            ]}
        """.trimIndent()
        val r = recipe("""{"app": "Day One", "about": "…", "shape": "json", "entries": "entries", "date": "creationDate", "body": "text", "tags": "tags", "date_format": "iso", "first_line_title": true, "clean": ["!\\[\\]\\(dayone-moment:[^)]*\\)"]}""")
        val read = Recipes.apply(r, listOf(TextFile("Journal.json", file)), zone)
        assertEquals(2, read.found.size)
        assertEquals(1, read.undated)
        val first = read.found[0]
        assertEquals("Praia", first.title)
        assertEquals("Fui à praia com a Ana.\n\nO mar estava calmo.", first.body)
        assertEquals(listOf("férias", "praia"), first.tags)
        assertEquals(LocalDate.of(2024, 1, 15), day(first.at))
        assertEquals("Dia de trabalho", read.found[1].title)
        assertEquals("Reunião longa.", read.found[1].body)
    }

    @Test fun textWithTheDateAndTitleOnTheLineThatStartsEachEntry() {
        val file = """
            Meu diário 2024

            15/01/2024 - Começo do ano
            Hoje comecei a academia.
            Cansada, mas feliz.

            17/01/2024 -
            Choveu o dia inteiro.
        """.trimIndent()
        val r = recipe("""{"app": "Texto", "about": "…", "shape": "text", "start": "^(?<date>\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(?<title>.*)$", "date_format": "dd/MM/yyyy"}""")
        val read = Recipes.apply(r, listOf(TextFile("diario.txt", file)), zone)
        assertEquals(2, read.found.size)
        assertEquals("Começo do ano", read.found[0].title)
        assertEquals("Hoje comecei a academia.\nCansada, mas feliz.", read.found[0].body)
        assertEquals("", read.found[1].title)
        assertEquals(LocalDate.of(2024, 1, 17), day(read.found[1].at))
    }

    @Test fun markdownWithTheDayWrittenOutInPortuguese() {
        val file = "## Segunda-feira, 15 de janeiro de 2024\nFui ao mercado.\n\n## Terça-feira, 16 de janeiro de 2024 às 21h\nLi um livro."
        val r = recipe("""{"app": "Markdown", "about": "…", "shape": "text", "start": "^## (?<date>.+)$", "date_format": "EEEE, d 'de' MMMM 'de' yyyy", "date_locale": "pt-BR"}""")
        val read = Recipes.apply(r, listOf(TextFile("diario.md", file)), zone)
        assertEquals(listOf(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 16)), read.found.map { day(it.at) })
        assertEquals("Li um livro.", read.found[1].body)
    }

    @Test fun aCsvWithQuotedNotesThatGoOverSeveralLines() {
        val file = "full_date,time,mood,activities,note_title,note\n" +
            "2024-01-15,10:30,good,work | reading,Bom dia,\"Acordei cedo.\nTomei café, \"\"devagar\"\".\"\n" +
            "2024-01-16,21:00,meh,,,\"Só cansaço.\"\n"
        val r = recipe("""{"app": "Daylio", "about": "…", "shape": "csv", "delimiter": ",", "header": true, "date": "full_date", "title": "note_title", "body": "note", "date_format": "yyyy-MM-dd"}""")
        val read = Recipes.apply(r, listOf(TextFile("daylio.csv", file)), zone)
        assertEquals(2, read.found.size)
        assertEquals("Bom dia", read.found[0].title)
        assertEquals("Acordei cedo.\nTomei café, \"devagar\".", read.found[0].body)
        assertEquals("Só cansaço.", read.found[1].body)
    }

    @Test fun oneFileADayInAZipTheDateInTheName() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
                put("Diario/2024-03-01.md", "# Março\nPrimeiro dia de março.".toByteArray())
                put("Diario/2024-03-02.md", "Segundo dia.".toByteArray())
                put("Diario/foto.jpg", byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 0, 0, 0, 0))
                put("__MACOSX/Diario/._2024-03-01.md", "x".toByteArray())
            }
        }.toByteArray()
        val files = Unpack.files("export.zip", zip.inputStream())
        assertEquals(listOf("Diario/2024-03-01.md", "Diario/2024-03-02.md"), files.map { it.name })
        val r = recipe("""{"app": "Obsidian", "about": "…", "shape": "text", "files": "\\.md$", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", "date_format": "iso", "first_line_title": true}""")
        val read = Recipes.apply(r, files, zone)
        assertEquals(listOf("Março", "Segundo dia."), read.found.map { it.title })
        assertEquals(LocalDate.of(2024, 3, 2), day(read.found[1].at))
        assertTrue(Sample.of("export.zip", files).contains("2 ARQUIVOS DE TEXTO"))
    }

    @Test fun oldWindowsTextComesWithItsAccents() {
        val text = Unpack.decode("Ação e emoção".toByteArray(charset("windows-1252")))
        assertEquals("Ação e emoção", text)
        assertNull(Unpack.decode(ByteArray(64) { if (it % 3 == 0) 0 else 7 }))
    }

    @Test fun theSampleIsOnlyTheStartAndAPieceOfTheMiddle() {
        val big = (1..4000).joinToString("\n") { "linha $it do diário" }
        val sample = Sample.of("grande.txt", listOf(TextFile("grande.txt", big)))
        assertTrue(sample.contains("linha 1 do diário"))
        assertTrue(sample.contains("UM PEDAÇO DO MEIO"))
        assertFalse(sample.contains("linha 4000 do diário"))
        assertTrue(sample.length < Sample.HEAD + Sample.MIDDLE + 400)
    }

    // ---- into the diary ----

    private fun at(d: Int, h: Int) = LocalDateTime.of(2024, 1, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val library get() = Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} })

    @Test fun oneDiaryADayAndADayThatHadOneIsToldWhatItLacked() = runBlocking {
        val had = db.entries().insert(Entry(kind = Kind.DIARY.key, title = "Meu dia 16", body = "Já tinha escrito isto.", createdAt = at(16, 20), updatedAt = at(16, 20)))
        val found = listOf(
            Found(at(15, 9), "Manhã", "Corri no parque.", listOf("corrida")),
            Found(at(15, 21), "Noite", "Jantei com a Ana."),
            Found(at(16, 10), "", "Fui ao médico."),
        )
        val kept = library.importDiaries(found, zone)
        assertEquals(1, kept.added)
        assertEquals(1, kept.joined)
        val diaries = db.entries().all().filter { it.kindEnum == Kind.DIARY }.sortedBy { it.createdAt }
        assertEquals(2, diaries.size)
        assertEquals("Manhã", diaries[0].title)
        assertEquals("Corri no parque.\n\nNoite\nJantei com a Ana.", diaries[0].body)
        assertEquals(listOf("corrida"), Tags.read(diaries[0].tags))
        assertEquals(had, diaries[1].id)
        assertEquals("Meu dia 16", diaries[1].title)
        assertEquals("Já tinha escrito isto.\n\nFui ao médico.", diaries[1].body)

        // The same file again: nothing new.
        library.importDiaries(found, zone)
        val again = db.entries().all().filter { it.kindEnum == Kind.DIARY }.sortedBy { it.createdAt }
        assertEquals(diaries.map { it.body }, again.map { it.body })
    }

    @Test fun pickedRecognizedConfirmedThenTheBackup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asked = mutableListOf<String>()
        var backedUp = false
        val importer = DiaryImport(
            scope = scope,
            ready = { null },
            ask = { _, user ->
                asked += user
                """{"app": "Texto", "about": "Cada entrada começa com a data.", "shape": "text", "start": "^(?<date>\\d{4}-\\d{2}-\\d{2})$", "date_format": "iso"}"""
            },
            keep = { library.importDiaries(it, zone) },
            backup = { backedUp = true; true },
            zone = { zone },
        )
        importer.open("diario.txt") { "2024-01-15\nPrimeiro.\n2024-01-16\nSegundo.".byteInputStream() }
        val ready = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Ready || it is DiaryImport.State.Failed } }
        ready as DiaryImport.State.Ready
        assertEquals(2, ready.reading.found.size)
        assertTrue(asked.single().contains("COMEÇO DO ARQUIVO"))

        // Recognized again: the model sees its recipe and what it gave.
        importer.again()
        withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Ready && asked.size == 2 } }
        assertTrue(asked[1].contains("UMA RECEITA ANTERIOR NÃO SERVIU"))

        importer.confirm()
        val done = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Done } } as DiaryImport.State.Done
        assertEquals(2, done.kept.added)
        assertEquals(true, done.backup)
        assertTrue(backedUp)
        assertEquals(2, db.entries().all().count { it.kindEnum == Kind.DIARY })
    }
}
