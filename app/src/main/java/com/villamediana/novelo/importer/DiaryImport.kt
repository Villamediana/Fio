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
    /** The question to Omni: its answer (a recipe, held by the grammar). */
    private val ask: suspend (system: String, user: String) -> String,
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
        data class Recognizing(val name: String) : State
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
    fun open(name: String, read: () -> InputStream?) {
        if (_state.value is State.Saving) return
        job?.cancel()
        this.name = name
        files = Unpacked(emptyList())
        last = null
        job = scope.launch {
            _state.value = State.Opening(name)
            val opened = try {
                withContext(Dispatchers.IO) {
                    clearStage()
                    Unpack.files(name, read() ?: throw IOException(t(R.string.import_not_text)), stage())
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

    private suspend fun recognize() {
        why()?.let {
            _state.value = State.Failed(name, it, canRetry = true)
            return
        }
        _state.value = State.Recognizing(name)
        val user = buildString {
            append(Sample.of(name, files.texts, files.others))
            last?.let { (r, reading) ->
                append("\n\nUMA RECEITA ANTERIOR NÃO SERVIU:\n").append(Recipes.write(r))
                append("\nO QUE ELA DEU: ").append(Sample.result(reading, zone(), files.photos))
                append("\nVeja de novo a amostra e escreva uma receita que pegue cada entrada com a data, o título (se houver) e o texto")
                if (files.others.isNotEmpty()) append(", e as notas e as fotos que houver")
                append('.')
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
        val reading = withContext(Dispatchers.Default) { Recipes.apply(recipe, files.texts, zone(), files.others) }
        last = recipe to reading
        _state.value = State.Ready(name, recipe, reading)
    }

    private fun why(): String? = runCatching { ready() }.getOrNull()

    companion object {
        /** What the model is asked: the recipe's fields, in the app's words. */
        fun system(language: String): String = """
Você reconhece o formato de diários exportados de outros apps (Day One, Journey, Diarium, Daylio, Penzu, Notion, Obsidian, um TXT ou Markdown escrito à mão…) pra que o Novelo importe cada entrada no diário dele.

Você recebe só uma amostra do arquivo (o começo e, quando é grande, um pedaço do meio; num .zip, as pastas com o que há em cada uma e o começo de alguns textos). Descubra o padrão e responda com uma receita de importação: o Novelo aplica a receita no arquivo inteiro, entrada por entrada. Cada entrada precisa de uma data; o título é opcional; o texto é o que a pessoa escreveu.

Num .zip, veja também como as pastas se organizam: além das entradas do diário pode haver notas (textos que não são de um dia) e fotos.

"shape" diz a forma:
- "json": "entries" = caminho até a lista de entradas (chaves separadas por ponto; "" = a raiz; uma raiz que é uma entrada só também é ""). Dentro de cada entrada, "date", "title", "body" e "tags" são caminhos (ex.: "creationDate", "text", "location.placeName"). Um campo que é lista de textos vira um texto só.
- "text" (TXT, Markdown): "start" = expressão regular (Java) da linha que começa uma entrada; nela, (?<date>…) pega a data e (?<title>…) o título, quando estão nessa linha. A linha do "start" não entra no texto. Sem "start", cada arquivo inteiro é uma entrada. "date_regex" e "title_regex": quando a data ou o título estão numa linha de cabeçalho dentro da entrada, com (?<date>…) ou (?<title>…); o trecho casado sai do texto.
- "csv": "delimiter" ("," ";" ou "\t"), "header" (true quando a primeira linha tem os nomes das colunas), e "date", "title", "body" e "tags" = o nome da coluna (ou o número, a partir de 0).
Em qualquer forma:
- "date_format": "iso" (2024-01-15, 2024-01-15T10:30:00Z…), "epoch_s", "epoch_ms" ou um padrão do DateTimeFormatter do Java ("dd/MM/yyyy", "EEEE, d 'de' MMMM 'de' yyyy", "MMMM d, yyyy h:mm a"). "date_locale": o idioma dos nomes de mês e de dia ("pt-BR", "en", "es").
- "date_from_name": regex com (?<date>…) que acha a data no nome do arquivo, quando cada arquivo é uma entrada e a data não está dentro dele.
- "files": num .zip com outros arquivos além das entradas, regex do caminho dos que são entradas do diário.
- "notes": num .zip, regex do caminho dos arquivos que são notas (cada arquivo, uma nota). "note_title": "name" quando o nome do arquivo é o título da nota, "first_line" quando é a primeira linha.
- "photos": num .zip, regex do caminho das fotos da pessoa (não miniaturas, ícones nem figurinhas do app). Cada foto vai pra galeria do Novelo no dia em que foi tirada, pela data de dentro dela; "photo_date_from_name" (regex com (?<date>…)) e "photo_date_format" acham a data no nome da foto, pra quando ela não tem data dentro.
- "first_line_title": true quando a primeira linha do texto é o título.
- "html": true quando o texto vem em HTML.
- "clean": lista de regex do que tirar do texto e não é dele (ex.: referências de imagem como "!\\[\\]\\(dayone-moment:[^)]*\\)"), só quando houver.
- "app": o nome do app ou do formato que você reconheceu, curto. "about": uma ou duas frases, pra pessoa, dizendo o padrão que você viu (onde ficam a data, o título e o texto). Escreva "app" e "about" em $language.

Escreva só os campos que usar. Exemplos do jeito:
{"app": "Day One", "about": "…", "shape": "json", "entries": "entries", "date": "creationDate", "body": "text", "tags": "tags", "date_format": "iso", "photos": "^photos/"}
{"app": "Obsidian", "about": "…", "shape": "text", "files": "^Diário/", "date_from_name": "(?<date>\\d{4}-\\d{2}-\\d{2})", "notes": "^Notas/", "note_title": "name", "photos": "^Anexos/.*\\.(jpe?g|png)$", "photo_date_from_name": "(?<date>\\d{8})", "photo_date_format": "yyyyMMdd"}
""".trim()
    }
}

/** A file over [Unpack.MAX_BYTES]. */
class TooBig : IOException()

/** What she picked, opened: the text files, and (in a .zip) everything else — photos set aside. */
data class Unpacked(val texts: List<TextFile>, val others: List<OtherFile> = emptyList()) {
    val photos: Int get() = others.count { it.isPhoto }
}

/**
 * The bytes she picked → the files in them. A .zip is opened as it streams (a .zip inside it too):
 * its text files read (up to [MAX_BYTES] of text in all), its photos copied aside to [stage]
 * (named by their content, so the same photo is one file) with the date the camera wrote inside,
 * anything else listed only by name and size.
 */
object Unpack {
    const val MAX_BYTES = 40L * 1024 * 1024
    private const val PEEK = 8192
    val PHOTOS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")

    fun files(name: String, input: InputStream, stage: java.io.File? = null): Unpacked {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        return buffered.use { inp ->
            inp.mark(4)
            val magic = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = inp.read(magic, n, 4 - n)
                if (r < 0) break
                n += r
            }
            inp.reset()
            if (n == 4 && isZip(magic)) {
                val texts = ArrayList<TextFile>()
                val others = ArrayList<OtherFile>()
                unzip(ZipInputStream(inp), "", stage, texts, others, longArrayOf(0))
                Unpacked(texts.sortedBy { it.name }, others.sortedBy { it.name })
            } else {
                Unpacked(listOfNotNull(decode(capped(inp))?.let { TextFile(name, it) }))
            }
        }
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    /** [zip]'s files into [texts] and [others]; [text] counts the bytes of text read so far (all .zips together). */
    private fun unzip(zip: ZipInputStream, prefix: String, stage: java.io.File?, texts: MutableList<TextFile>, others: MutableList<OtherFile>, text: LongArray) {
        while (true) {
            val e = zip.nextEntry ?: break
            val path = prefix + e.name
            val base = path.substringAfterLast('/')
            if (e.isDirectory || path.startsWith("__MACOSX") || "/__MACOSX/" in path || base.startsWith(".")) continue
            val ext = base.substringAfterLast('.', "").lowercase()
            val time = e.time.takeIf { it > 0 }
            when {
                ext in PHOTOS && stage != null -> others += photo(zip, path, stage)
                ext == "zip" -> unzip(ZipInputStream(Unclosed(zip)), "$path/", stage, texts, others, text)
                else -> {
                    val head = ByteArray(PEEK)
                    val got = zip.readNBytesCompat(head)
                    val start = head.copyOf(got)
                    if (looksBinary(start)) {
                        others += OtherFile(path, got + drain(zip))
                        continue
                    }
                    val data = java.io.ByteArrayOutputStream().apply { write(start) }
                    copyCapped(zip, data, MAX_BYTES - text[0] - got)
                    text[0] += data.size()
                    if (text[0] > MAX_BYTES) throw TooBig()
                    val bytes = data.toByteArray()
                    decode(bytes)?.let { texts += TextFile(path, it, time) } ?: run { others += OtherFile(path, bytes.size.toLong()) }
                }
            }
        }
    }

    /** A photo copied aside, named by what's in it; the date the camera wrote inside, when it did. */
    private fun photo(zip: InputStream, path: String, stage: java.io.File): OtherFile {
        stage.mkdirs()
        val tmp = java.io.File.createTempFile("photo", ".part", stage)
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (zip.read(buf).also { n = it } > 0) {
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

    /** A .zip inside a .zip: reading it to the end must not close the outer one. */
    private class Unclosed(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() {}
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
    const val FOLDERS = 24
    const val SHOWN = 3

    /** [texts] and, in a .zip, [others] (photos and the rest): one file is shown as it is; a .zip by its folders. */
    fun of(name: String, texts: List<TextFile>, others: List<OtherFile> = emptyList()): String = buildString {
        if (texts.size == 1 && others.isEmpty()) {
            val f = texts.first()
            append("ARQUIVO: ").append(name).append(" (").append(kb(f.text)).append(", ").append(f.text.lines().size).append(" linhas)\n\n")
            append("COMEÇO DO ARQUIVO:\n").append(head(f.text, HEAD))
            if (f.text.length > HEAD + MIDDLE * 2) {
                append("\n\nUM PEDAÇO DO MEIO:\n").append(middle(f.text, MIDDLE))
            }
            return@buildString
        }
        // Each folder: how many texts, photos and other files (by extension) and a few names.
        data class Item(val path: String, val kind: Int, val dated: Boolean = false)
        val items = texts.map { Item(it.name, TEXT) } + others.map { Item(it.name, if (it.isPhoto) PHOTO else OTHER, it.taken != null) }
        val folders = items.groupBy { it.path.substringBeforeLast('/', "") }.toSortedMap()
        append("UM .ZIP (").append(name).append(") COM ").append(items.size).append(" ARQUIVOS. AS PASTAS:\n")
        folders.entries.take(FOLDERS).forEach { (folder, list) ->
            append(if (folder.isEmpty()) "(raiz)" else "$folder/").append(" — ")
            val parts = list.groupBy { it.kind }.toSortedMap().map { (kind, of) ->
                val exts = of.map { "." + it.path.substringAfterLast('/').substringAfterLast('.', "?").lowercase() }.distinct().take(4).joinToString(" ")
                val dated = if (kind == PHOTO) of.count { it.dated }.let { d -> if (d > 0) ", $d com data dentro" else ", sem data dentro" } else ""
                "${of.size} " + (when (kind) { TEXT -> "de texto"; PHOTO -> "fotos"; else -> "outros" }) + " ($exts$dated)"
            }
            append(parts.joinToString(", ")).append(": ")
            append(list.take(3).joinToString(", ") { it.path.substringAfterLast('/') })
            if (list.size > 3) append(", …")
            append('\n')
        }
        if (folders.size > FOLDERS) append("… e mais ").append(folders.size - FOLDERS).append(" pastas\n")
        // The start of a few texts: one from each folder that has them first, then more of the biggest.
        val byFolder = texts.groupBy { it.name.substringBeforeLast('/', "") }
        val shown = LinkedHashSet<TextFile>()
        byFolder.values.forEach { if (shown.size < SHOWN) shown += it.first() }
        byFolder.values.maxByOrNull { it.size }?.let { big -> listOf(big.size / 2, big.size - 1).forEach { if (shown.size < SHOWN) shown += big[it] } }
        shown.forEach { f -> append("\n--- ").append(f.name).append(" (").append(kb(f.text)).append(", começo) ---\n").append(head(f.text, PER_FILE)).append('\n') }
    }

    private const val TEXT = 0
    private const val PHOTO = 1
    private const val OTHER = 2

    /** What a recipe gave, for the model to fix it ([photos]: how many the .zip has). */
    fun result(r: Reading, zone: ZoneId, photos: Int = 0): String = buildString {
        append(r.found.size).append(" entradas com data")
        if (r.undated > 0) append(", ").append(r.undated).append(" sem data")
        if (r.unread > 0) append(", ").append(r.unread).append(" arquivos que não abriram")
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
