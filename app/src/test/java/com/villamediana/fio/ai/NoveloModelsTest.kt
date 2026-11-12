package com.villamediana.fio.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NoveloModelsTest {
    private val server = MockWebServer()
    private var now = 1_000_000L
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-models-test", Context.MODE_PRIVATE)

    /** OpenRouter's list: free ones that do the job, and the ones that don't. */
    private val listing = """{"data":[
      {"id":"good/a:free","created":300,"context_length":131072,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"supported_parameters":["response_format","reasoning"]},
      {"id":"good/b:free","created":200,"context_length":65536,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":[]},
      {"id":"paid/c","created":900,"context_length":200000,"pricing":{"prompt":"0.000001","completion":"0.000002"},"architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
      {"id":"music/d","created":800,"context_length":1048576,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text"],"output_modalities":["audio","text"]}},
      {"id":"small/e:free","created":700,"context_length":8192,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
      {"id":"openrouter/free","created":999,"context_length":200000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text"],"output_modalities":["text"]}}
    ]}"""

    /** What each model answers (by id), and the order they were asked in. */
    private val answers = HashMap<String, MockResponse>()
    private val asked = mutableListOf<String>()

    @Before fun start() {
        prefs.edit().clear().commit()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath.endsWith("/models") -> MockResponse.Builder().body(listing).build()
                request.url.encodedPath.endsWith("/chat/completions") -> {
                    val model = Regex("\"model\":\"([^\"]+)\"").find(request.body!!.utf8())!!.groupValues[1]
                    synchronized(asked) { asked += model }
                    answers[model] ?: MockResponse.Builder().code(500).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After fun stop() { server.close() }

    private fun models(key: String? = "k") = NoveloModels(
        OkHttpClient(), prefs, key = { key }, clock = { now },
        listUrl = server.url("/api/v1/models").toString(), base = server.url("/api/v1").toString(),
    )

    private fun says(text: String) = MockResponse.Builder().body("""{"choices":[{"message":{"content":${org.json.JSONObject.quote(text)}}}]}""").build()

    private val plan = """{"reply":"ok"}"""
    private val valid: (String) -> Boolean = { it.trim().startsWith("{") }

    @Test
    fun onlyFreeTextModelsWithRoomAreKept() {
        val free = NoveloModels.free(listing)
        assertEquals(listOf("good/a:free", "good/b:free"), free.map { it.id })
        assertTrue(free[0].json && free[0].reasons)
        assertFalse(free[1].json || free[1].reasons)
    }

    @Test
    fun aModelThatDoesntGiveThePlanMakesWayForTheNext() = runBlocking {
        answers["good/a:free"] = says("sorry, I can't")
        answers["good/b:free"] = says(plan)
        val m = models()
        assertEquals(plan, m.answer("sys", "user", valid))
        assertEquals(listOf("good/a:free", "good/b:free"), asked)
        // The one that answered goes first next time; the one that didn't rests.
        assertEquals(listOf("good/b:free"), m.pool().map { it.id })
        // After its rest it is back, behind the one that answered.
        now += NoveloModels.BAD_ANSWER_MS + 1
        assertEquals(listOf("good/b:free", "good/a:free"), m.pool().map { it.id })
    }

    @Test
    fun theKeyRefusedStopsAtOnceWithoutBlamingTheModels() = runBlocking {
        answers["good/a:free"] = MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build()
        val m = models()
        try { m.answer("sys", "user", valid); fail() } catch (e: ApiException) { assertEquals(401, e.code) }
        assertEquals(listOf("good/a:free"), asked)
        assertEquals(2, m.pool().size)
    }

    @Test
    fun aModelClosedToAppsRestsLongAndTheNextAnswers() = runBlocking {
        // 403 here is the model's door ("only available on agentic harnesses"), not the key.
        answers["good/a:free"] = MockResponse.Builder().code(403).body("""{"error":{"message":"only available on agentic harnesses"}}""").build()
        answers["good/b:free"] = says(plan)
        val m = models()
        assertEquals(plan, m.answer("sys", "user", valid))
        now += NoveloModels.BAD_ANSWER_MS + 1
        assertEquals(listOf("good/b:free"), m.pool().map { it.id })
    }

    @Test
    fun theOnesMeasuredGoodComeFirstAndTheBadLast() {
        val ids = listOf("liquid/lfm-3-1.7b:free", "new/x:free", "nvidia/nemotron-3-ultra-550b-a55b:free", "baidu/ernie-5.5-flash-preview:free")
        val list = ids.mapIndexed { i, id -> """{"id":"$id","created":${1000 - i},"context_length":131072,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text"],"output_modalities":["text"]}}""" }
        prefs.edit().putString("models", kotlinx.serialization.json.Json.encodeToString(NoveloModels.free("""{"data":[${list.joinToString(",")}]}"""))).putLong("models_at", now).commit()
        assertEquals(
            listOf("baidu/ernie-5.5-flash-preview:free", "nvidia/nemotron-3-ultra-550b-a55b:free", "new/x:free", "liquid/lfm-3-1.7b:free"),
            models().pool().map { it.id },
        )
    }

    @Test
    fun aModelThatRefusesJsonModeIsAskedPlain() = runBlocking {
        answers["good/a:free"] = MockResponse.Builder().code(400).body("""{"error":{"message":"response_format not supported"}}""").build()
        answers["good/b:free"] = says(plan)
        val m = models()
        assertEquals(plan, m.answer("sys", "user", valid))
        // a was asked twice (with JSON mode, then plain), then b.
        assertEquals(listOf("good/a:free", "good/a:free", "good/b:free"), asked)
    }

    @Test
    fun withoutNetworkItWaitsAndBlamesNoOne() = runBlocking {
        val m = models()
        m.pool() // the list read once (kept)
        server.close()
        try { m.answer("sys", "user", valid); fail() } catch (e: IOException) { assertFalse(e is ApiException) }
        assertEquals(2, m.pool().size)
    }

    @Test
    fun noKeyNoNoveloIntelligence() {
        assertFalse(models(key = null).available)
        assertTrue(models().available)
    }

    @Test
    fun theKeyComesBackFromItsThreeArrays() {
        // What the build does (app/build.gradle.kts `masked`), by hand: shuffled, then masked.
        val key = "sk-test-123"
        val bytes = key.toByteArray()
        val order = intArrayOf(3, 0, 10, 7, 1, 9, 2, 5, 8, 4, 6)
        val mask = IntArray(bytes.size) { (it * 37 + 11) and 0xFF }
        val data = IntArray(bytes.size) { i -> (bytes[order[i]].toInt() and 0xFF) xor mask[i] }
        assertEquals(key, NoveloKey.decode(data, mask, order))
        assertNull(NoveloKey.decode(IntArray(0), IntArray(0), IntArray(0)))
        // Nothing of it as text in the arrays.
        assertFalse(String(ByteArray(data.size) { data[it].toByte() }).contains("sk-"))
    }
}
