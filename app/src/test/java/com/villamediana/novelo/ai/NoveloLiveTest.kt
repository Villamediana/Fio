package com.villamediana.novelo.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.mind.Brain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The Novelo's intelligence for real (OpenRouter, the build's key): only with NOVELO_LIVE=1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoveloLiveTest {
    @Test
    fun aRealTalkComesBackAsAPlan() = runBlocking {
        assumeTrue(System.getenv("NOVELO_LIVE") == "1")
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-live", Context.MODE_PRIVATE)
        val m = NoveloModels(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), prefs)
        assumeTrue(m.available)
        val context = "AGORA: quarta-feira, 2026-11-11 21:40 (America/Sao_Paulo)\n\nO QUE ELA DISSE:\n\"Amanhã às nove preciso ligar pro Pedro sobre o contrato, e anota que a senha do wifi da casa da minha mãe é girassol123.\"\n"
        val t0 = System.currentTimeMillis()
        val out = m.answer(Brain.SYSTEM, context) { Brain.parse(it) != null }
        val plan = Brain.parse(out)!!
        println("modelo: " + prefs.getString("last_good", null) + " · " + (System.currentTimeMillis() - t0) + " ms")
        println(out.take(600))
        assertTrue(plan.create.any { it.kind == "task" && it.title.contains("Pedro") })
        assertTrue(plan.create.any { it.kind == "note" && (it.body + it.title).contains("girassol") })
    }
}
