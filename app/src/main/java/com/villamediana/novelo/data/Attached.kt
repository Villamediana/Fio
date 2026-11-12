package com.villamediana.novelo.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Photos sent together with something said or written: they stay with the entries the model said
 * they belong to ("photos": true), as many as she sent.
 */
object Attached {
    private val json = Json { ignoreUnknownKeys = true }

    /** The entries that took the photos sent with [c]. */
    fun holders(c: Capture): Set<Long> =
        runCatching { json.decodeFromString(ListSerializer(Outcome.serializer()), c.outcomes) }.getOrDefault(emptyList())
            .filter { it.photos && it.action != Outcome.DELETED }
            .map { it.entryId }
            .toSet()

    /** Among [captures] (the photos and what they went with), the photos [entryId] holds, in order. */
    fun photosOf(entryId: Long, captures: List<Capture>): List<Capture> {
        val holders = captures.filter { it.source != "photo" || it.partOf == null }.associate { it.id to holders(it) }
        return captures
            .filter { it.partOf != null && it.imagePath != null && holders[it.partOf]?.contains(entryId) == true }
            .sortedWith(compareBy<Capture> { it.createdAt }.thenBy { it.id })
    }
}
