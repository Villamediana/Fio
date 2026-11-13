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
