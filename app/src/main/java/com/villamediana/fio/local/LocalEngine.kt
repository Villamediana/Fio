package com.villamediana.fio.local

import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import com.villamediana.fio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Text as it comes out of the model (UTF-8 bytes of the new part). Return false to stop. */
fun interface TokenSink {
    fun onText(bytes: ByteArray): Boolean
}

/**
 * LFM3-Omni on the phone (llama.cpp, app/src/main/cpp): one model hears, thinks and looks at the
 * photos. Loaded on first use and kept for a few minutes, then let go (~1.6 GB of RAM).
 */
/** [cacheDir]: where the instructions' snapshot is kept, so they run once per model and version. */
class LocalEngine(private val cacheDir: java.io.File? = null) {
    private val lock = Mutex()
    @Volatile private var handle = 0L
    private var loadedFrom: String? = null
    @Volatile private var lastUse = 0L

    suspend fun transcribe(files: LocalModel.Files, pcm: FloatArray, language: String?, onText: (String) -> Unit = {}): String =
        use(files) { h ->
            val seconds = pcm.size / 16_000
            val sink = streaming { onText(clean(it)) }
            val out = nativeTranscribe(h, pcm, asrPrompt(language).toByteArray(), 64 + seconds * 12, sink) ?: throw IllegalStateException(t(R.string.local_couldnt_hear))
            clean(String(out, Charsets.UTF_8))
        }

    /**
     * Loads the model now (while she is still talking), so the work after starts at once; with
     * [fixed] (the instructions), their snapshot gets ready too (from disk, or run once and kept).
     */
    suspend fun warm(files: LocalModel.Files, fixed: String = "") = use(files) { h ->
        if (fixed.isNotEmpty()) nativePrime(h, fixed.toByteArray())
    }

    /**
     * [image]: a JPEG the model looks at, placed at [MEDIA] in the prompt (left out when it can't be read).
     * [fixed]: the start of [prompt] that is the same every time (the instructions): run once and
     * then put back, so each answer only runs what changed.
     */
    suspend fun generate(files: LocalModel.Files, prompt: String, grammar: String?, image: ByteArray? = null, maxTokens: Int = 1200, fixed: String = "", onText: (String) -> Unit = {}): String =
        use(files) { h ->
            val bytes = prompt.toByteArray()
            val keep = if (fixed.isNotEmpty() && prompt.startsWith(fixed)) fixed.toByteArray().size else 0
            val out = nativeGenerate(h, bytes, keep, grammar, image, maxTokens, streaming(onText)) ?: throw IllegalStateException(t(R.string.local_no_answer))
            String(out, Charsets.UTF_8)
        }

    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    /** Lets the model go if it hasn't been used for [idleMs]. */
    suspend fun releaseIfIdle(idleMs: Long = 5 * 60_000L) {
        if (handle == 0L || System.currentTimeMillis() - lastUse < idleMs) return
        lock.withLock { freeLocked() }
    }

    private fun streaming(onText: (String) -> Unit): TokenSink {
        val acc = StringBuilder()
        return TokenSink { bytes ->
            acc.append(String(bytes, Charsets.UTF_8))
            onText(acc.toString())
            true
        }
    }

    private suspend fun <T> use(files: LocalModel.Files, block: (Long) -> T): T = lock.withLock {
        withContext(Dispatchers.Default) {
            check(available) { t(R.string.local_no_engine) }
            if (handle == 0L || loadedFrom != files.model.path) {
                freeLocked()
                val threads = threads()
                handle = nativeLoad(files.model.path, files.projector?.path.orEmpty(), threads, 8192, snapshotFile(files)?.path.orEmpty())
                check(handle != 0L) { t(R.string.local_cant_load) }
                loadedFrom = files.model.path
            }
            lastUse = System.currentTimeMillis()
            try {
                block(handle)
            } finally {
                lastUse = System.currentTimeMillis()
            }
        }
    }

    /** The instructions' snapshot for this model and this version of the app (others, left from before, go). */
    private fun snapshotFile(files: LocalModel.Files): java.io.File? {
        val dir = cacheDir ?: return null
        val name = "prompt-" + files.model.name + "-" + files.model.length() + "-" + com.villamediana.fio.BuildConfig.VERSION_CODE + ".bin"
        runCatching { dir.mkdirs(); dir.listFiles().orEmpty().filter { it.name.startsWith("prompt-") && it.name != name }.forEach { it.delete() } }
        return java.io.File(dir, name)
    }

    private fun freeLocked() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedFrom = null
    }

    private external fun nativeLoad(model: String, mmproj: String, threads: Int, nCtx: Int, cache: String): Long
    private external fun nativePrime(handle: Long, prefix: ByteArray): Boolean
    private external fun nativeFree(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, prompt: ByteArray, maxTokens: Int, sink: TokenSink?): ByteArray?
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, keep: Int, grammar: String?, image: ByteArray?, maxTokens: Int, sink: TokenSink?): ByteArray?

    companion object {
        /** Where a photo goes in the prompt (llama.cpp's media marker). */
        const val MEDIA = "<__media__>"

        /**
         * The model's chat with the thinking turned off: the assistant turn opens with an empty
         * "<think></think>", as LFM3's template writes it. Without it the model writes it itself,
         * at the start of every answer.
         */
        const val ANSWER = "<|im_start|>assistant\n<think></think>"

        /** The prompt that makes the model write down what is said (the one in llama.cpp and the model card). */
        fun asrPrompt(language: String?): String {
            val sys = if (language != null) "Perform ASR in $language." else "Perform ASR."
            return "<|startoftext|><|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$MEDIA<|im_end|>\n$ANSWER"
        }

        /** The text without any thinking block (a whole one, or one still open at the end of what came so far). */
        fun clean(text: String): String {
            val closed = text.replace(THINK, "")
            val open = closed.indexOf("<think>")
            return (if (open >= 0) closed.substring(0, open) else closed).trim()
        }

        private val THINK = Regex("<think>[\\s\\S]*?</think>")

        /**
         * As many threads as fast cores: the model runs at the pace of its slowest thread, so the
         * phone's small cores only hold it back. Cores at 70 % or more of the fastest one's top
         * speed count as fast; without that information, all but two.
         */
        fun threads(): Int {
            val n = Runtime.getRuntime().availableProcessors()
            val top = (0 until n).mapNotNull { i ->
                runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            if (top.size < 2) return n.coerceIn(2, 8).let { if (it > 4) it - 2 else it }
            val fastest = top.max()
            return top.count { it >= fastest * 0.7 }.coerceIn(2, 8)
        }

        /** The native library is in this build (and loads on this phone). */
        val available: Boolean by lazy {
            BuildConfig.HAS_LOCAL_ENGINE && runCatching { System.loadLibrary("fio_engine") }.isSuccess
        }
    }
}
