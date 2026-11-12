package com.villamediana.fio.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningTest {
    @Test
    fun omnisListBecomesWordsToSearchFor() {
        val words = Meaning.parse("""["Reparador", "conserto", "técnico de manutenção", "teléfono", "número", "fontanero"]""", "hay algún número de reparos")
        assertEquals(listOf("reparador", "conserto", "tecnico", "manutencao", "telefono", "fontanero"), words)
    }

    @Test
    fun theSearchsOwnWordsShortOnesAndRepeatsAreLeftOut() {
        val words = Meaning.parse("""["de", "senha", "Senha", "wifi", "contraseña", "rede", "5g"]""", "senha do wifi")
        assertEquals(listOf("contrasena", "rede", "5g"), words)
    }

    @Test
    fun anythingButAListGivesNothing() {
        assertTrue(Meaning.parse("", "x").isEmpty())
        assertTrue(Meaning.parse("não sei", "x").isEmpty())
        assertTrue(Meaning.parse("[\"aberto", "x").isEmpty())
    }

    @Test
    fun theWordsFindWhatHerWordsDont() {
        // The note says it another way: her words find nothing, the meaning's words find it.
        val note = com.villamediana.fio.data.Entry(id = 1, kind = "note", title = "Telefone do encanador", body = "Seu Jorge, 11 98888-7777, conserta vazamento", createdAt = 0, updatedAt = 0)
        val recall = Recall(listOf(note), emptyList(), emptyList(), emptyList())
        assertTrue(recall.search("hay algún número de reparos").isEmpty())
        val more = Meaning.parse("""["reparador", "encanador", "conserto", "telefone"]""", "hay algún número de reparos")
        assertEquals(1L, recall.search(more.joinToString(" ")).single().entry.id)
    }
}
