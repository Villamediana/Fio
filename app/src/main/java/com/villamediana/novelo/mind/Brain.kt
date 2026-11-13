package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.data.Step
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

/** A list the model may also write as one value ("search": "currículo"): read as a list of it. */
object Strings : kotlinx.serialization.json.JsonTransformingSerializer<List<String>>(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement) = oneOrMany(element)
}

/** The same for ids ("show": 12). */
object Longs : kotlinx.serialization.json.JsonTransformingSerializer<List<Long>>(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<Long>())) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement) = oneOrMany(element)
}

/**
 * Which of the photos or files she sent are an item's: their numbers ("photos": [1, 3]); true is all
 * of them (0), false or nothing is none. Anything else in the list is left out.
 */
object Picks : kotlinx.serialization.json.JsonTransformingSerializer<List<Int>>(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<Int>())) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        fun one(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonPrimitive? {
            val p = e as? kotlinx.serialization.json.JsonPrimitive ?: return null
            if (p is kotlinx.serialization.json.JsonNull) return null
            if (!p.isString && (p.content == "true" || p.content == "false")) return if (p.content == "true") kotlinx.serialization.json.JsonPrimitive(ALL) else null
            return p.content.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { kotlinx.serialization.json.JsonPrimitive(it) }
        }
        val items = if (element is kotlinx.serialization.json.JsonArray) element.mapNotNull(::one) else listOfNotNull(one(element))
        return kotlinx.serialization.json.JsonArray(items.distinct())
    }

    /** "All of them" (true). */
    const val ALL = 0
}

private fun oneOrMany(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.filter { it !is kotlinx.serialization.json.JsonNull })
    is kotlinx.serialization.json.JsonNull -> kotlinx.serialization.json.JsonArray(emptyList())
    else -> kotlinx.serialization.json.JsonArray(listOf(e))
}

/** What the model decided to do with one thing said. */
@Serializable
data class Plan(
    val reply: String = "",
    val create: List<NewItem> = emptyList(),
    val update: List<Change> = emptyList(),
    /** The model needs to look something up before answering: words to search for… */
    @Serializable(with = Strings::class) val search: List<String> = emptyList(),
    /** …and/or a stretch of days to look at ("o que fiz ontem", "o que vence semana que vem"). */
    val period: Period? = null,
    /** Entries that answer a question, to show. */
    @Serializable(with = Longs::class) val show: List<Long> = emptyList(),
    /** Photos that answer a question (foto#N → N), to show. */
    @SerialName("show_photos") @Serializable(with = Longs::class) val showPhotos: List<Long> = emptyList(),
    /** What she recorded: its sound is worth keeping too (a melody she sings), not only the words. */
    @SerialName("keep_audio") val keepAudio: Boolean = false,
    /** The tasks' categories renamed, joined or taken off. */
    val categories: List<CategoryChange> = emptyList(),
) {
    val wantsLookup: Boolean get() = search.isNotEmpty() || period?.from != null || period?.to != null
    val isLookupOnly: Boolean get() = wantsLookup && create.isEmpty() && update.isEmpty() && show.isEmpty() && showPhotos.isEmpty()
}

@Serializable
data class Period(val from: String? = null, val to: String? = null)

/** A category [name] renamed to [to] (joined, when [to] is another that exists); no [to]: it goes, its tasks with none. */
@Serializable
data class CategoryChange(val name: String = "", val to: String? = null)

@Serializable
data class NewItem(
    val kind: String,
    val title: String,
    val body: String = "",
    @Serializable(with = Strings::class) val tags: List<String> = emptyList(),
    val entities: List<NamedTopic> = emptyList(),
    /** Tasks: the part of her life it belongs to. */
    val category: String? = null,
    val due: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    /** Its reminder comes back (the first time is [remindAt]). */
    val repeat: RepeatAsk? = null,
    @Serializable(with = Strings::class) val steps: List<String> = emptyList(),
    val status: String? = null,
    val sensitive: Boolean = false,
    @Serializable(with = Longs::class) val related: List<Long> = emptyList(),
    /** The photos she sent (their numbers) that stay with this item. */
    @Serializable(with = Picks::class) val photos: List<Int> = emptyList(),
    /** The files she sent (their numbers) that live in this item. */
    @Serializable(with = Picks::class) val files: List<Int> = emptyList(),
)

/** How a reminder comes back, as the model says it: every day, week (on [days], 1 = Monday) or month; "none" stops it. */
@Serializable
data class RepeatAsk(val every: String = "", val days: List<Int> = emptyList())

@Serializable
data class NamedTopic(val name: String, val type: String = "topic")

@Serializable
data class Change(
    val id: Long,
    val status: String? = null,
    @SerialName("steps_done") @Serializable(with = Strings::class) val stepsDone: List<String> = emptyList(),
    @SerialName("add_steps") @Serializable(with = Strings::class) val addSteps: List<String> = emptyList(),
    val title: String? = null,
    /** What is new about it ("falei com o fornecedor, manda amanhã"), joined to its text: an item is one text. */
    val append: String? = null,
    /** Its whole text again, when what she said changes what the item is. */
    val body: String? = null,
    /** Tasks: moved to this category. */
    val category: String? = null,
    val due: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    /** Its reminder comes back from now on ("none": not any more). */
    val repeat: RepeatAsk? = null,
    val entities: List<NamedTopic> = emptyList(),
    val delete: Boolean = false,
    /** The photos she sent (their numbers) that go to this item. */
    @Serializable(with = Picks::class) val photos: List<Int> = emptyList(),
    /** The files she sent (their numbers) that live in this item. */
    @Serializable(with = Picks::class) val files: List<Int> = emptyList(),
)

/**
 * A file she sent, as the model sees it: its name, type and size, and at most the beginning of its
 * first page ([peek], empty when there is none to read) — never the whole of it.
 */
data class FileSeen(val name: String, val mime: String?, val size: Long, val peek: String = "", val about: String? = null)

/** The last exchange: what she said (or that it was a photo), Novelo's answer, the items it made or changed ("criado"…). */
data class Before(val at: Long, val said: String, val reply: String, val photo: Boolean = false, val items: List<Pair<Entry, String>> = emptyList(), val file: String? = null)

/** The model's side of Novelo: the instructions, the context it sees, and reading what it answers. */
object Brain {
    val SYSTEM: String = """
Você é o Novelo, a memória externa de uma pessoa. Ela fala o que vem à cabeça, do jeito que sai (às vezes misturando português e espanhol), e você organiza pra ela não precisar guardar nada na cabeça.

Numa mesma fala pode haver várias coisas:
- tarefa (task): algo que ela precisa ou quer fazer.
- nota (note): algo pra guardar e achar depois — ideia, informação, dado, senha, referência, decisão.
- diário (diary): um relato do dia — o que viveu, fez, sentiu, o que está acontecendo com ela. Todo relato assim vai pro diário, mesmo quando também traz coisas a fazer ou a guardar, ou é notícia de algo que já existe: aí faça as duas coisas — o diário com o relato e, além dele, as tarefas/notas ou o "update" do item. O diário é um por dia: se há DIÁRIO DE HOJE, o relato entra nele em "update" com "append" — parágrafos novos no fim, sem mudar o que já está escrito nem o título; se não há, crie o diário do dia.

Ela também pode:
- dar notícia de algo que já existe (andou, terminou, travou, mudou o prazo, mais detalhes, mais coisas a fazer): atualize o item existente pelo id em "update", não crie outro igual a ele. Decida onde cada parte vai, e nada vai em dois lugares:
  · o que ainda precisa ser feito pra tarefa andar — um passo, uma ação dela ou de outra pessoa — é etapa: "add_steps", curta, começando pela ação;
  · etapa que ela já fez vai em "steps_done"; a tarefa inteira feita, "status": "done"; outra mudança de situação, só "status";
  · o que completa a tarefa sem ser algo a fazer (o que aconteceu, o que decidiu, um dado) vai em "append": só o que ainda não está no texto, escrito como continuação dele;
  · se o que ela diz muda o que a tarefa é, reescreva o texto inteiro em "body" (e o "title", se preciso).
  Se a notícia vem num relato do dia dela, o relato inteiro vai também pro diário.
- corrigir, completar ou desfazer o que acabou de falar, ou se referir ao que acabou de falar: o item está em ÚLTIMAS COISAS GUARDADAS e em ANTES DISSO (a última conversa); mexa nele pelo id em "update" (ou apague com "delete": true) em vez de criar outro. ANTES DISSO serve só pra entender a que ela se refere: o que está ali já foi guardado — nunca crie de novo nem leve de novo pro diário.
- perguntar ou pedir pra ver coisas (o que tem pra hoje, quando falou de algo, qual era uma senha, como está um projeto, uma foto de algo): responda em "reply" usando só o que está no contexto e ponha em "show" os ids dos itens que respondem e em "show_photos" os números das fotos (foto#N) que respondem. Pergunta não cria item.

Como preencher:
- Use as palavras dela. Título curto (até ~8 palavras) que diz do que se trata — nunca o tipo do item. Corpo com os detalhes ditos, sem inventar nada.
- Diário é dela: escreva em primeira pessoa, no idioma, no jeito e no tom em que ela falou, como se ela mesma tivesse escrito. Só tire repetições e hesitações; não reescreva no seu estilo nem resuma o que ela contou. Divida em parágrafos como ela dividiria escrevendo: cada vez que o relato passa pra outro assunto, outro momento ou outro lugar, um parágrafo novo (uma linha em branco entre eles, "\n\n").
- A fala chega transcrita por máquina, e a transcrição às vezes ouve uma palavra errado (um nome de comida, de lugar, de pessoa, uma marca, um termo de outra língua que vira uma palavra parecida ou sem sentido). Quando o resto da frase deixa claro qual era a palavra que ela disse, escreva a certa; quando não dá pra ter certeza, deixe como veio — nunca invente.
- Datas relativas (amanhã, sexta, daqui a duas semanas, às 3 da tarde) viram data absoluta a partir de AGORA, no formato AAAA-MM-DD ou AAAA-MM-DDTHH:MM. Sem data dita, null.
- remind_at: quando ela pede pra ser lembrada, ou quando algo tem hora marcada (lembre um pouco antes).
- repeat: quando o lembrete (e a tarefa) volta sempre — todo dia, em dias da semana, todo mês: {"every": "day" | "week" | "month", "days": [1-7, 1 = segunda, só com week]}; remind_at é a primeira vez, com a hora. Pra parar de repetir: {"every": "none"}.
- check_in_at: em tarefas que levam dias ou têm etapas, escolha um bom momento pra perguntar como está. Pode ser null.
- steps: as etapas, quando a tarefa tem mais de um passo. status: todo, doing, waiting ou done.
- sensitive: true para senhas, documentos pessoais e números deles, dados bancários e afins.
- entities: pessoas, lugares, projetos, empresas e assuntos citados, com nome curto e type (person, place, project, org, topic). Servem pra ligar esta lembrança às outras.
- category (só em tarefa): a área da vida dela a que a tarefa pertence (por exemplo Trabalho, Casa, Pessoal, Saúde). Use o nome de uma das CATEGORIAS DAS TAREFAS quando alguma servir; crie outra só quando nenhuma serve, com nome curto (uma ou duas palavras) e amplo, que sirva pra outras tarefas também — poucas categorias no total, no idioma dela. Pra mudar uma tarefa de categoria, "category" no "update".
- categories: quando ela pede pra renomear, juntar ou tirar uma categoria: [{"name": "a categoria de agora", "to": "o nome novo, ou o de outra categoria pra juntar as duas"}]; sem "to", a categoria deixa de existir e as tarefas dela ficam sem.
- related: ids de itens do contexto que tratam do mesmo assunto.
- A foto de um momento já fica no diário do dia por si: não escreva o que se vê nela no diário nem em outro item; ela só ganha a "reply". A foto de algo pra guardar ou fazer (um documento, um papel, um recibo, um aviso, um quadro) é desse item: crie ou mude o item com o que ela traz.
- No diário fica só o relato do dia. O que ela diz das fotos e dos arquivos que mandou (o que são, de quem, pra quê) vai pro item deles, nunca pro diário.
- O que ela fala e o que ela manda junto (AS FOTOS QUE ELA MANDOU JUNTO, OS ARQUIVOS QUE ELA MANDOU JUNTO) podem ser da mesma coisa ou de coisas sem nada a ver entre si. Decida cada um por si: o que ela fala vai pro que é; cada foto e cada arquivo vai pro item a que pertence — um que você cria pra ele, um que já existe, ou o mesmo de outra coisa que ela mandou ou falou, quando são da mesma coisa. Em "photos" e "files" desse item, os números das fotos e dos arquivos que são dele; o que não for de nenhum item (a foto de um momento, a lembrança do dia) fica de fora: o diário do dia já mostra.
- Das fotos e dos arquivos você vê o que o celular disse deles e o texto lido (dos arquivos, o nome e o tipo) — nunca a foto nem o arquivo inteiro. Um documento, ou qualquer arquivo que ela vá querer achar de novo, tem sempre o item dele (em geral uma nota, com um título que diz o que é), mesmo quando ela o manda contando o dia. Não copie o conteúdo do arquivo pros itens.
- keep_audio: true quando ela falou (não escreveu) e o som do que gravou é o que vale guardar, porque as palavras não dão conta dele — uma melodia que ela canta ou cantarola pra não esquecer, um som que quer mostrar. O áudio fica junto do que você criar ou mudar. Quando o texto basta, deixe de fora.
- Se, pra responder, faltar algo que não está no contexto, peça pra procurar e deixe o resto vazio: "search" com as palavras a procurar e/ou "period" com o intervalo de dias (from e to, AAAA-MM-DD) quando a pergunta é sobre um tempo — o que foi guardado, feito ou vence nesses dias. Você recebe os resultados e responde em seguida.
- reply: uma frase curta e calorosa, no idioma dela, dizendo o que ficou guardado ou respondendo a pergunta.
- Escreva só os campos que usar: vazio, null, false ou [] ficam de fora. A resposta fica curta.

Responda somente com um objeto JSON. Um exemplo do jeito (só o que foi usado):
{"reply": "...", "create": [{"kind": "task", "title": "...", "due": "AAAA-MM-DD"}], "update": [{"id": 12, "add_steps": ["..."]}]}
Os campos que existem:
- reply
- create: [{kind (task, note ou diary), title, body, tags, entities [{name, type}], category, due, remind_at, repeat, check_in_at, steps, status, sensitive, related, photos [números], files [números]}]
- update: [{id, status, steps_done, add_steps, title, append, body, category, due, remind_at, repeat, check_in_at, entities, delete, photos [números], files [números]}]
- categories: [{name, to}]
- search, period {from, to}, show, show_photos, keep_audio
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
        /** The last thing she said before this, and what came of it: what "isso", "essa tarefa" refer to. */
        before: Before? = null,
        /** Photos she sent together with what she said (in the image, in this order): what they read. */
        sent: List<Photo> = emptyList(),
        /** The files she sent: with what she said, or alone ([fileAlone]: nothing said, only the file). */
        files: List<FileSeen> = emptyList(),
        fileAlone: Boolean = false,
        /** What the photo (or the photos sent with it) shows, as the phone saw it. */
        seen: String? = null,
        /** The tasks' categories there are (the ones with the most open tasks first). */
        categories: List<String> = emptyList(),
    ): String = buildString {
        // What changes least comes first and what changes every time last (the moment, the item
        // being answered about, the words): a server that caches the start of the prompt, and the
        // phone that keeps its instructions run, reuse as much of it as can be.
        val today = diary?.takeIf { it.id != focus?.id }
        val shown = setOfNotNull(focus?.id, today?.id)
        val tasks = openTasks.filter { it.id !in shown }.take(MAX_TASKS)
        if (categories.isNotEmpty()) append("CATEGORIAS DAS TAREFAS: ").append(categories.joinToString(", ")).append("\n\n")
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
            last.forEach { e ->
                val whole = line(e, zone, now, long = true)
                // An open task listed above with nothing more to say (no text): only pointed to.
                append(if (tasks.any { it.id == e.id } && whole == line(e, zone, now)) "#" + e.id + " (acima)" else whole).append('\n')
            }
        }
        // What is already written above: further down it is only pointed to (#id), never repeated.
        val listed = HashSet<Long>(shown).apply { tasks.forEach { add(it.id) }; last.forEach { add(it.id) } }
        if (looked == null) {
            val rel = related.filter { r -> r.id !in listed }.take(MAX_RELATED)
            if (rel.isNotEmpty()) {
                append("\nLEMBRANÇAS QUE PODEM TER A VER:\n")
                rel.forEach { append(line(it, zone, now, long = true)).append('\n') }
            }
            rel.forEach { listed += it.id }
        } else {
            // What was asked for comes whole, even when it is already listed above.
            val found = related.take(MAX_FOUND)
            found.forEach { listed += it.id }
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
            append("\nELA ESTÁ RESPONDENDO SOBRE ESTE ITEM (o Novelo perguntou como está):\n")
            // Its whole text (up to a point): what is new gets joined to it, so it has to know what is there.
            append(line(focus, zone, now, long = true, bodyMax = FOCUS_BODY)).append('\n')
        }
        if (before != null) {
            append("\nANTES DISSO (a última conversa, ").append(stamp(before.at, zone)).append("):\n")
            append("ela: ").append(
                when {
                    before.file != null -> "(mandou o arquivo \"" + before.file.take(80) + "\")"
                    before.photo -> "(mandou uma foto)"
                    else -> "\"" + before.said.trim().take(BEFORE_SAID).replace('\n', ' ') + "\""
                },
            ).append('\n')
            if (before.reply.isNotBlank()) append("você: \"").append(before.reply.trim().take(BEFORE_REPLY)).append("\"\n")
            before.items.take(MAX_BEFORE_ITEMS).forEach { (e, what) ->
                append(what).append(": ").append(if (e.id in listed) "#" + e.id + " (acima)" else line(e, zone, now)).append('\n')
            }
        }
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val weekday = at.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
        append("\nAGORA: ").append(weekday).append(", ").append(at.format(STAMP)).append(" (").append(zone.id).append(")\n")
        val takenOn = takenAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }?.takeIf { it.toLocalDate() != at.toLocalDate() }
        if (sent.isNotEmpty()) {
            append("\nAS FOTOS QUE ELA MANDOU JUNTO (").append(sent.size).append("):\n")
            sent.forEachIndexed { i, p ->
                append(i + 1).append(". ").append(stamp(p.takenAt, zone))
                p.description?.let { append(" · o que se vê: ").append(it.take(SEEN).replace('\n', ' ')) }
                p.text?.let { append(" · texto: ").append(it.take(160).replace('\n', ' ')) }
                append('\n')
            }
        }
        seen?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nO QUE SE VÊ (o celular olhou):\n").append(it.take(SEEN)).append('\n') }
        if (fileAlone) {
            // Nothing said: the file is the whole of it.
            append("\nO ARQUIVO QUE ELA MANDOU (sem dizer nada; é o arquivo 1):\n")
            files.forEach { append(fileLine(it)).append('\n') }
            return@buildString
        }
        if (files.isNotEmpty()) {
            append("\nOS ARQUIVOS QUE ELA MANDOU JUNTO (").append(files.size).append("):\n")
            files.forEachIndexed { i, f -> append(i + 1).append(". ").append(fileLine(f)).append('\n') }
        }
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

    /** A file as the model sees it: name, type, size and the beginning of its first page (when there is one). */
    fun fileLine(f: FileSeen): String = buildString {
        append('"').append(f.name.take(120)).append('"')
        FileKind.label(f.name, f.mime)?.let { append(" · ").append(it) }
        if (f.size > 0) append(" · ").append(FileKind.size(f.size))
        // What it is, as the phone said; only when it said nothing, the start of it.
        val about = f.about?.trim()?.takeIf { it.isNotEmpty() }
        if (about != null) append(" · o que é: ").append(about.take(SEEN))
        else f.peek.trim().takeIf { it.isNotEmpty() }?.let { append("\n   começo da primeira página: \"").append(it.take(PEEK).replace('\n', ' ')).append('"') }
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
        e.category?.takeIf { e.kindEnum == Kind.TASK }?.let { append(" · categoria ").append(it) }
        e.dueAt?.let { append(" · prazo ").append(if (e.dueHasTime) stamp(it, zone) else day(it, zone)) }
        e.doneAt?.let { append(" · feito em ").append(day(it, zone)) }
        e.remindAt?.takeIf { it > now }?.let { append(" · lembrete ").append(stamp(it, zone)) }
        com.villamediana.novelo.remind.Repeat.read(e.repeat)?.let { r -> append(" · repete ").append(r.every).append(if (r.every == "week") r.days.joinToString(",", "[", "]") else "").append(' ').append(r.time) }
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
    private const val MAX_RELATED = 8
    private const val MAX_RECENT = 5
    private const val MAX_FOUND = 40
    private const val MAX_PHOTOS = 12
    private const val BEFORE_SAID = 400
    private const val BEFORE_REPLY = 200
    private const val MAX_BEFORE_ITEMS = 6
    private const val FOCUS_BODY = 800

    /** The most of a file's first page the model gets (a peek, never the document). */
    const val PEEK = 800

    /** The most of what the phone saw in a photo, or says a file is. */
    private const val SEEN = 400
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
