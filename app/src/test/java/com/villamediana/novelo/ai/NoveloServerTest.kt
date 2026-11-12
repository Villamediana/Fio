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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The app's side of the Novelo's server: a pass for this phone, the plan and the day counted there, what a "no" means. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoveloServerTest {
    private val server = MockWebServer()
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-server-test", Context.MODE_PRIVATE)
    private lateinit var novelo: NoveloServer

    private fun status(plan: String = "trial", talkDay: Int = 1, talkDayLimit: Int? = 15, talkWeek: Int = 1, photoDay: Int = 0) =
        """{"plan":"$plan","trial_days":7,"trial_ends":1763600000000,"talk":{"day":{"used":$talkDay,"limit":${talkDayLimit ?: "null"}},"week":{"used":$talkWeek,"limit":60}},""" +
            """"photo":{"day":{"used":$photoDay,"limit":5},"week":{"used":0,"limit":20}},"day_resets":1763089200000,"week_resets":1763600000000,""" +
            """"offer":{"product":"novelo_plus","base_plan":"monthly","test_purchases":true,"limits":{"talk":{"day":60,"week":250},"photo":{"day":20,"week":80}}}}"""

    private fun ok(body: String) = MockResponse.Builder().body(body).build()
    private fun code(c: Int, body: String) = MockResponse.Builder().code(c).body(body).build()

    @Before fun start() {
        prefs.edit().clear().commit()
        server.start()
        novelo = NoveloServer(OkHttpClient(), prefs, { server.url("/").toString() }, "Novelo/9.9 (Android 16)", device = { "a".repeat(64) })
    }

    @After fun stop() { server.close() }

    @Test
    fun aPassForThisPhoneThenTheAnswerAndThePlan() = runBlocking {
        server.enqueue(ok("""{"token":"v1.pass","status":${status(talkDay = 0)}}"""))
        server.enqueue(ok("""{"text":"{\"reply\":\"Guardei.\"}","status":${status()}}"""))
        assertEquals("""{"reply":"Guardei."}""", novelo.answer("Você é o Novelo.", "O QUE ELA DISSE: oi", turn = "42", photo = false))

        val session = server.takeRequest()
        assertEquals("/v1/session", session.url.encodedPath)
        val s = session.body!!.utf8()
        assertTrue(s, s.contains(novelo.install) && s.contains("\"device\":\"" + "a".repeat(64)))
        assertEquals("Novelo/9.9 (Android 16)", session.headers["user-agent"])
        val ask = server.takeRequest()
        assertEquals("Bearer v1.pass", ask.headers["authorization"])
        val body = ask.body!!.utf8()
        assertTrue(body, body.contains("\"kind\":\"talk\"") && body.contains("\"turn\":\"42\""))

        // The plan, its limits and what was used: for the bars on the screen.
        val st = novelo.status.value!!
        assertEquals("trial", st.plan)
        assertEquals(1, st.talk.day.used)
        assertEquals(15, st.talk.day.limit)
        assertEquals(60, st.offer.limits.talk.day)
        assertTrue(st.showsPlans)
        // Kept: another start of the app has it before asking.
        assertEquals("trial", NoveloServer(OkHttpClient(), prefs, { server.url("/").toString() }, "x").status.value!!.plan)
    }

    @Test
    fun anExpiredPassIsAskedAgainOnce() = runBlocking {
        prefs.edit().putString("token", "old").commit()
        server.enqueue(code(401, """{"error":"bad_token"}"""))
        server.enqueue(ok("""{"token":"new","status":${status()}}"""))
        server.enqueue(ok("""{"text":"{}","status":${status()}}"""))
        assertEquals("{}", novelo.answer("s", "u", "1", false))
        assertEquals("Bearer old", server.takeRequest().headers["authorization"])
        assertEquals("/v1/session", server.takeRequest().url.encodedPath)
        assertEquals("Bearer new", server.takeRequest().headers["authorization"])
    }

    @Test
    fun aLimitSaysWhichAndWhenItTurns() = runBlocking {
        prefs.edit().putString("token", "t").commit()
        server.enqueue(code(429, """{"error":"daily_limit","kind":"talk","status":${status(talkDay = 15)}}"""))
        try {
            novelo.answer("s", "u", "1", false)
            fail("limit")
        } catch (e: Limit) {
            assertFalse(e.weekly)
            assertTrue(e.trial)
            assertEquals(15, e.limit)
            assertEquals(1763089200000, e.resetAt)
        }
        server.enqueue(code(429, """{"error":"weekly_limit","kind":"talk","status":${status(talkWeek = 60)}}"""))
        try {
            novelo.answer("s", "u", "2", false)
            fail("limit")
        } catch (e: Limit) {
            assertTrue(e.weekly)
            assertEquals(60, e.limit)
            assertEquals(1763600000000, e.resetAt)
        }
    }

    @Test
    fun theTrialOverIsNoPlanAndTheDayFullIsBusy() = runBlocking {
        prefs.edit().putString("token", "t").commit()
        server.enqueue(code(402, """{"error":"no_plan","status":${status(plan = "ended")}}"""))
        try { novelo.answer("s", "u", "1", false); fail("no plan") } catch (e: NoPlan) { /* the plans */ }
        assertEquals("ended", novelo.status.value!!.plan)
        server.enqueue(code(503, """{"error":"busy","reset_at":1763089200000}"""))
        try { novelo.answer("s", "u", "2", false); fail("busy") } catch (e: ServerBusy) { assertEquals(1763089200000, e.resetAt) }
        server.enqueue(code(502, """{"error":"model_unavailable"}"""))
        try { novelo.answer("s", "u", "3", false); fail("unavailable") } catch (e: NoveloUnavailable) { assertEquals(502, e.code) }
    }

    @Test
    fun aPurchaseGoesToTheServerAndComesBackAsNoveloPlus() = runBlocking {
        prefs.edit().putString("token", "t").commit()
        server.enqueue(ok("""{"status":${status(plan = "plus", talkDayLimit = 60)}}"""))
        val st = novelo.purchase("play", "purchase-token")
        assertEquals("plus", st.plan)
        assertEquals("plus", novelo.status.value!!.plan)
        val r = server.takeRequest()
        assertEquals("/v1/purchase", r.url.encodedPath)
        assertTrue(r.body!!.utf8().contains("\"token\":\"purchase-token\""))
        server.enqueue(code(402, """{"error":"not_entitled"}"""))
        try { novelo.purchase("play", "x"); fail("refused") } catch (e: PurchaseFailed) { assertEquals("not_entitled", e.error) }
    }

    @Test
    fun noNetworkIsNoNetwork() = runBlocking {
        server.close()
        try { novelo.answer("s", "u", "1", false); fail("offline") } catch (e: IOException) { /* waits for the network */ }
        assertNull(novelo.refresh())
    }

    @Test
    fun testersAndTheOwnerSeeNoPlans() {
        assertFalse(NoveloServer.Status(plan = "tester").showsPlans)
        assertFalse(NoveloServer.Status(plan = "owner").showsPlans)
        assertTrue(NoveloServer.Status(plan = "ended").showsPlans)
        assertEquals(0.5f, NoveloServer.Count(used = 30, limit = 60).fraction)
        assertEquals(0f, NoveloServer.Count(used = 30, limit = null).fraction)
    }

    @Test
    fun aBuildWithoutTheServerHasNoNovelo() {
        assertFalse(NoveloServer(OkHttpClient(), prefs, { "" }, "x").available)
        assertTrue(novelo.available)
    }
}
