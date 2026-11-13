package com.villamediana.novelo.mind

import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.Job
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.ui.settings.intelligenceSummary
import com.villamediana.novelo.ui.setup.earsNeeded
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** Who hears: the Novelo's ears on the phone, or her API's transcription model (its own provider, key and model). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HearingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val graph get() = NoveloApplication.graph
    private val st get() = graph.settings
    private val asked = AtomicInteger()
    private lateinit var hearing: Hearing

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath.endsWith("/audio/transcriptions") -> MockResponse.Builder().body("""{"text":" pedaço ${asked.incrementAndGet()} "}""").build()
                request.url.encodedPath.endsWith("/models") -> MockResponse.Builder().body("""{"data":[{"id":"whisper-1"}]}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        hearing = Hearing(st, graph.api, graph.engine, graph.localModel, tmp.newFolder("pieces"))
    }

    @After fun stop() {
        server.close()
        runBlocking {
            st.setHear(Source.NOVELO)
            st.setApiProvider(Job.HEAR, "")
            st.setApiBase(Job.HEAR, "")
            st.setApiKey(Job.HEAR, "")
            st.setModel(Job.HEAR, "")
            st.setApiProvider(Job.THINK, "")
            st.setApiKey(Job.THINK, "")
        }
    }

    /** [seconds] of a voice-like tone (not silence). */
    private fun wav(seconds: Int): File {
        val n = seconds * Wav.RATE
        val samples = FloatArray(n) { i -> 0.3f * sin(2 * PI * 220 * i / Wav.RATE).toFloat() }
        return File(tmp.root, "voice-$seconds-${System.nanoTime()}.wav").also { Wav.write(it, samples) }
    }

    private suspend fun herApi(model: String = "whisper-1") {
        st.setHear(Source.API)
        st.setApiProvider(Job.HEAR, "custom")
        st.setApiBase(Job.HEAR, server.url("/v1").toString())
        st.setApiKey(Job.HEAR, "hear-key")
        st.setModel(Job.HEAR, model)
    }

    @Test
    fun theNovelosEarsNeedToBeOnThePhone() = runBlocking {
        st.setHear(Source.NOVELO)
        // This build has no engine: it says so, and nothing goes anywhere.
        try {
            hearing.transcribe(wav(2)) {}
            fail()
        } catch (e: NotReady) {
            assertEquals(Need.HEAR, e.what)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun herApiHearsTheWholeRecordingAtOnce() = runBlocking {
        st.setHear(Source.API)
        // Not set up yet: what is missing, in words.
        try {
            hearing.transcribe(wav(2)) {}
            fail()
        } catch (e: NotReady) {
            assertEquals(Need.HEAR, e.what)
            assertEquals("configure a API de quem ouve em Ajustes", e.message)
        }
        st.setApiProvider(Job.HEAR, "custom")
        st.setApiBase(Job.HEAR, server.url("/v1").toString())
        st.setApiKey(Job.HEAR, "hear-key")
        try {
            hearing.transcribe(wav(2)) {}
            fail()
        } catch (e: NotReady) {
            assertEquals("escolha o modelo que ouve em Ajustes", e.message)
        }
        st.setModel(Job.HEAR, "whisper-1")

        val pieces = ArrayList<Heard>()
        val text = hearing.transcribe(wav(3), onPiece = { pieces += it }) {}
        assertEquals("pedaço 1", text)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
        // Its own key, not the one that thinks.
        assertEquals("Bearer hear-key", req.headers["Authorization"])
        assertTrue(req.body!!.utf8().contains("whisper-1"))
        assertEquals("api:300p", pieces.last().by)
        // Only the ears on the phone hear while she talks.
        assertNull(hearing.ahead(wav(40), Heard(), 0))
    }

    @Test
    fun aLongRecordingGoesToHerApiInPieces() = runBlocking {
        herApi()
        // ~11 minutes: too big for one request (over 20 MB), so 5-minute pieces, each kept.
        val pieces = ArrayList<Heard>()
        val text = hearing.transcribe(wav(660), onPiece = { pieces += it }) {}
        assertEquals("pedaço 1 pedaço 2 pedaço 3", text)
        assertEquals(3, server.requestCount)
        assertEquals(3, pieces.last().total)
        assertEquals(3, pieces.last().pieces)
        // A fall after the first piece goes on from there.
        asked.set(10)
        val again = hearing.transcribe(wav(660), from = pieces.first { it.pieces == 1 }) {}
        assertEquals("pedaço 1 pedaço 11 pedaço 12", again)
    }

    @Test
    fun theSameKeyIsHersToShareByHand() = runBlocking {
        st.setApiProvider(Job.THINK, "openai")
        st.setApiKey(Job.THINK, "think-key")
        st.setApiProvider(Job.HEAR, "openai")
        // Nothing is shared by itself.
        assertTrue(!st.current().api(Job.HEAR).hasKey)
        st.shareKey(Job.THINK, Job.HEAR)
        assertEquals("think-key", st.apiKey(Job.HEAR))
        assertEquals("https://api.openai.com/v1", st.current().api(Job.HEAR).address)
    }

    @Test
    fun theFirstStepsWaitForTheEarsOnlyWhenTheyHear() {
        assertTrue(earsNeeded(Source.NOVELO, true, LocalModel.State.Missing))
        assertTrue(earsNeeded(Source.NOVELO, true, LocalModel.State.Downloading(0.4f, 1, 2)))
        assertTrue(!earsNeeded(Source.NOVELO, true, LocalModel.State.Ready))
        // Her API hears: nothing to download, nothing to wait for.
        assertTrue(!earsNeeded(Source.API, true, LocalModel.State.Missing))
        // A build without the engine can't have them.
        assertTrue(!earsNeeded(Source.NOVELO, false, LocalModel.State.Missing))
    }

    @Test
    fun theSummarySaysWhoHearsAndWhoThinks() = runBlocking {
        val ready = LocalModel.State.Ready
        st.setHear(Source.NOVELO)
        // This build has no engine: the ears are what is missing.
        assertTrue(intelligenceSummary(st.current(), ready, novelo = true).second)
        herApi()
        assertEquals("ouve com whisper-1 · Inteligência do Novelo" to false, intelligenceSummary(st.current(), LocalModel.State.Missing, novelo = true))
        st.setModel(Job.HEAR, "")
        assertEquals("falta o modelo que ouve" to true, intelligenceSummary(st.current(), LocalModel.State.Missing, novelo = true))
    }
}
