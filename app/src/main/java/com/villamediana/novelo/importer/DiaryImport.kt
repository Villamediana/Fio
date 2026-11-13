package com.villamediana.novelo.importer

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** What an import left in the diary: days new, days that already had a diary (told some more), and the span. */
data class Kept(val added: Int, val joined: Int, val from: Long, val to: Long) {
    val days: Int get() = added + joined
}

/**
 * Ajustes › Backup › Importar diário. A diary exported by another app (TXT, Markdown, JSON, CSV, or
 * a .zip of them): Omni, on the phone ([OmniRecipe]), reads a sample of it, recognizes the pattern
 * and writes the [Recipe]; the app applies it to the whole file and shows her what came out (the
 * pattern, how many entries, a few as they will be). She confirms, the entries go into the diary
 * (one a day: a day that already has one is told some more) and a backup goes right after.
 * It runs on the app's scope: the sheet can close while it goes.
 */
class DiaryImport(
    private val scope: CoroutineScope,
    /** Why Omni can't answer now, or null. */
    private val ready: () -> String?,
    /** The question to Omni: its answer (a recipe, held by the grammar). */
    private val ask: suspend (system: String, user: String) -> String,
    /** The entries into the diary. */
    private val keep: suspend (List<Found>) -> Kept,
    /** The backup right after (false: no folder, or it went wrong). */
    private val backup: suspend () -> Boolean,
    /** What went wrong with the backup, when it did. */
    private val backupProblem: suspend () -> String? = { null },
    private val language: () -> String = { "Português" },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    sealed interface State {
        data object Idle : State
        data class Opening(val name: String) : State
        data class Recognizing(val name: String) : State
        /** The pattern recognized and applied: waiting for her. */
        data class Ready(val name: String, val recipe: Recipe, val reading: Reading) : State
        data class Failed(val name: String?, val message: String, val canRetry: Boolean) : State
        data class Saving(val count: Int, val backingUp: Boolean = false) : State
        /** [backup]: true done, false not (see [problem]), null: no folder. */
        data class Done(val kept: Kept, val backup: Boolean?, val problem: String? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var name: String = ""
    private var files: List<TextFile> = emptyList()
    private var last: Pair<Recipe, Reading>? = null

    /** A file picked: opened, then its pattern recognized. [read] gives its bytes (on IO). */
    fun open(name: String, read: () -> InputStream?) {
        if (_state.value is State.Saving) return
        job?.cancel()
        this.name = name
        files = emptyList()
        last = null
        job = scope.launch {
            _state.value = State.Opening(name)
            val opened = try {
                withContext(Dispatchers.IO) { Unpack.files(name, read() ?: throw IOException(t(R.string.import_not_text))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooBig) {
                _state.value = State.Failed(name, t(R.string.import_too_big), canRetry = false)
                return@launch
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: t(R.string.import_not_text), canRetry = false)
                return@launch
            }
            if (opened.isEmpty()) {
                _state.value = State.Failed(name, t(R.string.import_not_text), canRetry = false)
                return@launch
            }
            files = opened
            recognize()
        }
    }

    /** Recognized again: the model sees what its recipe gave (or that the answer didn't come) and fixes it. */
    fun again() {
        if (files.isEmpty() || _state.value is State.Saving || _state.value is State.Recognizing) return
        job?.cancel()
        job = scope.launch { recognize() }
    }

    /** She confirmed: the entries go in, then the backup. */
    fun confirm() {
        val ready = _state.value as? State.Ready ?: return
        if (ready.reading.found.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            _state.value = State.Saving(ready.reading.found.size)
            val kept = try {
                keep(ready.reading.found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: e.javaClass.simpleName, canRetry = false)
                return@launch
            }
            _state.value = State.Saving(ready.reading.found.size, backingUp = true)
            val went = runCatching { backup() }.getOrDefault(false)
            val problem = if (went) null else backupProblem()
            _state.value = State.Done(kept, if (went) true else if (problem == null) null else false, problem)
            files = emptyList()
            last = null
        }
    }

    /** Back to nothing (the sheet closed on a result, or she gave up). An import being saved goes on. */
    fun close() {
        if (_state.value is State.Saving) return
        job?.cancel()
        files = emptyList()
        last = null
        _state.value = State.Idle
    }

    private suspend fun recognize() {
        why()?.let {
            _state.value = State.Failed(name, it, canRetry = true)
            return
        }
        _state.value = State.Recognizing(name)
        val user = buildString {
            append(Sample.of(name, files))
            last?.let { (r, reading) ->
                append("\n\nUMA RECEITA ANTERIOR NÃO SERVIU:\n").append(Recipes.write(r))
                append("\nO QUE ELA DEU: ").append(Sample.result(reading, zone()))
                append("\nVeja de novo a amostra e escreva uma receita que pegue cada entrada com a data, o título (se houver) e o texto.")
            }
        }
        val recipe = try {
            Recipes.parse(ask(system(language()), user))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(name, e.message ?: t(R.string.import_bad_answer), canRetry = true)
            return
        }
        if (recipe == null) {
            _state.value = State.Failed(name, t(R.string.import_bad_answer), canRetry = true)
            return
        }
        val reading = withContext(Dispatchers.Default) { Recipes.apply(recipe, files, zone()) }
        last = recipe to reading
        _state.value = State.Ready(name, recipe, reading)
    }

    private fun why(): String? = runCatching { ready() }.getOrNull()

    companion object {
        /** What the model is asked: the recipe's fields, in the app's words. */
        fun system(language: String): String = """
Você reconhece o formato de diários exportados de outros apps (Day One, Journey, Diarium, Daylio, Penzu, Notion, Obsidian, um TXT ou Markdown escrito à mão…) pra que o Novelo importe cada entrada no diário dele.

Você recebe só uma amostra do arquivo (o começo e, quando é grande, um pedaço do meio; num .zip, a lista dos arquivos e o começo de alguns). Descubra o padrão e responda com uma receita de importação: o Novelo aplica a receita no arquivo inteiro, entrada por entrada. Cada entrada precisa de uma data; o título é opcional; o texto é o que a pessoa escreveu.

"shape" diz a forma:
- "json": "entries" = caminho até a lista de entradas (chaves separadas por ponto; "" = a raiz; uma raiz que é uma entrada só também é ""). Dentro de cada entrada, "date", "title", "body" e "tags" são caminhos (ex.: "creationDate", "text", "location.placeName"). Um campo que é lista de textos vira um texto só.
- "text" (TXT, Markdown): "start" = expressão regular (Java) da linha que começa uma entrada; nela, (?<date>…) pega a data e (?<title>…) o título, quando estão nessa linha. A linha do "start" não entra no texto. Sem "start", cada arquivo inteiro é uma entrada. "date_regex" e "title_regex": quando a data ou o título estão numa linha de cabeçalho dentro da entrada, com (?<date>…) ou (?<title>…); o trecho casado sai do texto.
- "csv": "delimiter" ("," ";" ou "\t"), "header" (true quando a primeira linha tem os nomes das colunas), e "date", "title", "body" e "tags" = o nome da coluna (ou o número, a partir de 0).
Em qualquer forma:
- "date_format": "iso" (2024-01-15, 2024-01-15T10:30:00Z…), "epoch_s", "epoch_ms" ou um padrão do DateTimeFormatter do Java ("dd/MM/yyyy", "EEEE, d 'de' MMMM 'de' yyyy", "MMMM d, yyyy h:mm a"). "date_locale": o idioma dos nomes de mês e de dia ("pt-BR", "en", "es").
- "date_from_name": regex com (?<date>…) que acha a data no nome do arquivo, quando cada arquivo é uma entrada e a data não está dentro dele.
- "files": num .zip com outros arquivos além das entradas, regex do nome dos que são entradas.
- "first_line_title": true quando a primeira linha do texto é o título.
- "html": true quando o texto vem em HTML.
- "clean": lista de regex do que tirar do texto e não é dele (ex.: referências de imagem como "!\\[\\]\\(dayone-moment:[^)]*\\)"), só quando houver.
- "app": o nome do app ou do formato que você reconheceu, curto. "about": uma ou duas frases, pra pessoa, dizendo o padrão que você viu (onde ficam a data, o título e o texto). Escreva "app" e "about" em $language.

Escreva só os campos que usar. Exemplo do jeito:
{"app": "Day One", "about": "…", "shape": "json", "entries": "entries", "date": "creationDate", "body": "text", "tags": "tags", "date_format": "iso"}
""".trim()
    }
}

/** A file over [Unpack.MAX_BYTES]. */
class TooBig : IOException()

/** The bytes she picked → the text files in them (a .zip opened; photos and the like left out). */
object Unpack {
    const val MAX_BYTES = 40L * 1024 * 1024

    fun files(name: String, input: InputStream): List<TextFile> {
        val bytes = input.use { capped(it) }
        return if (isZip(bytes)) unzip(bytes) else listOfNotNull(decode(bytes)?.let { TextFile(name, it) })
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun unzip(bytes: ByteArray): List<TextFile> {
        val out = ArrayList<TextFile>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val path = e.name
                val base = path.substringAfterLast('/')
                if (e.isDirectory || path.startsWith("__MACOSX") || base.startsWith(".")) continue
                val data = capped(zip)
                total += data.size
                if (total > MAX_BYTES) throw TooBig()
                decode(data)?.let { out += TextFile(path, it) }
            }
        }
        return out.sortedBy { it.name }
    }

    private fun capped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw TooBig()
        }
        return out.toByteArray()
    }

    /** Bytes → text, when they are text: UTF-8 (or UTF-16 with its mark; old Windows text as cp1252). Null: not text. */
    fun decode(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val text = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> {
                val head = b.copyOf(minOf(b.size, 8192))
                if (head.any { it == 0.toByte() }) return null
                val utf8 = String(b, Charsets.UTF_8)
                if (utf8.contains('�')) String(b, Charset.forName("windows-1252")) else utf8
            }
        }
        // Control characters other than tabs and line breaks: a binary file that happened to decode.
        val odd = text.take(4096).count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        return if (odd > 8) null else text
    }
}

/** What the model sees of the file: enough to tell the pattern, never the whole diary. */
object Sample {
    // Omni reads it on the phone: enough to tell the pattern, little enough to answer soon.
    const val HEAD = 3500
    const val MIDDLE = 1500
    const val PER_FILE = 1500
    const val NAMES = 30

    fun of(name: String, files: List<TextFile>): String = buildString {
        if (files.size == 1) {
            val f = files.first()
            append("ARQUIVO: ").append(name).append(" (").append(kb(f.text)).append(", ").append(f.text.lines().size).append(" linhas)\n\n")
            append("COMEÇO DO ARQUIVO:\n").append(head(f.text, HEAD))
            if (f.text.length > HEAD + MIDDLE * 2) {
                append("\n\nUM PEDAÇO DO MEIO:\n").append(middle(f.text, MIDDLE))
            }
        } else {
            append("UM .ZIP (").append(name).append(") COM ").append(files.size).append(" ARQUIVOS DE TEXTO:\n")
            files.take(NAMES).forEach { append(it.name).append(" (").append(kb(it.text)).append(")\n") }
            if (files.size > NAMES) append("… e mais ").append(files.size - NAMES).append("\n")
            val shown = listOf(0, files.size / 2, files.size - 1).distinct().map { files[it] }
            shown.forEach { f -> append("\n--- ").append(f.name).append(" (começo) ---\n").append(head(f.text, PER_FILE)).append('\n') }
        }
    }

    /** What a recipe gave, for the model to fix it. */
    fun result(r: Reading, zone: ZoneId): String = buildString {
        append(r.found.size).append(" entradas com data")
        if (r.undated > 0) append(", ").append(r.undated).append(" sem data")
        if (r.unread > 0) append(", ").append(r.unread).append(" arquivos que não abriram")
        if (r.found.isNotEmpty()) {
            append(". Primeiras:")
            r.found.take(3).forEach { e ->
                append("\n- ").append(java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDateTime())
                if (e.title.isNotEmpty()) append(" · ").append(e.title)
                append(" · ").append(e.body.take(160).replace('\n', ' '))
            }
        }
    }

    private fun kb(s: String): String = "${(s.length + 1023) / 1024} KB"

    /** The start, cut at a line when there is one near the end. */
    fun head(s: String, n: Int): String {
        if (s.length <= n) return s
        val cut = s.lastIndexOf('\n', n).takeIf { it > n / 2 } ?: n
        return s.substring(0, cut) + "\n[…]"
    }

    private fun middle(s: String, n: Int): String {
        val from = s.indexOf('\n', s.length / 2).takeIf { it in 0 until s.length - 1 }?.plus(1) ?: (s.length / 2)
        return head(s.substring(from), n)
    }
}
