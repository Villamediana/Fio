package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.EntryTopic
import com.villamediana.fio.data.Link
import com.villamediana.fio.data.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallTest {
    private val now = 1_800_000_000_000L
    private fun e(id: Long, title: String, body: String = "", kind: String = "note", daysAgo: Int = 0) =
        Entry(id = id, kind = kind, title = title, body = body, createdAt = now - daysAgo * 86_400_000L, updatedAt = now)

    @Test
    fun findsWithoutAccentsAndCase() {
        val r = Recall(listOf(e(1, "Reunião com a Ana", "falamos do orçamento"), e(2, "Comprar pão")), emptyList(), emptyList(), emptyList())
        val hits = r.search("reuniao ORCAMENTO", now = now)
        assertEquals(1L, hits.first().entry.id)
        assertTrue("orcamento" in hits.first().matched)
    }

    @Test
    fun rareWordsWeighMore() {
        val list = listOf(
            e(1, "senha do wifi de casa", "minha casa"),
            e(2, "casa da praia"),
            e(3, "casa nova"),
        )
        val hits = Recall(list, emptyList(), emptyList(), emptyList()).search("senha casa", now = now)
        assertEquals(1L, hits.first().entry.id)
    }

    @Test
    fun topicsAndLinksBringNeighbours() {
        val list = listOf(e(1, "Contrato com a Vivo"), e(2, "Ligar pro Pedro", kind = "task"), e(3, "Almoço"), e(4, "Revisar planilha"))
        val topics = listOf(Topic(10, "Pedro", "pedro", "person"))
        val ties = listOf(EntryTopic(1, 10), EntryTopic(2, 10))
        val links = listOf(Link(1, 4))
        val r = Recall(list, topics, ties, links)
        val ids = r.search("contrato", now = now).map { it.entry.id }
        assertEquals(1L, ids.first())
        assertTrue(2L in ids) // same person
        assertTrue(4L in ids) // linked by the model
        assertTrue(3L !in ids)
        assertEquals(listOf(4L, 2L), r.related(1).map { it.id })
    }

    @Test
    fun topicNamesAreSearchable() {
        val r = Recall(listOf(e(1, "Ligar amanhã")), listOf(Topic(5, "Dra. Marta", "dra marta", "person")), listOf(EntryTopic(1, 5)), emptyList())
        assertEquals(1L, r.search("marta", now = now).single().entry.id)
    }

    @Test
    fun prefixesMatchLongWords() {
        val r = Recall(listOf(e(1, "Aniversário do João", "comprar presente")), emptyList(), emptyList(), emptyList())
        assertEquals(1L, r.search("presentes", now = now).single().entry.id)
    }

    @Test
    fun snippetAndHighlightRanges() {
        val text = "Hoje falei com a Ana sobre o orçamento do projeto e ficou de mandar amanhã cedo, sem falta, pra fechar."
        val s = Snippet.around(text, listOf("orcamento"), radius = 20)
        assertTrue(s, s.contains("orçamento"))
        assertTrue(s.startsWith("…"))
        val r = Snippet.ranges("Orçamento e orçamentos", listOf("orcamento"))
        assertEquals(listOf(0..8, 12..21), r)
    }
}
