package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.settings.Lang
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The open tasks with no category yet go into one, by themselves, when the app starts: the model
 * that thinks gets them (a batch at a time) with the categories there are, and uses those —
 * a new one only when none fits. Not ready or offline: nothing happens, the next start tries again.
 */
class Organizer(private val db: NoveloDatabase, private val thinking: Thinker) {
    private val started = AtomicBoolean(false)

    /** Once per start of the app. */
    suspend fun onStart() {
        if (started.compareAndSet(false, true)) runCatching { organize() }.onFailure { if (it is CancellationException) throw it }
    }

    /** The tasks with no category, put in one; how many were. */
    suspend fun organize(): Int {
        val loose = db.entries().uncategorized()
        if (loose.isEmpty()) return 0
        val categories = db.entries().categories().toMutableList()
        var done = 0
        for (batch in loose.chunked(BATCH)) {
            val answer = thinking.answer(ASK.replace("{lang}", Lang.current.label), ask(batch, categories))
            val picked = parse(answer, batch.map { it.id }.toSet())
            for (e in batch) {
                val c = Categories.pick(picked[e.id], categories) ?: continue
                // Moved by her (or by the model) in the meantime: hers stays.
                if (db.entries().get(e.id)?.category != null) continue
                db.entries().setCategory(e.id, c)
                if (c !in categories) categories += c
                done++
            }
        }
        return done
    }

    companion object {
        const val BATCH = 30

        val ASK: String = """
Você organiza as tarefas de uma pessoa em categorias — as áreas da vida dela (por exemplo Trabalho, Casa, Pessoal, Saúde).
Pra cada tarefa, escolha uma das CATEGORIAS QUE JÁ EXISTEM quando alguma servir. Crie outra só quando nenhuma serve, com nome curto (uma ou duas palavras) e amplo, que sirva pra várias tarefas: poucas categorias no total. Escreva os nomes em {lang}.
Responda só com um objeto JSON: o id de cada tarefa e a categoria dela, assim: {"12": "Trabalho", "15": "Casa"}
""".trim()

        /** What the model gets: the categories there are, then each task (its id, title and the start of its text). */
        fun ask(tasks: List<Entry>, categories: List<String>): String = buildString {
            append("CATEGORIAS QUE JÁ EXISTEM: ").append(categories.joinToString(", ").ifEmpty { "nenhuma ainda" }).append("\n\nTAREFAS:\n")
            tasks.forEach { e ->
                append('#').append(e.id).append(" \"").append(e.title.take(90)).append('"')
                if (!e.sensitive && e.body.isNotBlank()) append(" — ").append(e.body.take(120).replace('\n', ' '))
                append('\n')
            }
        }

        private val json = Json { isLenient = true }

        /** The model's answer → task id → category (only the [ids] asked about). */
        fun parse(answer: String, ids: Set<Long>): Map<Long, String> {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyMap()
            val root = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return emptyMap()
            return root.entries.mapNotNull { (k, v) ->
                val id = k.trim().removePrefix("#").toLongOrNull()?.takeIf { it in ids } ?: return@mapNotNull null
                val name = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }.toMap()
        }
    }
}
