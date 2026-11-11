package com.villamediana.fio.ai

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun chatAsksForJsonAndReadsTheAnswer() {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"ok\"}"}}]}""").build())
        assertEquals("""{"reply":"ok"}""", api.chat(ep, "gpt-x", "sys", "user"))
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("\"model\":\"gpt-x\""))
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
