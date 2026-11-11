package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Progress
import com.villamediana.fio.data.Status
import com.villamediana.fio.data.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What the model decided to do with one thing said. */
@Serializable
data class Plan(
    val reply: String = "",
    val create: List<NewItem> = emptyList(),
    val update: List<Change> = emptyList(),
    /** The model needs to look something up before answering: words to search for. */
    val search: List<String> = emptyList(),
    /** Entries that answer a question, to show. */
    val show: List<Long> = emptyList(),
) {
    val isLookupOnly: Boolean get() = search.isNotEmpty() && create.isEmpty() && update.isEmpty() && show.isEmpty()
}

@Serializable
data class NewItem(
    val kind: String,
    val title: String,
    val body: String = "",
    val tags: List<String> = emptyList(),
    val entities: List<NamedTopic> = emptyList(),
    val due: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    val steps: List<String> = emptyList(),
    val status: String? = null,
    val sensitive: Boolean = false,
    val related: List<Long> = emptyList(),
)

@Serializable
data class NamedTopic(val name: String, val type: String = "topic")

@Serializable
data class Change(
    val id: Long,
    val status: String? = null,
    @SerialName("steps_done") val stepsDone: List<String> = emptyList(),
    @SerialName("add_steps") val addSteps: List<String> = emptyList(),
    /** News about it, kept in its history ("falei com o fornecedor, manda amanhã"). */
    val progress: String? = null,
    val title: String? = null,
    val append: String? = null,
    val due: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    val entities: List<NamedTopic> = emptyList(),
    val delete: Boolean = false,
)

/** The model's side of Fio: the instructions, the context it sees, and reading what it answers. */
object Brain {
    val SYSTEM: String = """
Você é o Fio, a memória externa de uma pessoa. Ela fala o que vem à cabeça, do jeito que sai (às vezes misturando português e espanhol), e você organiza pra ela não precisar guardar nada na cabeça.

Numa mesma fala pode haver várias coisas:
- tarefa (task): algo que ela precisa ou quer fazer.
- nota (note): algo pra guardar e achar depois — ideia, informação, dado, senha, referência, decisão.
- diário (diary): um relato do dia — o que viveu, fez, sentiu. Se o relato traz coisas a fazer ou a guardar, crie o diário E também essas tarefas/notas.

Ela também pode:
- dar notícia de algo que já existe (andou, terminou, travou, mudou o prazo, mais detalhes): atualize o item existente pelo id em "update", não crie um novo. Notícias de andamento vão em "progress".
- perguntar ou pedir pra ver coisas (o que tem pra hoje, quando falou de algo, qual era uma senha, como está um projeto): responda em "reply" usando só o que está no contexto e ponha em "show" os ids dos itens que respondem. Pergunta não cria item.

Como preencher:
- Use as palavras dela. Título curto (até ~8 palavras). Corpo com os detalhes ditos, sem inventar nada.
- Datas relativas (amanhã, sexta, daqui a duas semanas, às 3 da tarde) viram data absoluta a partir de AGORA, no formato AAAA-MM-DD ou AAAA-MM-DDTHH:MM. Sem data dita, null.
- remind_at: quando ela pede pra ser lembrada, ou quando algo tem hora marcada (lembre um pouco antes).
- check_in_at: em tarefas que levam dias ou têm etapas, escolha um bom momento pra perguntar como está. Pode ser null.
- steps: as etapas, quando a tarefa tem mais de um passo. status: todo, doing, waiting ou done.
- sensitive: true para senhas, números de documento, dados bancários e afins.
- entities: pessoas, lugares, projetos, empresas e assuntos citados, com nome curto e type (person, place, project, org, topic). Servem pra ligar esta lembrança às outras.
- related: ids de itens do contexto que tratam do mesmo assunto.
- Se, pra responder, faltar algo que não está no contexto, devolva só "search" com as palavras a procurar (o resto vazio): você recebe os resultados e responde em seguida.
- reply: uma frase curta e calorosa, no idioma dela, dizendo o que ficou guardado ou respondendo a pergunta.

Responda somente com um objeto JSON neste formato:
{"reply": "...",
 "create": [{"kind": "task|note|diary", "title": "...", "body": "...", "tags": ["..."], "entities": [{"name": "...", "type": "..."}], "due": null, "remind_at": null, "check_in_at": null, "steps": [], "status": "todo", "sensitive": false, "related": []}],
 "update": [{"id": 0, "status": null, "steps_done": [], "add_steps": [], "progress": null, "title": null, "append": null, "due": null, "remind_at": null, "check_in_at": null, "entities": [], "delete": false}],
 "search": [],
 "show": []}
""".trim()

    /** What the model sees: now, the open tasks, what may be related, and the words said. */
    fun context(
        said: String,
        now: Long,
        zone: ZoneId,
        openTasks: List<Entry>,
        related: List<Entry>,
        focus: Entry? = null,
        focusProgress: List<Progress> = emptyList(),
        searched: List<String> = emptyList(),
        photo: Boolean = false,
    ): String = buildString {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val weekday = at.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
        append("AGORA: ").append(weekday).append(", ").append(at.format(STAMP)).append(" (").append(zone.id).append(")\n")
        if (focus != null) {
            append("\nELA ESTÁ RESPONDENDO SOBRE ESTE ITEM (Fio perguntou como está):\n")
            append(line(focus, zone, now, long = true)).append('\n')
            focusProgress.take(5).forEach { p -> append("   · ").append(day(p.at, zone)).append(": ").append(p.text).append('\n') }
        }
        val tasks = openTasks.filter { it.id != focus?.id }.take(MAX_TASKS)
        if (tasks.isNotEmpty()) {
            append("\nTAREFAS ABERTAS:\n")
            tasks.forEach { append(line(it, zone, now)).append('\n') }
        }
        val rel = related.filter { r -> r.id != focus?.id && tasks.none { it.id == r.id } }.take(MAX_RELATED)
        if (rel.isNotEmpty()) {
            append(if (searched.isEmpty()) "\nLEMBRANÇAS QUE PODEM TER A VER:\n" else "\nRESULTADOS DA BUSCA (${searched.joinToString(", ")}):\n")
            rel.forEach { append(line(it, zone, now, long = true)).append('\n') }
        } else if (searched.isNotEmpty()) {
            append("\nA BUSCA (${searched.joinToString(", ")}) NÃO ACHOU NADA. Responda com o que há.\n")
        }
        append(if (photo) "\nO QUE ESTÁ NA FOTO QUE ELA TIROU:\n" else "\nO QUE ELA DISSE:\n")
        append('"').append(said.trim()).append("\"\n")
    }

    /** One entry in a line the model can read and point to (#id). */
    fun line(e: Entry, zone: ZoneId, now: Long, long: Boolean = false): String = buildString {
        append('#').append(e.id).append(' ')
        append(
            when (e.kindEnum) {
                Kind.TASK -> "tarefa[" + (e.statusEnum ?: Status.TODO).key + "]"
                Kind.NOTE -> "nota"
                Kind.DIARY -> "diário"
            },
        )
        append(' ').append(day(e.createdAt, zone)).append(" \"").append(e.title.take(90)).append('"')
        e.dueAt?.let { append(" · prazo ").append(if (e.dueHasTime) stamp(it, zone) else day(it, zone)) }
        e.remindAt?.takeIf { it > now }?.let { append(" · lembrete ").append(stamp(it, zone)) }
        val steps = Steps.read(e.steps)
        if (steps.isNotEmpty()) append(" · etapas: ").append(steps.joinToString(" ") { (if (it.done) "[x] " else "[ ] ") + it.text.take(40) })
        if (long && e.body.isNotBlank()) {
            val body = if (e.sensitive) e.body.take(160) else e.body.take(220)
            append(" — ").append(body.replace('\n', ' '))
        }
    }

    /** The plain chat format LFM3 was trained on (for the on-device model). */
    fun chatml(system: String, user: String): String =
        "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** The model's answer → [Plan]; code fences and text around the object are ignored. Null if no JSON. */
    fun parse(answer: String): Plan? {
        val text = answer.substringAfterLast("</think>")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(Plan.serializer(), text.substring(start, end + 1)) }.getOrNull()
            ?.let { plan -> plan.copy(create = plan.create.filter { it.title.isNotBlank() && Kind.of(it.kind) != null }) }
    }

    /** "2026-11-12", "2026-11-12T15:00", "2026-11-12 15:00:00" → epoch ms (and whether a time was given). */
    fun time(text: String?, zone: ZoneId): Pair<Long, Boolean>? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" } ?: return null
        val norm = s.replace(' ', 'T').removeSuffix("Z")
        runCatching {
            val dt = LocalDateTime.parse(norm.take(19).let { if (it.length == 16) "$it:00" else it })
            return dt.atZone(zone).toInstant().toEpochMilli() to true
        }
        runCatching {
            val d = LocalDate.parse(norm.take(10))
            return d.atStartOfDay(zone).toInstant().toEpochMilli() to false
        }
        return null
    }

    /** A reminder needs a time: a bare day reminds at 9:00. */
    fun remindTime(text: String?, zone: ZoneId): Long? {
        val (ms, hasTime) = time(text, zone) ?: return null
        if (hasTime) return ms
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return d.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    /** A JSON object grammar (GBNF) for the on-device model: the answer can only be JSON. */
    val JSON_GRAMMAR: String = """
root   ::= ws object ws
object ::= "{" ws ( pair ( ws "," ws pair )* )? ws "}"
pair   ::= string ws ":" ws value
array  ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
value  ::= object | array | string | number | ("true" | "false" | "null")
string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
number ::= "-"? ([0-9] | [1-9] [0-9]{0,15}) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
ws     ::= [ \t\n]{0,20}
""".trim()

    private const val MAX_TASKS = 30
    private const val MAX_RELATED = 12
    private val PT = Locale.forLanguageTag("pt-BR")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)
    private fun stamp(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(STAMP)
}

object Steps {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = kotlinx.serialization.builtins.ListSerializer(Step.serializer())

    fun read(text: String?): List<Step> = runCatching { json.decodeFromString(serializer, text ?: "[]") }.getOrDefault(emptyList())
    fun write(steps: List<Step>): String = json.encodeToString(serializer, steps)
}

object Tags {
    private val serializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
    fun read(text: String?): List<String> = runCatching { Json.decodeFromString(serializer, text ?: "[]") }.getOrDefault(emptyList())
    fun write(tags: List<String>): String = Json.encodeToString(serializer, tags)
}
