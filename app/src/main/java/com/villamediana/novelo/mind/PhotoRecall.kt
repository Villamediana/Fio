package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Capture
import kotlin.math.ln

/** A photo kept: its file, when it was taken, what it shows (the model's words) and the text read in it. */
data class Photo(val id: Long, val path: String, val takenAt: Long, val description: String?, val text: String?, val turn: Int = 0) {
    companion object {
        fun of(c: Capture): Photo? = c.imagePath?.let { Photo(c.id, it, c.createdAt, c.description?.takeIf(String::isNotBlank), c.transcript?.takeIf(String::isNotBlank), c.turn) }
    }
}

/**
 * Finding photos by what they show and what is written in them — the words the model used to
 * describe each one (in her language) and the text read on it — matched the way [Recall] matches
 * entries: rarer words weigh more, a word's start finds the rest of it. The newest first on a tie.
 */
class PhotoRecall(photos: List<Photo>) {
    private val all = photos
    private val words: Map<Long, Set<String>> = photos.associate { p -> p.id to Words.of("${p.description.orEmpty()} ${p.text.orEmpty()}").toSet() }
    private val df: Map<String, Int> = buildMap {
        words.values.forEach { set -> set.forEach { w -> put(w, (get(w) ?: 0) + 1) } }
    }

    fun search(query: String, limit: Int = 12): List<Photo> {
        val terms = Words.of(query).filter { it.length >= 3 || it.any(Char::isDigit) }.distinct()
        if (terms.isEmpty() || all.isEmpty()) return emptyList()
        val n = all.size.toDouble()
        return all.mapNotNull { p ->
            val set = words[p.id].orEmpty()
            var score = 0.0
            for (t in terms) {
                val exact = t in set
                val prefix = !exact && t.length >= 4 && set.any { it.length >= 4 && (it.startsWith(t) || t.startsWith(it)) }
                if (exact || prefix) score += ln(1.0 + n / ((df[t] ?: 1).toDouble())) * (if (exact) 1.0 else 0.6)
            }
            if (score > 0) p to score else null
        }
            .sortedWith(compareByDescending<Pair<Photo, Double>> { it.second }.thenByDescending { it.first.takenAt })
            .take(limit)
            .map { it.first }
    }

    /** The photos taken in [days] (first to last ms), the newest first. */
    fun during(days: LongRange, limit: Int = 12): List<Photo> =
        all.filter { it.takenAt in days }.sortedByDescending { it.takenAt }.take(limit)
}
