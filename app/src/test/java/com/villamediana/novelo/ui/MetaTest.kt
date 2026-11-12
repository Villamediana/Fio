package com.villamediana.novelo.ui

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.ui.components.meta
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The quiet line under a card's title says each day once. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class MetaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 11)
    private val now = today.atTime(7, 40).atZone(zone).toInstant().toEpochMilli()
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun task(due: Long?, hasTime: Boolean = false, remind: Long? = null, status: String = "doing") =
        Entry(kind = "task", title = "Recibir a Alma en el aeropuerto", status = status, dueAt = due, dueHasTime = hasTime, remindAt = remind, createdAt = at(today, 5, 54), updatedAt = now)

    private fun texts(e: Entry, named: Boolean = false, inToday: Boolean = false) =
        meta(e, named = named, withStatus = true, inToday = inToday, zone = zone, now = now).map { it.first }

    @Test
    fun aReminderOnTheDueDaySaysOnlyItsTime() {
        assertEquals(listOf("Fazendo", "hoje", "lembrete 08:30"), texts(task(at(today, 0), remind = at(today, 8, 30))))
        assertEquals(listOf("Fazendo", "amanhã", "lembrete 08:30"), texts(task(at(today.plusDays(1), 0), remind = at(today.plusDays(1), 8, 30))))
        // Another day than the due: both days said.
        assertEquals(listOf("Fazendo", "sexta-feira", "lembrete amanhã · 09:00"), texts(task(at(today.plusDays(2), 0), remind = at(today.plusDays(1), 9))))
    }

    @Test
    fun theTodayTabLeavesHojeOut() {
        assertEquals(listOf("Fazendo", "lembrete 08:30"), texts(task(at(today, 0), remind = at(today, 8, 30)), inToday = true))
        assertEquals(listOf("Fazendo", "15:00"), texts(task(at(today, 15), hasTime = true), inToday = true))
        // Late stays said, in the accent.
        val late = meta(task(at(today.minusDays(1), 0)), named = false, withStatus = true, inToday = true, zone = zone, now = now)
        assertEquals(listOf("Fazendo" to false, "ontem" to true), late)
        val diary = Entry(kind = "diary", title = "Una mañana interesante", createdAt = at(today, 7, 38), updatedAt = now)
        assertEquals(listOf("Diário"), texts(diary, named = true, inToday = true))
        assertEquals(listOf("Diário · hoje"), texts(diary, named = true))
    }
}
