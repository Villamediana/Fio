package com.villamediana.fio.backup

import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.EntryTopic
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Link
import com.villamediana.fio.data.Progress
import com.villamediana.fio.data.Status
import com.villamediana.fio.data.Topic
import com.villamediana.fio.mind.DayDiary
import com.villamediana.fio.mind.Steps
import com.villamediana.fio.mind.Tags
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything in Fio at one moment: what an import puts back as it was (ids kept). */
@Serializable
data class Snapshot(
    val format: Int = FORMAT,
    val app: String = "",
    val madeAt: Long = 0,
    val entries: List<Entry> = emptyList(),
    val captures: List<Capture> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val ties: List<EntryTopic> = emptyList(),
    val links: List<Link> = emptyList(),
    val progress: List<Progress> = emptyList(),
    /** Each photo (capture id) → its file in the backup folder ("Fotos/2026/…jpg"). */
    val photos: Map<Long, String> = emptyMap(),
) {
    val photoCount: Int get() = photos.size

    companion object {
        const val FORMAT = 1
    }
}

/**
 * The backup folder, in the open: one Markdown file per thing kept, by kind — the diary by year,
 * notes, tasks open and done — and the photos by year. Readable in any file app or notes app
 * (Obsidian, a text editor); the snapshot in Backups/ is what an import reads.
 */
object Readable {
    const val DIARY = "Diário"
    const val NOTES = "Notas"
    const val TASKS = "Tarefas"
    const val OPEN = "Abertas"
    const val DONE = "Feitas"
    const val PHOTOS = "Fotos"
    const val BACKUPS = "Backups"

    /** The folders holding Markdown kept in step with the app (a file whose item is gone leaves). */
    val MIRRORED = listOf(DIARY, NOTES, TASKS)

    private val WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val PHOTO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")
    private val SNAPSHOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")
    private val SNAPSHOT_NAME = Regex("""fio (\d{4}-\d{2}-\d{2} \d{2}\.\d{2}\.\d{2})\.json""")

    /** Where a photo goes in the backup: Fotos/<ano>/<data hora> (<id>).<ext>. */
    fun photoPath(c: Capture, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(c.createdAt).atZone(zone)
        val ext = c.imagePath?.substringAfterLast('.', "jpg")?.lowercase()?.takeIf { it.length in 2..5 } ?: "jpg"
        return "$PHOTOS/${at.year}/${at.format(PHOTO)} (${c.id}).$ext"
    }

    /** The name of a snapshot made at [ms]: Backups/fio 2026-11-11 14.32.05.json (the newest sorts last). */
    fun snapshotName(ms: Long, zone: ZoneId): String = "fio " + Instant.ofEpochMilli(ms).atZone(zone).format(SNAPSHOT) + ".json"

    fun isSnapshot(name: String): Boolean = SNAPSHOT_NAME.matches(name)

    /** When a snapshot was made, read from its name; null for any other file. */
    fun snapshotTime(name: String, zone: ZoneId): Long? = SNAPSHOT_NAME.matchEntire(name)?.let { m ->
        runCatching { java.time.LocalDateTime.parse(m.groupValues[1], SNAPSHOT).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    /** A title as a file name any phone or computer takes: no slashes or reserved signs, not too long. */
    fun fileName(title: String): String {
        val clean = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().trim('.')
        return clean.take(80).trim().ifEmpty { "Sem título" }
    }

    /** Every Markdown file of the snapshot: path → text. */
    fun files(s: Snapshot, zone: ZoneId): Map<String, String> {
        val topicsById = s.topics.associateBy { it.id }
        val topicsOf = s.ties.groupBy({ it.entryId }, { topicsById[it.topicId]?.name }).mapValues { (_, v) -> v.filterNotNull().sortedBy { it.lowercase() } }
        val photoCaptures = s.captures.filter { it.id in s.photos }
        // The photos of a day show in that day's diary — the newest one, as in the app.
        val dayOfPhotos = photoCaptures.groupBy { DayDiary.bounds(it.createdAt, zone).first }
        val diaryOfDay = s.entries.filter { it.kindEnum == Kind.DIARY }
            .groupBy { DayDiary.bounds(it.createdAt, zone).first }
            .mapValues { (_, list) -> list.maxWith(compareBy<Entry> { it.createdAt }.thenBy { it.id }).id }

        val paths = LinkedHashMap<String, Entry>()
        // The oldest takes the plain name; another with the same name gets its number.
        s.entries.sortedBy { it.id }.forEach { e ->
            val (folder, base) = when (e.kindEnum) {
                Kind.DIARY -> {
                    val at = Instant.ofEpochMilli(e.createdAt).atZone(zone)
                    "$DIARY/${at.year}" to at.format(DAY)
                }
                Kind.NOTE -> NOTES to fileName(e.title)
                Kind.TASK -> "$TASKS/${if (e.statusEnum == Status.DONE) DONE else OPEN}" to fileName(e.title)
            }
            var path = "$folder/$base.md"
            if (path in paths) path = "$folder/$base (#${e.id}).md"
            paths[path] = e
        }
        return paths.mapValues { (path, e) ->
            val depth = path.count { it == '/' }
            val photos = if (e.kindEnum == Kind.DIARY) {
                val day = DayDiary.bounds(e.createdAt, zone).first
                if (diaryOfDay[day] == e.id) dayOfPhotos[day].orEmpty().sortedBy { it.createdAt }.mapNotNull { s.photos[it.id] } else emptyList()
            } else emptyList()
            markdown(e, topicsOf[e.id].orEmpty(), photos.map { "../".repeat(depth) + it }, zone)
        }
    }

    fun markdown(e: Entry, topics: List<String>, photos: List<String>, zone: ZoneId): String = buildString {
        append("# ").append(e.title.ifBlank { "Sem título" }.trim()).append("\n\n")
        val about = buildList {
            if (e.kindEnum == Kind.TASK) {
                add("Tarefa")
                add(status(e.statusEnum ?: Status.TODO))
                e.dueAt?.let { add("vence " + (if (e.dueHasTime) at(it, zone) else day(it, zone))) }
                e.remindAt?.let { add("lembrete " + at(it, zone)) }
                e.doneAt?.let { add("feita em " + at(it, zone)) }
            } else if (e.kindEnum == Kind.NOTE) {
                add("Nota")
            }
            if (e.sensitive) add("sensível")
        }
        if (about.isNotEmpty()) append(about.joinToString(" · ")).append("\n\n")
        if (e.body.isNotBlank()) append(e.body.trim()).append("\n\n")
        val steps = Steps.read(e.steps)
        if (steps.isNotEmpty()) {
            steps.forEach { append(if (it.done) "- [x] " else "- [ ] ").append(it.text.trim()).append('\n') }
            append('\n')
        }
        photos.forEach { append("![](").append(it.replace(" ", "%20").replace("(", "%28").replace(")", "%29")).append(")\n") }
        if (photos.isNotEmpty()) append('\n')
        append("---\n")
        val tags = Tags.read(e.tags)
        if (topics.isNotEmpty()) append("Tópicos: ").append(topics.joinToString(" · ")).append('\n')
        if (tags.isNotEmpty()) append("Marcadores: ").append(tags.joinToString(" ") { "#" + it.trim().replace(' ', '-') }).append('\n')
        append("Criado em ").append(at(e.createdAt, zone))
        if (e.updatedAt > e.createdAt + 60_000) append(" · atualizado em ").append(at(e.updatedAt, zone))
        append('\n')
    }

    private fun status(s: Status) = when (s) {
        Status.TODO -> "a fazer"
        Status.DOING -> "fazendo"
        Status.WAITING -> "esperando"
        Status.DONE -> "feita"
    }

    private fun at(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(WHEN)
    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}
