package com.villamediana.novelo.backup

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.EntryTopic
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Link
import com.villamediana.novelo.data.Progress
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.data.Topic
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Steps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: NoveloDatabase
    private val zone = ZoneId.of("America/Sao_Paulo")
    private var now = LocalDateTime.of(2026, 11, 11, 14, 32, 5).atZone(zone).toInstant().toEpochMilli()
    private lateinit var app: File
    private lateinit var chosen: File
    private val synced = mutableListOf<Long>()

    @Before fun open() {
        db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext())
        app = tmp.newFolder("captures")
        chosen = tmp.newFolder("Documents")
    }

    @After fun close() { db.close() }

    private fun backup() = Backup(
        db, app, File(tmp.root, "state.json"),
        object : ReminderSync { override suspend fun sync(entryIds: List<Long>) { synced += entryIds } },
        "1.14.0", zone = { zone }, clock = { now },
    )

    private val root get() = FileShelf(chosen)
    private val zip get() = File(chosen, Backup.FILE)

    /** What is in the backup file: path → bytes. */
    private fun inside(file: File = zip): Map<String, ByteArray> = java.util.zip.ZipFile(file).use { z ->
        z.entries().toList().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).readBytes() }
    }

    private fun text(path: String) = inside().getValue(path).toString(Charsets.UTF_8)

    /** A day with a bit of everything: a diary with a photo, a note, a task open, one done, topics and links. */
    private suspend fun aDay(): Map<String, Long> {
        val diary = db.entries().insert(Entry(kind = "diary", title = "Quarta-feira, 11 de novembro", body = "Fui à praia com a Alma.", createdAt = now - 3_600_000, updatedAt = now))
        val note = db.entries().insert(Entry(kind = "note", title = "Senha do wi-fi: casa/2", body = "abc123", sensitive = true, createdAt = now, updatedAt = now))
        val open = db.entries().insert(
            Entry(
                kind = "task", title = "Pagar o IPVA", status = "doing", steps = Steps.write(listOf(Step("boleto", done = true), Step("pagar"))),
                dueAt = now + 86_400_000, remindAt = now + 3_600_000, createdAt = now, updatedAt = now,
            ),
        )
        val done = db.entries().insert(Entry(kind = "task", title = "Comprar pão", status = "done", doneAt = now, createdAt = now, updatedAt = now))
        val topic = db.graph().insertTopic(Topic(name = "Alma", norm = "alma", type = "person"))
        db.graph().tie(EntryTopic(diary, topic))
        db.graph().link(Link(minOf(open, done), maxOf(open, done)))
        db.progress().insert(Progress(entryId = done, at = now, text = "", status = "done"))
        val photo = File(app, "photo-1.jpg").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val shot = db.captures().insert(Capture(createdAt = now - 1_800_000, source = "photo", imagePath = photo.path, transcript = "PRAIA", description = "Alma na praia", state = Capture.DONE))
        db.captures().insert(Capture(createdAt = now, source = "text", transcript = "Comprei pão", state = Capture.DONE))
        // Something said and not heard yet: only a sound on the phone, it doesn't travel.
        db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = File(app, "voice-1.wav").path, state = Capture.RECORDED))
        return mapOf("diary" to diary, "note" to note, "open" to open, "done" to done, "topic" to topic, "photo" to shot)
    }

    @Test
    fun everythingGoesIntoOneFile() = runBlocking {
        val ids = aDay()
        val saved = backup().save(root, "k")
        assertEquals(4, saved.entries)
        assertEquals(1, saved.photos)
        assertEquals(Backup.FILE, saved.snapshot)
        // One file in the folder she chose, nothing else.
        assertEquals(listOf(Backup.FILE), chosen.list()!!.toList())
        assertEquals(
            listOf(
                "Diário/2026/2026-11-11.md",
                "Fotos/2026/2026-11-11 14.02.05 (${ids["photo"]}).jpg",
                "Notas/Senha do wi-fi casa 2.md",
                "Tarefas/Abertas/Pagar o IPVA.md",
                "Tarefas/Feitas/Comprar pão.md",
                Backup.INSIDE,
            ),
            inside().keys.sorted(),
        )
        val diary = text("Diário/2026/2026-11-11.md")
        assertTrue(diary, diary.startsWith("# Quarta-feira, 11 de novembro\n\nFui à praia com a Alma.\n"))
        // The day's photo, from the diary's place in the file.
        assertTrue(diary, diary.contains("![](../../Fotos/2026/2026-11-11%2014.02.05%20%28${ids["photo"]}%29.jpg)"))
        assertTrue(diary, diary.contains("Tópicos: Alma"))
        val task = text("Tarefas/Abertas/Pagar o IPVA.md")
        assertTrue(task, task.contains("Tarefa · fazendo · vence 12/11/2026 · lembrete 11/11/2026 15:32"))
        assertTrue(task, task.contains("- [x] boleto\n- [ ] pagar\n"))
        assertEquals(2048, inside().getValue("Fotos/2026/2026-11-11 14.02.05 (${ids["photo"]}).jpg").size)
        assertEquals(now, backup().read(root, Backup.FILE).madeAt)
    }

    @Test
    fun nothingChangedNothingWrittenAndTheFileIsReplacedWhenSomethingDid() = runBlocking {
        val ids = aDay()
        val b = backup()
        b.save(root, "k")
        zip.setLastModified(1000)
        now += 60_000
        // Again, nothing new: the file isn't written again.
        assertNull(b.save(root, "k").snapshot)
        assertEquals(1000L, zip.lastModified())

        // The task is done and the note is deleted: the file is made again, still the only one.
        db.entries().update(db.entries().get(ids.getValue("open"))!!.copy(status = "done", doneAt = now))
        db.entries().delete(ids.getValue("note"))
        now += 60_000
        assertEquals(Backup.FILE, b.save(root, "k").snapshot)
        val after = inside().keys
        assertTrue(after.toString(), "Tarefas/Feitas/Pagar o IPVA.md" in after)
        assertFalse(after.toString(), "Tarefas/Abertas/Pagar o IPVA.md" in after)
        assertFalse(after.toString(), "Notas/Senha do wi-fi casa 2.md" in after)
        assertEquals(listOf(Backup.FILE), chosen.list()!!.toList())
        assertEquals(listOf(Backup.Kept(Backup.FILE, now)), b.kept(root))
    }

    @Test
    fun anEmptyAppWritesNothing() = runBlocking {
        val saved = backup().save(root, "k")
        assertTrue(saved.empty)
        assertTrue(chosen.list()!!.isEmpty())
    }

    @Test
    fun theOldFoldersBackupsCanStillComeBack() = runBlocking {
        // Up to 1.17.0: Documents/Fio/Backups/fio <date>.json.
        val old = Snapshot(madeAt = now - 86_400_000, entries = listOf(Entry(id = 5, kind = "note", title = "Do backup antigo", createdAt = now, updatedAt = now)))
        File(chosen, "Fio/Backups").apply { mkdirs() }.resolve("fio 2026-11-10 14.32.05.json").writeText(Backup.json.encodeToString(Snapshot.serializer(), old))
        val b = backup()
        assertEquals("Documents/Novelo backup.zip", b.place(root))
        db.entries().insert(Entry(kind = "note", title = "De hoje", createdAt = now, updatedAt = now))
        b.save(root, "k")
        // The new file goes in the chosen folder; the old folder is left as it was.
        assertTrue(zip.isFile)
        assertEquals(listOf("fio 2026-11-10 14.32.05.json"), File(chosen, "Fio/Backups").list()!!.toList())
        assertEquals(listOf(Backup.FILE, "fio 2026-11-10 14.32.05.json"), b.kept(root).map { it.name })
        b.restore(root, "fio 2026-11-10 14.32.05.json")
        assertEquals(listOf("Do backup antigo"), db.backup().entries().map { it.title })
    }

    @Test
    fun anImportPutsEverythingBackAsItWas() = runBlocking {
        val ids = aDay()
        val b = backup()
        b.save(root, "k")
        val before = db.backup().entries()
        val topicsBefore = db.backup().topics()

        // Life goes on: something new, something deleted, the photo gone from the app.
        now += 86_400_000
        db.entries().delete(ids.getValue("diary"))
        val extra = db.entries().insert(Entry(kind = "note", title = "Depois do backup", createdAt = now, updatedAt = now))
        File(db.captures().get(ids.getValue("photo"))!!.imagePath!!).delete()

        val restored = b.restore(root, Backup.FILE)
        assertEquals(4, restored.entries)
        assertEquals(1, restored.photos)
        assertEquals(before, db.backup().entries()) // the same items, the same ids
        assertEquals(topicsBefore, db.backup().topics())
        assertEquals(1, db.backup().ties().size)
        assertEquals(1, db.backup().links().size)
        assertEquals(1, db.backup().progress().size)
        val photo = db.captures().get(ids.getValue("photo"))!!
        assertEquals("Alma na praia", photo.description)
        assertNotEquals("photo-1.jpg", File(photo.imagePath!!).name)
        assertEquals(2048L, File(photo.imagePath!!).length())
        // The sound that never was heard didn't travel; the written thing did.
        assertEquals(listOf("photo", "text"), db.backup().captures().map { it.source })
        assertNull(db.entries().get(extra))
        // Reminders: the ones that were here and the ones that came back.
        assertTrue(synced.containsAll(listOf(extra, ids.getValue("open"))))
        // Still one file, and nothing left over on the phone from opening it.
        assertEquals(listOf(Backup.FILE), chosen.list()!!.toList())
        assertFalse(File(tmp.root, "backup-work").exists())
    }

    @Test
    fun aSoundKeptGoesWithItsItemAndComesBack() = runBlocking {
        val note = db.entries().insert(Entry(kind = "note", title = "Música que eu inventei", createdAt = now, updatedAt = now))
        val wav = File(app, "voice-9.wav").apply { writeBytes(ByteArray(3000) { it.toByte() }) }
        val sung = db.captures().insert(
            Capture(
                createdAt = now, source = "voice", audioPath = wav.path, sound = true, durationMs = 2000, transcript = "lá lá lá", state = Capture.DONE,
                outcomes = """[{"entryId":$note,"action":"created"}]""",
            ),
        )
        db.entries().update(db.entries().get(note)!!.copy(captureId = sung))
        val b = backup()
        val saved = b.save(root, "k")
        assertEquals(1, saved.sounds)
        assertEquals(3000, inside().getValue("Áudios/2026/2026-11-11 14.32.05 ($sung).wav").size)
        val md = text("Notas/Música que eu inventei.md")
        assertTrue(md, md.contains("[Áudio](../Áudios/2026/2026-11-11%2014.32.05%20%28$sung%29.wav)"))

        // Gone from the phone, back with the import.
        wav.delete()
        val restored = b.restore(root, Backup.FILE)
        assertEquals(1, restored.sounds)
        val back = db.captures().get(sung)!!
        assertTrue(back.sound)
        assertEquals(3000L, File(back.audioPath!!).length())
    }

    @Test
    fun photosATaskHoldsAreInItsFile() = runBlocking {
        val task = db.entries().insert(Entry(kind = "task", title = "Comprar o tênis da Alma", status = "todo", createdAt = now, updatedAt = now))
        val said = db.captures().insert(Capture(createdAt = now, source = "text", transcript = "comprar isso pra Alma", state = Capture.DONE, outcomes = """[{"entryId":$task,"action":"created","photos":true}]"""))
        val file = File(app, "photo-7.jpg").apply { writeBytes(ByteArray(512)) }
        val photo = db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = file.path, state = Capture.DONE, partOf = said))
        backup().save(root, "k")
        val md = text("Tarefas/Abertas/Comprar o tênis da Alma.md")
        assertTrue(md, md.contains("![](../../Fotos/2026/2026-11-11%2014.32.05%20%28$photo%29.jpg)"))
    }

    @Test
    fun aBrokenOrNewerBackupIsNotImported() = runBlocking {
        aDay()
        val b = backup()
        val count = db.backup().count()
        zip.writeText("{ estragado")
        val broken = runCatching { b.restore(root, Backup.FILE) }.exceptionOrNull()
        assertTrue(broken?.message.orEmpty(), broken?.message.orEmpty().contains("estragado"))
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(Backup.INSIDE)); z.write("""{"format":99}""".toByteArray()); z.closeEntry()
        }
        val newer = runCatching { b.restore(root, Backup.FILE) }.exceptionOrNull()
        assertTrue(newer?.message.orEmpty(), newer?.message.orEmpty().contains("versão mais nova"))
        // A name inside that points out of the folder goes nowhere.
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("../fora.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertTrue(runCatching { b.restore(root, Backup.FILE) }.isFailure)
        assertFalse(File(tmp.root, "fora.txt").exists())
        assertEquals(count, db.backup().count())
    }

    @Test
    fun namesAreSafeForAnyFolder() {
        assertEquals("Senha do wi-fi casa 2", Readable.fileName("Senha do wi-fi: casa/2"))
        assertEquals("Sem título", Readable.fileName("  /:  "))
        assertEquals(80, Readable.fileName("a".repeat(200)).length)
        assertTrue(Readable.isSnapshot("fio 2026-11-11 14.32.05.json"))
        assertFalse(Readable.isSnapshot("fio.json"))
        assertNull(Readable.snapshotTime("outra coisa.json", zone))
    }

    @Test
    fun twoItemsWithTheSameNameBothStay() {
        val a = Entry(id = 1, kind = "note", title = "Lista", createdAt = now, updatedAt = now)
        val b = Entry(id = 7, kind = "note", title = "Lista", createdAt = now, updatedAt = now)
        assertEquals(setOf("Notas/Lista.md", "Notas/Lista (#7).md"), Readable.files(Snapshot(entries = listOf(b, a)), zone).keys)
    }
}
