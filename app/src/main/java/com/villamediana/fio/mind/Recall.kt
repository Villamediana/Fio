package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.EntryTopic
import com.villamediana.fio.data.Link
import com.villamediana.fio.data.Topic
import java.text.Normalizer
import kotlin.math.ln

/** Lowercase, no accents, words only: "Reunião às 3h" → ["reuniao", "as", "3h"]. */
object Words {
    private val MARKS = Regex("\\p{M}+")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

    fun of(text: String): List<String> = SPLIT.split(fold(text)).filter { it.isNotEmpty() }
}

/**
 * Finding what was said before: words that match (rarer words weigh more), the topics the hits
 * share (one hop along the graph) and the links the model drew between entries.
 */
class Recall(
    entries: List<Entry>,
    topics: List<Topic>,
    ties: List<EntryTopic>,
    links: List<Link>,
) {
    private val byId = entries.associateBy { it.id }
    private val topicName = topics.associate { it.id to it.name }
    private val topicsOf: Map<Long, List<Long>> = ties.groupBy({ it.entryId }, { it.topicId })
    private val entriesOf: Map<Long, List<Long>> = ties.groupBy({ it.topicId }, { it.entryId })
    private val linked: Map<Long, List<Long>> = buildMap<Long, MutableList<Long>> {
        links.forEach { l ->
            getOrPut(l.a) { mutableListOf() }.add(l.b)
            getOrPut(l.b) { mutableListOf() }.add(l.a)
        }
    }

    /** Each entry's words (title, body, tags, topics), folded. */
    private val words: Map<Long, Set<String>> = entries.associate { e ->
        val topicText = topicsOf[e.id].orEmpty().mapNotNull { topicName[it] }.joinToString(" ")
        e.id to Words.of("${e.title} ${e.body} ${e.tags} $topicText").toSet()
    }

    private val df: Map<String, Int> = buildMap {
        words.values.forEach { set -> set.forEach { w -> put(w, (get(w) ?: 0) + 1) } }
    }

    data class Hit(val entry: Entry, val score: Double, val matched: Set<String>)

    /** The query's own words that can find something (short words are noise). */
    fun terms(query: String): List<String> = Words.of(query).filter { it.length >= 3 || it.any(Char::isDigit) }.distinct()

    fun search(query: String, limit: Int = 12, now: Long = System.currentTimeMillis()): List<Hit> {
        val terms = terms(query)
        if (terms.isEmpty() || byId.isEmpty()) return emptyList()
        val n = byId.size.toDouble()
        val direct = HashMap<Long, Pair<Double, MutableSet<String>>>()
        for ((id, set) in words) {
            var score = 0.0
            val matched = mutableSetOf<String>()
            for (t in terms) {
                val exact = t in set
                val prefix = !exact && t.length >= 4 && set.any { it.length >= 4 && (it.startsWith(t) || t.startsWith(it)) }
                if (exact || prefix) {
                    val idf = ln(1.0 + n / ((df[t] ?: 1).toDouble()))
                    score += idf * (if (exact) 1.0 else 0.6)
                    matched += t
                }
            }
            if (score > 0) direct[id] = score to matched
        }
        if (direct.isEmpty()) return emptyList()

        // One hop: entries that share a topic with a hit, or that the model linked to it.
        val total = HashMap<Long, Double>()
        direct.forEach { (id, v) -> total[id] = (total[id] ?: 0.0) + v.first }
        direct.entries.sortedByDescending { it.value.first }.take(8).forEach { (id, v) ->
            val near = topicsOf[id].orEmpty().flatMap { entriesOf[it].orEmpty() } + linked[id].orEmpty()
            near.filter { it != id }.forEach { other -> total[other] = (total[other] ?: 0.0) + v.first * 0.35 }
        }
        return total.entries
            .mapNotNull { (id, s) -> byId[id]?.let { Hit(it, s + recency(it, now), direct[id]?.second.orEmpty()) } }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Entries tied to [id] through a shared topic or a link, closest first. */
    fun related(id: Long, limit: Int = 8): List<Entry> {
        val counts = HashMap<Long, Int>()
        topicsOf[id].orEmpty().forEach { t -> entriesOf[t].orEmpty().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        linked[id].orEmpty().forEach { counts[it] = (counts[it] ?: 0) + 2 }
        counts.remove(id)
        return counts.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(limit)
    }

    /** A small nudge toward what is recent (never enough to beat a real match). */
    private fun recency(e: Entry, now: Long): Double {
        val days = ((now - e.createdAt).coerceAtLeast(0) / 86_400_000.0)
        return 0.3 / (1.0 + days / 7.0)
    }
}

/** The bit of text around the first match, for the list ("…falei com a Ana sobre o contrato…"). */
object Snippet {
    fun around(text: String, terms: Collection<String>, radius: Int = 70): String {
        val flat = text.replace('\n', ' ').trim()
        if (terms.isEmpty() || flat.length <= radius * 2) return flat
        val folded = Words.fold(flat)
        val at = terms.mapNotNull { t -> folded.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: return flat.take(radius * 2) + "…"
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + radius).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    /** Character ranges of [text] that match [terms] (folded), for highlighting. */
    fun ranges(text: String, terms: Collection<String>): List<IntRange> {
        if (terms.isEmpty()) return emptyList()
        val folded = Words.fold(text)
        // NFD folding can change length (rare: only when a letter decomposes into more than one mark).
        if (folded.length != text.length) return emptyList()
        val out = mutableListOf<IntRange>()
        for (t in terms) {
            var i = folded.indexOf(t)
            while (i >= 0) {
                val startsWord = i == 0 || !folded[i - 1].isLetterOrDigit()
                if (startsWord) {
                    var end = i + t.length
                    while (end < folded.length && folded[end].isLetterOrDigit()) end++
                    out += i until end
                }
                i = folded.indexOf(t, i + t.length)
            }
        }
        return out.sortedBy { it.first }.fold(mutableListOf()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && r.first <= last.last + 1) acc[acc.size - 1] = last.first..maxOf(last.last, r.last) else acc += r
            acc
        }
    }
}
