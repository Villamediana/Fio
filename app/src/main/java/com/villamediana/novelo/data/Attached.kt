package com.villamediana.novelo.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Photos and files sent together with something said or written: each one stays with the entries
 * the model said it belongs to (by its number; "true" was all of them) — what she says and what she
 * sends can be about different things.
 */
object Attached {
    private val json = Json { ignoreUnknownKeys = true }

    private fun outcomes(c: Capture): List<Outcome> =
        runCatching { json.decodeFromString(ListSerializer(Outcome.serializer()), c.outcomes) }.getOrDefault(emptyList())

    /** The entries that took [part], a photo sent with [owner] (what she said). */
    fun holders(owner: Capture, part: Capture): Set<Long> = outcomes(owner).filter { it.holdsPhoto(part.id) }.map { it.entryId }.toSet()

    /**
     * The entries a photo sent alone turned into or changed (a receipt that became a note, a flyer
     * that became a task): the photo is theirs too.
     */
    fun madeFrom(photo: Capture): Set<Long> =
        if (photo.source != "photo" || photo.partOf != null) emptySet()
        else outcomes(photo).filter { it.action != Outcome.DELETED }.map { it.entryId }.toSet()

    /** The entries that hold [photo]: what it was sent with gave it to them, or it made them (sent alone). */
    private fun homesOf(photo: Capture, byId: Map<Long, Capture>): Set<Long> =
        if (photo.partOf != null) byId[photo.partOf]?.let { holders(it, photo) }.orEmpty() else madeFrom(photo)

    /** Among [captures] (the photos and what they went with), the photos [entryId] holds, in order. */
    fun photosOf(entryId: Long, captures: List<Capture>): List<Capture> {
        val byId = captures.associateBy { it.id }
        return captures
            .filter { it.imagePath != null && entryId in homesOf(it, byId) }
            .sortedWith(compareBy<Capture> { it.createdAt }.thenBy { it.id })
    }

    /** The entries that took [part], a file sent alone ([owner] is itself) or with what [owner] said. */
    fun fileHolders(owner: Capture, part: Capture): Set<Long> = outcomes(owner).filter { it.holdsFile(part.id) }.map { it.entryId }.toSet()

    /**
     * Where each file lives (file capture id → entry id): the first item the model put it in that
     * is still there; a file with none (or whose item is gone) is loose — it lives in its day's diary.
     */
    fun fileHomes(captures: List<Capture>, entries: Set<Long>): Map<Long, Long?> {
        val byId = captures.associateBy { it.id }
        return captures.filter { it.filePath != null }.associate { f ->
            val from = if (f.partOf != null) byId[f.partOf] else f
            f.id to from?.let { fileHolders(it, f) }?.firstOrNull { it in entries }
        }
    }

    /**
     * The photos that live inside an item and not loose: those that belong to a note, or to anything
     * sensitive. They stay out of the gallery, the day's diary and the photo search (a note's paper,
     * a document, a receipt is the note's; a sensitive one is shown only in its item, hidden).
     */
    fun keptInside(captures: List<Capture>, entries: List<Entry>): Set<Long> {
        val kept = entries.filter { it.kindEnum == Kind.NOTE || it.sensitive }.map { it.id }.toSet()
        if (kept.isEmpty()) return emptySet()
        val byId = captures.associateBy { it.id }
        return captures.filter { c -> c.source == "photo" && c.imagePath != null && homesOf(c, byId).any { it in kept } }.map { it.id }.toSet()
    }
}
