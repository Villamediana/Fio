package com.villamediana.novelo.ui.library

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.ui.Filter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The diary's card already says its day: only Hoje and Ontem get a label over it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiaryDaysTest {
    @Test
    fun onlyTodayAndYesterdayHaveALabel() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 13)
        val now = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        fun day(back: Long) = today.minusDays(back).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val diary = listOf(0L, 1L, 3L, 50L).mapIndexed { i, back -> Entry(id = i + 1L, kind = "diary", title = "Dia $back", createdAt = day(back), updatedAt = day(back)) }
        val sections = sectionsFor(Filter.DIARY, diary, showDone = false, zone = zone, now = now)
        assertEquals(listOf("Hoje", "Ontem", null, null), sections.map { it.first })
        // Each day still its own group (its own card), the newest first.
        assertEquals(listOf(1L, 2L, 3L, 4L), sections.flatMap { it.second }.map { it.id })
    }
}
