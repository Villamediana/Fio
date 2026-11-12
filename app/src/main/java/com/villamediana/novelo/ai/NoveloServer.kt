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
 * key at all. Each phone is someone there (its Android ID, hashed here and again on the server; no
 * name, no account): the server knows the plan — 7 days of trial on free models, then Novelo+ on
 * the better ones — counts the day and the week, and only then asks the model. Nothing she says is
 * kept there. Her own intelligence (her API) never comes here and has no limits.
 */
class NoveloServer(
    http: OkHttpClient,
    private val prefs: SharedPreferences,
    /** Where the server is (read at each request). */
    private val base: () -> String,
    private val agent: String,
    /** This phone, hashed (SHA-256 hex), or null: one trial per phone, reinstalled or not. */
    private val device: () -> String? = { null },
) {
    private val client = http.newBuilder().callTimeout(ANSWER_TIMEOUT_S, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null

    /** The plan, its limits and what was used: the last the server said (kept, so the screen has it offline too). */
    private val _status = MutableStateFlow(prefs.getString(STATUS, null)?.let { runCatching { json.decodeFromString(Status.serializer(), it) }.getOrNull() })
    val status: StateFlow<Status?> = _status

    /** This build knows where the server is (without it only her own intelligence works). */
    val available: Boolean get() = base().isNotBlank()

    /** This install's code: made once, kept (the store's purchase carries it, to know whose it is). */
    val install: String
        get() = prefs.getString(INSTALL, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALL, it).apply() }

    /** Lets go of the request going on now (a stop is quick). */
    fun cancel() {
        call?.cancel()
    }

    /**
     * The model's answer to [system]/[user] for one thing she said or photographed ([turn]: the
     * same for the second look of a talk that looks something up, so it counts once; [photo]: a
     * photo sent alone, counted apart). No network: [IOException]. A limit reached: [Limit]. The
     * trial over, no Novelo+: [NoPlan]. The server's day full: [ServerBusy]. No answer: [NoveloUnavailable].
     */
    suspend fun answer(system: String, user: String, turn: String, photo: Boolean): String = withContext(Dispatchers.IO) {
        val ask = json.encodeToString(Organize.serializer(), Organize(system, user, if (photo) "photo" else "talk", turn.ifBlank { UUID.randomUUID().toString() }))
        val r = authorized { post("/v1/organize", ask, it) }
        val reply = r.reply()
        when {
            r.code == 200 && reply?.text != null -> reply.text
            r.code == 429 && (reply?.error == "daily_limit" || reply?.error == "weekly_limit") -> {
                val st = reply.status
                val weekly = reply.error == "weekly_limit"
                val window = st?.let { if (reply.kind == "photo") it.photo else it.talk }
                throw Limit(
                    photo = reply.kind == "photo",
                    weekly = weekly,
                    trial = st?.plan == TRIAL,
                    limit = (if (weekly) window?.week else window?.day)?.limit,
                    resetAt = st?.let { if (weekly) it.weekResets else it.dayResets } ?: 0,
                )
            }
            r.code == 402 -> throw NoPlan()
            r.code == 503 && reply?.error == "busy" -> throw ServerBusy(reply.resetAt ?: 0)
            else -> throw NoveloUnavailable(r.code, reply?.error)
        }
    }

    /** Today's plan and use, asked of the server (for the screen); null when it can't be asked now. */
    suspend fun refresh(): Status? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        runCatching { authorized { get("/v1/me", it) }.reply()?.status }.getOrNull()
    }

    /**
     * Novelo+ bought: [store] "play" with the store's [token] (the server checks it with Google), or
     * "test" (the GitHub build: nothing is charged). The new status, or [PurchaseFailed].
     */
    suspend fun purchase(store: String, token: String? = null): Status = withContext(Dispatchers.IO) {
        val ask = json.encodeToString(Purchase.serializer(), Purchase(store, token))
        val r = authorized { post("/v1/purchase", ask, it) }
        val reply = r.reply()
        reply?.status?.takeIf { r.code == 200 } ?: throw PurchaseFailed(r.code, reply?.error)
    }

    /** A request with the pass; an expired pass (or a new server secret) gets a new one, once. */
    private fun authorized(send: (String) -> Answer): Answer {
        val first = send(token())
        if (first.code != 401) return first
        prefs.edit().remove(TOKEN).apply()
        return send(token())
    }

    /** The pass, from before or asked now. */
    private fun token(): String {
        prefs.getString(TOKEN, null)?.let { return it }
        val r = post("/v1/session", json.encodeToString(Session.serializer(), Session(install, device())), null)
        val reply = r.reply()
        val token = reply?.token ?: throw NoveloUnavailable(r.code, reply?.error)
        prefs.edit().putString(TOKEN, token).apply()
        return token
    }

    private inner class Answer(val code: Int, val body: String) {
        /** What the server said, its status kept for the screen. */
        fun reply(): Reply? = runCatching { json.decodeFromString(Reply.serializer(), body) }.getOrNull()?.also { r ->
            r.status?.let { st ->
                _status.value = st
                prefs.edit().putString(STATUS, json.encodeToString(Status.serializer(), st)).apply()
            }
        }
    }

    private fun post(path: String, body: String, token: String?): Answer =
        send(Request.Builder().url(base().trimEnd('/') + path).post(body.toRequestBody(JSON_TYPE)), token)

    private fun get(path: String, token: String?): Answer = send(Request.Builder().url(base().trimEnd('/') + path).get(), token)

    private fun send(builder: Request.Builder, token: String?): Answer {
        builder.header("user-agent", agent)
        token?.let { builder.header("authorization", "Bearer $it") }
        val c = client.newCall(builder.build())
        call = c
        return c.execute().use { Answer(it.code, it.body.string()) }
    }

    @Serializable
    data class Count(val used: Int = 0, val limit: Int? = null) {
        /** How much of it is used, 0..1 (no limit: 0). */
        val fraction: Float get() = limit?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) } ?: if (limit == 0) 1f else 0f
    }

    @Serializable
    data class Window(val day: Count = Count(), val week: Count = Count())

    @Serializable
    data class Limits(val talk: Pair = Pair(), val photo: Pair = Pair()) {
        @Serializable
        data class Pair(val day: Int? = null, val week: Int? = null)
    }

    @Serializable
    data class Offer(
        val product: String = "novelo_plus",
        @SerialName("base_plan") val basePlan: String = "monthly",
        @SerialName("test_purchases") val testPurchases: Boolean = false,
        val limits: Limits = Limits(),
    )

    @Serializable
    data class Status(
        /** trial, plus, tester, owner, ended, blocked. */
        val plan: String = TRIAL,
        @SerialName("trial_days") val trialDays: Int = 7,
        @SerialName("trial_ends") val trialEnds: Long? = null,
        @SerialName("sub_until") val subUntil: Long? = null,
        @SerialName("sub_store") val subStore: String? = null,
        val talk: Window = Window(),
        val photo: Window = Window(),
        @SerialName("day_resets") val dayResets: Long = 0,
        @SerialName("week_resets") val weekResets: Long = 0,
        val offer: Offer = Offer(),
    ) {
        /** The plans and the payment are for her to see (not a tester's, not the owner's). */
        val showsPlans: Boolean get() = plan == TRIAL || plan == PLUS || plan == ENDED
    }

    @Serializable
    private data class Session(val install: String, val device: String? = null)

    @Serializable
    private data class Organize(val system: String, val user: String, val kind: String, val turn: String)

    @Serializable
    private data class Purchase(val store: String, val token: String? = null)

    @Serializable
    private data class Reply(
        val text: String? = null,
        val token: String? = null,
        val error: String? = null,
        val kind: String? = null,
        val status: Status? = null,
        @SerialName("reset_at") val resetAt: Long? = null,
    )

    companion object {
        const val ANSWER_TIMEOUT_S = 120L
        const val TRIAL = "trial"
        const val PLUS = "plus"
        const val ENDED = "ended"
        private const val INSTALL = "install"
        private const val TOKEN = "token"
        private const val STATUS = "status"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}

/**
 * A limit reached: talks (or photos, [photo]) of the day, or of the week ([weekly]; in the trial, the
 * whole trial, [trial]) — [limit] of them. What she said waits for [resetAt] (ms).
 */
class Limit(val photo: Boolean, val weekly: Boolean, val trial: Boolean, val limit: Int?, val resetAt: Long) : Exception("limit")

/** The trial is over and there is no Novelo+: subscribe, or use her own intelligence. */
class NoPlan : Exception("no plan")

/** The server's day is full (its spending ceiling): what she said waits, at most until [resetAt]. */
class ServerBusy(val resetAt: Long) : Exception("busy")

/** The server or its model didn't answer ([code], [error] as the server said it). */
class NoveloUnavailable(val code: Int, val error: String?) : Exception("novelo $code ${error.orEmpty()}")

/** A purchase the server didn't take ([code], [error]: not_entitled, billing_not_configured, test_purchases_off…). */
class PurchaseFailed(val code: Int, val error: String?) : Exception("purchase $code ${error.orEmpty()}")
