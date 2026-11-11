package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BrainTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun parsesAPlanInsideFencesAndThinking() {
        val raw = """<think>vou ver</think>
```json
{"reply":"Guardei duas coisas.","create":[{"kind":"task","title":"Ligar pro dentista","due":"2026-11-12T09:00","steps":["achar o número"],"entities":[{"name":"Dentista","type":"person"}]},{"kind":"diary","title":"Dia corrido","body":"muito trabalho"},{"kind":"poema","title":"x"}],"update":[{"id":7,"status":"done","progress":"terminei"}],"search":[],"show":[]}
```"""
        val plan = Brain.parse(raw)!!
        assertEquals("Guardei duas coisas.", plan.reply)
        assertEquals(listOf("task", "diary"), plan.create.map { it.kind }) // unknown kinds dropped
        assertEquals("2026-11-12T09:00", plan.create[0].due)
        assertEquals(7L, plan.update.single().id)
        assertEquals("done", plan.update.single().status)
        assertFalse(plan.isLookupOnly)
    }

    @Test
    fun nullsAndMissingFieldsAreFine() {
        val plan = Brain.parse("""{"reply":"ok","create":[{"kind":"note","title":"Senha do wifi","body":"abc123","due":null,"remind_at":null,"sensitive":true}]}""")!!
        assertTrue(plan.create.single().sensitive)
        assertNull(plan.create.single().due)
        assertNull(Brain.parse("sem json aqui"))
    }

    @Test
    fun lookupOnly() {
        val plan = Brain.parse("""{"reply":"","create":[],"update":[],"search":["senha","banco"],"show":[]}""")!!
        assertTrue(plan.isLookupOnly)
    }

    @Test
    fun times() {
        val (ms, hasTime) = Brain.time("2026-11-12T15:30", zone)!!
        assertTrue(hasTime)
        assertEquals(LocalDateTime.of(2026, 11, 12, 15, 30), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone))
        val (d, t) = Brain.time("2026-11-12", zone)!!
        assertFalse(t)
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 0), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(d), zone))
        assertTrue(Brain.time("2026-11-12 15:30:00", zone)!!.second)
        assertNull(Brain.time("null", zone))
        assertNull(Brain.time("amanhã", zone))
        // A bare day reminds at 9:00.
        val r = Brain.remindTime("2026-11-12", zone)!!
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r), zone))
    }

    @Test
    fun contextNamesItemsById() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val task = Entry(id = 12, kind = "task", title = "Contrato da torre", status = "doing", steps = Steps.write(listOf(Step("orçamento", true), Step("assinar"))), createdAt = now, updatedAt = now)
        val note = Entry(id = 40, kind = "note", title = "Senha do wifi", body = "abc", sensitive = true, createdAt = now, updatedAt = now)
        val ctx = Brain.context("o que eu tenho pra hoje?", now, zone, listOf(task), listOf(note))
        assertTrue(ctx, ctx.contains("AGORA: quarta-feira, 2026-11-11 10:00"))
        assertTrue(ctx.contains("#12 tarefa[doing]"))
        assertTrue(ctx.contains("[x] orçamento [ ] assinar"))
        assertTrue(ctx.contains("#40 nota"))
        assertTrue(ctx.contains("\"o que eu tenho pra hoje?\""))
    }

    @Test
    fun chatmlIsLfmFormat() {
        val p = Brain.chatml("S", "U")
        assertEquals("<|startoftext|><|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n", p)
    }

    @Test
    fun stepsMatchLoosely() {
        assertTrue(Steps.same("Mandar o orçamento", "mandar o orcamento"))
        assertTrue(Steps.same("orçamento", "pedir orçamento pro fornecedor"))
        assertFalse(Steps.same("ligar", "pagar"))
    }
}
