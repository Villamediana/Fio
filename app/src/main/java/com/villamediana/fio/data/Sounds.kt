package com.villamediana.fio.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The sounds kept (a melody sung, a sound): which entries each one goes with. */
object Sounds {
    private val json = Json { ignoreUnknownKeys = true }

    /** What its recording made or changed (and the entry it began): there the sound shows. */
    fun entries(c: Capture): Set<Long> {
        val touched = runCatching { json.decodeFromString(ListSerializer(Outcome.serializer()), c.outcomes) }.getOrDefault(emptyList())
            .filter { it.action != Outcome.DELETED }
            .map { it.entryId }
        return touched.toSet()
    }

    fun goesWith(c: Capture, e: Entry): Boolean = e.captureId == c.id || e.id in entries(c)
}
