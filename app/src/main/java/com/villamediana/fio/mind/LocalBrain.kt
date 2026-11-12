package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Organizing on the phone, in two small steps (what the lab measured best, ~/novelo-lab):
 *  1. d1 (a decision model) answers a few yes/no and pick-one questions about what she said — is
 *     it her day, a new task, something to keep, a question, which open task she finished or gave
 *     news about. It only reads; nothing is written.
 *  2. LFM3-1.7B writes only what d1 decided, held to a grammar with just those fields.
 * The [Plan] comes from those two answers by rule. Everything here is plain text work; the models
 * run in [com.villamediana.fio.local.LocalOrganizer].
 */
object LocalBrain {
    // ------------------------------------------------------------------ 1. what d1 is asked

    enum class Type(val key: String) { NOUL("noul"), CHOICE("choice") }

    /** One question; a choice's [options] are (key, what it reads as). */
    data class Question(val id: String, val type: Type, val instructions: String, val options: List<Pair<String, String>> = emptyList())

    const val NONE = "none"

    /** A task as d1 and the writer see it: its id, title and when it is due (or reminds). */
    data class TaskLine(val id: Long, val title: String, val due: String?)

    /** The questions for what she said; the pick-one ones only when she has open tasks. */
    fun questions(tasks: List<TaskLine>): List<Question> {
        val choices = listOf(NONE to "None of her open tasks") + tasks.map { it.id.toString() to it.title }
        return buildList {
            add(Question("question", Type.NOUL, "Is she asking a question, wanting to know something?"))
            add(Question("diary", Type.NOUL, "Is she telling about her day: something she lived, did or felt?"))
            add(Question("task", Type.NOUL, "Does she say something she will have to do (an action, an appointment), new, not one of her open tasks? Information to keep is not."))
            add(Question("note", Type.NOUL, "Does she give a piece of information to keep (a number, a password, an address, an idea)? A question or a to-do is not."))
            if (tasks.isNotEmpty()) {
                add(Question("done", Type.CHOICE, "Which of her open tasks does she say she already did, bought, paid or finished?", choices))
                add(Question("news", Type.CHOICE, "Which of her open tasks does she give news about (progress or a change) without finishing it?", choices))
            }
        }
    }

    /** Asked only when there is something to keep: is it secret (shown hidden)? */
    val SENSITIVE = Question("sensitive", Type.NOUL, "Is what she wants kept secret, like a password, a document number or bank details?")

    /** What d1 reads before each question: her open tasks and what she said. */
    fun state(said: String, tasks: List<TaskLine>): String = buildString {
        append("Her open tasks:\n")
        if (tasks.isEmpty()) append("(none)\n") else tasks.forEach { t -> append('#').append(t.id).append(' ').append(t.title).append(t.due?.let { " ($it)" }.orEmpty()).append('\n') }
        append("\nWhat she just said (automatic transcription):\n\"").append(said.trim()).append('"')
    }

    /** The open tasks d1 can pick from (A to Z, "none" being one): the one she answers about first. */
    const val MAX_TASKS = 25

    /**
     * The prompt for one question, as d1's own template ("systemone") writes it. What comes from her
     * can't spell the model's control tokens: each one found is broken by a zero-width space after
     * its first character ([spellings], longest first — as llama.cpp's d1_break).
     */
    fun prompt(state: String, q: Question, spellings: List<String>): String {
        fun b(s: String) = breakSpellings(s, spellings)
        return buildString {
            append("<|startoftext|><|im_start|>user\n")
            append(b(state)).append("\n\n\nQUESTION:\n")
            append(b(q.instructions))
            when (q.type) {
                Type.CHOICE -> {
                    append("\n\nOptions:\n")
                    q.options.forEachIndexed { i, (key, text) ->
                        if (i > 0) append('\n')
                        append(b(code(i))).append(' ').append(b(text.ifEmpty { key.replace('_', ' ') }))
                    }
                    append("\n\nReply with the option code only.")
                }
                Type.NOUL -> append("\n\nReply with yes or no only.")
            }
            append("<|im_end|>\n<|im_start|>assistant\n<think></think>")
        }
    }

    /** A choice's code: A, B, C… */
    fun code(i: Int): String = ('A' + i).toString()

    /** The spellings of each answer's label (what its score is read from): yes/no, or the codes. */
    fun labels(q: Question): List<List<String>> = when (q.type) {
        Type.NOUL -> listOf(listOf("yes", "Yes", "YES"), listOf("no", "No", "NO"))
        Type.CHOICE -> q.options.indices.map { listOf(code(it), " " + code(it)) }
    }

    /** The scores as probabilities, softened by the model's [temperature]. */
    fun probabilities(scores: FloatArray, temperature: Double): DoubleArray {
        val max = scores.maxOrNull()?.toDouble() ?: return DoubleArray(0)
        val e = DoubleArray(scores.size) { Math.exp((scores[it] - max) / temperature) }
        val sum = e.sum()
        return DoubleArray(e.size) { e[it] / sum }
    }

    private const val ZWSP = "​"

    fun breakSpellings(text: String, spellings: List<String>): String {
        if (spellings.isEmpty()) return text
        val first = spellings.mapNotNull { it.firstOrNull() }.toSet()
        var cur = text
        var changed = true
        while (changed) {
            changed = false
            val out = StringBuilder()
            var i = 0
            while (i < cur.length) {
                val hit = if (cur[i] in first) spellings.firstOrNull { cur.startsWith(it, i) } else null
                if (hit == null) { out.append(cur[i]); i++; continue }
                val n = Character.charCount(hit.codePointAt(0))
                out.append(hit, 0, n).append(ZWSP).append(hit, n, hit.length)
                i += hit.length
                changed = true
            }
            cur = out.toString()
        }
        return cur
    }

    /** What d1 decided: the parts the writer fills, and the tasks done or with news. */
    data class Decision(
        val question: Boolean = false,
        val diary: Boolean = false,
        val task: Boolean = false,
        val note: Boolean = false,
        val done: Long? = null,
        val news: Long? = null,
        val sensitive: Boolean = false,
    )

    /**
     * The answers (each question's probabilities, in its options' order) → the decision: yes from
     * 0.5; a task picked when it isn't "none" and has 0.5 or more (what the lab used).
     */
    fun decide(questions: List<Question>, answers: Map<String, DoubleArray>): Decision {
        fun yes(id: String) = answers[id]?.firstOrNull()?.let { it >= 0.5 } == true
        fun picked(id: String): Long? {
            val q = questions.firstOrNull { it.id == id } ?: return null
            val p = answers[id] ?: return null
            val best = p.indices.maxByOrNull { p[it] } ?: return null
            if (p[best] < 0.5) return null
            return q.options.getOrNull(best)?.first?.takeIf { it != NONE }?.toLongOrNull()
        }
        val done = picked("done")
        val question = yes("question")
        return Decision(
            question = question,
            diary = yes("diary"),
            task = yes("task"),
            note = yes("note"),
            done = done,
            // A question changes nothing it asks about (in the lab "what do I have today?" gave the
            // day's task news); a task done is said plainly, so that one stays.
            news = picked("news")?.takeIf { it != done && !question },
            sensitive = yes(SENSITIVE.id),
        )
    }

    // ------------------------------------------------------------------ 2. what the writer fills

    /** The fields the writer fills, in this order, from [d]: the guide line of each and its rule. */
    fun fields(d: Decision): List<String> = buildList {
        if (d.diary) add("diary")
        if (d.task) add("tasks")
        if (d.note) add("notes")
        if (d.news != null) add("news")
        add(if (d.question) "answer" else "reply")
    }

    private val GUIDE = mapOf(
        "diary" to "diary: her account of her day, in first person, as she said it, without repetitions or hesitations (a word the transcription clearly misheard is written right). Only the account, not the to-dos.",
        "tasks" to "tasks: each new action she has to do (a password, a number or a piece of information is not a task): a short title in her words, due \"YYYY-MM-DD\" when she said the day (count from NOW), time \"HH:MM\" when she said the hour.",
        "notes" to "notes: each thing she wants kept: a short title and the text.",
        "news" to "news: the new part she said about her task #%d, in her words.",
        "answer" to "answer: the answer to her question, from her open tasks and NOW.",
        "reply" to "reply: one short, warm sentence in her language saying what you kept.",
    )

    /**
     * The writer's instructions: only the decided fields; NOW (with tomorrow worked out); her open
     * tasks and what may be related only when there is a question; the task she gives news about.
     */
    fun writerSystem(d: Decision, now: Long, zone: ZoneId, tasks: List<TaskLine>, related: List<String> = emptyList()): String = buildString {
        append("You keep a person's notebook. Her message is what she just said (automatic transcription: it may mishear a word). ")
        append("Fill the JSON fields below, every text in the language she spoke, only from what she said:\n")
        fields(d).forEach { f -> append("- ").append(if (f == "news") GUIDE.getValue(f).format(d.news) else GUIDE.getValue(f)).append('\n') }
        append("\nNOW: ").append(nowLine(now, zone))
        if (d.question) {
            append("\nHER OPEN TASKS:\n")
            if (tasks.isEmpty()) append("(none)") else append(tasks.joinToString("\n") { quoted(it) })
            if (related.isNotEmpty()) append("\nWHAT SHE KEPT THAT MAY BE RELATED:\n").append(related.joinToString("\n"))
        }
        d.news?.let { id -> tasks.firstOrNull { it.id == id }?.let { append("\nTHE TASK SHE TALKS ABOUT: ").append(quoted(it)) } }
    }.trimEnd()

    private fun quoted(t: TaskLine) = "#" + t.id + " \"" + t.title + "\"" + (t.due?.let { " ($it)" } ?: "")

    /** "Wednesday, 2026-11-11 21:40 (TOMORROW is Thursday, 2026-11-12)". */
    fun nowLine(now: Long, zone: ZoneId): String {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val tomorrow = at.toLocalDate().plusDays(1)
        return weekday(at.toLocalDate()) + ", " + at.format(STAMP) + " (TOMORROW is " + weekday(tomorrow) + ", " + tomorrow.format(DAY) + ")"
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** A task's line for d1 and the writer: when it is due (today / tomorrow / the day), else when it reminds. */
    fun taskLine(e: Entry, now: Long, zone: ZoneId): TaskLine {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun whenIs(ms: Long, withTime: Boolean): String {
            val at = Instant.ofEpochMilli(ms).atZone(zone)
            val d = at.toLocalDate()
            val day = when (d) {
                today -> "today"
                today.plusDays(1) -> "tomorrow"
                else -> d.format(DAY)
            }
            return if (withTime) day + " " + at.format(HM) else day
        }
        val due = e.dueAt?.let { "due " + whenIs(it, e.dueHasTime) }
            ?: e.remindAt?.takeIf { it > now }?.let { "reminder " + whenIs(it, true) }
        return TaskLine(e.id, e.title.trim().take(120), due)
    }

    /**
     * The grammar the writer is held to: one JSON object with exactly the decided fields, in their
     * order (the one llama.cpp makes from that JSON schema — dates and hours in their shape, or null).
     */
    fun grammar(d: Decision): String {
        val kv = fields(d).joinToString(" \",\" space ") { "$it-kv" }
        return """
root ::= "{" space $kv space "}"
diary-kv ::= "\"diary\"" space ":" space string
tasks-kv ::= "\"tasks\"" space ":" space tasks
notes-kv ::= "\"notes\"" space ":" space notes
news-kv ::= "\"news\"" space ":" space string
answer-kv ::= "\"answer\"" space ":" space string
reply-kv ::= "\"reply\"" space ":" space string
tasks ::= "[" space (task ("," space task)*)? space "]"
task ::= "{" space "\"title\"" space ":" space string "," space "\"due\"" space ":" space (date | null) "," space "\"time\"" space ":" space (hour | null) space "}"
notes ::= "[" space (note ("," space note)*)? space "]"
note ::= "{" space "\"title\"" space ":" space string "," space "\"text\"" space ":" space string space "}"
date ::= "\"" [0-9]{4} "-" [0-9]{2} "-" [0-9]{2} "\""
hour ::= "\"" [0-9]{2} ":" [0-9]{2} "\""
null ::= "null"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})
space ::= | " " | "\n"{1,2} [ \t]{0,20}
""".trim()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The writer's answer as an object (whatever is around it ignored); empty when there is none. */
    fun filled(answer: String): JsonObject {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    /**
     * The [Plan], by rule: her day goes to the day's diary (named [diaryTitle] when it begins now);
     * each task and note is new; the task done is done; the news joins its task. A task with an hour
     * reminds a little before it. [photosTo]: photos sent with what she said stay with the first
     * thing made or changed.
     */
    fun plan(d: Decision, filled: JsonObject, now: Long, zone: ZoneId, diaryTitle: String, photos: Boolean = false, photoDescription: String? = null): Plan {
        val create = mutableListOf<NewItem>()
        val update = mutableListOf<Change>()
        text(filled["diary"])?.takeIf { d.diary }?.let { create += NewItem(kind = Kind.DIARY.key, title = diaryTitle, body = it) }
        if (d.task) list(filled["tasks"]).forEach { t ->
            val title = text(t["title"]) ?: return@forEach
            val (due, remind) = due(text(t["due"]), text(t["time"]), now, zone)
            create += NewItem(kind = Kind.TASK.key, title = title, due = due, remindAt = remind)
        }
        if (d.note) list(filled["notes"]).forEach { n ->
            val title = text(n["title"]) ?: text(n["text"])?.take(60) ?: return@forEach
            create += NewItem(kind = Kind.NOTE.key, title = title, body = text(n["text"]).orEmpty(), sensitive = d.sensitive)
        }
        d.done?.let { update += Change(id = it, status = "done") }
        d.news?.let { id -> text(filled["news"])?.let { update += Change(id = id, append = it) } }
        // The photos go with the first task or note made, else with the task changed.
        var photosLeft = photos
        val made = create.map { if (photosLeft && it.kind != Kind.DIARY.key) it.copy(photos = true).also { photosLeft = false } else it }
        val changed = update.map { if (photosLeft) it.copy(photos = true).also { photosLeft = false } else it }
        return Plan(
            reply = (text(filled["answer"]) ?: text(filled["reply"])).orEmpty(),
            create = made,
            update = changed,
            photoDescription = photoDescription,
        )
    }

    /**
     * A task's day and hour → its due ("2026-11-12" or "2026-11-12T08:00") and, with an hour, a
     * reminder 15 minutes before. An hour said without a day is the next time that hour comes.
     */
    fun due(day: String?, hour: String?, now: Long, zone: ZoneId): Pair<String?, String?> {
        val d = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val h = hour?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (h == null) return d?.format(DAY) to null
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val date = d ?: if (h.isAfter(at.toLocalTime())) at.toLocalDate() else at.toLocalDate().plusDays(1)
        val due = LocalDateTime.of(date, h)
        return due.format(DUE) to due.minusMinutes(15).format(DUE)
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun list(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    // ------------------------------------------------------------------ photos

    /** What Omni is asked about a photo: what can be seen, in a sentence or two, for the search. */
    fun describe(language: String): String =
        "Describe this photo in one or two sentences, in $language: the place, the people, animals and things, the colours, the moment. Only what can be seen, nothing made up. Plain text."

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
}
