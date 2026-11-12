package com.villamediana.fio.mind

import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The search by meaning, on the phone: Omni (the Novelo's ears, already here) turns what she
 * searched for into the words her notes may use for it — the same thing said other ways and in
 * the other languages she mixes, and what goes with it — so the word search finds it by meaning
 * too. Offline, nothing more to download, no call to anyone.
 */
class Meaning(private val engine: LocalEngine, private val model: LocalModel) {
    /** The ears are here and can run. */
    val available: Boolean get() = LocalEngine.available && model.ready

    /** What was already asked (folded query → words): typing back, or searching again, is instant. */
    private val kept = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > KEEP
    }

    /** Loads Omni and runs the instructions now (she opened the search), so the first answer comes quicker. */
    suspend fun warm() {
        if (available) runCatching { engine.warm(model.files, HEAD) }
    }

    /**
     * The words her notes may use for [query] (none of the query's own); empty when Omni isn't
     * here. Stopped at once when the search changes (another query, the screen closed).
     */
    suspend fun words(query: String): List<String> {
        val key = Words.of(query).joinToString(" ")
        if (key.isEmpty() || !available) return emptyList()
        synchronized(kept) { kept[key] }?.let { return it }
        val tag = Any()
        val out = coroutineScope {
            val work = async(Dispatchers.Default) {
                engine.generate(model.files, Brain.chatml(ASK, query.trim()), GRAMMAR, maxTokens = MAX_TOKENS, fixed = HEAD, tag = tag)
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                engine.cancel(tag)
                throw e
            }
        }
        val words = parse(LocalEngine.clean(out), query)
        synchronized(kept) { kept[key] = words }
        return words
    }

    companion object {
        private const val KEEP = 50
        private const val MAX_TOKENS = 120
        const val MAX_WORDS = 16

        val ASK: String = """
Ela procura uma coisa nas próprias anotações, que misturam português, espanhol e às vezes inglês, e foram escritas com outras palavras que não as da busca. Escreva as palavras que essas anotações podem ter usado pra isso: a mesma coisa dita de outros jeitos e nas outras línguas, e o que anda junto com ela (quem faz, o lugar, o objeto, o tipo de coisa). Palavras curtas, sem repetir as da busca.
Responda só com uma lista JSON de até 12 palavras.
""".trim()

        /** The start of every ask (the instructions): run once and put back. */
        private val HEAD = Brain.chatmlHead(ASK)

        /** A list of short words, nothing else. */
        val GRAMMAR: String = """
root ::= "[" ws item ( ws "," ws item ){0,15} ws "]"
item ::= "\"" [^"\\\x7F\x00-\x1F]{1,32} "\""
ws   ::= [ \t\n]{0,4}
""".trim()

        private val json = Json { isLenient = true }

        /** Omni's list → the words to search for: folded, three letters or more (or a number), none of [query]'s, no repeats. */
        fun parse(answer: String, query: String): List<String> {
            val start = answer.indexOf('[')
            val end = answer.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val items = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray.map { it.jsonPrimitive.content } }.getOrNull() ?: return emptyList()
            val own = Words.of(query).toSet()
            return items.flatMap(Words::of)
                .filter { (it.length >= 3 || it.any(Char::isDigit)) && it !in own }
                .distinct()
                .take(MAX_WORDS)
        }
    }
}
