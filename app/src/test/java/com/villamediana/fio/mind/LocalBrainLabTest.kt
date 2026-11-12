package com.villamediana.fio.mind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The phone's organizing, end to end against the lab's models (~/novelo-lab): d1 on llama-server
 * (/v1/systemone, which the phone's C++ matches to the digit) and the writer (LFM3-1.7B) on another,
 * held to [LocalBrain.grammar]. Runs only with NOVELO_LAB=<cases.json>; writes the plans beside it
 * for the lab's scorer.
 */
class LocalBrainLabTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 11, 11, 21, 40).atZone(zone).toInstant().toEpochMilli()
    private val tasks = listOf(
        LocalBrain.TaskLine(41, "Fechar contrato da torre de Campinas", "due tomorrow 15:00"),
        LocalBrain.TaskLine(52, "Pagar o IPVA", "due today"),
        LocalBrain.TaskLine(57, "Presente de aniversário da Ana", "reminder tomorrow 09:00"),
    )
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun post(url: String, body: JsonObject): JsonObject {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "$url: ${res.statusCode()} ${res.body().take(400)}" }
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    private fun ask(said: String, qs: List<LocalBrain.Question>): Map<String, DoubleArray> {
        val body = buildJsonObject {
            put("state", LocalBrain.state(said, tasks))
            put("questions", buildJsonObject {
                qs.forEach { q ->
                    put(q.id, buildJsonObject {
                        put("type", q.type.key)
                        put("instructions", q.instructions)
                        if (q.type == LocalBrain.Type.CHOICE) put("criteria", buildJsonObject { q.options.forEach { (k, v) -> put(k, v) } })
                    })
                }
            })
        }
        val a = post("http://127.0.0.1:8902/v1/systemone", body)["answers"]!!.jsonObject
        return qs.associate { q ->
            val x = a[q.id]!!.jsonObject
            q.id to if (q.type == LocalBrain.Type.NOUL) x["noul"]!!.jsonPrimitive.double.let { doubleArrayOf(it, 1 - it) }
            else q.options.map { (k, _) -> x["probabilities"]!!.jsonObject[k]!!.jsonPrimitive.double }.toDoubleArray()
        }
    }

    @Test
    fun organizesTheLabsCases() {
        val path = System.getenv("NOVELO_LAB")
        assumeTrue(path != null)
        val cases = Json.parseToJsonElement(File(path!!).readText()).jsonArray
        val out = buildJsonArray {
            for (c in cases.map { it.jsonObject }) {
                val said = c["said"]!!.jsonPrimitive.content
                val t0 = System.nanoTime()
                val qs = LocalBrain.questions(tasks)
                var answers = ask(said, qs)
                var d = LocalBrain.decide(qs, answers)
                if (d.note) { answers = answers + ask(said, listOf(LocalBrain.SENSITIVE)); d = LocalBrain.decide(qs + LocalBrain.SENSITIVE, answers) }
                val t1 = System.nanoTime()
                val prompt = Brain.chatml(LocalBrain.writerSystem(d, now, zone, tasks), said)
                val gen = post("http://127.0.0.1:8904/completion", buildJsonObject {
                    put("prompt", prompt); put("grammar", LocalBrain.grammar(d)); put("n_predict", 900); put("temperature", 0.0); put("top_k", 1); put("cache_prompt", false)
                })
                val text = gen["content"]!!.jsonPrimitive.content
                val plan = LocalBrain.plan(d, LocalBrain.filled(text), now, zone, "Quarta-feira, 11 de novembro")
                val t2 = System.nanoTime()
                add(buildJsonObject {
                    put("name", c["name"]!!)
                    put("decision", d.toString())
                    put("writer", text)
                    put("plan", Json.encodeToJsonElement(Plan.serializer(), plan))
                    put("d1_s", (t1 - t0) / 1e9); put("writer_s", (t2 - t1) / 1e9)
                })
                println("${c["name"]}: $d\n   $text")
            }
        }
        File(File(path).parentFile, "plans-kotlin.json").writeText(out.toString())
    }
}
