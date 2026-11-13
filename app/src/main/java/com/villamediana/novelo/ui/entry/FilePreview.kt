package com.villamediana.novelo.ui.entry

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.villamediana.novelo.data.KeptFile
import com.villamediana.novelo.mind.FileKind
import com.villamediana.novelo.mind.Peek
import com.villamediana.novelo.mind.uprightBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a file looks like before it is opened: a picture (a PDF's first page, a photo, a frame of a
 * video) or its first words (a text, a Word document, a sheet, slides). Sound and anything else
 * has none — the file stays one line.
 */
sealed interface FilePreview {
    class Picture(val bitmap: Bitmap) : FilePreview {
        val aspect: Float get() = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    }

    class Words(val text: String) : FilePreview

    /** Looked at and nothing to show (a PDF that won't open, an empty text). */
    data object None : FilePreview

    companion object {
        /** Whether a file of this kind may have one (its place is kept while it is made). */
        fun expected(f: KeptFile): Boolean = FileKind.look(f.name, f.mime) !in setOf(FileKind.Look.AUDIO, FileKind.Look.OTHER)

        /** Made once per file and kept a while (an item opened again shows it at once). */
        private val made = object : LruCache<String, FilePreview>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: FilePreview): Int = when (value) {
                is Picture -> value.bitmap.byteCount
                is Words -> value.text.length * 2 + 64
                None -> 64
            }
        }

        private fun key(f: KeptFile) = "${f.id}@${f.size}"

        fun cached(f: KeptFile): FilePreview? = made.get(key(f))

        /** Made off the main thread; [None] when there is nothing to show. */
        suspend fun of(f: KeptFile): FilePreview = cached(f) ?: withContext(Dispatchers.IO) {
            val file = File(f.path)
            val made = if (!file.isFile) None else runCatching {
                when (FileKind.look(f.name, f.mime)) {
                    FileKind.Look.PDF -> pdfPage(file)?.let(::Picture)
                    FileKind.Look.IMAGE -> uprightBitmap(file, SIDE)?.let(::Picture)
                    FileKind.Look.VIDEO -> frame(file)?.let(::Picture)
                    FileKind.Look.TEXT, FileKind.Look.SHEET, FileKind.Look.SLIDES ->
                        Peek.of(file, f.name, f.mime).trim().takeIf { it.isNotEmpty() }?.let { Words(it.take(WORDS)) }
                    else -> null
                }
            }.getOrNull() ?: None
            FilePreview.made.put(key(f), made)
            made
        }

        /** The PDF's first page, white, [SIDE] wide. */
        private fun pdfPage(file: File): Bitmap? =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount == 0) return null
                    pdf.openPage(0).use { p ->
                        val scale = SIDE.toFloat() / p.width.coerceAtLeast(1)
                        Bitmap.createBitmap(SIDE, (p.height * scale).toInt().coerceIn(1, SIDE * 2), Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }

        /** A frame a second in (or the first one, for a shorter video). */
        private fun frame(file: File): Bitmap? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.path)
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
                    ?: r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
            } finally {
                r.release()
            }
        }

        /** Pictures this wide at most: sharp across a phone's width. */
        private const val SIDE = 960

        /** As many characters as the few lines shown hold. */
        private const val WORDS = 600
    }
}

/** [f]'s preview: what is kept at once, else made off the main thread; null while it is made. */
@Composable
internal fun rememberFilePreview(f: KeptFile): FilePreview? {
    val state = remember(f.id, f.size) { mutableStateOf(FilePreview.cached(f)) }
    LaunchedEffect(f.id, f.size) { if (state.value == null) state.value = FilePreview.of(f) }
    return state.value
}
