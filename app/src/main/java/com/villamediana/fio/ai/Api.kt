package com.villamediana.fio.ai

import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class ApiException(val code: Int, message: String) : IOException(message) {
    /** The server understood but won't take the request as written (not the key, not the network). */
    val refused: Boolean get() = code == 400 || code == 415 || code == 422
}

/**
 * The provider's API: the list of models, speech to text, and chat. Most speak the OpenAI way
 * (OpenAI, Kimi, OpenRouter, Azure AI Foundry "/openai/v1", a local server): the key goes both as
 * "Authorization: Bearer" and as "api-key" (Azure's header), so the same fields work for all of
 * them. Anthropic is asked in its own Messages API ([Wire.ANTHROPIC]); it doesn't hear audio.
 */
class Api(private val http: OkHttpClient) {
    data class Endpoint(val base: String, val key: String, val wire: Wire = Wire.OPENAI)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun models(ep: Endpoint): List<String> {
        val body = call(ep, Request.Builder().url(url(ep, if (ep.wire == Wire.ANTHROPIC) "models?limit=1000" else "models")).get())
        val root = json.parseToJsonElement(body).jsonObject
        val list = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
        return list.mapNotNull { el ->
            when (el) {
                is JsonObject -> (el["id"] as? JsonPrimitive)?.contentOrNull ?: (el["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> el.contentOrNull
                else -> null
            }
        }.distinct().sorted()
    }

    /** One WAV file → its text. [language]: ISO code ("pt"), null to let the model tell. */
    fun transcribe(ep: Endpoint, model: String, wav: File, language: String?): String {
        if (ep.wire == Wire.ANTHROPIC) throw ApiException(400, t(R.string.api_no_audio))
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .apply { if (language != null) addFormDataPart("language", language) }
            .build()
        val body = call(ep, Request.Builder().url(url(ep, "audio/transcriptions")).post(form))
        val text = runCatching { json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return (text ?: body).trim()
    }

    /** A chat answer. [jsonOut]: asks for a JSON object. [image]: a JPEG seen together with the text. */
    /** [effort]: the reasoning level for a model that reasons ("low"), left out when null. */
    /** [lowDetail]: the photo looked at lightly (a fraction of the tokens; its text comes read already). */
    fun chat(ep: Endpoint, model: String, system: String, user: String, image: ByteArray? = null, jsonOut: Boolean = true, effort: String? = null, lowDetail: Boolean = false): String {
        if (ep.wire == Wire.ANTHROPIC) return messages(ep, model, system, user, image, effort)
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject {
                    put("role", "user")
                    if (image == null) {
                        put("content", user)
                    } else {
                        putJsonArray("content") {
                            addJsonObject { put("type", "text"); put("text", user) }
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP))
                                    if (lowDetail) put("detail", "low")
                                }
                            }
                        }
                    }
                }
            }
            if (jsonOut) putJsonObject("response_format") { put("type", "json_object") }
            if (effort != null) put("reasoning_effort", effort)
        }
        val body = call(ep, Request.Builder().url(url(ep, "chat/completions")).post(payload.toString().toRequestBody(JSON)))
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val content = message?.get("content")
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> ""
        }.ifEmpty { throw ApiException(200, t(R.string.api_empty)) }
    }

    /**
     * Anthropic's Messages API: the instructions as the system, the photo (when there is one)
     * before the words. JSON comes from the instructions (they ask for it and the answer is read
     * from its braces). [effort] goes as output_config.effort; a model that refuses it is asked
     * again without it ([ApiAsks]). On the models that take it, a declined answer is retried by the
     * server on a fallback model ("fallbacks": "default").
     */
    private fun messages(ep: Endpoint, model: String, system: String, user: String, image: ByteArray?, effort: String?): String {
        val fallback = model in FALLBACK_MODELS
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (image != null) addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.encodeToString(image, Base64.NO_WRAP))
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", user) }
                    }
                }
            }
            if (effort != null) putJsonObject("output_config") { put("effort", effort) }
            if (fallback) put("fallbacks", "default")
        }
        val builder = Request.Builder().url(url(ep, "messages")).post(payload.toString().toRequestBody(JSON))
        if (fallback) builder.header("anthropic-beta", FALLBACK_BETA)
        val root = json.parseToJsonElement(call(ep, builder)).jsonObject
        if (root["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw ApiException(200, t(R.string.api_declined))
        val text = root["content"]?.jsonArray.orEmpty().joinToString("") { block ->
            val b = block as? JsonObject ?: return@joinToString ""
            if (b["type"]?.jsonPrimitive?.contentOrNull == "text") b["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
        }
        return text.ifEmpty { throw ApiException(200, t(R.string.api_empty)) }
    }

    private fun url(ep: Endpoint, path: String) = ep.base.trimEnd('/') + "/" + path

    /** Stops the requests going on now (the "parar" on the main screen); the update download is not touched. */
    fun cancelAll() {
        http.dispatcher.runningCalls().filter { it.request().tag(String::class.java) == TAG }.forEach { it.cancel() }
    }

    private fun call(ep: Endpoint, builder: Request.Builder): String {
        when (ep.wire) {
            Wire.OPENAI -> builder.header("Authorization", "Bearer ${ep.key}").header("api-key", ep.key)
            Wire.ANTHROPIC -> builder.header("x-api-key", ep.key).header("anthropic-version", ANTHROPIC_VERSION)
        }
        val request = builder
            .header("Accept", "application/json")
            .tag(String::class.java, TAG)
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw ApiException(r.code, errorText(text) ?: "HTTP ${r.code}")
            return text
        }
    }

    private fun errorText(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val err = root["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> root["message"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()?.take(300)

    private companion object {
        val JSON = "application/json".toMediaType()
        const val TAG = "fio-ai"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** The Claude models whose declined answers the server can retry on another model. */
        val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
