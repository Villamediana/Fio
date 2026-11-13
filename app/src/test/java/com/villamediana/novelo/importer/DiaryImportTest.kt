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

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    private fun jpeg(seed: Int) = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 0, 0, 0, seed.toByte(), -1, -39)

    private fun stage() = kotlin.io.path.createTempDirectory("stage").toFile()

    /**
     * A .zip written as it streams, the way One Goal's export is: stored, each file with its sizes
     * only after it (bit 3), the index at the end. Reading it file by file finds nothing.
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


    /** One Goal's export: everything in a diario.txt, and one folder a day with the entry as text, as HTML, and its photos and audio. */
    private val oneGoal = arrayOf(
        "diario-2026-11-13/diario.txt" to "🗓 23/01/2022 00:00\nPrimeiro.\n----\n".toByteArray(),
        "diario-2026-11-13/2022-01-23/entrada.html" to "<small>23/01/2022</small><p>Primeiro.</p>".toByteArray(),
        "diario-2026-11-13/2022-01-23/texto.txt" to "23/01/2022\n\nPrimeiro.\n".toByteArray(),
        "diario-2026-11-13/2024-12-17/entrada.html" to "<small>17/12/2024</small><p>Segundo.</p>".toByteArray(),
        "diario-2026-11-13/2024-12-17/texto.txt" to "17/12/2024\n\nSegundo.\n\nOutro parágrafo.\n".toByteArray(),
        "diario-2026-11-13/2024-12-17/fotos/01.jpg" to jpeg(7),
        "diario-2026-11-13/2024-12-17/fotos/02.jpg" to jpeg(8),
        "diario-2026-11-13/2024-12-17/audios/01.webm" to ByteArray(300) { 0 },
        "diario-2026-11-13/2024-12-25 - Natal/texto.txt" to "25/12/2024 — Natal em casa\n\nCeia.\n".toByteArray(),
    )

    // ---- reading the day folders ----

    @Test fun oneGoalsZipEachDaysFolderIsAnEntryTheDateFromTheFolder() {
        val files = Unpack.files("diario-2026-11-13.zip", streamedZip(*oneGoal).inputStream(), stage())
        assertEquals(6, files.texts.size)
        val read = DayFolders.read(files.texts, files.others, zone)
        // The day's folder gives the date (not the export's, above it); diario.txt and the .html are left as they are.
        assertEquals(listOf(LocalDate.of(2022, 1, 23), LocalDate.of(2024, 12, 17), LocalDate.of(2024, 12, 25)), read.found.map { day(it.at) })
        // The first line, the day's date itself, leaves the text; what follows it is the title.
        assertEquals(listOf("", "", "Natal em casa"), read.found.map { it.title })
        assertEquals(listOf("Primeiro.", "Segundo.\n\nOutro parágrafo.", "Ceia."), read.found.map { it.body })
        // The photos in the day's folder go on that day.
        assertEquals(2, read.photos.size)
        assertTrue(read.photos.all { day(it.at) == LocalDate.of(2024, 12, 17) })
    }

    @Test fun aFolderPickedReadsTheSame() {
        val pieces = oneGoal.map { (path, bytes) -> Unpack.Piece(path.removePrefix("diario-2026-11-13/")) { bytes.inputStream() } }
        val files = Unpack.pieces(pieces, stage())
        val read = DayFolders.read(files.texts, files.others, zone)
        assertEquals(3, read.found.size)
        assertEquals(2, read.photos.size)
    }

    @Test fun aFirstLineThatIsntTheDaysDateStaysInTheText() {
        val read = DayFolders.read(listOf(TextFile("2024-03-01/nota.md", "Acordei cedo.\nFui correr.")), emptyList(), zone)
        assertEquals("", read.found.single().title)
        assertEquals("Acordei cedo.\nFui correr.", read.found.single().body)
        assertEquals(LocalDate.of(2024, 3, 1), day(read.found.single().at))
        // A folder that isn't a date, or a text that isn't .txt/.md, is no entry.
        assertTrue(DayFolders.read(listOf(TextFile("notas/ideia.txt", "x"), TextFile("2024-03-01/a.html", "x")), emptyList(), zone).isEmpty)
        assertNull(DayFolders.dayOf("2024-13-40"))
    }

    @Test fun aZipInsideAZipIsOpenedToo() {
        val inner = zipOf("2024-03-01/texto.txt" to "Dentro.".toByteArray())
        val files = Unpack.files("fora.zip", zipOf("export/dentro.zip" to inner).inputStream())
        assertEquals(listOf("export/dentro.zip/2024-03-01/texto.txt"), files.texts.map { it.name })
        assertEquals(1, DayFolders.read(files.texts, files.others, zone).found.size)
    }

    @Test fun oldWindowsTextComesWithItsAccents() {
        val text = Unpack.decode("Ação e emoção".toByteArray(charset("windows-1252")))
        assertEquals("Ação e emoção", text)
        assertNull(Unpack.decode(ByteArray(64) { if (it % 3 == 0) 0 else 7 }))
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


    @Test fun theZipsPhotosGoInOnceEachOnItsDay() = runBlocking {
        val files = Unpack.files("diario.zip", streamedZip(*oneGoal).inputStream(), stage())
        val read = DayFolders.read(files.texts, files.others, zone)
        val photosDir = stage()
        val kept = library.importAll(read, photosDir, zone)
        assertEquals(3, kept.added)
        assertEquals(2, kept.photos)
        val photos = db.backup().captures().filter { it.source == "photo" }
        assertEquals(2, photos.size)
        assertTrue(photos.all { day(it.createdAt) == LocalDate.of(2024, 12, 17) && java.io.File(it.imagePath!!).isFile })
        // The same .zip again: nothing twice.
        val again = library.importAll(read, photosDir, zone)
        assertEquals(0, again.added)
        assertEquals(0, again.photos)
        assertEquals(2, db.backup().captures().count { it.source == "photo" })
        assertEquals(3, db.entries().all().count { it.kindEnum == Kind.DIARY })
    }

    @Test fun pickedReadConfirmedThenTheBackup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var backedUp = false
        val importer = DiaryImport(scope = scope, keep = { library.importAll(it, stage(), zone) }, backup = { backedUp = true; true }, zone = { zone }, stage = { stage() })
        importer.open("diario.zip") { streamedZip(*oneGoal).inputStream() }
        val ready = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Ready || it is DiaryImport.State.Failed } }
        ready as DiaryImport.State.Ready
        assertEquals(3, ready.reading.found.size)
        importer.confirm()
        val done = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Done } } as DiaryImport.State.Done
        assertEquals(3, done.kept.added)
        assertEquals(2, done.kept.photos)
        assertEquals(true, done.backup)
        assertTrue(backedUp)
    }

    @Test fun nothingInDayFoldersSaysSo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val importer = DiaryImport(scope = scope, keep = { error("no") }, backup = { true }, stage = { stage() })
        importer.open("diario.zip") { zipOf("app/dados.db" to ByteArray(4000) { 0 }, "notas/a.txt" to "x".toByteArray()).inputStream() }
        val failed = withTimeout(5_000) { importer.state.first { it is DiaryImport.State.Failed } } as DiaryImport.State.Failed
        assertTrue(failed.message, failed.message.contains("AAAA-MM-DD"))
    }
}
