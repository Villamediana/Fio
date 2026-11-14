package com.villamediana.novelo.meds

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.mind.Keeper
import kotlinx.coroutines.runBlocking
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Remédios: the times of a day, the next reminder, the dose worked out, and the model making, changing, ticking and taking them off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedsTest {
    private lateinit var db: NoveloDatabase
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 14) // a Saturday
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 10)

    @Before fun open() { db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    private fun med(name: String, times: List<String>, start: Long = at(today, 0), end: Long? = null, days: List<Int> = emptyList(), person: String? = null, id: Long = 0) =
        Med(id = id, name = name, times = Doses.writeTimes(times.map { LocalTime.parse(it) }), days = Doses.writeDays(days), startsAt = start, endsAt = end, person = person, createdAt = start, updatedAt = start)

    @Test
    fun timesAndDosesAreReadAsTheyAreSaid() {
        assertEquals(LocalTime.of(8, 0), Doses.time("8"))
        assertEquals(LocalTime.of(8, 0), Doses.time("8h"))
        assertEquals(LocalTime.of(20, 30), Doses.time("20h30"))
        assertEquals(LocalTime.of(7, 5), Doses.time("07:05"))
        assertEquals(LocalTime.MIDNIGHT, Doses.time("24:00"))
        assertNull(Doses.time("de manhã"))
        // De 8 em 8 horas from 08:00; de 12 em 12 from 07:00.
        assertEquals(listOf("00:00", "08:00", "16:00"), Doses.every(LocalTime.of(8, 0), 8).map(Doses::hhmm))
        assertEquals(listOf("07:00", "19:00"), Doses.every(LocalTime.of(7, 0), 12).map(Doses::hhmm))
        // 500 mg, half a tablet: 250 mg. Two: 1000 mg. One is the box itself (nothing to add).
        assertEquals("250 mg", Doses.amount("500 mg", "½ comprimido"))
        assertEquals("250 mg", Doses.amount("500mg", "meio comprimido"))
        assertEquals("250 mg", Doses.amount("500 mg", "1/2"))
        assertEquals("1000 mg", Doses.amount("500 mg", "2 comprimidos"))
        assertEquals("1,5 g", Doses.amount("1 g", "1 e meio comprimido"))
        assertEquals("0,25 mg", Doses.amount("0,5 mg", "meia"))
        assertNull(Doses.amount("500 mg", "1 comprimido"))
        // Drops of a syrup, a strength per ml: not in pieces, not worked out.
        assertNull(Doses.amount("200 mg/ml", "10 gotas"))
        assertNull(Doses.amount("500 mg", "10 gotas"))
        assertNull(Doses.amount("500 mg", "5 ml"))
    }

    @Test
    fun aDayHasItsTimesTogether() {
        val amox = med("Amoxicilina", listOf("08:00", "16:00", "00:00"), person = "Cooper", id = 1)
        val losartana = med("Losartana", listOf("08:00"), id = 2)
        // Started today at 10:00: today's 08:00 doesn't count.
        val late = med("Vitamina C", listOf("08:00", "20:00"), start = at(today, 10), id = 3)
        // Ended yesterday, and one only on Mondays: not today.
        val gone = med("Antialérgico", listOf("08:00"), start = at(today.minusDays(5), 0), end = at(today.minusDays(1), 23, 59), id = 4)
        val monday = med("Vitamina D", listOf("09:00"), days = listOf(1), id = 5)
        val slots = Doses.day(listOf(amox, losartana, late, gone, monday), today, zone)
        assertEquals(listOf("00:00", "08:00", "16:00", "20:00"), slots.map { Doses.hhmm(java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalTime()) })
        assertEquals(listOf("Amoxicilina", "Losartana"), slots[1].meds.map { it.name })
        assertEquals(listOf("Vitamina C"), slots[3].meds.map { it.name })
        // The same time of two medicines is one reminder with both.
        assertEquals(listOf(1L, 2L), Doses.at(listOf(amox, losartana, late), at(today, 8), zone).map { it.id })
        assertTrue(Doses.ended(gone, now))
        assertFalse(Doses.ended(amox, now))
    }

    @Test
    fun theNextReminderIsTheFirstTimeOfAny() {
        val a = med("A", listOf("08:00", "20:00"), id = 1)
        val b = med("B", listOf("13:30"), id = 2)
        assertEquals(at(today, 13, 30), Doses.next(listOf(a, b), now, zone))
        assertEquals(at(today, 20), Doses.next(listOf(a, b), at(today, 13, 30), zone))
        assertEquals(at(today.plusDays(1), 8), Doses.next(listOf(a, b), at(today, 21), zone))
        // Only on Mondays: the coming Monday. Only when needed, or over: none.
        val monday = med("D", listOf("09:00"), days = listOf(1), id = 3)
        assertEquals(at(LocalDate.of(2026, 11, 16), 9), Doses.next(listOf(monday), now, zone))
        assertNull(Doses.next(listOf(med("Dipirona", emptyList(), id = 4)), now, zone))
        assertNull(Doses.next(listOf(med("Fim", listOf("08:00"), end = at(today, 9), id = 5)), now, zone))
        // One that starts in three weeks still has its first time armed.
        val later = med("Depois", listOf("08:00"), start = at(today.plusDays(21), 0), id = 6)
        assertEquals(at(today.plusDays(21), 8), Doses.next(listOf(later), now, zone))
    }

    @Test
    fun theModelMakesChangesTicksAndTakesOff() = runBlocking {
        val keeper = Keeper(db, zone = { zone }, clock = { now })
        // A prescription said: for Cooper, every 8 hours, for 7 days; the sum is worked out when not said.
        val r = keeper.apply(Brain.parse("""{"reply":"ok","meds":[
            {"name":"Amoxicilina","strength":"500 mg","dose":"½ comprimido","person":"cooper","times":["08:00"],"every_hours":8,"end":"2026-11-20","notes":"depois de comer"},
            {"name":"Losartana","strength":"50 mg","dose":"1 comprimido","times":["8h"],"start":"2026-11-13"},
            {"name":"Dipirona","strength":"1 g","dose":"1 comprimido"}
        ]}""")!!, null)
        assertTrue(r.medsChanged)
        assertEquals(3, r.meds.size)
        val (amox, losartana, dipirona) = r.meds.map { db.meds().get(it)!! }
        assertEquals("Cooper", amox.person)
        assertEquals("250 mg", amox.amount)
        assertEquals(listOf("00:00", "08:00", "16:00"), Doses.times(amox).map(Doses::hhmm))
        assertEquals(8, amox.everyHours)
        assertEquals(Doses.endOf(LocalDate.of(2026, 11, 20), zone), amox.endsAt)
        assertEquals(now, amox.startsAt) // started today, said without an hour: from now on
        assertNull(losartana.person)
        assertNull(losartana.amount)
        assertTrue(Doses.times(dipirona).isEmpty()) // only when needed
        // Another for "COOPER" is the same Cooper.
        keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"Vermífugo","person":"COOPER","times":["09:00"]}]}""")!!, null)
        assertEquals(setOf("Cooper"), db.meds().all().mapNotNull { it.person }.toSet())
        // Changed: other hours (not every 8 any more), no end; the dose of now given.
        keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"id":${amox.id},"times":["07:00","19:00"],"end":"none"},{"id":${losartana.id},"taken":true}]}""")!!, null)
        val changed = db.meds().get(amox.id)!!
        assertEquals(listOf("07:00", "19:00"), Doses.times(changed).map(Doses::hhmm))
        assertNull(changed.everyHours)
        assertNull(changed.endsAt)
        assertEquals(listOf(MedTaken(losartana.id, at(today, 8), now)), db.meds().takenBetween(at(today, 0), at(today, 23, 59)))
        // Taken off: it goes, with its times taken.
        val gone = keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"id":${losartana.id},"delete":true}]}""")!!, null)
        assertTrue(gone.medsChanged)
        assertNull(db.meds().get(losartana.id))
        assertTrue(db.meds().takenBetween(at(today, 0), at(today, 23, 59)).isEmpty())
    }

    @Test
    fun aPhotoSentAloneStaysWithTheMedicineMadeFromIt() = runBlocking {
        val keeper = Keeper(db, zone = { zone }, clock = { now })
        val r = keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"Prednisolona","strength":"20 mg","times":["08:00"]}]}""")!!, 77, photo = 77)
        assertEquals(77L, db.meds().get(r.meds.single())!!.photoId)
    }

    @Test
    fun theModelSeesHerMedicinesAndTodaysTimes() {
        val amox = med("Amoxicilina", listOf("08:00", "16:00", "00:00"), person = "Cooper", id = 3).copy(strength = "500 mg", dose = "½ comprimido", amount = "250 mg", everyHours = 8, endsAt = at(today.plusDays(6), 23, 59))
        val text = Brain.context("já dei o remédio do Cooper", now, zone, emptyList(), emptyList(), meds = listOf(amox), taken = listOf(MedTaken(3, at(today, 8), now)))
        assertTrue(text, text.contains("REMÉDIOS:"))
        assertTrue(text, text.contains("remédio#3 \"Amoxicilina\" · 500 mg · ½ comprimido · 250 mg · pra Cooper · 00:00, 08:00, 16:00 · de 8 em 8 h"))
        assertTrue(text, text.contains("até 2026-11-20"))
        assertTrue(text, text.contains("hoje: 00:00, 08:00 tomado, 16:00"))
        // A medicine is not something to look up: a plan with one is not only a lookup.
        assertFalse(Brain.parse("""{"reply":"ok","search":["amoxicilina"],"meds":[{"name":"Amoxicilina"}]}""")!!.isLookupOnly)
        assertTrue(Brain.SYSTEM.contains("\"meds\""))
    }

    @Test
    fun aMedicineInWords() {
        val amox = med("Amoxicilina", listOf("08:00", "16:00", "00:00"), id = 1).copy(strength = "500 mg", dose = "½ comprimido", everyHours = 8, endsAt = Doses.endOf(today.plusDays(6), zone))
        assertEquals("½ comprimido · 250 mg (de 500 mg)", MedText.dose(amox))
        assertEquals("de 8 em 8 h · 00:00 · 08:00 · 16:00", MedText.schedule(amox))
        assertEquals("quando precisar", MedText.schedule(med("Dipirona", emptyList())))
        assertEquals("seg, qua e sex · 09:00", MedText.schedule(med("D", listOf("09:00"), days = listOf(1, 3, 5))))
        assertTrue(MedText.period(amox, now, zone)!!.endsWith("faltam 7 dias"))
        assertEquals("último dia", MedText.period(amox.copy(endsAt = Doses.endOf(today, zone)), now, zone))
        assertNull(MedText.period(amox.copy(endsAt = null), now, zone))
        assertEquals("500 mg", MedText.dose(amox.copy(dose = null)))
    }

    /** Rodada 1: "tomei agora" of one written down this minute is the time of now, shown ticked today. */
    @Test
    fun takenNowIsTheTimeOfNowEvenJustWrittenDown() = runBlocking {
        val tenAndABit = at(today, 10) + 37_000
        val keeper = Keeper(db, zone = { zone }, clock = { tenAndABit })
        val r = keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"Amoxicilina","times":["10:00","18:00","02:00"],"every_hours":8,"taken":true}]}""")!!, null)
        val m = db.meds().get(r.meds.single())!!
        assertEquals(listOf(MedTaken(m.id, at(today, 10), tenAndABit)), db.meds().takenBetween(at(today, 0), at(today, 23, 59)))
        assertTrue(Doses.day(listOf(m), today, zone).any { it.at == at(today, 10) })
        // Taken a bit early for the next one: that next one.
        Keeper(db, zone = { zone }, clock = { at(today, 17, 40) }).apply(Brain.parse("""{"reply":"ok","meds":[{"id":"remédio#${m.id}","taken":true}]}""")!!, null)
        assertTrue(db.meds().takenBetween(at(today, 18), at(today, 18)).isNotEmpty())
    }

    /** Rodada 1: the same medicine said as new again (same name, same person) changes the one there is. */
    @Test
    fun theSameMedicineSaidAgainIsTheOneThereIs() = runBlocking {
        val keeper = Keeper(db, zone = { zone }, clock = { now })
        keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"Amoxicilina","person":"Cooper","times":["08:00","20:00"]}]}""")!!, null)
        val again = keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"amoxicilina","person":"cooper","times":["07:00","19:00"]}]}""")!!, null)
        val all = db.meds().all()
        assertEquals(1, all.size)
        assertEquals(listOf(all.single().id), again.meds)
        assertEquals(listOf("07:00", "19:00"), Doses.times(all.single()).map(Doses::hhmm))
        // For someone else, it is another one.
        keeper.apply(Brain.parse("""{"reply":"ok","meds":[{"name":"Amoxicilina","person":"Aurora","times":["09:00"]}]}""")!!, null)
        assertEquals(2, db.meds().all().size)
    }

    /** Rodada 1: what the model writes a bit off (the id as in the context, "8h", days by name) is still read. */
    @Test
    fun theModelsWordsAroundNumbersAreRead() {
        val plan = Brain.parse("""{"reply":"ok","meds":[{"id":"remédio#3","every_hours":"8h","days":["seg","quarta","6"]},{"id":"#4","days":"dom"}]}""")!!
        assertEquals(3L, plan.meds[0].id)
        assertEquals(8, plan.meds[0].everyHours)
        assertEquals(listOf(1, 3, 6), plan.meds[0].days)
        assertEquals(4L, plan.meds[1].id)
        assertEquals(listOf(7), plan.meds[1].days)
        assertNull(Brain.parse("""{"reply":"ok","meds":[{"name":"X"}]}""")!!.meds[0].days)
    }

    /** Rodada 1: "1.000 mg" is a thousand; "0.125 mg" has decimals. */
    @Test
    fun thousandsAndDecimalsInTheStrength() {
        assertEquals("500 mg", Doses.amount("1.000 mg", "½ comprimido"))
        assertEquals("0.25 mg", Doses.amount("0.125 mg", "2 comprimidos"))
        assertEquals("750 mg", Doses.amount("1.500 mg", "meio"))
    }

    /** Rodada 1: the model sees the ones in use first, and none still without a name. */
    @Test
    fun theModelSeesTheOnesInUseFirst() {
        val old = med("Antigo", listOf("08:00"), start = at(today.minusDays(40), 0), end = at(today.minusDays(30), 23, 59), id = 1)
        val now1 = med("Atual", listOf("08:00"), id = 2)
        val blank = med("", listOf("09:00"), id = 3)
        val text = Brain.context("oi", now, zone, emptyList(), emptyList(), meds = listOf(old, now1, blank), taken = emptyList())
        assertTrue(text, text.indexOf("remédio#2") in 0 until text.indexOf("remédio#1"))
        assertFalse(text, text.contains("remédio#3"))
    }

    @Test
    fun theTreatmentGoesAlongItsDays() {
        val m = med("A", listOf("08:00"), start = at(today.minusDays(3), 9), end = Doses.endOf(today.plusDays(6), zone))
        assertEquals(7, Doses.daysLeft(m, now, zone))
        assertEquals(0.3f, Doses.progress(m, now, zone)!!, 0.001f)
        assertNull(Doses.progress(m.copy(endsAt = null), now, zone))
    }
}
