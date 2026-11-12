package com.villamediana.novelo.ai

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val api = Api(OkHttpClient())
    private lateinit var ep: Api.Endpoint

    @Before fun start() { server.start(); ep = Api.Endpoint(server.url("/openai/v1").toString(), "k-123") }
    @After fun stop() { server.close() }

    @Test
    fun listsModelsAndSendsBothKeyHeaders() {
        server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"gpt-x"},{"id":"whisper-1"},{"id":"gpt-x"}]}""").build())
        assertEquals(listOf("gpt-x", "whisper-1"), api.models(ep))
        val req = server.takeRequest()
        assertEquals("/openai/v1/models", req.url.encodedPath)
        assertEquals("Bearer k-123", req.headers["Authorization"])
        assertEquals("k-123", req.headers["api-key"])
    }

    @Test
    fun anthropicIsAskedInItsOwnMessagesApi() {
        val a = Api.Endpoint(server.url("/v1").toString(), "sk-ant", Wire.ANTHROPIC)
        server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"claude-opus-5-5"},{"id":"claude-haiku-5-5"}]}""").build())
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), api.models(a))
        val list = server.takeRequest()
        assertEquals("/v1/models", list.url.encodedPath)
        assertEquals("sk-ant", list.headers["x-api-key"])
        assertEquals("2023-06-01", list.headers["anthropic-version"])
        assertNull(list.headers["Authorization"])

        // The answer is its text blocks (a thinking block says nothing to read).
        server.enqueue(MockResponse.Builder().body("""{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"reply\":\"ok\"}"}],"stop_reason":"end_turn"}""").build())
        assertEquals("""{"reply":"ok"}""", api.chat(a, "claude-opus-5-5", "sys", "user", image = byteArrayOf(1, 2, 3), effort = "low"))
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("server-side-fallback-2026-07-01", req.headers["anthropic-beta"])
        val body = req.body!!.utf8()
        // The instructions marked for Anthropic's cache.
        assertTrue(body, body.contains("\"system\":[{\"type\":\"text\",\"text\":\"sys\",\"cache_control\":{\"type\":\"ephemeral\"}}]"))
        assertTrue(body, body.contains("\"type\":\"image\""))
        assertTrue(body, body.contains("\"output_config\":{\"effort\":\"low\"}"))
        assertTrue(body, body.contains("\"fallbacks\":\"default\""))
        assertFalse(body, body.contains("response_format"))
    }

    @Test
    fun aModelWithoutServerFallbacksIsAskedPlainly() {
        val a = Api.Endpoint(server.url("/v1").toString(), "sk-ant", Wire.ANTHROPIC)
        server.enqueue(MockResponse.Builder().body("""{"content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn"}""").build())
        api.chat(a, "claude-haiku-4-5", "sys", "user")
        val req = server.takeRequest()
        assertNull(req.headers["anthropic-beta"])
        assertFalse(req.body!!.utf8().contains("fallbacks"))
    }

    @Test
    fun chatAsksForJsonAndReadsTheAnswer() {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"ok\"}"}}]}""").build())
        assertEquals("""{"reply":"ok"}""", api.chat(ep, "gpt-x", "sys", "user"))
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("\"model\":\"gpt-x\""))
    }

    @Test
    fun aPhotoIsLookedAtLightlyWhenAsked() {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"{}"}}]}""").build())
        api.chat(ep, "gpt-x", "s", "u", image = byteArrayOf(1, 2, 3), lowDetail = true)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"detail\":\"low\""))
        assertTrue(body.contains("data:image/jpeg;base64,"))
    }

    @Test
    fun aRefusedRequestIsToldApartAndJsonModeCanBeLeftOut() {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"response_format is not supported"}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"{}"}}]}""").build())
        try {
            api.chat(ep, "tiny", "s", "u")
            fail()
        } catch (e: ApiException) {
            assertTrue(e.refused)
        }
        server.takeRequest()
        assertEquals("{}", api.chat(ep, "tiny", "s", "u", jsonOut = false))
        assertTrue(!server.takeRequest().body!!.utf8().contains("response_format"))
        assertTrue(!ApiException(401, "x").refused)
    }

    @Test
    fun transcribesAWav() {
        server.enqueue(MockResponse.Builder().body("""{"text":" olá mundo "}""").build())
        val wav = tmp.newFile("a.wav").apply { writeBytes(ByteArray(64)) }
        assertEquals("olá mundo", api.transcribe(ep, "whisper-1", wav, "pt"))
        val req = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", req.url.encodedPath)
        val body = req.body!!.utf8()
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("whisper-1"))
    }

    @Test
    fun errorsCarryTheApisMessage() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"The API deployment for this resource does not exist."}}""").build())
        try {
            api.chat(ep, "nope", "s", "u")
            fail()
        } catch (e: ApiException) {
            assertEquals(404, e.code)
            assertEquals("The API deployment for this resource does not exist.", e.message)
        }
    }
}
