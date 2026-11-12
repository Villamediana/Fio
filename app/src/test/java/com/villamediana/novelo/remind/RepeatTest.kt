package com.villamediana.novelo.remind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.mind.Keeper
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Steps
import com.villamediana.novelo.settings.Lang
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** Reminders that come back: every day, on days of the week, every month — and a task that repeats, done. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeatTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: NoveloDatabase

    @Before fun open() {
        Lang.init(ApplicationProvider.getApplicationContext())
        db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun close() { db.close() }

    // Wednesday, 2026-11-11.
    private val wed10 = at(2026, 11, 11, 10)

    @Test
    fun theNextTime() {
        assertEquals(at(2026, 11, 11, 21), Repeat(Repeat.DAY, time = "21:00").next(wed10, zone))
        assertEquals(at(2026, 11, 12, 8), Repeat(Repeat.DAY, time = "08:00").next(wed10, zone))
        // Mondays and Wednesdays at 8: today's 8 is gone, next is Monday.
        assertEquals(at(2026, 11, 16, 8), Repeat(Repeat.WEEK, listOf(1, 3), "08:00").next(wed10, zone))
        assertEquals(at(2026, 11, 11, 18), Repeat(Repeat.WEEK, listOf(1, 3), "18:00").next(wed10, zone))
        // The 31st of every month: a short month takes its last day.
        assertEquals(at(2026, 11, 30, 9), Repeat(Repeat.MONTH, time = "09:00", day = 31).next(wed10, zone))
        assertEquals(at(2026, 12, 10, 9), Repeat(Repeat.MONTH, time = "09:00", day = 10).next(wed10, zone))
        assertNull(Repeat.read("""{"every":"week","days":[]}"""))
    }

    @Test
    fun saidInWords() {
        assertEquals("todo dia às 21:00", Repeat(Repeat.DAY, time = "21:00").describe())
        assertEquals("seg e qua às 08:00", Repeat(Repeat.WEEK, listOf(3, 1), "08:00").describe())
        assertEquals("todo mês no dia 10 às 09:00", Repeat(Repeat.MONTH, time = "09:00", day = 10).describe())
    }

    @Test
    fun aTaskThatRepeatsDoneComesBackForTheNextTime() {
        val rule = Repeat(Repeat.WEEK, listOf(1, 3), "08:00")
        val e = Entry(
            id = 1, kind = "task", title = "Tomar o remédio", status = "todo", remindAt = at(2026, 11, 11, 8), dueAt = at(2026, 11, 11, 0),
            steps = Steps.write(listOf(Step("água", done = true))), repeat = Repeat.write(rule), createdAt = wed10, updatedAt = wed10,
        )
        val after = Repeat.done(e, wed10, zone)
        assertEquals(Status.TODO, after.statusEnum)
        assertEquals(at(2026, 11, 16, 8), after.remindAt)
        assertEquals(at(2026, 11, 16, 0), after.dueAt)
        assertEquals(false, Steps.read(after.steps).single().done)
        // One that doesn't repeat is just done.
        val once = Repeat.done(e.copy(repeat = null), wed10, zone)
        assertEquals(Status.DONE, once.statusEnum)
        assertNull(once.remindAt)
    }

    @Test
    fun theModelSetsItAndStopsIt() = runBlocking {
        val keeper = Keeper(db, zone = { zone }, clock = { wed10 })
        val r = keeper.apply(Brain.parse("""{"create":[{"kind":"task","title":"Academia","remind_at":"2026-11-11T18:00","repeat":{"every":"week","days":[1,3,5]}}]}""")!!, null)
        val id = r.outcomes.single().entryId
        val e = db.entries().get(id)!!
        assertEquals(Repeat(Repeat.WEEK, listOf(1, 3, 5), "18:00"), Repeat.read(e.repeat))
        assertEquals(at(2026, 11, 11, 18), e.remindAt)
        // Done (said): it comes back on Friday.
        keeper.apply(Brain.parse("""{"update":[{"id":$id,"status":"done"}]}""")!!, null)
        val done = db.entries().get(id)!!
        assertEquals(Status.TODO, done.statusEnum)
        assertEquals(at(2026, 11, 13, 18), done.remindAt)
        assertEquals("done", db.progress().latest(id, 1).single().status)
        // Not any more.
        keeper.apply(Brain.parse("""{"update":[{"id":$id,"repeat":{"every":"none"}}]}""")!!, null)
        assertNull(db.entries().get(id)!!.repeat)
        // Every day with no hour said: 9:00, the next one.
        val daily = keeper.apply(Brain.parse("""{"create":[{"kind":"task","title":"Regar as plantas","repeat":{"every":"day"}}]}""")!!, null).outcomes.single().entryId
        assertEquals(at(2026, 11, 12, 9), db.entries().get(daily)!!.remindAt)
    }

    @Test
    fun tickedInTheAppItComesBackToo() = runBlocking {
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { wed10 })
        val id = db.entries().insert(Entry(kind = "task", title = "Regar", status = "todo", remindAt = at(2026, 11, 11, 9), repeat = Repeat.write(Repeat(Repeat.DAY, time = "09:00")), createdAt = wed10, updatedAt = wed10))
        lib.setStatus(id, Status.DONE, zone)
        val e = db.entries().get(id)!!
        assertEquals(Status.TODO, e.statusEnum)
        assertEquals(at(2026, 11, 12, 9), e.remindAt)
    }
}
