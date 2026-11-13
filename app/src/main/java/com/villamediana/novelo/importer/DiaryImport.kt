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

/**
 * What an import left: days new in the diary, days that already had one (told some more), notes
 * and photos new, and the span (0 when nothing had a date).
 */
data class Kept(val added: Int, val joined: Int, val from: Long, val to: Long, val notes: Int = 0, val photos: Int = 0) {
    val days: Int get() = added + joined
}

/**
 * Ajustes › Backup › Importar diário. A diary exported by another app (TXT, Markdown, JSON, CSV, or
 * a .zip of them, with its folders): Omni, on the phone ([OmniRecipe]), reads a sample of it (in a
 * .zip, how its folders are made too), recognizes the pattern and writes the [Recipe] — which files
 * are the diary, which are notes, which are photos; the app applies it to the whole file and shows
 * her what came out (the pattern, how many entries, notes and photos, a few as they will be). She
 * confirms, the entries go into the diary (one a day: a day that already has one is told some more),
 * the notes in as notes, the photos into the gallery on their days, and a backup goes right after.
 * It runs on the app's scope: the sheet can close while it goes.
 */
class DiaryImport(
    private val scope: CoroutineScope,
    /** Why Omni can't answer now, or null. */
    private val ready: () -> String?,
    /** The question to Omni: its answer (a recipe, held by the grammar to [shapes], the ones that can be). */
    private val ask: suspend (system: String, user: String, shapes: Set<String>) -> String,
    /** What came out into Novelo: the entries into the diary, the notes, the photos. */
    private val keep: suspend (Reading) -> Kept,
    /** The backup right after (false: no folder, or it went wrong). */
    private val backup: suspend () -> Boolean,
    /** What went wrong with the backup, when it did. */
    private val backupProblem: suspend () -> String? = { null },
    private val language: () -> String = { "Português" },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Where the photos of a .zip wait until she imports (emptied when the import ends or is let go). */
    private val stage: () -> java.io.File? = { null },
) {
    sealed interface State {
        data object Idle : State
        data class Opening(val name: String) : State
        /** [attempt] > 1: the pattern before got nothing out, Omni looks again on its own. */
        data class Recognizing(val name: String, val attempt: Int = 1) : State
        /** The pattern recognized and applied: waiting for her. */
        data class Ready(val name: String, val recipe: Recipe, val reading: Reading) : State
        data class Failed(val name: String?, val message: String, val canRetry: Boolean) : State
        /** [count] entries, [notes] and [photos] going in (then the backup). */
        data class Saving(val count: Int, val backingUp: Boolean = false, val notes: Int = 0, val photos: Int = 0) : State
        /** [backup]: true done, false not (see [problem]), null: no folder. */
        data class Done(val kept: Kept, val backup: Boolean?, val problem: String? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var name: String = ""
    private var files: Unpacked = Unpacked(emptyList())
    private var last: Pair<Recipe, Reading>? = null

    /** A file picked: opened, then its pattern recognized. [read] gives its bytes (on IO). */
    fun open(name: String, read: () -> InputStream?) =
        start(name) { stage -> Unpack.files(name, read() ?: throw IOException(t(R.string.import_not_text)), stage) }

    /** A folder picked: every file in it and in its folders ([list], on IO), then its pattern recognized. */
    fun openFolder(name: String, list: () -> List<Unpack.Piece>) =
        start(name, folder = true) { stage -> Unpack.pieces(list(), stage) }

    private var folder = false

    private fun start(name: String, folder: Boolean = false, unpack: (java.io.File?) -> Unpacked) {
        if (_state.value is State.Saving) return
        job?.cancel()
        this.name = name
        this.folder = folder
        files = Unpacked(emptyList())
        last = null
        job = scope.launch {
            _state.value = State.Opening(name)
            val opened = try {
                withContext(Dispatchers.IO) {
                    clearStage()
                    unpack(stage())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooBig) {
                _state.value = State.Failed(name, t(R.string.import_too_big), canRetry = false)
                return@launch
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: t(R.string.import_not_text), canRetry = false)
                return@launch
            }
            if (opened.texts.isEmpty() && opened.photos == 0) {
                val inside = opened.others.groupingBy { it.name.substringAfterLast('/').substringAfterLast('.', "?").lowercase() }.eachCount()
                    .entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.value} .${it.key}" }
                _state.value = State.Failed(name, if (inside.isEmpty()) t(R.string.import_not_text) else t(R.string.import_nothing_inside, inside), canRetry = false)
                return@launch
            }
            files = opened
            recognize()
        }
    }

    /** Recognized again: the model sees what its recipe gave (or that the answer didn't come) and fixes it. */
    fun again() {
        if (files.texts.isEmpty() && files.photos == 0 || _state.value is State.Saving || _state.value is State.Recognizing) return
        job?.cancel()
        job = scope.launch { recognize() }
    }

    /** She confirmed: the entries go in, then the backup. */
    fun confirm() {
        val ready = _state.value as? State.Ready ?: return
        if (ready.reading.isEmpty) return
        job?.cancel()
        job = scope.launch {
            val going = State.Saving(ready.reading.found.size, notes = ready.reading.notes.size, photos = ready.reading.photos.size)
            _state.value = going
            val kept = try {
                withContext(Dispatchers.IO) { keep(ready.reading) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: e.javaClass.simpleName, canRetry = false)
                return@launch
            }
            withContext(Dispatchers.IO) { clearStage() }
            _state.value = going.copy(backingUp = true)
            val went = runCatching { backup() }.getOrDefault(false)
            val problem = if (went) null else backupProblem()
            _state.value = State.Done(kept, if (went) true else if (problem == null) null else false, problem)
            files = Unpacked(emptyList())
            last = null
        }
    }

    /** Back to nothing (the sheet closed on a result, or she gave up). An import being saved goes on. */
    fun close() {
        if (_state.value is State.Saving) return
        job?.cancel()
        files = Unpacked(emptyList())
        last = null
        _state.value = State.Idle
        scope.launch(Dispatchers.IO) { clearStage() }
    }

    /** The photos set aside by an import that ended or was let go. */
    private fun clearStage() {
        stage()?.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Omni reads the sample and writes the recipe; applied to everything. When nothing came out (or
     * the answer couldn't run), it is shown what it wrote and what that gave, and looks again — up to
     * [TRIES] times on its own — before she sees it.
     */
    private suspend fun recognize() {
        why()?.let {
            _state.value = State.Failed(name, it, canRetry = true)
            return
        }
        val shapes = Sample.shapes(files.texts)
        var unusable: String? = null
        for (attempt in 1..TRIES) {
            _state.value = State.Recognizing(name, attempt)
            val user = buildString {
                append(Sample.of(name, files.texts, files.others, folder))
                val tried = last
                if (unusable != null) {
                    append("\n\nA RESPOSTA ANTERIOR NÃO RODOU (uma regex que não compila, ou um date_format que não existe):\n").append(unusable)
                } else if (tried != null) {
                    append("\n\nUMA RECEITA ANTERIOR NÃO SERVIU:\n").append(Recipes.write(tried.first))
                    append("\nO QUE ELA DEU: ").append(Sample.result(tried.second, zone(), files.photos))
                }
                if (unusable != null || tried != null) {
                    append("\nVeja de novo a amostra e escreva uma receita que pegue cada entrada com a data, o título (se houver) e o texto")
                    if (files.others.isNotEmpty()) append(", e as notas e as fotos que houver")
                    append('.')
                }
            }
            val answer = try {
                ask(system(language()), user, shapes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: t(R.string.import_bad_answer), canRetry = true)
                return
            }
            val recipe = Recipes.parse(answer)?.takeIf { it.shape in shapes }
            if (recipe == null) {
                unusable = answer.take(1500)
                if (attempt < TRIES) continue
                _state.value = State.Failed(name, t(R.string.import_bad_answer), canRetry = true)
                return
            }
            unusable = null
            val reading = withContext(Dispatchers.Default) { Recipes.apply(recipe, files.texts, zone(), files.others) }
            last = recipe to reading
            if (reading.isEmpty && attempt < TRIES) continue
            _state.value = State.Ready(name, recipe, reading)
            return
        }
    }

    private fun why(): String? = runCatching { ready() }.getOrNull()

    companion object {
        /** How many times Omni looks on its own when its pattern gets nothing out. */
        const val TRIES = 3

        /** What the model is asked: the recipe's fields, in the app's words. */
        fun system(language: String): String = """
Você reconhece o formato de diários exportados de outros apps (Day One, Journey, Diarium, Daylio, Penzu, Notion, Obsidian, um TXT ou Markdown escrito à mão…) pra que o Novelo importe cada entrada no diário dele.

Você recebe só uma amostra do arquivo (o começo e, quando é grande, um pedaço do meio; num .zip ou numa pasta, a árvore — as pastas feitas do mesmo jeito, como uma por dia, contadas juntas com o que cada uma tem dentro — e o começo de um texto de cada tipo, com o formato dele: HTML, JSON, CSV ou texto). Descubra o padrão e responda com uma receita de importação: o Novelo aplica a receita no arquivo inteiro, entrada por entrada. Cada entrada precisa de uma data; o título é opcional; o texto é o que a pessoa escreveu.

Num .zip ou numa pasta, veja também como as pastas se organizam: além das entradas do diário pode haver notas (textos que não são de um dia) e fotos, e às vezes o mesmo diário em mais de um arquivo (um texto com tudo e uma pasta por dia, por exemplo) — escolha um jeito só, pra nada entrar duas vezes. Os caminhos das regex são os da lista, com as pastas.

"shape" diz a forma, pelo formato dos textos que são as entradas:
- "json" (só quando eles são JSON): "entries" = caminho até a lista de entradas (chaves separadas por ponto; "" = a raiz; uma raiz que é uma entrada só também é ""). Dentro de cada entrada, "date", "title", "body" e "tags" são caminhos (ex.: "creationDate", "text", "location.placeName"). Um campo que é lista de textos vira um texto só.
- "text" (texto, Markdown, ou HTML com "html": true): "start" = expressão regular (Java) da linha que começa uma entrada; nela, (?<date>…) pega a data e (?<title>…) o título, quando estão nessa linha. A linha do "start" não entra no texto. Sem "start", cada arquivo inteiro é uma entrada. "date_regex" e "title_regex": quando a data ou o título estão numa linha de cabeçalho dentro da entrada, com (?<date>…) ou (?<title>…); o trecho casado sai do texto.
- "csv" (só quando eles são CSV): "delimiter" ("," ";" ou "\t"), "header" (true quando a primeira linha tem os nomes das colunas), e "date", "title", "body" e "tags" = o nome da coluna (ou o número, a partir de 0).
Em qualquer forma:
- "date_format": "iso" (2024-01-15, 2024-01-15T10:30:00Z…), "epoch_s", "epoch_ms" ou um padrão do DateTimeFormatter do Java ("dd/MM/yyyy", "EEEE, d 'de' MMMM 'de' yyyy", "MMMM d, yyyy h:mm a"). "date_locale": o idioma dos nomes de mês e de dia ("pt-BR", "en", "es").
- "date_from_name": regex com (?<date>…) que acha a data no caminho do arquivo (nas pastas ou no nome), quando cada arquivo é uma entrada e a data não está dentro dele; com mais de uma data no caminho, vale a mais perto do arquivo.
- "files": num .zip ou numa pasta com outros arquivos além das entradas, regex do caminho dos que são entradas do diário.
- "notes": num .zip ou numa pasta, regex do caminho dos arquivos que são notas (cada arquivo, uma nota). "note_title": "name" quando o nome do arquivo é o título da nota, "first_line" quando é a primeira linha.
- "photos": num .zip ou numa pasta, regex do caminho das fotos da pessoa (não miniaturas, ícones nem figurinhas do app). Cada foto vai pra galeria do Novelo no dia dela: "photo_date_from_name" (regex com (?<date>…)) e "photo_date_format" acham a data no caminho da foto — a pasta do dia em que ela está, ou o nome dela — e valem antes da data que a câmera escreveu dentro; sem eles, vale a de dentro.
- "first_line_title": true quando a primeira linha do texto é o título.
- "html": true quando o texto vem em HTML.
- "clean": lista de regex do que tirar do texto e não é dele (ex.: referências de imagem como "!\\[\\]\\(dayone-moment:[^)]*\\)"), só quando houver.
- "app": o nome do app ou do formato que você reconheceu, curto. "about": uma ou duas frases, pra pessoa, dizendo o padrão que você viu (onde ficam a data, o título e o texto). Escreva "app" e "about" em $language.

Escreva só os campos que usar. Os exemplos mostram só o jeito de escrever: o app, os caminhos e as regex saem do que você vê na amostra, nunca dos exemplos.
{"app": "Day One", "about": "…", "shape": "json", "entries": "entries", "date": "creationDate", "body": "text", "tags": "tags", "date_format": "iso", "photos": "^photos/"}
{"app": "Obsidian", "about": "…", "shape": "text", "files": "^Diário/", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", "notes": "^Notas/", "note_title": "name", "photos": "^Anexos/.*\\.(jpe?g|png)$", "photo_date_from_name": "(?<date>\\d{8})", "photo_date_format": "yyyyMMdd"}
""".trim()
    }
}

/** A file over [Unpack.MAX_BYTES]. */
class TooBig : IOException()

/** What she picked, opened: the text files, and (in a .zip or a folder) everything else — photos set aside. */
data class Unpacked(val texts: List<TextFile>, val others: List<OtherFile> = emptyList()) {
    val photos: Int get() = others.count { it.isPhoto }
}

/**
 * What she picked → the files in it. A .zip is copied aside and read by its index (the list at its
 * end: a .zip written as it streams, without the sizes before each file, reads as well), a .zip
 * inside it too; a folder, file by file with its folders. The text files are read (up to
 * [MAX_BYTES] of text in all), the photos copied aside to the stage (named by their content, so the
 * same photo is one file) with the date the camera wrote inside, anything else listed only by name
 * and size.
 */
object Unpack {
    const val MAX_BYTES = 40L * 1024 * 1024
    private const val PEEK = 8192
    val PHOTOS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")

    /** A file in what she picked: its path inside (with its folders), its own date when known, and how to read it. */
    class Piece(val path: String, val time: Long? = null, val open: () -> InputStream)

    /** What was read so far: the texts, the rest, and how much text. */
    private class Taken {
        val texts = ArrayList<TextFile>()
        val others = ArrayList<OtherFile>()
        var text = 0L
        fun done() = Unpacked(texts.sortedBy { it.name }, others.sortedBy { it.name })
    }

    /** One file picked: a .zip (by its first bytes) or a text. */
    fun files(name: String, input: InputStream, stage: java.io.File? = null): Unpacked {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        return buffered.use { inp ->
            inp.mark(4)
            val magic = ByteArray(4)
            val n = inp.readNBytesCompat(magic)
            inp.reset()
            if (n == 4 && isZip(magic)) {
                val taken = Taken()
                zip(inp, "", stage, taken)
                taken.done()
            } else {
                Unpacked(listOfNotNull(decode(capped(inp))?.let { TextFile(name, it) }))
            }
        }
    }

    /** A folder picked: each of its files ([pieces], with their paths). */
    fun pieces(pieces: List<Piece>, stage: java.io.File? = null): Unpacked {
        val taken = Taken()
        pieces.forEach { take(it, stage, taken) }
        return taken.done()
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    /** A .zip ([input]): copied aside, then each file by its index; [prefix]: the path of the .zip it was inside. */
    private fun zip(input: InputStream, prefix: String, stage: java.io.File?, taken: Taken) {
        stage?.mkdirs()
        val copy = java.io.File.createTempFile("import", ".zip", stage)
        try {
            copy.outputStream().use { input.copyTo(it, 64 * 1024) }
            java.util.zip.ZipFile(copy).use { z ->
                for (e in z.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }) {
                    take(Piece(prefix + e.name, e.time.takeIf { it > 0 }) { z.getInputStream(e) }, stage, taken)
                }
            }
        } finally {
            copy.delete()
        }
    }

    private fun take(p: Piece, stage: java.io.File?, taken: Taken) {
        val path = p.path.trimStart('/')
        val base = path.substringAfterLast('/')
        if (path.startsWith("__MACOSX") || "/__MACOSX/" in path || base.startsWith(".")) return
        val ext = base.substringAfterLast('.', "").lowercase()
        p.open().use { input ->
            when {
                ext in PHOTOS && stage != null -> taken.others += photo(input, path, stage)
                ext == "zip" -> zip(input, "$path/", stage, taken)
                else -> {
                    val head = ByteArray(PEEK)
                    val got = input.readNBytesCompat(head)
                    val start = head.copyOf(got)
                    if (looksBinary(start)) {
                        taken.others += OtherFile(path, got + drain(input))
                        return
                    }
                    val data = java.io.ByteArrayOutputStream().apply { write(start) }
                    copyCapped(input, data, MAX_BYTES - taken.text - got)
                    taken.text += data.size()
                    if (taken.text > MAX_BYTES) throw TooBig()
                    val bytes = data.toByteArray()
                    decode(bytes)?.let { taken.texts += TextFile(path, it, p.time) } ?: run { taken.others += OtherFile(path, bytes.size.toLong()) }
                }
            }
        }
    }

    /** A photo copied aside, named by what's in it; the date the camera wrote inside, when it did. */
    private fun photo(input: InputStream, path: String, stage: java.io.File): OtherFile {
        stage.mkdirs()
        val tmp = java.io.File.createTempFile("photo", ".part", stage)
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                size += n
            }
        }
        val ext = path.substringAfterLast('.').lowercase()
        val file = java.io.File(stage, digest.digest().joinToString("") { "%02x".format(it) } + "." + ext)
        if (file.exists()) tmp.delete() else if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        val exif = runCatching { androidx.exifinterface.media.ExifInterface(file) }.getOrNull()
        val taken = exif?.let { x ->
            listOf(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                .firstNotNullOfOrNull { x.getAttribute(it)?.trim()?.takeIf { v -> v.isNotEmpty() && !v.startsWith("0000") } }
        }
        val offset = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        return OtherFile(path, size, file, taken, offset)
    }

    /** NUL bytes at the start, and no UTF-16 mark: not text. */
    private fun looksBinary(b: ByteArray): Boolean {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) return false
        return b.any { it == 0.toByte() }
    }

    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = read(into, n, into.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun drain(input: InputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var n: Int
        while (input.read(buf).also { n = it } > 0) total += n
        return total
    }

    private fun copyCapped(input: InputStream, out: java.io.ByteArrayOutputStream, room: Long) {
        val buf = ByteArray(64 * 1024)
        var n: Int
        var left = room
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
            left -= n
            if (left < 0) throw TooBig()
        }
    }

    private fun capped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyCapped(input, out, MAX_BYTES)
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
    const val PER_FILE = 1300
    const val SHOWN = 5
    /** All the texts shown of a .zip or a folder together. */
    const val TEXTS = 4000

    /**
     * [texts] and, in a .zip or a [folder], [others] (photos and the rest): one file is shown as it
     * is; many as their tree — folders made alike (one a day, say) told together, with what each
     * has inside — then the start of one text of each kind, with its format.
     */
    fun of(name: String, texts: List<TextFile>, others: List<OtherFile> = emptyList(), folder: Boolean = false): String = buildString {
        if (texts.size == 1 && others.isEmpty()) {
            val f = texts.first()
            append("ARQUIVO: ").append(name).append(" (").append(formatOf(f)).append(", ").append(kb(f.text)).append(", ").append(f.text.lines().size).append(" linhas)\n\n")
            append("COMEÇO DO ARQUIVO:\n").append(head(f.text, HEAD))
            if (f.text.length > HEAD + MIDDLE * 2) {
                append("\n\nUM PEDAÇO DO MEIO:\n").append(middle(f.text, MIDDLE))
            }
            return@buildString
        }
        val items = texts.map { Item(it.name, formatOf(it)) } + others.map { Item(it.name, if (it.isPhoto) PHOTO else OTHER, it.taken != null) }
        append(if (folder) "UMA PASTA (" else "UM .ZIP (").append(name).append(") COM ").append(items.size).append(" ARQUIVOS:\n")
        val lines = ArrayList<String>()
        tree("", items, lines)
        lines.take(LINES).forEach { append(it).append('\n') }
        if (lines.size > LINES) append("… e mais ").append(lines.size - LINES).append(" linhas\n")
        val formats = texts.groupingBy { formatOf(it) }.eachCount()
        append("Os textos: ").append(formats.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" })
        if (JSON !in formats) append(" — nenhum é JSON")
        if (CSV !in formats) append(", nenhum é CSV")
        append(".\n")
        // The start of one text of each kind (alike folders, alike name), the most common kinds first, while there is room.
        val kinds = texts.groupBy { kindOf(it.name) }.values.sortedWith(compareByDescending<List<TextFile>> { it.size }.thenBy { it.first().name })
        var room = TEXTS
        for (kind in kinds.take(SHOWN)) {
            if (room < 300) break
            val f = kind.first()
            val shown = head(f.text, minOf(PER_FILE, room))
            room -= shown.length
            append("\n--- ").append(f.name).append(" (").append(formatOf(f)).append(", ").append(kb(f.text))
            append(if (kind.size > 1) ", começo; há ${kind.size} assim" else ", começo").append(") ---\n").append(shown).append('\n')
        }
    }

    /** A file in the tree: its path, what it is (a text's format, [PHOTO] or [OTHER]), and for a photo whether it has its date inside. */
    private class Item(val path: String, val kind: String, val dated: Boolean = false) {
        val base: String get() = path.substringAfterLast('/')
    }

    /**
     * The lines of the tree under [dir]: its files (alike names together), then its folders —
     * three or more made alike told as one, with what is inside each; the others, each in turn.
     */
    private fun tree(dir: String, items: List<Item>, lines: MutableList<String>) {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val (here, below) = items.partition { '/' !in it.path.removePrefix(prefix) }
        here.groupBy { shape(it.base) + "|" + it.kind }.values.sortedBy { it.first().path }.forEach { g ->
            lines += if (g.size == 1) "$prefix${g[0].base} (${one(g[0].kind)})"
            else "${prefix.ifEmpty { "(raiz)" }} ${count(g)}: " + g.map { it.base }.sorted().let { n -> n.take(3).joinToString(", ") + if (n.size > 3) ", …" else "" }
        }
        val subs = below.groupBy { it.path.removePrefix(prefix).substringBefore('/') }
        subs.entries.groupBy { folderKey(it.key) }.values.sortedBy { it.first().key }.forEach { alike ->
            if (alike.size >= ALIKE) {
                val names = alike.map { it.key }.sorted()
                lines += "${prefix.ifEmpty { "(raiz)" }} tem ${alike.size} pastas assim (" + names.take(3).joinToString(", ") { "$it/" } + (if (names.size > 3) ", …" else "") + "); dentro de cada uma:"
                val inside = alike.flatMap { (child, list) -> list.map { child to it } }
                inside.groupBy { (child, it) -> kindOf(it.path.removePrefix("$prefix$child/")) + "|" + it.kind }.values
                    .sortedByDescending { it.size }.take(INSIDE).forEach { g ->
                        val rels = g.map { (child, it) -> it.path.removePrefix("$prefix$child/") }.distinct().sorted()
                        val folders = g.map { it.first }.distinct().size
                        lines += "  " + rels.take(2).joinToString(", ") + (if (rels.size > 2) ", …" else "") +
                            " — ${count(g.map { it.second })}, em " + (if (folders == alike.size) "todas" else "$folders delas")
                    }
            } else {
                alike.forEach { (child, list) -> tree("$prefix$child", list, lines) }
            }
        }
    }

    private fun one(kind: String) = when (kind) { PHOTO -> "foto"; OTHER -> "outro"; else -> kind }

    private fun count(g: List<Item>): String {
        val n = g.size
        val exts = g.map { "." + it.base.substringAfterLast('.', "?").lowercase() }.distinct().take(4).joinToString(" ")
        return when (val k = g.first().kind) {
            PHOTO -> "$n fotos ($exts, " + g.count { it.dated }.let { d -> if (d > 0) "$d com data dentro" else "sem data dentro" } + ")"
            OTHER -> "$n outros ($exts)"
            else -> "$n $k ($exts)"
        }
    }

    /** A path with its numbers taken out, folder by folder: texts of the same kind ("2022-01-23/texto.txt", "2024-12-17 - Natal/texto.txt"). */
    private fun kindOf(path: String): String {
        val parts = path.split('/')
        return (parts.dropLast(1).map(::folderKey) + shape(parts.last())).joinToString("/")
    }

    /** A folder's kind: one whose name starts with numbers (a date) by those numbers only ("2024-12-17 - Natal" is "#-#-#"). */
    private fun folderKey(name: String): String =
        Regex("""^\d[\d\-_. ]*""").find(name)?.value?.trimEnd(' ', '-', '_', '.')?.let(::shape) ?: shape(name)

    /** A name with its numbers taken out: "2024-12-17" and "2022-01-23" are alike. */
    private fun shape(s: String): String = s.replace(Regex("""\d+"""), "#")

    /** What a text is, as the model is told: HTML, JSON, CSV or plain text. */
    fun formatOf(f: TextFile): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val t = f.text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            ext == "csv" || ext == "tsv" -> CSV
            (t.startsWith("{") || t.startsWith("[")) && runCatching { kotlinx.serialization.json.Json.parseToJsonElement(t) }.isSuccess -> JSON
            ext == "html" || ext == "htm" || (t.startsWith("<") && "</" in t) -> HTML
            else -> PLAIN
        }
    }

    /** The shapes a recipe can have for [texts]: "text" always; "json" and "csv" only when some text is one. */
    fun shapes(texts: List<TextFile>): Set<String> {
        val formats = texts.map { formatOf(it) }.toSet()
        return buildSet {
            add("text")
            if (JSON in formats) add("json")
            if (CSV in formats) add("csv")
        }
    }

    private const val PHOTO = "foto"
    private const val OTHER = "outro"
    private const val JSON = "JSON"
    private const val CSV = "CSV"
    private const val HTML = "HTML"
    private const val PLAIN = "texto"
    private const val LINES = 40
    private const val ALIKE = 3
    private const val INSIDE = 8

    /** What a recipe gave, for the model to fix it ([photos]: how many the .zip has). */
    fun result(r: Reading, zone: ZoneId, photos: Int = 0): String = buildString {
        append(r.found.size).append(" entradas com data")
        if (r.undated > 0) append(", ").append(r.undated).append(" sem data")
        if (r.unread > 0) append(", ").append(r.unread).append(" arquivos que não abriram como JSON")
        if (r.notes.isNotEmpty()) append(", ").append(r.notes.size).append(" notas")
        if (photos > 0) {
            append(", ").append(r.photos.size).append(" fotos de ").append(photos).append(" no .zip")
            if (r.undatedPhotos > 0) append(" (").append(r.undatedPhotos).append(" sem data)")
        }
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
