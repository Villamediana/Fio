package com.villamediana.novelo.mind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.ui.library.inCategory
import com.villamediana.novelo.ui.library.mergeOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** The tasks' categories: the model's and hers, few, one name each, and the loose ones put in one when the app starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoriesTest {
    private lateinit var db: NoveloDatabase
    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Before fun open() { db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    private fun task(title: String, category: String? = null, status: String = "todo") = runBlocking {
        db.entries().insert(Entry(kind = "task", title = title, status = status, category = category, createdAt = now, updatedAt = now))
    }

    @Test
    fun aNameIsOneWhateverTheWayItIsWritten() {
        assertEquals("Trabalho", Categories.clean("  trabalho  "))
        assertEquals("Vida pessoal", Categories.clean("vida   pessoal"))
        assertNull(Categories.clean("   "))
        assertEquals("Saúde", Categories.pick("saude", listOf("Trabalho", "Saúde")))
        assertEquals("Casa", Categories.pick("casa", listOf("Trabalho")))
    }

    @Test
    fun theModelUsesTheOnesThereAreAndChangesThem() = runBlocking {
        val old = task("Relatório do mês", "Trabalho")
        val keeper = Keeper(db, zone = { zone }, clock = { now })
        // A new task: "trabalho" is the one there is; another that fits none makes a new one.
        val plan = Brain.parse("""{"reply":"ok","create":[{"kind":"task","title":"Ligar pro cliente","category":"trabalho"},{"kind":"task","title":"Consertar a pia","category":"Casa"},{"kind":"note","title":"Senha","category":"Casa"}]}""")!!
        val r = keeper.apply(plan, null)
        val made = r.outcomes.map { db.entries().get(it.entryId)!! }
        assertEquals(listOf("Trabalho", "Casa", null), made.map { it.category })
        // Moved by the model.
        keeper.apply(Brain.parse("""{"reply":"ok","update":[{"id":$old,"category":"Casa"}]}""")!!, null)
        assertEquals("Casa", db.entries().get(old)!!.category)
        // Renamed, then joined into another, then taken off.
        keeper.apply(Brain.parse("""{"reply":"ok","categories":[{"name":"casa","to":"Lar"}]}""")!!, null)
        assertEquals(setOf("Trabalho", "Lar"), db.entries().categories().toSet())
        keeper.apply(Brain.parse("""{"reply":"ok","categories":[{"name":"Lar","to":"trabalho"}]}""")!!, null)
        assertEquals(listOf("Trabalho"), db.entries().categories())
        keeper.apply(Brain.parse("""{"reply":"ok","categories":[{"name":"Trabalho"}]}""")!!, null)
        assertTrue(db.entries().categories().isEmpty())
    }

    @Test
    fun theModelSeesTheCategories() {
        val t = Entry(id = 7, kind = "task", title = "Pagar o IPVA", status = "todo", category = "Casa", createdAt = now, updatedAt = now)
        val context = Brain.context("oi", now, zone, listOf(t), emptyList(), categories = listOf("Casa", "Trabalho"))
        assertTrue(context, context.contains("CATEGORIAS DAS TAREFAS: Casa, Trabalho"))
        assertTrue(context, context.contains("\"Pagar o IPVA\" · categoria Casa"))
        assertTrue(Brain.SYSTEM.contains("- categories: [{name, to}]"))
    }

    @Test
    fun theLooseOnesGoIntoOneWhenTheAppStarts() = runBlocking {
        task("Relatório", "Trabalho")
        val a = task("Mandar o orçamento")
        val b = task("Comprar ração")
        val c = task("Já feita", status = "done")
        var asked = ""
        val thinker = object : Thinker {
            override suspend fun seesImages() = false
            override suspend fun answer(system: String, user: String, image: ByteArray?): String {
                asked = user
                return """{"$a": "trabalho", "$b": "Casa", "999": "Outra"}"""
            }
        }
        val organizer = Organizer(db, thinker)
        assertEquals(2, organizer.organize())
        // It got the categories there are and only the open ones with none.
        assertTrue(asked, asked.contains("CATEGORIAS QUE JÁ EXISTEM: Trabalho"))
        assertTrue(asked, asked.contains("#$a \"Mandar o orçamento\""))
        assertTrue(asked, !asked.contains("Já feita"))
        assertEquals("Trabalho", db.entries().get(a)!!.category)
        assertEquals("Casa", db.entries().get(b)!!.category)
        assertNull(db.entries().get(c)!!.category)
        // Once per start; nothing left the next time.
        organizer.onStart()
        assertEquals(0, organizer.organize())
    }

    @Test
    fun aModelThatCantNeverStopsTheApp() = runBlocking {
        task("Mandar o orçamento")
        val organizer = Organizer(db, object : Thinker {
            override suspend fun seesImages() = false
            override suspend fun answer(system: String, user: String, image: ByteArray?): String = throw NotReady(Need.THINK, "sem")
        })
        organizer.onStart()
        assertEquals(1, db.entries().uncategorized().size)
    }

    /** 1.63: Tarefas is one group per category, under its name; none empty; the loose ones on top; the done ones last (they fold). */
    @Test
    fun theTasksGoInAGroupPerCategory() {
        var id = 0L
        fun e(c: String?, position: Long? = null, status: String = "todo") = Entry(id = ++id, kind = "task", title = "$id", status = status, category = c, position = position, createdAt = now + id, updatedAt = now, doneAt = if (status == "done") now else null)
        val a1 = e("Trabalho"); val b1 = e("Casa"); val a2 = e("Trabalho", 1); val a3 = e("Trabalho", 0)
        val loose = e(null); val done = e("Casa", status = "done"); val doing = e("Casa", status = "doing")
        val sections = com.villamediana.novelo.ui.library.sectionsFor(com.villamediana.novelo.ui.Filter.TASKS, listOf(a1, b1, a2, a3, loose, done, doing), showDone = false)
        assertEquals(listOf("Sem categoria", "Trabalho", "Casa", "Feitas"), sections.map { it.first })
        // Inside: the new one on top, then her order; doing or waiting is the same group (the card says it).
        assertEquals(listOf(a1.id, a3.id, a2.id), sections[1].second.map { it.id })
        assertEquals(setOf(b1.id, doing.id), sections[2].second.map { it.id }.toSet())
        assertEquals(listOf(done.id), sections[3].second.map { it.id })
        // A category with no open task has no group.
        val onlyDone = com.villamediana.novelo.ui.library.sectionsFor(com.villamediana.novelo.ui.Filter.TASKS, listOf(a1, e("Saúde", status = "done")), showDone = false)
        assertEquals(listOf("Trabalho", "Feitas"), onlyDone.map { it.first })
    }

    @Test
    fun inAnotherCategoryItGoesWithThoseOnes() = runBlocking {
        val t = db.entries().insert(Entry(kind = "task", title = "Pia", status = "todo", category = "Trabalho", position = 3, createdAt = now, updatedAt = now))
        com.villamediana.novelo.data.Library(db, com.villamediana.novelo.remind.Reminders(ApplicationProvider.getApplicationContext(), db)).setCategory(t, "Casa")
        assertEquals("Casa" to null, db.entries().get(t)!!.let { it.category to it.position })
        // By the model too.
        db.entries().update(db.entries().get(t)!!.copy(position = 4))
        Keeper(db, zone = { zone }, clock = { now }).apply(Brain.parse("""{"reply":"ok","update":[{"id":$t,"category":"Trabalho"}]}""")!!, null)
        assertEquals("Trabalho" to null, db.entries().get(t)!!.let { it.category to it.position })
    }

    @Test
    fun theOrderFromBeforeCategoriesIsLetGoOnce() = runBlocking {
        val t = db.entries().insert(Entry(kind = "task", title = "Pia", status = "todo", position = 3, createdAt = now, updatedAt = now))
        val n = db.entries().insert(Entry(kind = "note", title = "Ideia", position = 1, createdAt = now, updatedAt = now))
        NoveloDatabase.MIGRATION_10_11.migrate(db.openHelper.writableDatabase)
        assertNull(db.entries().get(t)!!.position)
        // The notes' order is not the tasks'.
        assertEquals(1L, db.entries().get(n)!!.position)
    }

    @Test
    fun oneCategoryOnTheScreenKeepsTheOthersInTheirPlaces() {
        // Whole group: 1 2 3 4 5; on the screen only 2 and 4 (one category), moved to 4, 2.
        assertEquals(listOf(1L, 4L, 3L, 2L, 5L), mergeOrder(listOf(1L, 2L, 3L, 4L, 5L), listOf(4L, 2L)))
        val e = { id: Long, c: String? -> Entry(id = id, kind = "task", title = "$id", category = c, createdAt = now, updatedAt = now) }
        val sections = listOf<Pair<String?, List<Entry>>>("A fazer" to listOf(e(1, "Casa"), e(2, "Trabalho")), "Esperando" to listOf(e(3, "Casa")))
        assertEquals(listOf(listOf(1L), listOf(3L)), inCategory(sections, "Casa").map { (_, l) -> l.map { it.id } })
        assertEquals(sections, inCategory(sections, null))
    }
}
