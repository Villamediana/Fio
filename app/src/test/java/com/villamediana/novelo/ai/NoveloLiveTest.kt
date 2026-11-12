package com.villamediana.novelo.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.mind.Picks
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

    /**
     * A day told with documents sent along (an ID card's photos, a work-card PDF): the documents get
     * their own place (a note, sensitive), the day its diary — not everything in the diary.
     */
    @Test
    fun documentsSentWithADayToldGetTheirOwnNote() = runBlocking {
        assumeTrue(System.getenv("NOVELO_LIVE") == "1")
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-live", Context.MODE_PRIVATE)
        val m = NoveloModels(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), prefs)
        assumeTrue(m.available)
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val now = java.time.LocalDateTime.of(2026, 11, 12, 15, 38).atZone(zone).toInstant().toEpochMilli()
        val sent = listOf(
            com.villamediana.novelo.mind.Photo(1, "/a.jpg", now, "documento de identidade de estrangeiro (RNM) com foto de uma mulher", "REPÚBLICA FEDERATIVA DO BRASIL CARTEIRA DE REGISTRO NACIONAL MIGRATÓRIO SOBRENOME: SILVA PEREIRA NOME: ANA LUCIA DATA DE NASCIMENTO: 01/02/1990 NACIONALIDADE: Chile VALIDADE: 10/02/2031 RNM: F0000000"),
            com.villamediana.novelo.mind.Photo(2, "/b.jpg", now, "pôr do sol laranja visto da janela do escritório", null),
        )
        val files = listOf(com.villamediana.novelo.mind.FileSeen("CTPSContratosDigitais_000.000.000-00.pdf", "application/pdf", 257_000, about = "carteira de trabalho digital com os contratos da Ana"))
        val said = "Bueno, después de todo fue un buen día y estoy trabajando bien. Ese es mi documento de mi cartera de trabajo."
        val context = Brain.context(said, now, zone, openTasks = emptyList(), related = emptyList(), sent = sent, files = files)
        val out = m.answer(Brain.SYSTEM, context) { Brain.parse(it) != null }
        val plan = Brain.parse(out)!!
        println("modelo: " + prefs.getString("last_good", null))
        println(out.take(1200))
        val diary = plan.create.filter { it.kind == "diary" }
        val keeps = plan.create.filter { it.kind != "diary" }
        // The day in the diary, without the documents in its text.
        assertTrue(out, diary.isNotEmpty() && diary.none { (it.body + it.title).contains("documento", ignoreCase = true) })
        // The documents kept apart, sensitive, holding the photos and the file.
        assertTrue(out, keeps.any { 1 in it.photos && it.sensitive } && keeps.any { 1 in it.files && it.sensitive })
        // The sunset is a moment: in no item (it shows in the day's diary by itself).
        assertTrue(out, (plan.create + emptyList()).none { 2 in it.photos || Picks.ALL in it.photos })
        assertTrue(out, diary.none { it.files.isNotEmpty() || it.photos.isNotEmpty() })
    }

    /** The ID card's photo sent alone: a document — a sensitive note holding it, not a moment of the day. */
    @Test
    fun aPhotoOfADocumentSentAloneBecomesASensitiveNote() = runBlocking {
        assumeTrue(System.getenv("NOVELO_LIVE") == "1")
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-live", Context.MODE_PRIVATE)
        val m = NoveloModels(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), prefs)
        assumeTrue(m.available)
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val now = java.time.LocalDateTime.of(2026, 11, 12, 15, 35).atZone(zone).toInstant().toEpochMilli()
        val read = "REPÚBLICA FEDERATIVA DO BRASIL CARTEIRA DE REGISTRO NACIONAL MIGRATÓRIO SOBRENOME: SILVA PEREIRA NOME: ANA LUCIA DATA DE NASCIMENTO: 01/02/1990 NACIONALIDADE: Chile VALIDADE: 10/02/2031 RNM: F0000000"
        val context = Brain.context(read, now, zone, openTasks = emptyList(), related = emptyList(), photo = true, takenAt = now, seen = "documento de identidade de estrangeiro (RNM) com foto de uma mulher")
        val out = m.answer(Brain.SYSTEM, context) { Brain.parse(it) != null }
        println("modelo: " + prefs.getString("last_good", null))
        println(out.take(1200))
        val plan = Brain.parse(out)!!
        assertTrue(out, plan.create.any { it.kind == "note" && it.sensitive })
    }

    /** The work card sent with a day told, the day's diary already there: the file to a note, the day to the diary. */
    @Test
    fun aDocumentSentWithADayToldWhenTheDiaryIsThere() = runBlocking {
        assumeTrue(System.getenv("NOVELO_LIVE") == "1")
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("novelo-live", Context.MODE_PRIVATE)
        val m = NoveloModels(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), prefs)
        assumeTrue(m.available)
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val now = java.time.LocalDateTime.of(2026, 11, 12, 15, 38).atZone(zone).toInstant().toEpochMilli()
        val diary = com.villamediana.novelo.data.Entry(id = 41, kind = "diary", title = "Quinta-feira, 12 de novembro", body = "", createdAt = now - 600_000, updatedAt = now - 600_000)
        val files = listOf(com.villamediana.novelo.mind.FileSeen("CTPSContratosDigitais_000.000.000-00.pdf", "application/pdf", 257_000, about = "carteira de trabalho digital com os contratos da Ana"))
        val said = "Bueno, después de todo fue un buen día y estoy trabajando bien. Ese es mi documento de mi cartera de trabajo."
        val context = Brain.context(said, now, zone, openTasks = emptyList(), related = emptyList(), diary = diary, recent = listOf(diary), files = files)
        val out = m.answer(Brain.SYSTEM, context) { Brain.parse(it) != null }
        println("modelo: " + prefs.getString("last_good", null))
        println(out.take(1200))
        val plan = Brain.parse(out)!!
        val toDiary = plan.update.filter { it.id == 41L } + plan.create.filter { it.kind == "diary" }.map { com.villamediana.novelo.mind.Change(41, append = it.body, files = it.files) }
        assertTrue(out, toDiary.none { it.files.isNotEmpty() })
        assertTrue(out, toDiary.none { (it.append.orEmpty() + it.body.orEmpty()).contains("documento", ignoreCase = true) })
        assertTrue(out, plan.create.any { it.kind == "note" && it.files.isNotEmpty() && it.sensitive })
    }
}
