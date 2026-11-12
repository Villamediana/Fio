package com.villamediana.novelo.mind

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** What kind of file it is, from its name (or its type): the short label shown and its size in words. */
object FileKind {
    /** "pdf", "docx"… (lowercase), or null when the name has none. */
    fun ext(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }

    /** "PDF", "DOCX"… what the file is, in a word. */
    fun label(name: String, mime: String?): String? =
        ext(name)?.uppercase() ?: mime?.substringAfter('/', "")?.substringAfterLast('.')?.takeIf { it.isNotEmpty() && it.length <= 8 }?.uppercase()

    /** "230 KB", "1,2 MB" in [locale]. */
    fun size(bytes: Long, locale: Locale = Locale.forLanguageTag("pt-BR")): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** What the file is, to pick its icon: a document of text, a sheet, slides, a picture, sound, video or anything else. */
    enum class Look { TEXT, SHEET, SLIDES, PDF, IMAGE, AUDIO, VIDEO, OTHER }

    fun look(name: String, mime: String?): Look {
        val e = ext(name)
        val m = mime.orEmpty()
        return when {
            e == "pdf" || m == "application/pdf" -> Look.PDF
            e in SHEETS || m.contains("spreadsheet") || m.contains("excel") || m == "text/csv" -> Look.SHEET
            e in SLIDES || m.contains("presentation") || m.contains("powerpoint") -> Look.SLIDES
            m.startsWith("image/") -> Look.IMAGE
            m.startsWith("audio/") -> Look.AUDIO
            m.startsWith("video/") -> Look.VIDEO
            e in TEXTS || e in WORDS || m.startsWith("text/") || m.contains("word") -> Look.TEXT
            else -> Look.OTHER
        }
    }

    internal val WORDS = setOf("doc", "docx", "odt", "rtf", "pages")
    internal val SHEETS = setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
    internal val SLIDES = setOf("ppt", "pptx", "odp", "key")
    internal val TEXTS = setOf("txt", "md", "markdown", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "csv", "tsv")
}

/**
 * Only the beginning of a file, for the model to know what it is: never the document whole (it
 * isn't read, it is only kept). At most [Brain.PEEK] characters of its first page.
 */
interface FilePeek {
    suspend fun peek(file: File, name: String, mime: String?): String

    /** Its first page (a PDF's) or itself (a picture) as a JPEG for Omni to look at; null for anything else. */
    suspend fun look(file: File, name: String, mime: String?): ByteArray? = null
}

/** Nothing peeked: the name and the type only. */
object NoPeek : FilePeek {
    override suspend fun peek(file: File, name: String, mime: String?): String = ""
}

/**
 * The beginnings that need nothing of Android: a text file's first lines, and the first words of an
 * Office or OpenDocument file (what is inside its zip: the first page, the first sheet's words,
 * the first slide). A file of another kind gives nothing.
 */
object Peek {
    private const val MAX = Brain.PEEK

    /** The most read of a file (or of a part inside it) to find the beginning. */
    private const val READ = 256 * 1024

    fun of(file: File, name: String, mime: String?): String = runCatching {
        val e = FileKind.ext(name)
        when {
            e in setOf("docx", "docm", "dotx") -> part(file, "word/document.xml", "</w:p>")
            e in setOf("xlsx", "xlsm") -> part(file, "xl/sharedStrings.xml", "</si>")
            e in setOf("pptx", "pptm") -> part(file, "ppt/slides/slide1.xml", "</a:p>")
            e in setOf("odt", "ods", "odp") -> part(file, "content.xml", "</text:p>")
            e == "html" || e == "htm" -> clean(strip(head(file), "</p>"))
            e in FileKind.TEXTS || (mime?.startsWith("text/") == true) -> clean(head(file))
            else -> ""
        }
    }.getOrDefault("")

    /** The words of the zip's [inside] (an XML), a paragraph ending at each [end]. */
    private fun part(file: File, inside: String, end: String): String = ZipFile(file).use { z ->
        val entry = z.getEntry(inside) ?: return@use ""
        val bytes = z.getInputStream(entry).use { it.upTo(READ) }
        clean(strip(bytes.toString(Charsets.UTF_8), end))
    }

    private fun head(file: File): String = file.inputStream().use { it.upTo(16 * 1024) }.toString(Charsets.UTF_8)

    /** At most [n] bytes from the start. */
    private fun java.io.InputStream.upTo(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private val TAG = Regex("<[^>]*>")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val LINES = Regex("\\s*\\n\\s*")

    private fun strip(xml: String, end: String): String =
        TAG.replace(xml.replace(end, "\n"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    private fun clean(text: String): String =
        LINES.replace(SPACES.replace(text.replace('\r', '\n').filter { it == '\n' || !it.isISOControl() }, " "), "\n").trim().take(MAX)
}

/**
 * On the phone: [Peek] for text and Office files; a PDF's first page drawn and its text read
 * (ML Kit, offline), like a picture's. Nothing leaves the phone for this.
 */
class PhonePeek(private val context: Context, private val photos: PhotoReader) : FilePeek {
    override suspend fun peek(file: File, name: String, mime: String?): String = withContext(Dispatchers.IO) {
        when (FileKind.look(name, mime)) {
            FileKind.Look.PDF -> runCatching { firstPage(file) { photos.text(it).take(Brain.PEEK) } }.getOrNull().orEmpty()
            FileKind.Look.IMAGE -> runCatching { photos.text(file).take(Brain.PEEK) }.getOrDefault("")
            else -> Peek.of(file, name, mime)
        }
    }

    override suspend fun look(file: File, name: String, mime: String?): ByteArray? = withContext(Dispatchers.IO) {
        when (FileKind.look(name, mime)) {
            FileKind.Look.PDF -> runCatching { firstPage(file) { photos.jpeg(it, LOOK_SIDE) } }.getOrNull()
            FileKind.Look.IMAGE -> runCatching { photos.jpeg(file, LOOK_SIDE) }.getOrNull()
            else -> null
        }
    }

    /** The PDF's first page drawn as a picture, handed to [use] (null: no page). */
    private suspend fun <T> firstPage(file: File, use: suspend (File) -> T): T? {
        val page = File(context.cacheDir, "peek-${System.nanoTime()}.png")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount == 0) return null
                    pdf.openPage(0).use { p ->
                        // About an A4 at 150 dpi: enough to read, light to hold.
                        val scale = PAGE_WIDTH.toFloat() / p.width.coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, (p.height * scale).toInt().coerceIn(1, PAGE_WIDTH * 2), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
            return use(page)
        } finally {
            page.delete()
        }
    }

    private companion object {
        const val PAGE_WIDTH = 1240

        /** As much as Omni looks at a photo. */
        const val LOOK_SIDE = 768
    }
}
