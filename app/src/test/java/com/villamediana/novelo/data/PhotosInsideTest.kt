package com.villamediana.novelo.data

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.mind.ReminderSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where a photo lives: a moment's is loose (the gallery, the day's diary, the photo search); one that
 * belongs to a note (a paper, a receipt) or to anything sensitive lives only inside that item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotosInsideTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: NoveloDatabase
    private lateinit var lib: Library
    private val now = 1_763_000_000_000L

    @Before fun open() {
        db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext())
        lib = Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} })
    }

    @After fun close() { db.close() }

    private fun file(name: String) = File(tmp.root, name).apply { writeBytes(ByteArray(10)) }.path

    private suspend fun entry(kind: String, title: String, sensitive: Boolean = false) =
        db.entries().insert(Entry(kind = kind, title = title, sensitive = sensitive, createdAt = now, updatedAt = now))

    /** A photo sent alone; [made]: what it turned into. */
    private suspend fun alone(name: String, vararg made: Long) = db.captures().insert(
        Capture(createdAt = now, source = "photo", imagePath = file(name), state = Capture.DONE, outcomes = made.joinToString(",", "[", "]") { """{"entryId":$it,"action":"created"}""" }),
    )

    /** Photos sent with something said that went to [to] ("photos": true). */
    private suspend fun sentWith(to: Long, vararg names: String): List<Long> {
        val said = db.captures().insert(Capture(createdAt = now, source = "text", transcript = "isso", state = Capture.DONE, outcomes = """[{"entryId":$to,"action":"created","photos":true}]"""))
        return names.map { db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = file(it), state = Capture.DONE, partOf = said)) }
    }

    @Test
    fun aMomentIsLooseANotesPaperLivesInTheNote() = runBlocking {
        val moment = alone("praia.jpg")
        val note = entry("note", "Recibo do mercado")
        val receipt = alone("recibo.jpg", note)
        // The gallery (and the search, the day's diary): the moment, not the receipt.
        assertEquals(listOf(moment), lib.loosePhotos().first().map { it.id })
        // The receipt is in its note.
        assertEquals(listOf(receipt), lib.photosOf(note).first().map { it.id })
    }

    @Test
    fun photosSentWithANoteLiveInTheNoteATasksStayLoose() = runBlocking {
        val note = entry("note", "Documentos do carro")
        val docs = sentWith(note, "crlv.jpg", "seguro.jpg")
        val task = entry("task", "Comprar o tênis azul")
        val shoe = sentWith(task, "tenis.jpg")
        assertEquals(shoe, lib.loosePhotos().first().map { it.id })
        assertEquals(docs, lib.photosOf(note).first().map { it.id })
        assertEquals(shoe, lib.photosOf(task).first().map { it.id })
    }

    @Test
    fun anythingSensitiveKeepsItsPhotosInside() = runBlocking {
        val task = entry("task", "Renovar o passaporte", sensitive = true)
        val passport = sentWith(task, "passaporte.jpg")
        assertEquals(emptyList<Long>(), lib.loosePhotos().first().map { it.id })
        assertEquals(passport, lib.photosOf(task).first().map { it.id })
        // Not sensitive any more: back in the gallery (a task's photos are loose).
        lib.setSensitive(task, false)
        assertEquals(passport, lib.loosePhotos().first().map { it.id })
    }

    @Test
    fun theDaysPhotosAreTheLooseOnes() = runBlocking {
        val moment = alone("por-do-sol.jpg")
        alone("rg.jpg", entry("note", "RG"))
        val day = (now - 1000)..(now + 1000)
        assertEquals(listOf(moment), lib.photos(day).first().map { it.id })
    }
}
