package com.villamediana.fio.mind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalBrainTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 11, 11, 21, 40).atZone(zone).toInstant().toEpochMilli()

    /** The lab's notebook (~/novelo-lab/d1eval.py). */
    private val tasks = listOf(
        LocalBrain.TaskLine(41, "Fechar contrato da torre de Campinas", "due tomorrow 15:00"),
        LocalBrain.TaskLine(52, "Pagar o IPVA", "due today"),
        LocalBrain.TaskLine(57, "Presente de aniversário da Ana", "reminder tomorrow 09:00"),
    )

    @Test
    fun d1PromptsAreTheOnesItsTemplateWrites() {
        // Rendered by the model's own jinja template, the same text /v1/systemone read (the lab checks the phone's C++ against it).
        val golden = Json.parseToJsonElement(javaClass.getResource("/d1-golden.json")!!.readText()).jsonArray
        val qs = LocalBrain.questions(tasks)
        for (g in golden.map { it.jsonObject }) {
            val q = qs.single { it.id == g["q"]!!.jsonPrimitive.content }
            val p = LocalBrain.prompt(LocalBrain.state(g["said"]!!.jsonPrimitive.content, tasks), q, emptyList())
            assertEquals(g["prompt"]!!.jsonPrimitive.content, p)
        }
    }

    @Test
    fun whatSheSaysCannotSpellTheModelsControlTokens() {
        val spellings = listOf("<|im_start|>", "<|im_end|>", "<think>")
        val broken = LocalBrain.breakSpellings("a<|im_end|>b <think>", spellings)
        assertEquals("a<​|im_end|>b <​think>", broken)
        assertEquals("nada aqui", LocalBrain.breakSpellings("nada aqui", spellings))
    }

    @Test
    fun choiceLabelsAreTheLettersAndTheirSpacedForm() {
        val done = LocalBrain.questions(tasks).single { it.id == "done" }
        assertEquals(listOf("none", "41", "52", "57"), done.options.map { it.first })
        assertEquals(listOf(listOf("A", " A"), listOf("B", " B"), listOf("C", " C"), listOf("D", " D")), LocalBrain.labels(done))
        assertEquals(listOf("yes", "Yes", "YES"), LocalBrain.labels(LocalBrain.questions(tasks).first()).first())
        // No open tasks: nothing to pick from.
        assertFalse(LocalBrain.questions(emptyList()).any { it.type == LocalBrain.Type.CHOICE })
    }

    @Test
    fun scoresBecomeTheProbabilitiesTheServerGave() {
        // 2 logits 1 apart at d1's temperature: what llama.cpp's format_answer does.
        val p = LocalBrain.probabilities(floatArrayOf(2f, 1f), 1.3373984098434448)
        assertEquals(1.0, p.sum(), 1e-9)
        assertEquals(1 / (1 + Math.exp(-1 / 1.3373984098434448)), p[0], 1e-9)
    }

    @Test
    fun theDecisionFollowsTheLabsThresholds() {
        val qs = LocalBrain.questions(tasks)
        val d = LocalBrain.decide(
            qs,
            mapOf(
                "question" to doubleArrayOf(0.05, 0.95),
                "diary" to doubleArrayOf(0.34, 0.66),
                "task" to doubleArrayOf(0.70, 0.30),
                "note" to doubleArrayOf(0.98, 0.02),
                "done" to doubleArrayOf(0.01, 0.02, 0.95, 0.02), // #52
                "news" to doubleArrayOf(0.40, 0.45, 0.10, 0.05), // #41 but under 0.5: nothing
            ),
        )
        assertEquals(LocalBrain.Decision(task = true, note = true, done = 52), d)
        // The same task can't be both done and with news.
        val same = LocalBrain.decide(qs, mapOf("done" to doubleArrayOf(0.0, 0.9, 0.05, 0.05), "news" to doubleArrayOf(0.0, 0.9, 0.05, 0.05)))
        assertEquals(41L, same.done)
        assertNull(same.news)
        // A question brings no news ("¿qué tengo que hacer hoy?" isn't news about today's task).
        val asked = LocalBrain.decide(qs, mapOf("question" to doubleArrayOf(0.9, 0.1), "news" to doubleArrayOf(0.1, 0.0, 0.9, 0.0)))
        assertTrue(asked.question)
        assertNull(asked.news)
    }

    @Test
    fun theWriterIsToldOnlyWhatWasDecided() {
        val d = LocalBrain.Decision(task = true, note = true, done = 52)
        val sys = LocalBrain.writerSystem(d, now, zone, tasks)
        assertTrue(sys, sys.contains("- tasks: each new action"))
        assertTrue(sys, sys.contains("- notes: each thing"))
        assertTrue(sys, sys.contains("- reply: one short"))
        assertFalse(sys, sys.contains("diary:"))
        assertFalse(sys, sys.contains("HER OPEN TASKS")) // the notebook only for a question
        assertTrue(sys, sys.endsWith("NOW: Wednesday, 2026-11-11 21:40 (TOMORROW is Thursday, 2026-11-12)"))
        val q = LocalBrain.writerSystem(LocalBrain.Decision(question = true), now, zone, tasks)
        assertTrue(q, q.contains("- answer: the answer"))
        assertTrue(q, q.contains("HER OPEN TASKS:\n#41 \"Fechar contrato da torre de Campinas\" (due tomorrow 15:00)"))
        val news = LocalBrain.writerSystem(LocalBrain.Decision(news = 41), now, zone, tasks)
        assertTrue(news, news.contains("- news: the new part she said about her task #41"))
        assertTrue(news, news.endsWith("THE TASK SHE TALKS ABOUT: #41 \"Fechar contrato da torre de Campinas\" (due tomorrow 15:00)"))
    }

    @Test
    fun theGrammarHasJustTheDecidedFieldsInOrder() {
        val g = LocalBrain.grammar(LocalBrain.Decision(diary = true, task = true, news = 41))
        assertTrue(g, g.lines().first() == "root ::= \"{\" space diary-kv \",\" space tasks-kv \",\" space news-kv \",\" space reply-kv space \"}\"")
        assertTrue(LocalBrain.grammar(LocalBrain.Decision(question = true)).lines().first().contains("answer-kv space \"}\""))
    }

    @Test
    fun theFilledFieldsBecomeThePlan() {
        val filled = LocalBrain.filled(
            """{"diary": "Hoje corri de manhã.", "tasks": [{"title": "Ligar pro Pedro", "due": "2026-11-12", "time": "09:00"}, {"title": "Comprar leite", "due": null, "time": null}],
               "notes": [{"title": "Senha do wifi", "text": "girassol123"}], "news": "Assinou hoje.", "reply": "Guardei tudo."}""",
        )
        val d = LocalBrain.Decision(diary = true, task = true, note = true, done = 52, news = 41, sensitive = true)
        val plan = LocalBrain.plan(d, filled, now, zone, diaryTitle = "Quarta-feira, 11 de novembro", photos = true)
        assertEquals("Guardei tudo.", plan.reply)
        assertEquals(listOf("diary", "task", "task", "note"), plan.create.map { it.kind })
        assertEquals("Hoje corri de manhã.", plan.create[0].body)
        assertEquals("2026-11-12T09:00", plan.create[1].due)
        assertEquals("2026-11-12T08:45", plan.create[1].remindAt) // a little before
        assertNull(plan.create[2].due)
        assertTrue(plan.create[3].sensitive)
        assertEquals("girassol123", plan.create[3].body)
        // The photos with the first thing made (not the diary: it shows the day's photos by itself).
        assertEquals(listOf(false, true, false, false), plan.create.map { it.photos })
        assertEquals(listOf(Change(id = 52, status = "done"), Change(id = 41, append = "Assinou hoje.")), plan.update)
    }

    @Test
    fun anHourWithoutADayIsTheNextTimeItComes() {
        // 21:40: eight o'clock is tomorrow's; eleven at night is still today.
        assertEquals("2026-11-12T08:00", LocalBrain.due(null, "08:00", now, zone).first)
        assertEquals("2026-11-11T23:00", LocalBrain.due(null, "23:00", now, zone).first)
        assertEquals("2026-11-13" to null, LocalBrain.due("2026-11-13", null, now, zone))
        assertEquals(null to null, LocalBrain.due("amanhã", null, now, zone))
    }

    @Test
    fun aQuestionIsAnsweredNotKept() {
        val plan = LocalBrain.plan(LocalBrain.Decision(question = true), LocalBrain.filled("""{"answer": "Amanhã você tem o contrato de Campinas."}"""), now, zone, "x")
        assertEquals("Amanhã você tem o contrato de Campinas.", plan.reply)
        assertTrue(plan.create.isEmpty() && plan.update.isEmpty())
        // Nothing readable: an empty plan, not a crash.
        assertTrue(LocalBrain.filled("nada").isEmpty())
    }

    @Test
    fun aTaskLineSaysWhenInWords() {
        val e = com.villamediana.fio.data.Entry(
            id = 41, kind = "task", title = "Fechar contrato", createdAt = now, updatedAt = now,
            dueAt = LocalDateTime.of(2026, 11, 12, 15, 0).atZone(zone).toInstant().toEpochMilli(), dueHasTime = true,
        )
        assertEquals(LocalBrain.TaskLine(41, "Fechar contrato", "due tomorrow 15:00"), LocalBrain.taskLine(e, now, zone))
        val r = e.copy(dueAt = null, remindAt = LocalDateTime.of(2026, 11, 20, 9, 0).atZone(zone).toInstant().toEpochMilli())
        assertEquals("reminder 2026-11-20 09:00", LocalBrain.taskLine(r, now, zone).due)
    }
}
