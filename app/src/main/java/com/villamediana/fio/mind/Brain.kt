package com.villamediana.fio.mind

import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
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
    /** The model needs to look something up before answering: words to search for… */
    val search: List<String> = emptyList(),
    /** …and/or a stretch of days to look at ("o que fiz ontem", "o que vence semana que vem"). */
    val period: Period? = null,
    /** Entries that answer a question, to show. */
    val show: List<Long> = emptyList(),
    /** Photos that answer a question (foto#N → N), to show. */
    @SerialName("show_photos") val showPhotos: List<Long> = emptyList(),
    /** The photo just taken: what can be seen in it, in her words — what the search finds it by later. */
    @SerialName("photo_description") val photoDescription: String? = null,
) {
    val wantsLookup: Boolean get() = search.isNotEmpty() || period?.from != null || period?.to != null
    val isLookupOnly: Boolean get() = wantsLookup && create.isEmpty() && update.isEmpty() && show.isEmpty() && showPhotos.isEmpty()
}

@Serializable
data class Period(val from: String? = null, val to: String? = null)

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
    val title: String? = null,
    /** What is new about it ("falei com o fornecedor, manda amanhã"), joined to its text: an item is one text. */
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
- diário (diary): um relato do dia — o que viveu, fez, sentiu, o que está acontecendo com ela. Todo relato assim vai pro diário, mesmo quando também traz coisas a fazer ou a guardar, ou é notícia de algo que já existe: aí faça as duas coisas — o diário com o relato e, além dele, as tarefas/notas ou o "update" do item. O diário é um por dia: se há DIÁRIO DE HOJE, o relato entra nele em "update" com "append" — um parágrafo novo no fim, sem mudar o que já está escrito nem o título; se não há, crie o diário do dia.

Ela também pode:
- dar notícia de algo que já existe (andou, terminou, travou, mudou o prazo, mais detalhes): atualize o item existente pelo id em "update", não crie outro igual a ele. O item é um texto só: o que há de novo vai em "append", só o que ainda não está nele e só o que muda pra ele, escrito como continuação do texto dele, sem repetir o que ele já diz. A mudança de situação vai só em "status". Se a notícia vem num relato do dia dela, o relato inteiro vai também pro diário.
- corrigir, completar ou desfazer o que acabou de falar: o item está em ÚLTIMAS COISAS GUARDADAS; mexa nele pelo id em "update" (ou apague com "delete": true) em vez de criar outro.
- perguntar ou pedir pra ver coisas (o que tem pra hoje, quando falou de algo, qual era uma senha, como está um projeto, uma foto de algo): responda em "reply" usando só o que está no contexto e ponha em "show" os ids dos itens que respondem e em "show_photos" os números das fotos (foto#N) que respondem. Pergunta não cria item.

Como preencher:
- Use as palavras dela. Título curto (até ~8 palavras) que diz do que se trata — nunca o tipo do item. Corpo com os detalhes ditos, sem inventar nada.
- Diário é dela: escreva em primeira pessoa, no idioma, no jeito e no tom em que ela falou, como se ela mesma tivesse escrito. Só tire repetições e hesitações; não reescreva no seu estilo nem resuma o que ela contou.
- Datas relativas (amanhã, sexta, daqui a duas semanas, às 3 da tarde) viram data absoluta a partir de AGORA, no formato AAAA-MM-DD ou AAAA-MM-DDTHH:MM. Sem data dita, null.
- remind_at: quando ela pede pra ser lembrada, ou quando algo tem hora marcada (lembre um pouco antes).
- check_in_at: em tarefas que levam dias ou têm etapas, escolha um bom momento pra perguntar como está. Pode ser null.
- steps: as etapas, quando a tarefa tem mais de um passo. status: todo, doing, waiting ou done.
- sensitive: true para senhas, números de documento, dados bancários e afins.
- entities: pessoas, lugares, projetos, empresas e assuntos citados, com nome curto e type (person, place, project, org, topic). Servem pra ligar esta lembrança às outras.
- related: ids de itens do contexto que tratam do mesmo assunto.
- photo_description: quando ela tirou uma foto, descreva o que se vê nela — lugar, pessoas, bichos, coisas, cores, o momento — em uma ou duas frases, no idioma dela, com as palavras que ela usaria pra procurar essa foto depois. Só o que dá pra ver, nada inventado. Esse texto é só pra achar a foto; ela não o vê.
- A foto já fica no diário do dia por si: não escreva o que se vê nela no diário nem em outro item. Crie ou mude itens só com o que a foto traz pra guardar ou fazer (um texto, um papel, um recibo, um aviso, um quadro); uma foto de um momento só ganha a "reply".
- Se, pra responder, faltar algo que não está no contexto, peça pra procurar e deixe o resto vazio: "search" com as palavras a procurar e/ou "period" com o intervalo de dias (from e to, AAAA-MM-DD) quando a pergunta é sobre um tempo — o que foi guardado, feito ou vence nesses dias. Você recebe os resultados e responde em seguida.
- reply: uma frase curta e calorosa, no idioma dela, dizendo o que ficou guardado ou respondendo a pergunta.
- Deixe de fora os campos vazios ou null: a resposta fica curta.

Responda somente com um objeto JSON neste formato:
{"reply": "...",
 "create": [{"kind": "task|note|diary", "title": "...", "body": "...", "tags": ["..."], "entities": [{"name": "...", "type": "..."}], "due": null, "remind_at": null, "check_in_at": null, "steps": [], "status": "todo", "sensitive": false, "related": []}],
 "update": [{"id": 0, "status": null, "steps_done": [], "add_steps": [], "title": null, "append": null, "due": null, "remind_at": null, "check_in_at": null, "entities": [], "delete": false}],
 "search": [],
 "period": {"from": null, "to": null},
 "show": [],
 "show_photos": [],
 "photo_description": null}
""".trim()

    /**
     * What the model sees: now, the open tasks, the last things kept, what may be related (or,
     * after a lookup, what was found — [looked] says what was looked for), and the words said.
     */
    fun context(
        said: String,
        now: Long,
        zone: ZoneId,
        openTasks: List<Entry>,
        related: List<Entry>,
        focus: Entry? = null,
        recent: List<Entry> = emptyList(),
        looked: String? = null,
        photo: Boolean = false,
        typed: Boolean = false,
        diary: Entry? = null,
        photos: List<Photo> = emptyList(),
        /** A photo: when it was taken (one from the gallery may be from another day). */
        takenAt: Long? = null,
    ): String = buildString {
        // What changes least comes first and what changes every time last (the moment, the item
        // being answered about, the words): a server that caches the start of the prompt, and the
        // phone that keeps its instructions run, reuse as much of it as can be.
        val today = diary?.takeIf { it.id != focus?.id }
        val shown = setOfNotNull(focus?.id, today?.id)
        val tasks = openTasks.filter { it.id !in shown }.take(MAX_TASKS)
        if (tasks.isNotEmpty()) {
            append("TAREFAS ABERTAS:\n")
            tasks.forEach { append(line(it, zone, now)).append('\n') }
        }
        if (today != null) {
            // Whole (up to a point): the day's story goes on in it without repeating it.
            append("\nDIÁRIO DE HOJE:\n")
            append(line(today, zone, now, long = true, bodyMax = FOCUS_BODY)).append('\n')
        }
        val last = recent.filter { it.id !in shown }.take(MAX_RECENT)
        if (last.isNotEmpty()) {
            append("\nÚLTIMAS COISAS GUARDADAS (a mais nova primeiro):\n")
            last.forEach { append(line(it, zone, now, long = true)).append('\n') }
        }
        if (looked == null) {
            val rel = related.filter { r -> r.id !in shown && tasks.none { it.id == r.id } && last.none { it.id == r.id } }.take(MAX_RELATED)
            if (rel.isNotEmpty()) {
                append("\nLEMBRANÇAS QUE PODEM TER A VER:\n")
                rel.forEach { append(line(it, zone, now, long = true)).append('\n') }
            }
        } else {
            // What was asked for comes whole, even when it is already listed above.
            val found = related.take(MAX_FOUND)
            if (found.isNotEmpty()) {
                append("\nRESULTADOS DA BUSCA (").append(looked).append("):\n")
                found.forEach { append(line(it, zone, now, long = true)).append('\n') }
            } else {
                append("\nA BUSCA (").append(looked).append(") NÃO ACHOU NADA. Responda com o que há.\n")
            }
        }
        if (photos.isNotEmpty()) {
            append(if (looked == null) "\nFOTOS QUE PODEM TER A VER:\n" else "\nFOTOS ACHADAS:\n")
            photos.take(MAX_PHOTOS).forEach { append(photoLine(it, zone)).append('\n') }
        }
        if (focus != null) {
            append("\nELA ESTÁ RESPONDENDO SOBRE ESTE ITEM (Fio perguntou como está):\n")
            // Its whole text (up to a point): what is new gets joined to it, so it has to know what is there.
            append(line(focus, zone, now, long = true, bodyMax = FOCUS_BODY)).append('\n')
        }
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val weekday = at.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
        append("\nAGORA: ").append(weekday).append(", ").append(at.format(STAMP)).append(" (").append(zone.id).append(")\n")
        val takenOn = takenAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }?.takeIf { it.toLocalDate() != at.toLocalDate() }
        append(
            when {
                photo && takenOn != null -> "\nO QUE ESTÁ NA FOTO (TIRADA EM " + takenOn.dayOfWeek.getDisplayName(TextStyle.FULL, PT) + ", " + takenOn.format(STAMP) + "):\n"
                photo -> "\nO QUE ESTÁ NA FOTO QUE ELA TIROU:\n"
                typed -> "\nO QUE ELA ESCREVEU:\n"
                else -> "\nO QUE ELA DISSE:\n"
            },
        )
        append('"').append(said.trim()).append("\"\n")
    }

    /** Today's diary (the newest one of the day in [zone]), where the day's story goes on; null before the first. */
    fun todaysDiary(entries: List<Entry>, now: Long, zone: ZoneId): Entry? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries
            .filter { it.kindEnum == Kind.DIARY && Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == today }
            .maxByOrNull { it.createdAt }
    }

    /** One photo in a line the model can point to (foto#id): when, what it shows, what is written in it. */
    fun photoLine(p: Photo, zone: ZoneId): String = buildString {
        append("foto#").append(p.id).append(' ').append(stamp(p.takenAt, zone))
        p.description?.let { append(" — ").append(it.take(200).replace('\n', ' ')) }
        p.text?.let { append(" · texto: ").append(it.take(120).replace('\n', ' ')) }
    }

    /** One entry in a line the model can read and point to (#id). */
    fun line(e: Entry, zone: ZoneId, now: Long, long: Boolean = false, bodyMax: Int = 220): String = buildString {
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
        e.doneAt?.let { append(" · feito em ").append(day(it, zone)) }
        e.remindAt?.takeIf { it > now }?.let { append(" · lembrete ").append(stamp(it, zone)) }
        val steps = Steps.read(e.steps)
        if (steps.isNotEmpty()) append(" · etapas: ").append(steps.joinToString(" ") { (if (it.done) "[x] " else "[ ] ") + it.text.take(40) })
        if (long && e.body.isNotBlank()) {
            val body = if (e.sensitive) e.body.take(160) else e.body.take(bodyMax)
            append(" — ").append(body.replace('\n', ' '))
        }
    }

    /** The plain chat format LFM3 was trained on (for the on-device model), the thinking turned off as its template does. */
    fun chatml(system: String, user: String): String =
        chatmlHead(system) + "$user<|im_end|>\n<|im_start|>assistant\n<think></think>"

    /** The start of [chatml] that only depends on the instructions: the same on every answer (the phone keeps it run). */
    fun chatmlHead(system: String): String = "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n"

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

    /** The days a [Period] covers, whole days on both ends; an open end goes on forever. Null when it names no day. */
    fun range(period: Period?, zone: ZoneId): LongRange? {
        val from = time(period?.from, zone)
        val to = time(period?.to, zone)
        if (from == null && to == null) return null
        val start = from?.let { startOfDay(it.first, zone) } ?: Long.MIN_VALUE
        val end = to?.let { startOfDay(it.first, zone, plusDays = 1) - 1 } ?: Long.MAX_VALUE
        return if (end < start) null else start..end
    }

    private fun startOfDay(ms: Long, zone: ZoneId, plusDays: Long = 0): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(plusDays).atStartOfDay(zone).toInstant().toEpochMilli()

    /** What a lookup asked for, in a few words (it heads the results the model gets back). */
    fun looked(plan: Plan): String = listOfNotNull(
        plan.search.joinToString(", ").takeIf { it.isNotBlank() },
        plan.period?.takeIf { it.from != null || it.to != null }?.let { p ->
            when {
                p.from != null && p.to != null && p.from != p.to -> "de ${p.from} a ${p.to}"
                p.from != null && p.to != null -> "dia ${p.from}"
                p.from != null -> "desde ${p.from}"
                else -> "até ${p.to}"
            }
        },
    ).joinToString(" · ")

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
    private const val MAX_RECENT = 5
    private const val MAX_FOUND = 40
    private const val MAX_PHOTOS = 12
    private const val FOCUS_BODY = 800
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
