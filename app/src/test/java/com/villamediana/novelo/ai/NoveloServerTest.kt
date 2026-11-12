package com.villamediana.novelo.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The app's side of the Novelo's server: a pass for this install, the day counted there, and what a "no" means. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoveloServerTest {
    private val server = MockWebServer()
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-server-test", Context.MODE_PRIVATE)
    private lateinit var novelo: NoveloServer

    private val usage = """{"plan":"free","reset_at":1763089200000,"talk":{"used":1,"limit":10},"photo":{"used":0,"limit":3}}"""
    private fun ok(body: String) = MockResponse.Builder().body(body).build()
    private fun status(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Before fun start() {
        prefs.edit().clear().commit()
        server.start()
        novelo = NoveloServer(OkHttpClient(), prefs, server.url("/").toString(), "Novelo/9.9 (Android 16)")
    }

    @After fun stop() { server.close() }

    @Test
    fun aPassForThisInstallThenTheAnswer() = runBlocking {
        server.enqueue(ok("""{"token":"v1.pass","usage":$usage}"""))
        server.enqueue(ok("""{"text":"{\"reply\":\"Guardei.\"}","usage":$usage}"""))
        assertEquals("""{"reply":"Guardei."}""", novelo.answer("Você é o Novelo.", "O QUE ELA DISSE: oi", turn = "42", photo = false))

        val session = server.takeRequest()
        assertEquals("/v1/session", session.url.encodedPath)
        val install = prefs.getString("install", null)!!
        assertTrue(session.body!!.utf8().contains(install))
        assertEquals("Novelo/9.9 (Android 16)", session.headers["user-agent"])
        val ask = server.takeRequest()
        assertEquals("/v1/organize", ask.url.encodedPath)
        assertEquals("Bearer v1.pass", ask.headers["authorization"])
        val body = ask.body!!.utf8()
        assertTrue(body, body.contains("\"kind\":\"talk\"") && body.contains("\"turn\":\"42\"") && body.contains("O QUE ELA DISSE: oi"))
        assertEquals(10, novelo.usage.value!!.talk.limit)

        // The pass is kept: the next one goes straight.
        server.enqueue(ok("""{"text":"{}","usage":$usage}"""))
        novelo.answer("s", "u", turn = "43", photo = true)
        val next = server.takeRequest()
        assertEquals("/v1/organize", next.url.encodedPath)
        assertTrue(next.body!!.utf8().contains("\"kind\":\"photo\""))
        // And the same install code.
        assertEquals(install, prefs.getString("install", null))
    }

    @Test
    fun anExpiredPassIsAskedAgainOnce() = runBlocking {
        prefs.edit().putString("token", "old").commit()
        server.enqueue(status(401, """{"error":"bad_token"}"""))
        server.enqueue(ok("""{"token":"new","usage":$usage}"""))
        server.enqueue(ok("""{"text":"{}","usage":$usage}"""))
        assertEquals("{}", novelo.answer("s", "u", "1", false))
        assertEquals("Bearer old", server.takeRequest().headers["authorization"])
        assertEquals("/v1/session", server.takeRequest().url.encodedPath)
        assertEquals("Bearer new", server.takeRequest().headers["authorization"])
    }

    @Test
    fun todaysLimitSaysWhenItTurns() = runBlocking {
        prefs.edit().putString("token", "t").commit()
        server.enqueue(status(429, """{"error":"daily_limit","kind":"photo","usage":{"plan":"free","reset_at":1763089200000,"talk":{"used":2,"limit":10},"photo":{"used":3,"limit":3}}}"""))
        try {
            novelo.answer("s", "u", "1", true)
            fail("limit")
        } catch (e: DailyLimit) {
            assertTrue(e.photo)
            assertEquals(3, e.limit)
            assertEquals(1763089200000, e.resetAt)
        }
    }

    @Test
    fun aFullDayAndAModelThatDidntAnswer() = runBlocking {
        prefs.edit().putString("token", "t").commit()
        server.enqueue(status(503, """{"error":"busy","reset_at":1763089200000}"""))
        try { novelo.answer("s", "u", "1", false); fail("busy") } catch (e: ServerBusy) { assertEquals(1763089200000, e.resetAt) }
        server.enqueue(status(502, """{"error":"model_unavailable"}"""))
        try { novelo.answer("s", "u", "2", false); fail("unavailable") } catch (e: NoveloUnavailable) { assertEquals(502, e.code) }
    }

    @Test
    fun noNetworkIsNoNetwork() = runBlocking {
        server.close()
        try { novelo.answer("s", "u", "1", false); fail("offline") } catch (e: IOException) { /* waits for the network */ }
    }

    @Test
    fun aBuildWithoutTheServerHasNoNovelo() {
        assertFalse(NoveloServer(OkHttpClient(), prefs, "", "x").available)
        assertTrue(novelo.available)
    }
}
