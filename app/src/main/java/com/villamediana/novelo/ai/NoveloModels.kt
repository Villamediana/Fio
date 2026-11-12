package com.villamediana.novelo.ai

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Novelo's own intelligence: free models on OpenRouter, behind one choice ("Inteligência do
 * Novelo") she never has to set up. Free models come and go, so the list is read from OpenRouter
 * itself (every [LIST_EVERY] ms) and the app keeps a pool of [POOL] of them, the ones known to
 * organize well first ([PREFERRED]), then the newest. One that fails (an error, too slow, an
 * answer that isn't the plan) waits a while out of the pool and the next one takes its place; the
 * one that answered last goes first next time. The prompt is the same one her API gets.
 */
class NoveloModels(
    http: OkHttpClient,
    private val prefs: SharedPreferences,
    private val key: () -> String? = { NoveloKey.value },
    private val clock: () -> Long = System::currentTimeMillis,
    private val listUrl: String = "https://openrouter.ai/api/v1/models",
    private val base: String = "https://openrouter.ai/api/v1",
) {
    /** A free model answers in time or makes way for the next. */
    private val client = http.newBuilder().callTimeout(ANSWER_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val api = Api(client)

    @Serializable
    data class Model(val id: String, val created: Long = 0, val json: Boolean = false, val reasons: Boolean = false)

    /** Lets go of the request going on now (a stop is quick). */
    fun cancel() = api.cancelAll()

    /** This build has the app's key (without it only her own intelligence works). */
    val available: Boolean get() = !key().isNullOrBlank()

    /**
     * The plan for [user] under [system], from the first model of the pool that gives one that
     * [valid] accepts. No network: an [IOException] (it waits for the network, nothing is held
     * against the models). All of them failing: the last error.
     */
    suspend fun answer(system: String, user: String, valid: (String) -> Boolean): String = withContext(Dispatchers.IO) {
        val k = key() ?: throw IllegalStateException("no key")
        val ep = Api.Endpoint(base, k, Wire.OPENAI)
        var last: Exception? = null
        for (m in pool().take(MAX_TRIES)) {
            try {
                val out = try {
                    api.chat(ep, m.id, system, user, jsonOut = m.json, effort = if (m.reasons) "low" else null)
                } catch (e: ApiException) {
                    // It didn't take the JSON mode or the reasoning level as asked: once more, plain.
                    if (!e.refused || !(m.json || m.reasons)) throw e
                    api.chat(ep, m.id, system, user, jsonOut = false, effort = null)
                }
                if (valid(out)) {
                    prefs.edit().putString(LAST_GOOD, m.id).apply()
                    return@withContext out
                }
                rest(m.id, BAD_ANSWER_MS)
                last = IllegalStateException("${m.id}: not a plan")
            } catch (e: ApiException) {
                // The key itself (refused, out of credit): no model would do better.
                if (e.code == 401 || e.code == 402) throw e
                // Gone or closed to apps like this one (404, 403: "only on agentic harnesses") for long;
                // too many asking (429) for a few minutes; anything else for a while.
                rest(m.id, when (e.code) { 403, 404 -> GONE_MS; 429 -> BUSY_MS; else -> FAILED_MS })
                last = e
            } catch (e: IOException) {
                if (!online()) throw e
                // Online, but this one didn't answer in time.
                rest(m.id, FAILED_MS)
                last = e
            }
        }
        throw last ?: IOException("no free model")
    }

    /** The models to try now, best first: the last that answered, the known good ones, the newest; none resting. */
    fun pool(): List<Model> {
        val now = clock()
        val all = models()
        val lastGood = prefs.getString(LAST_GOOD, null)
        val ranked = all.sortedWith(
            compareBy<Model> { if (it.id == lastGood) 0 else 1 }
                .thenBy { PREFERRED.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { if (it.id in AVOID) 1 else 0 }
                .thenByDescending { it.created },
        )
        return ranked.filter { prefs.getLong(REST + it.id, 0) <= now }.take(POOL)
    }

    /** OpenRouter's free models (kept for [LIST_EVERY]; the kept list when it can't be read). */
    private fun models(): List<Model> {
        val now = clock()
        val kept = prefs.getString(LIST, null)?.let { runCatching { json.decodeFromString<List<Model>>(it) }.getOrNull() }.orEmpty()
        if (kept.isNotEmpty() && now - prefs.getLong(LIST_AT, 0) < LIST_EVERY) return kept
        val fresh = runCatching { fetch() }.getOrNull()
        if (fresh.isNullOrEmpty()) return kept.ifEmpty { throw IOException("free models unavailable") }
        prefs.edit().putString(LIST, json.encodeToString(fresh)).putLong(LIST_AT, now).apply()
        return fresh
    }

    private fun fetch(): List<Model> {
        val body = client.newCall(Request.Builder().url(listUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("models: ${r.code}")
            r.body.string()
        }
        return free(body)
    }

    private fun rest(id: String, ms: Long) {
        prefs.edit().putLong(REST + id, clock() + ms).apply()
    }

    private fun online(): Boolean = runCatching {
        client.newCall(Request.Builder().url(listUrl).head().build()).execute().use { true }
    }.getOrDefault(false)

    companion object {
        /** How many are kept to try, and how many are tried for one answer. */
        const val POOL = 10
        const val MAX_TRIES = 5

        const val ANSWER_TIMEOUT_S = 90L
        const val LIST_EVERY = 12 * 60 * 60_000L
        const val FAILED_MS = 30 * 60_000L
        const val BUSY_MS = 5 * 60_000L
        const val BAD_ANSWER_MS = 6 * 60 * 60_000L
        const val GONE_MS = 7 * 24 * 60 * 60_000L

        /**
         * Measured with the app's prompt on the lab's 10 talks (~/novelo-lab/openrouter, 2026-11-12):
         * all 23 checks right, quickest first (3, 7, 8 and 28 s typical).
         */
        val PREFERRED: List<String> = listOf(
            "baidu/ernie-5.5-flash-preview:free",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "nvidia/nemotron-3.5-super-vl:free",
            "dots-studio/dots-3-note-preview:free",
        )

        /** Measured bad at it (wrong or no plan, or a minute and more): only when nothing else is left. */
        val AVOID: Set<String> = setOf(
            "liquid/lfm-3-1.7b:free",
            "nvidia/nemotron-3.5-lightning:free",
            "recursive/flint-v1.0:free",
            "cohere/north-mini-code:free",
            "thinkingmachines/inkling-2-small:free",
        )

        private const val LIST = "models"
        private const val LIST_AT = "models_at"
        private const val LAST_GOOD = "last_good"
        private const val REST = "rest:"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        @Serializable
        private data class Listing(val data: List<Entry> = emptyList())

        @Serializable
        private data class Entry(
            val id: String,
            val created: Long = 0,
            @kotlinx.serialization.SerialName("context_length") val context: Long = 0,
            val pricing: Pricing = Pricing(),
            val architecture: Architecture = Architecture(),
            @kotlinx.serialization.SerialName("supported_parameters") val params: List<String> = emptyList(),
        )

        @Serializable
        private data class Pricing(val prompt: String = "", val completion: String = "")

        @Serializable
        private data class Architecture(
            @kotlinx.serialization.SerialName("input_modalities") val input: List<String> = emptyList(),
            @kotlinx.serialization.SerialName("output_modalities") val output: List<String> = emptyList(),
        )

        /**
         * The free ones that can do the job: nothing to pay, text in and only text out, room for
         * the notebook (32k), not OpenRouter's own router (it picks for itself, out of our hands).
         */
        fun free(listing: String): List<Model> = json.decodeFromString<Listing>(listing).data
            .filter { e -> e.pricing.prompt.toDoubleOrNull() == 0.0 && e.pricing.completion.toDoubleOrNull() == 0.0 }
            .filter { e -> "text" in e.architecture.input && e.architecture.output == listOf("text") }
            .filter { e -> e.context >= 32_000 && e.id != "openrouter/free" }
            .map { e -> Model(e.id, e.created, json = "response_format" in e.params, reasons = "reasoning" in e.params) }
    }
}
