package com.villamediana.novelo.ai

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Novelo's own intelligence, through the Novelo's server (server/ in the repo): the app holds no
 * key at all. Each install has a random code (no name, no account); the server gives it a pass,
 * counts what it organized today against its plan (free: 10 talks and 3 photos a day), and only
 * then asks the model with its own key. Nothing she says is kept there.
 */
class NoveloServer(
    http: OkHttpClient,
    private val prefs: SharedPreferences,
    private val base: String,
    private val agent: String,
) {
    private val client = http.newBuilder().callTimeout(ANSWER_TIMEOUT_S, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null

    /** What today allows and what was used (from the last answer), or null before the first. */
    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage

    /** This build knows where the server is (without it only her own intelligence works). */
    val available: Boolean get() = base.isNotBlank()

    /** Lets go of the request going on now (a stop is quick). */
    fun cancel() {
        call?.cancel()
    }

    /**
     * The model's answer to [system]/[user] for one thing she said or photographed ([turn]: the
     * same for the second look of a talk that looks something up, so it counts once; [photo]: a
     * photo sent alone, counted apart). No network: [IOException]. Today's limit reached:
     * [DailyLimit]. The server's day is full (its spending ceiling): [ServerBusy]. The model didn't
     * answer: [NoveloUnavailable].
     */
    suspend fun answer(system: String, user: String, turn: String, photo: Boolean): String = withContext(Dispatchers.IO) {
        val ask = json.encodeToString(Organize.serializer(), Organize(system, user, if (photo) "photo" else "talk", turn.ifBlank { UUID.randomUUID().toString() }))
        var r = post("/v1/organize", ask, token())
        if (r.code == 401) {
            // The pass expired (or the server's secret changed): a new one, once.
            prefs.edit().remove(TOKEN).apply()
            r = post("/v1/organize", ask, token())
        }
        val reply = runCatching { json.decodeFromString(Reply.serializer(), r.body) }.getOrNull()
        reply?.usage?.let { _usage.value = it }
        when {
            r.code == 200 && reply?.text != null -> reply.text
            r.code == 429 && reply?.error == "daily_limit" -> throw DailyLimit(reply.kind == "photo", reply.usage?.limitFor(reply.kind == "photo"), reply.usage?.resetAt ?: 0)
            r.code == 503 -> throw ServerBusy(reply?.resetAt ?: reply?.usage?.resetAt ?: 0)
            else -> throw NoveloUnavailable(r.code, reply?.error)
        }
    }

    /** Today's use, asked of the server (for the screen); null when it can't be asked now. */
    suspend fun refresh(): Usage? = withContext(Dispatchers.IO) {
        runCatching {
            val r = get("/v1/me", token())
            json.decodeFromString(Reply.serializer(), r.body).usage
        }.getOrNull()?.also { _usage.value = it }
    }

    /** This install's code: made once, kept. */
    private val install: String
        get() = prefs.getString(INSTALL, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALL, it).apply() }

    /** The pass, from before or asked now. */
    private fun token(): String {
        prefs.getString(TOKEN, null)?.let { return it }
        val r = post("/v1/session", json.encodeToString(Session.serializer(), Session(install)), null)
        val reply = runCatching { json.decodeFromString(Reply.serializer(), r.body) }.getOrNull()
        val token = reply?.token ?: throw NoveloUnavailable(r.code, reply?.error)
        reply.usage?.let { _usage.value = it }
        prefs.edit().putString(TOKEN, token).apply()
        return token
    }

    private class Answer(val code: Int, val body: String)

    private fun post(path: String, body: String, token: String?): Answer = send(
        Request.Builder().url(base.trimEnd('/') + path).post(body.toRequestBody(JSON_TYPE)),
        token,
    )

    private fun get(path: String, token: String?): Answer = send(Request.Builder().url(base.trimEnd('/') + path).get(), token)

    private fun send(builder: Request.Builder, token: String?): Answer {
        builder.header("user-agent", agent)
        token?.let { builder.header("authorization", "Bearer $it") }
        val c = client.newCall(builder.build())
        call = c
        return c.execute().use { Answer(it.code, it.body.string()) }
    }

    @Serializable
    data class Count(val used: Int = 0, val limit: Int? = null)

    @Serializable
    data class Usage(
        val plan: String = "free",
        @SerialName("reset_at") val resetAt: Long = 0,
        val talk: Count = Count(),
        val photo: Count = Count(),
    ) {
        fun limitFor(photo: Boolean): Int? = if (photo) this.photo.limit else talk.limit
    }

    @Serializable
    private data class Session(val install: String)

    @Serializable
    private data class Organize(val system: String, val user: String, val kind: String, val turn: String)

    @Serializable
    private data class Reply(
        val text: String? = null,
        val token: String? = null,
        val error: String? = null,
        val kind: String? = null,
        val usage: Usage? = null,
        @SerialName("reset_at") val resetAt: Long? = null,
    )

    companion object {
        const val ANSWER_TIMEOUT_S = 120L
        private const val INSTALL = "install"
        private const val TOKEN = "token"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}

/** Today's [limit] of talks (or of photos, [photo]) is used up: what she said waits for [resetAt] (ms). */
class DailyLimit(val photo: Boolean, val limit: Int?, val resetAt: Long) : Exception("daily limit")

/** The server's day is full (its spending ceiling): what she said waits, at most until [resetAt]. */
class ServerBusy(val resetAt: Long) : Exception("busy")

/** The server or its model didn't answer ([code], [error] as the server said it). */
class NoveloUnavailable(val code: Int, val error: String?) : Exception("novelo $code ${error.orEmpty()}")
