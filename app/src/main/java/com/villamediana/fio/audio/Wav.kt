package com.villamediana.fio.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 16-bit mono PCM WAV files: written while recording, read back as floats, cut into pieces. */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    /** A 44-byte header for [dataBytes] of 16-bit mono PCM at [rate]. */
    fun header(dataBytes: Int, rate: Int = RATE): ByteArray = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes)
    }.array()

    /** Rewrites the header once the size is known (the recorder writes a placeholder first). */
    fun finish(file: File, rate: Int = RATE) {
        RandomAccessFile(file, "rw").use { f ->
            val data = (f.length() - HEADER).coerceAtLeast(0).toInt()
            f.seek(0)
            f.write(header(data, rate))
        }
    }

    /** The samples of a file written by [write]/the recorder, as floats in [-1, 1]. */
    fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val start = dataOffset(bytes)
        val n = (bytes.size - start) / 2
        val buf = ByteBuffer.wrap(bytes, start, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buf.short / 32768f }
    }

    /** How loud the file is along its length, in [bars] steps from 0 to 1 (the loudest is 1): its drawing. */
    fun levels(file: File, bars: Int): FloatArray {
        val samples = readFloats(file)
        if (samples.isEmpty() || bars <= 0) return FloatArray(bars.coerceAtLeast(0))
        val out = FloatArray(bars) { b ->
            val from = (samples.size.toLong() * b / bars).toInt()
            val to = (samples.size.toLong() * (b + 1) / bars).toInt().coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            sqrt(sum / (to - from)).toFloat()
        }
        val top = out.max()
        return if (top <= 0f) out else FloatArray(bars) { out[it] / top }
    }

    fun write(file: File, samples: FloatArray, rate: Int = RATE) {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        file.outputStream().use { it.write(header(samples.size * 2, rate)); it.write(out.array()) }
    }

    fun durationMs(file: File): Long = ((file.length() - HEADER).coerceAtLeast(0) / 2) * 1000L / RATE

    /** Finds the "data" chunk (any extra chunk before it is skipped). */
    private fun dataOffset(bytes: ByteArray): Int {
        if (bytes.size < HEADER || String(bytes, 0, 4) != "RIFF") return 0
        var i = 12
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val len = b.getInt(i + 4)
            if (id == "data") return i + 8
            i += 8 + len + (len and 1)
        }
        return HEADER
    }
}

/**
 * Long speech in pieces the models take: each cut falls in the quietest moment of the last
 * seconds before the limit (between words, not in the middle of one).
 */
object Pieces {
    fun cut(samples: FloatArray, maxSeconds: Int, rate: Int = Wav.RATE, lookBackSeconds: Int = 4): List<FloatArray> {
        val max = maxSeconds * rate
        if (samples.size <= max) return listOf(samples)
        val out = mutableListOf<FloatArray>()
        var start = 0
        val frame = rate / 20 // 50 ms
        while (samples.size - start > max) {
            val limit = start + max
            val from = (limit - lookBackSeconds * rate).coerceAtLeast(start + frame)
            var best = limit
            var bestEnergy = Double.MAX_VALUE
            var at = from
            while (at + frame <= limit) {
                val e = energy(samples, at, frame)
                if (e < bestEnergy) { bestEnergy = e; best = at + frame / 2 }
                at += frame / 2
            }
            out += samples.copyOfRange(start, best)
            start = best
        }
        if (start < samples.size) out += samples.copyOfRange(start, samples.size)
        return out.filter { it.size > rate / 4 }
    }

    /** True when there is (almost) only silence: nothing to transcribe. */
    fun isSilent(samples: FloatArray, rate: Int = Wav.RATE): Boolean {
        if (samples.size < rate / 3) return true
        val frame = rate / 20
        var loud = 0
        var i = 0
        while (i + frame <= samples.size) {
            if (sqrt(energy(samples, i, frame)) > 0.012) loud++
            i += frame
        }
        return loud < 4
    }

    private fun energy(s: FloatArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until (from + n).coerceAtMost(s.size)) sum += s[i] * s[i]
        return sum / n
    }
}
