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
        assertEquals(listOf("Diario/2024-03-01.md", "Diario/2024-03-02.md"), files.texts.map { it.name })
        val r = recipe("""{"app": "Obsidian", "about": "…", "shape": "text", "files": "\\.md$", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", "date_format": "iso", "first_line_title": true}""")
        val read = Recipes.apply(r, files.texts, zone)
        assertEquals(listOf("Março", "Segundo dia."), read.found.map { it.title })
        assertEquals(LocalDate.of(2024, 3, 2), day(read.found[1].at))
        assertTrue(Sample.of("export.zip", files.texts, files.others).contains("Diario/ 2 texto (.md): 2024-03-01.md, 2024-03-02.md"))
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    private fun jpeg(seed: Int) = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 0, 0, 0, seed.toByte(), -1, -39)

    private fun stage() = kotlin.io.path.createTempDirectory("stage").toFile()

    @Test fun aZipWithFoldersBringsTheDiaryTheNotesAndThePhotosEachOnItsDay() = runBlocking {
        val zip = zipOf(
            "meu-diario/dias/2024-03-01.md" to "Primeiro dia de março.".toByteArray(),
            "meu-diario/dias/2024-03-02.md" to "Segundo dia.".toByteArray(),
            "meu-diario/notas/Ideias.md" to "# Ideias\nUm livro sobre o mar.".toByteArray(),
            "meu-diario/fotos/IMG_20240302_101010.jpg" to jpeg(1),
            "meu-diario/fotos/sem-nome.jpg" to jpeg(2),
            "meu-diario/fotos/miniaturas/IMG_20240302_101010_thumb.jpg" to jpeg(3),
            "meu-diario/dados.db" to ByteArray(4000) { 0 },
        )
        val stage = stage()
        val files = Unpack.files("diario.zip", zip.inputStream(), stage)
        assertEquals(3, files.texts.size)
        assertEquals(3, files.photos)
        val sample = Sample.of("diario.zip", files.texts, files.others)
        assertTrue(sample, sample.contains("meu-diario/fotos/sem-nome.jpg (foto)"))
        assertTrue(sample, sample.contains("meu-diario/dados.db (outro)"))
        assertTrue(sample, sample.contains("--- meu-diario/notas/Ideias.md"))

        val r = recipe(
            """{"app": "Meu Diário", "about": "…", "shape": "text", "files": "/dias/", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", """ +
                """"notes": "/notas/", "note_title": "first_line", "photos": "/fotos/[^/]+\\.jpg$", "photo_date_from_name": "(?<date>\\d{8})", "photo_date_format": "yyyyMMdd"}""",
        )
        val read = Recipes.apply(r, files.texts, zone, files.others)
        assertEquals(2, read.found.size)
        assertEquals(listOf("Ideias"), read.notes.map { it.title })
        assertEquals("Um livro sobre o mar.", read.notes.single().body)
        assertEquals(1, read.photos.size)
        assertEquals(LocalDate.of(2024, 3, 2), day(read.photos.single().at))
        assertEquals(1, read.undatedPhotos)

        val photosDir = stage()
        val kept = library.importAll(read, photosDir, zone)
        assertEquals(2, kept.added)
        assertEquals(1, kept.notes)
        assertEquals(1, kept.photos)
        val photo = db.backup().captures().single { it.source == "photo" }
        assertEquals(LocalDate.of(2024, 3, 2), day(photo.createdAt))
        assertTrue(java.io.File(photo.imagePath!!).isFile)
        assertEquals(1, db.entries().all().count { it.kindEnum == Kind.NOTE })

        // The same .zip again: nothing twice.
        val again = library.importAll(read, photosDir, zone)
        assertEquals(0, again.added)
        assertEquals(0, again.notes)
        assertEquals(0, again.photos)
        assertEquals(1, db.backup().captures().count { it.source == "photo" })
    }

    /**
     * A .zip written as it streams, the way some servers do (One Goal's export): stored, each file
     * with its sizes only after it (bit 3), the index at the end. Reading it file by file finds nothing.
     */
    private fun streamedZip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        class At(val name: ByteArray, val crc: Long, val size: Int, val start: Int)
        val at = ArrayList<At>()
        for ((name, data) in files) {
            val n = name.toByteArray(); val start = out.size()
            le(0x04034b50, 4); le(20, 2); le(0x0808, 2); le(0, 2); le(0, 4); le(0, 4); le(0, 4); le(0, 4); le(n.size.toLong(), 2); le(0, 2)
            out.write(n); out.write(data)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            le(0x08074b50, 4); le(crc, 4); le(data.size.toLong(), 4); le(data.size.toLong(), 4)
            at += At(n, crc, data.size, start)
        }
        val cd = out.size()
        for (e in at) {
            le(0x02014b50, 4); le(20, 2); le(20, 2); le(0x0808, 2); le(0, 2); le(0, 4); le(e.crc, 4); le(e.size.toLong(), 4); le(e.size.toLong(), 4)
            le(e.name.size.toLong(), 2); le(0, 2); le(0, 2); le(0, 2); le(0, 2); le(0, 4); le(e.start.toLong(), 4); out.write(e.name)
        }
        val end = out.size()
        le(0x06054b50, 4); le(0, 2); le(0, 2); le(at.size.toLong(), 2); le(at.size.toLong(), 2); le((end - cd).toLong(), 4); le(cd.toLong(), 4); le(0, 2)
        return out.toByteArray()
    }

    private val oneGoal = arrayOf(
        "diario-2026-11-13/diario.txt" to "🗓 23/01/2022 00:00\nPrimeiro.\n----\n".toByteArray(),
        "diario-2026-11-13/2022-01-23/entrada.html" to "<small>23/01/2022</small><p>Primeiro.</p>".toByteArray(),
        "diario-2026-11-13/2022-01-23/texto.txt" to "23/01/2022\n\nPrimeiro.\n".toByteArray(),
        "diario-2026-11-13/2024-12-17/entrada.html" to "<small>17/12/2024</small><p>Segundo.</p>".toByteArray(),
        "diario-2026-11-13/2024-12-17/texto.txt" to "17/12/2024\n\nSegundo.\n".toByteArray(),
        "diario-2026-11-13/2024-12-17/fotos/01.jpg" to jpeg(7),
        "diario-2026-11-13/2024-12-17/audios/01.webm" to ByteArray(300) { 0 },
        "diario-2026-11-13/2024-12-25 - Natal/texto.txt" to "25/12/2024 — Natal\n\nCeia.\n".toByteArray(),
    )

    private val oneGoalRecipe get() = recipe(
        """{"app": "One Goal", "about": "…", "shape": "text", "files": "/texto\\.txt$", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", """ +
            """"title_regex": "^\\d{2}/\\d{2}/\\d{4}(?: — (?<title>.+))?$", "photos": "/fotos/", "photo_date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})"}""",
    )

    @Test fun aZipWrittenAsItStreamsIsReadByItsIndex() {
        val files = Unpack.files("diario-2026-11-13.zip", streamedZip(*oneGoal).inputStream(), stage())
        assertEquals(6, files.texts.size)
        assertEquals("Segundo.\n", files.texts.single { it.name.endsWith("2024-12-17/texto.txt") }.text.substringAfter("\n\n"))
        assertEquals(1, files.photos)
        assertEquals(1, files.others.count { !it.isPhoto })

        // Alike folders told together with what each has inside, the formats, and one text of each kind.
        val sample = Sample.of("diario-2026-11-13.zip", files.texts, files.others)
        assertTrue(sample, sample.contains("diario-2026-11-13/diario.txt (texto)"))
        assertTrue(sample, sample.contains("diario-2026-11-13/ tem 3 pastas assim (2022-01-23/, 2024-12-17/, 2024-12-25 - Natal/); dentro de cada uma:"))
        assertTrue(sample, sample.contains("  texto.txt — 3 texto (.txt), em todas"))
        assertTrue(sample, sample.contains("  entrada.html — 2 HTML (.html), em 2 delas"))
        assertTrue(sample, sample.contains("  fotos/01.jpg — 1 fotos (.jpg, sem data dentro), em 1 delas"))
        assertTrue(sample, sample.contains("Os textos: 4 texto, 2 HTML — nenhum é JSON, nenhum é CSV."))
        assertTrue(sample, sample.contains("--- diario-2026-11-13/2022-01-23/texto.txt (texto, 1 KB, começo; há 3 assim)"))
        assertEquals(setOf("text"), Sample.shapes(files.texts))
        assertTrue(OmniRecipe.grammar(setOf("text")).contains("shape ::= \"\\\"text\\\"\"\n"))
        assertFalse(OmniRecipe.grammar(setOf("text")).contains("json\\\"\" |"))

        // The date nearest the file in the path: the day's folder, not the export's.
        val read = Recipes.apply(oneGoalRecipe, files.texts, zone, files.others)
        assertEquals(listOf(LocalDate.of(2022, 1, 23), LocalDate.of(2024, 12, 17), LocalDate.of(2024, 12, 25)), read.found.map { day(it.at) })
        assertEquals(listOf("", "", "Natal"), read.found.map { it.title })
        assertEquals(listOf("Primeiro.", "Segundo.", "Ceia."), read.found.map { it.body })
        assertEquals(LocalDate.of(2024, 12, 17), day(read.photos.single().at))
    }

    @Test fun aFolderPickedIsReadFileByFileWithItsFolders() {
        val pieces = oneGoal.map { (path, bytes) -> Unpack.Piece(path.removePrefix("diario-2026-11-13/"), 1_700_000_000_000L) { bytes.inputStream() } }
        val files = Unpack.pieces(pieces, stage())
        assertEquals(6, files.texts.size)
        assertEquals(1_700_000_000_000L, files.texts.first().time)
        assertTrue(Sample.of("diario-2026-11-13", files.texts, files.others, folder = true).startsWith("UMA PASTA (diario-2026-11-13) COM 8 ARQUIVOS"))
        val read = Recipes.apply(oneGoalRecipe, files.texts, zone, files.others)
        assertEquals(3, read.found.size)
        assertEquals(1, read.photos.size)
    }

    @Test fun whenNothingComesOutOmniLooksAgainOnItsOwnSeeingWhatItGot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asked = mutableListOf<Pair<String, Set<String>>>()
        val answers = ArrayDeque(listOf(
            // JSON when nothing is JSON: can't be.
            """{"app": "Day One", "about": "…", "shape": "json", "entries": "entries"}""",
            // A text recipe whose line matches nothing.
            """{"app": "Texto", "about": "…", "shape": "text", "start": "^NADA$"}""",
            """{"app": "Texto", "about": "Cada entrada começa com a data.", "shape": "text", "start": "^(?<date>\\d{4}-\\d{2}-\\d{2})$", "date_format": "iso"}""",
        ))
        val importer = DiaryImport(scope = scope, ready = { null }, ask = { _, user, shapes -> asked += user to shapes; answers.removeFirst() }, keep = { error("no") }, backup = { true }, zone = { zone })
        importer.open("diario.txt") { "2024-01-15\nPrimeiro.\n2024-01-16\nSegundo.".byteInputStream() }
        val ready = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Ready || it is DiaryImport.State.Failed } } as DiaryImport.State.Ready
        assertEquals(2, ready.reading.found.size)
        assertEquals(DiaryImport.TRIES, asked.size)
        assertEquals(setOf("text"), asked[0].second)
        assertTrue(asked[1].first.contains("A RESPOSTA ANTERIOR NÃO RODOU"))
        assertTrue(asked[2].first.contains("UMA RECEITA ANTERIOR NÃO SERVIU"))
        assertTrue(asked[2].first.contains("O QUE ELA DEU: 0 entradas com data"))
    }

    @Test fun aZipInsideAZipIsOpenedToo() {
        val inner = zipOf("2024-03-01.txt" to "Dentro.".toByteArray())
        val files = Unpack.files("fora.zip", zipOf("export/dentro.zip" to inner).inputStream())
        assertEquals(listOf("export/dentro.zip/2024-03-01.txt"), files.texts.map { it.name })
    }

    @Test fun theCamerasDateInsideAPhoto() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30).atZone(zone).toInstant().toEpochMilli(), Recipes.exif("2024:01:15 10:30:00", null, zone))
        assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), Recipes.exif("2024:01:15 10:30:00", "+00:00", zone))
        assertNull(Recipes.exif("0000:00:00 00:00:00", null, zone))
    }

    @Test fun aZipWithNothingToReadSaysWhatIsInIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val importer = DiaryImport(scope = scope, ready = { null }, ask = { _, _, _ -> "" }, keep = { error("no") }, backup = { true }, stage = { stage() })
        val zip = zipOf("app/dados.db" to ByteArray(4000) { 0 }, "app/config.bin" to ByteArray(100) { 0 })
        importer.open("diario.zip") { zip.inputStream() }
        val failed = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Failed } } as DiaryImport.State.Failed
        assertTrue(failed.message, failed.message.contains(".db") && failed.message.contains(".bin"))
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
            ask = { _, user, _ ->
                asked += user
                """{"app": "Texto", "about": "Cada entrada começa com a data.", "shape": "text", "start": "^(?<date>\\d{4}-\\d{2}-\\d{2})$", "date_format": "iso"}"""
            },
            keep = { library.importAll(it, stage(), zone) },
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
