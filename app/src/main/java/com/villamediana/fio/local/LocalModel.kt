package com.villamediana.fio.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * The on-device model: LFM3-Audio-2B (Liquid AI), the text model in 4 bits and its audio projector
 * in 8 bits, downloaded once from Hugging Face by Android's DownloadManager (it resumes by itself)
 * and checked against the SHA-256 Hugging Face publishes.
 */
class LocalModel(private val context: Context, private val scope: CoroutineScope) {
    data class Part(val name: String, val url: String, val size: Long, val sha256: String)
    data class Files(val model: File, val projector: File)

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float, val bytes: Long, val total: Long) : State
        data object Checking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    private val memo get() = File(dir, "ready.txt")
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state
    private var watch: Job? = null

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    val files: Files get() = Files(File(dir, MODEL.name), File(dir, PROJECTOR.name))

    /** Both files here and checked. */
    val ready: Boolean get() = _state.value == State.Ready

    val totalBytes: Long get() = PARTS.sumOf { it.size }

    fun download() {
        if (_state.value is State.Downloading || _state.value == State.Ready || _state.value == State.Checking) return
        scope.launch(Dispatchers.IO) {
            PARTS.forEach { part ->
                val file = File(dir, part.name)
                if (file.length() == part.size) return@forEach
                if (activeId(part) != null) return@forEach
                file.delete()
                val request = DownloadManager.Request(Uri.parse(part.url))
                    .setTitle("Fio · modelo no celular")
                    .setDescription(part.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(file))
                    .setAllowedOverMetered(true)
                val id = manager.enqueue(request)
                File(dir, part.name + ".id").writeText(id.toString())
            }
            follow()
        }
    }

    fun cancel() {
        PARTS.forEach { part ->
            activeId(part)?.let { runCatching { manager.remove(it) } }
            File(dir, part.name + ".id").delete()
        }
        watch?.cancel()
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun delete() {
        cancel()
        PARTS.forEach { File(dir, it.name).delete() }
        memo.delete()
        _state.value = State.Missing
    }

    private fun refresh() {
        if (memo.isFile && PARTS.all { File(dir, it.name).length() == it.size }) {
            _state.value = State.Ready
            return
        }
        if (PARTS.any { activeId(it) != null }) {
            follow()
            return
        }
        if (PARTS.all { File(dir, it.name).length() == it.size }) {
            verify()
            return
        }
        _state.value = State.Missing
    }

    private fun follow() {
        if (watch?.isActive == true) return
        watch = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var done = 0L
                var failed: String? = null
                var running = false
                PARTS.forEach { part ->
                    val file = File(dir, part.name)
                    val id = activeId(part)
                    if (id == null) {
                        done += file.length().coerceAtMost(part.size)
                        return@forEach
                    }
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> { File(dir, part.name + ".id").delete(); done += part.size }
                                DownloadManager.STATUS_FAILED -> {
                                    failed = "o download parou (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"
                                    File(dir, part.name + ".id").delete()
                                }
                                else -> { running = true; done += got }
                            }
                        } else {
                            File(dir, part.name + ".id").delete()
                        }
                    }
                }
                val total = totalBytes
                when {
                    failed != null -> { _state.value = State.Failed(failed!!); return@launch }
                    running -> _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total)
                    else -> { verify(); return@launch }
                }
                delay(700)
            }
        }
    }

    private fun verify() {
        _state.value = State.Checking
        val bad = PARTS.firstOrNull { part ->
            val f = File(dir, part.name)
            f.length() != part.size || sha256(f) != part.sha256
        }
        if (bad == null) {
            memo.writeText(PARTS.joinToString("\n") { it.sha256 })
            _state.value = State.Ready
        } else {
            File(dir, bad.name).delete()
            _state.value = State.Failed("o arquivo ${bad.name} chegou com defeito; baixe de novo")
        }
    }

    private fun activeId(part: Part): Long? = File(dir, part.name + ".id").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REPO = "https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF/resolve/main/"
        val MODEL = Part("LFM3-Audio-2B-Q4_0.gguf", REPO + "LFM3-Audio-2B-Q4_0.gguf", 1_049_613_856, "112e4acb79be79dcd8df5749495614817e51c064cd9ca576aead02b64b2b7584")
        val PROJECTOR = Part("mmproj-LFM3-Audio-2B-Q8_0.gguf", REPO + "mmproj-LFM3-Audio-2B-Q8_0.gguf", 303_811_840, "2b6648b1fd48af16aa0877b0592b144d15070783673e038a379fdbc51d40e365")
        val PARTS = listOf(MODEL, PROJECTOR)
        const val NAME = "LFM3-Audio 2B"
    }
}
