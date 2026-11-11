package com.villamediana.fio.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PhotoRecallTest {
    private val photos = listOf(
        Photo(1, "/p/1.jpg", 1_000, "Pôr do sol na praia de Itacaré, céu laranja", null),
        Photo(2, "/p/2.jpg", 2_000, "Alma no aeroporto de Guarulhos com a mala azul", null),
        Photo(3, "/p/3.jpg", 3_000, null, "RECIBO Restaurante Sol R$ 32,00"),
        Photo(4, "/p/4.jpg", 4_000, "Outra praia, mar calmo", null),
    )

    @Test fun findsByWhatItShowsAndWhatIsWrittenInIt() {
        val r = PhotoRecall(photos)
        assertEquals(listOf(2L), r.search("foto da Alma no aeroporto").map { it.id })
        // Accents and the start of a word are enough.
        assertEquals(listOf(2L), r.search("aeroport").map { it.id })
        assertEquals(listOf(3L), r.search("recibo do restaurante").map { it.id })
        // Two matches: the rarer word decides, then the newest.
        assertEquals(listOf(1L, 4L), r.search("praia itacare").map { it.id })
        assertEquals(listOf(4L, 1L), r.search("praia").map { it.id })
        assertTrue(r.search("a de o").isEmpty())
    }

    @Test fun aStretchOfDays() {
        assertEquals(listOf(3L, 2L), PhotoRecall(photos).during(1_500L..3_500L).map { it.id })
    }

    @Test fun theModelSeesAPhotoAsOneLineItCanPointTo() {
        val line = Brain.photoLine(Photo(7, "/p/7.jpg", 1_762_862_400_000, "Alma no aeroporto", "GRU T2"), ZoneId.of("America/Sao_Paulo"))
        assertTrue(line, line.startsWith("foto#7 2025-11-11 09:00 — Alma no aeroporto"))
        assertTrue(line, line.endsWith(" · texto: GRU T2"))
    }
}
