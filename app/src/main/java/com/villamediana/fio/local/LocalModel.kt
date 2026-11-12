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
 * The model that lives on the phone: LFM3-Omni-2B (Liquid AI) — it hears, thinks and looks at the
 * photos. Its text model in 4 bits and its vision/audio projector in 8 bits, downloaded once from
 * Hugging Face by Android's DownloadManager (it resumes by itself) and checked against the SHA-256
 * Hugging Face publishes.
 */
class LocalModel(private val context: Context, private val scope: CoroutineScope, val spec: Spec = Spec.OMNI) {
    data class Part(val name: String, val url: String, val size: Long, val sha256: String)
    data class Files(val model: File, val projector: File?)

    /** [memo]: the file that says "downloaded and checked". */
    data class Spec(val name: String, val model: Part, val projector: Part?, val memo: String) {
        val parts: List<Part> get() = listOfNotNull(model, projector)

        companion object {
            private const val OMNI_REPO = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/"

            val OMNI = Spec(
                "LFM3-Omni 2B",
                Part("LFM3-Omni-2B-Q4_0.gguf", OMNI_REPO + "LFM3-Omni-2B-Q4_0.gguf", 1_049_617_920, "6d2273a383b92dd7c5db0eb4c8dfeae13e805b485a6621c4b391ae179c2b5f28"),
                Part("mmproj-LFM3-Omni-2B-Q8_0.gguf", OMNI_REPO + "mmproj-LFM3-Omni-2B-Q8_0.gguf", 506_842_528, "8a25f9d1931244429de404fccacfb7fb933970a36aa5688d2f5069f29c6b27b1"),
                memo = "ready-omni.txt",
            )

            /** What the phone kept of the model that used to hear (LFM3-Audio, until 1.6): it goes, it's not used any more. */
            val LEFTOVERS = listOf("LFM3-Audio-2B-Q4_0.gguf", "mmproj-LFM3-Audio-2B-Q8_0.gguf", "ready.txt")
        }
    }

    private val parts get() = spec.parts

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float, val bytes: Long, val total: Long) : State
        data object Checking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    private val memo get() = File(dir, spec.memo)
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state
    private var watch: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            leftovers()
            refresh()
        }
    }

    private fun leftovers() {
        Spec.LEFTOVERS.forEach { name ->
            File(dir, "$name.id").takeIf { it.isFile }?.let { id ->
                id.readText().trim().toLongOrNull()?.let { runCatching { manager.remove(it) } }
                id.delete()
            }
            File(dir, name).delete()
        }
    }

    val files: Files get() = Files(File(dir, spec.model.name), spec.projector?.let { File(dir, it.name) })

    /** Both files here and checked. */
    val ready: Boolean get() = _state.value == State.Ready

    val totalBytes: Long get() = parts.sumOf { it.size }

    fun download() {
        if (_state.value is State.Downloading || _state.value == State.Ready || _state.value == State.Checking) return
        scope.launch(Dispatchers.IO) {
            parts.forEach { part ->
                val file = File(dir, part.name)
                if (file.length() == part.size) return@forEach
                if (activeId(part) != null) return@forEach
                file.delete()
                val request = DownloadManager.Request(Uri.parse(part.url))
                    .setTitle("Novelo · ${spec.name}")
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
        parts.forEach { part ->
            activeId(part)?.let { runCatching { manager.remove(it) } }
            File(dir, part.name + ".id").delete()
        }
        watch?.cancel()
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun delete() {
        cancel()
        parts.forEach { File(dir, it.name).delete() }
        memo.delete()
        _state.value = State.Missing
    }

    private fun refresh() {
        if (memo.isFile && parts.all { File(dir, it.name).length() == it.size }) {
            _state.value = State.Ready
            return
        }
        if (parts.any { activeId(it) != null }) {
            follow()
            return
        }
        if (parts.all { File(dir, it.name).length() == it.size }) {
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
                parts.forEach { part ->
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
        val bad = parts.firstOrNull { part ->
            val f = File(dir, part.name)
            f.length() != part.size || sha256(f) != part.sha256
        }
        if (bad == null) {
            memo.writeText(parts.joinToString("\n") { it.sha256 })
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

}
