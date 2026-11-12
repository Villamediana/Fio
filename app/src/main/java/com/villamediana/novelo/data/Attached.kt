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

    /**
     * The entries a photo sent alone turned into or changed (a receipt that became a note, a flyer
     * that became a task): the photo is theirs too.
     */
    fun madeFrom(photo: Capture): Set<Long> =
        if (photo.source != "photo" || photo.partOf != null) emptySet()
        else runCatching { json.decodeFromString(ListSerializer(Outcome.serializer()), photo.outcomes) }.getOrDefault(emptyList())
            .filter { it.action != Outcome.DELETED }
            .map { it.entryId }
            .toSet()

    /** Among [captures] (the photos and what they went with), the photos [entryId] holds, in order. */
    fun photosOf(entryId: Long, captures: List<Capture>): List<Capture> {
        val holders = captures.filter { it.source != "photo" || it.partOf == null }.associate { it.id to holders(it) }
        return captures
            .filter { it.imagePath != null && (if (it.partOf != null) holders[it.partOf]?.contains(entryId) == true else entryId in madeFrom(it)) }
            .sortedWith(compareBy<Capture> { it.createdAt }.thenBy { it.id })
    }

    /**
     * The photos that live inside an item and not loose: those that belong to a note, or to anything
     * sensitive. They stay out of the gallery, the day's diary and the photo search (a note's paper,
     * a document, a receipt is the note's; a sensitive one is shown only in its item, hidden).
     */
    fun keptInside(captures: List<Capture>, entries: List<Entry>): Set<Long> {
        val kept = entries.filter { it.kindEnum == Kind.NOTE || it.sensitive }.map { it.id }.toSet()
        if (kept.isEmpty()) return emptySet()
        val holders = captures.filter { it.source != "photo" || it.partOf == null }.associate { it.id to holders(it) }
        return captures.filter { c ->
            c.source == "photo" && c.imagePath != null &&
                (if (c.partOf != null) holders[c.partOf].orEmpty() else madeFrom(c)).any { it in kept }
        }.map { it.id }.toSet()
    }
}
