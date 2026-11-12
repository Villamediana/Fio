package com.villamediana.novelo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** What a thing kept in Novelo is. */
enum class Kind(val key: String) {
    TASK("task"), NOTE("note"), DIARY("diary");

    companion object {
        fun of(key: String?): Kind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** Where a task stands. */
enum class Status(val key: String) {
    TODO("todo"), DOING("doing"), WAITING("waiting"), DONE("done");

    companion object {
        fun of(key: String?): Status? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

@Serializable
data class Step(val text: String, val done: Boolean = false)

/** A task, a note or a diary entry. */
@Serializable
@Entity(tableName = "entries", indices = [Index("kind"), Index("createdAt"), Index("captureId")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val body: String = "",
    /** Tasks only. */
    val status: String? = null,
    /** JSON list of [Step]. */
    val steps: String = "[]",
    /** JSON list of strings. */
    val tags: String = "[]",
    val dueAt: Long? = null,
    /** The due date came with a time of day ("sexta às 15h"), not just a day. */
    val dueHasTime: Boolean = false,
    val remindAt: Long? = null,
    /** When Novelo asks how the task is going (decided by the model). */
    val checkInAt: Long? = null,
    /** Passwords, documents: hidden until tapped. */
    val sensitive: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val doneAt: Long? = null,
    /** The capture (what was said or photographed) it came from. */
    val captureId: Long? = null,
) {
    val kindEnum: Kind get() = Kind.of(kind) ?: Kind.NOTE
    val statusEnum: Status? get() = Status.of(status)
    val isOpenTask: Boolean get() = kindEnum == Kind.TASK && statusEnum != Status.DONE
}

/** One thing said or photographed, and what came of it. */
@Serializable
@Entity(tableName = "captures", indices = [Index("createdAt")])
data class Capture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** "voice", "photo" or "text" (written instead of said). */
    val source: String,
    val audioPath: String? = null,
    val imagePath: String? = null,
    val durationMs: Long = 0,
    val transcript: String? = null,
    /** recorded → transcribing → thinking → done | failed. */
    val state: String,
    val error: String? = null,
    /** Novelo's one-line answer. */
    val reply: String? = null,
    /** JSON list of [Outcome]: what was created or changed. */
    val outcomes: String = "[]",
    /** JSON list of entry ids shown as the answer to a question. */
    val shown: String = "[]",
    /** The entry a check-in was about (the reply goes to it first). */
    val focusEntryId: Long? = null,
    /** While transcribing: the text of the pieces already heard, so a restart goes on from there. */
    val heard: String? = null,
    @ColumnInfo(defaultValue = "0") val heardPieces: Int = 0,
    /** How it was cut into pieces ("local:28", "api:300"): another way of hearing starts over. */
    val heardBy: String? = null,
    /** Runs that started and never ended (the app closed or fell in the middle): after a few, it waits for a hand. */
    @ColumnInfo(defaultValue = "0") val tries: Int = 0,
    /** A photo: what it shows, in her words (written by the model that looked at it) — what the search finds it by. */
    val description: String? = null,
    /** JSON list of photos (capture ids) shown as the answer to a question. */
    @ColumnInfo(defaultValue = "[]") val shownPhotos: String = "[]",
    /** A photo turned by hand in the viewer, in degrees to the right (0, 90, 180, 270); the file isn't touched. */
    @ColumnInfo(defaultValue = "0") val turn: Int = 0,
    /** Something said whose sound itself was kept (a melody sung, a sound): [audioPath] stays, with what came of it. */
    @ColumnInfo(defaultValue = "0") val sound: Boolean = false,
    /** A photo sent together with something said or written (that capture's id): it goes where that went. */
    val partOf: Long? = null,
) {
    companion object {
        const val RECORDED = "recorded"
        const val TRANSCRIBING = "transcribing"
        const val THINKING = "thinking"
        const val DONE = "done"
        const val FAILED = "failed"

        /** Stopped by hand while it was going through: it waits for a "Tentar de novo", never retried on its own. */
        const val STOPPED = "stopped"

        /** [source] of something written instead of said. */
        const val TEXT = "text"
    }
}

@Serializable
data class Outcome(
    val entryId: Long,
    val action: String,
    /** The photos sent with what was said stay with this entry. */
    val photos: Boolean = false,
) {
    companion object {
        const val CREATED = "created"
        const val UPDATED = "updated"
        const val DONE = "done"
        const val DELETED = "deleted"
    }
}

/** A person, place, project, company or subject: the knots of the graph. */
@Serializable
@Entity(tableName = "entities", indices = [Index(value = ["norm"], unique = true)])
data class Topic(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val norm: String,
    val type: String = "topic",
)

@Serializable
@Entity(
    tableName = "entry_topics",
    primaryKeys = ["entryId", "topicId"],
    indices = [Index("topicId")],
    foreignKeys = [
        ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Topic::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EntryTopic(val entryId: Long, val topicId: Long)

/** Two entries the model saw as the same matter (always a < b). */
@Serializable
@Entity(
    tableName = "links",
    primaryKeys = ["a", "b"],
    indices = [Index("b")],
    foreignKeys = [
        ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["a"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["b"], onDelete = ForeignKey.CASCADE),
    ],
)
data class Link(val a: Long, val b: Long)

/** How a task went: each status change or bit of news, in order. */
@Serializable
@Entity(
    tableName = "progress",
    indices = [Index("entryId")],
    foreignKeys = [ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class Progress(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val at: Long,
    val text: String,
    @ColumnInfo(defaultValue = "NULL") val status: String? = null,
)

/** A topic with how many entries it ties together (the graph's nodes). */
data class TopicCount(val id: Long, val name: String, val type: String, val count: Int)

/** Two topics that appear in the same entries (the graph's edges). */
data class TopicPair(val a: Long, val b: Long, val weight: Int)
