package com.villamediana.fio.local

import com.villamediana.fio.R
import com.villamediana.fio.settings.t
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
 * The Novelo's ears, on the phone: LFM3-Omni-2B (Liquid AI), which hears and looks at photos —
 * its text model in 4 bits and its vision/audio projector in 8 bits. One thing to download (one
 * bar): the parts come one after the other through Android's DownloadManager (it resumes by
 * itself), each checked against the SHA-256 Hugging Face publishes.
 */
class LocalModel(private val context: Context, private val scope: CoroutineScope) {
    data class Part(val name: String, val url: String, val size: Long, val sha256: String)
    data class Files(val model: File, val projector: File?)

    companion object {
        private const val OMNI_REPO = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/"
        val OMNI = Part("LFM3-Omni-2B-Q4_0.gguf", OMNI_REPO + "LFM3-Omni-2B-Q4_0.gguf", 1_049_617_920, "6d2273a383b92dd7c5db0eb4c8dfeae13e805b485a6621c4b391ae179c2b5f28")
        val OMNI_PROJECTOR = Part("mmproj-LFM3-Omni-2B-Q8_0.gguf", OMNI_REPO + "mmproj-LFM3-Omni-2B-Q8_0.gguf", 506_842_528, "8a25f9d1931244429de404fccacfb7fb933970a36aa5688d2f5069f29c6b27b1")

        val PARTS = listOf(OMNI, OMNI_PROJECTOR)

        /**
         * What the phone kept from before and isn't used any more: LFM3-Audio (until 1.6), the one
         * memo Omni had (until 1.18), and the models that thought on the phone (1.19: d1-2B and
         * LFM3-1.7B — thinking is online since 1.20). Each goes with its download, if one is running.
         */
        val LEFTOVERS = listOf(
            "LFM3-Audio-2B-Q4_0.gguf", "mmproj-LFM3-Audio-2B-Q8_0.gguf", "ready.txt",
            "d1-2B-Q4_0.gguf", "LFM3-1.7B-QAD-Q4_0.gguf",
        )
        private const val OLD_MEMO = "ready-omni.txt"
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float, val bytes: Long, val total: Long) : State
        data object Checking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    /** Asked to come (and whether only on Wi-Fi): the next part starts when one ends, after the app closed too. */
    private val wanted get() = File(dir, "wanted.txt")

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
        LEFTOVERS.forEach { name ->
            File(dir, "$name.id").takeIf { it.isFile }?.let { id ->
                id.readText().trim().toLongOrNull()?.let { runCatching { manager.remove(it) } }
                id.delete()
            }
            File(dir, name).delete()
            File(dir, "$name.ok").delete()
        }
        // The one memo Omni had: each part has its own now.
        File(dir, OLD_MEMO).takeIf { it.isFile }?.let { old ->
            PARTS.filter { File(dir, it.name).length() == it.size }.forEach { ok(it).writeText(it.sha256) }
            old.delete()
        }
    }

    /** LFM3-Omni: hears and looks. */
    val files: Files get() = Files(File(dir, OMNI.name), File(dir, OMNI_PROJECTOR.name))

    /** All of it here and checked (the files themselves: true as soon as they are, whatever the state says yet). */
    val ready: Boolean get() = PARTS.all(::checked)

    val totalBytes: Long get() = PARTS.sumOf { it.size }

    /** Whether the phone is on a network that doesn't count data (Wi-Fi, usually). */
    fun onWifi(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    /** To the phone; [wifiOnly]: it waits for Wi-Fi (Android's download goes by itself when it comes). */
    fun download(wifiOnly: Boolean = false) {
        if (_state.value is State.Downloading || _state.value == State.Ready || _state.value == State.Checking) return
        scope.launch(Dispatchers.IO) {
            wanted.writeText(if (wifiOnly) "wifi" else "any")
            next()
            follow()
        }
    }

    fun cancel() {
        watch?.cancel()
        scope.launch(Dispatchers.IO) {
            wanted.delete()
            PARTS.forEach { part ->
                activeId(part)?.let { runCatching { manager.remove(it) } }
                File(dir, part.name + ".id").delete()
            }
            refresh()
        }
    }

    /** The next part that isn't here yet starts coming; false when there is none left. */
    private fun next(): Boolean {
        if (PARTS.any { activeId(it) != null }) return true
        val part = PARTS.firstOrNull { File(dir, it.name).length() != it.size } ?: return false
        val file = File(dir, part.name)
        file.delete()
        val request = DownloadManager.Request(Uri.parse(part.url))
            .setTitle("Novelo")
            .setDescription(t(R.string.local_pack_hear))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(wanted.takeIf { it.isFile }?.readText()?.trim() != "wifi")
        val id = manager.enqueue(request)
        File(dir, part.name + ".id").writeText(id.toString())
        return true
    }

    private fun refresh() {
        when {
            PARTS.all(::checked) -> { wanted.delete(); _state.value = State.Ready }
            PARTS.any { activeId(it) != null } || wanted.isFile -> follow()
            PARTS.all { File(dir, it.name).length() == it.size } -> verify()
            else -> _state.value = State.Missing
        }
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
                                    failed = t(R.string.local_download_stopped, c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                                    File(dir, part.name + ".id").delete()
                                }
                                else -> { running = true; done += got.coerceAtLeast(0) }
                            }
                        } else {
                            File(dir, part.name + ".id").delete()
                        }
                    }
                }
                val total = totalBytes
                when {
                    failed != null -> { wanted.delete(); _state.value = State.Failed(failed!!); return@launch }
                    running -> _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total)
                    // One part ended: the next one comes (the bar goes on), or all are here.
                    wanted.isFile && next() -> _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total)
                    else -> { verify(); return@launch }
                }
                delay(700)
            }
        }
    }

    private fun verify() {
        if (PARTS.any { File(dir, it.name).length() != it.size }) {
            _state.value = State.Missing
            return
        }
        _state.value = State.Checking
        val bad = PARTS.firstOrNull { part ->
            if (checked(part)) return@firstOrNull false
            val good = sha256(File(dir, part.name)) == part.sha256
            if (good) ok(part).writeText(part.sha256)
            !good
        }
        wanted.delete()
        if (bad == null) {
            _state.value = State.Ready
        } else {
            File(dir, bad.name).delete()
            _state.value = State.Failed(t(R.string.local_file_damaged_any))
        }
    }

    /** The memo that says [part] came whole. */
    private fun ok(part: Part) = File(dir, part.name + ".ok")

    private fun checked(part: Part): Boolean =
        File(dir, part.name).length() == part.size && ok(part).let { it.isFile && it.readText().trim() == part.sha256 }

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
