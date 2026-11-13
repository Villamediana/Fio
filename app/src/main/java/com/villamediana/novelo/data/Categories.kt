package com.villamediana.novelo.data

import java.text.Normalizer

/**
 * The tasks' categories: names, kept on each task (no list apart: one goes away by itself when no
 * task has it). "trabalho" and "Trabalho" are one; a name is short and starts with a capital.
 */
object Categories {
    const val MAX = 24

    /** [name] as a category: trimmed, one space between words, a capital first; null when empty. */
    fun clean(name: String?): String? {
        val n = name?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX)?.trim().orEmpty()
        if (n.isEmpty()) return null
        return n.replaceFirstChar { it.titlecase() }
    }

    /** How a name is compared: no case, no accents. */
    fun fold(name: String): String =
        Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** [name] as one of [existing] when it is the same one written another way; otherwise [name] cleaned (a new one). */
    fun pick(name: String?, existing: Collection<String>): String? {
        val n = clean(name) ?: return null
        val f = fold(n)
        return existing.firstOrNull { fold(it) == f } ?: n
    }

    /** The categories in use among [tasks], the ones with the most open tasks first, then by name. */
    fun inUse(tasks: List<Entry>): List<String> =
        tasks.filter { it.kindEnum == Kind.TASK && it.category != null }
            .groupBy { it.category!! }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Entry>>> { (_, l) -> l.count { it.statusEnum != Status.DONE } }.thenBy { fold(it.key) })
            .map { it.key }
}
