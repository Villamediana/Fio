package com.villamediana.fio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PiecesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** "Speech" (a tone) with a quiet gap at [gapAt] seconds. */
    private fun speech(seconds: Int, gapAt: Double): FloatArray {
        val rate = Wav.RATE
        return FloatArray(seconds * rate) { i ->
            val t = i.toDouble() / rate
            if (abs(t - gapAt) < 0.2) 0.0005f else (0.3 * sin(2 * PI * 220 * t)).toFloat()
        }
    }

    @Test
    fun cutsInTheQuietBeforeTheLimit() {
        val s = speech(40, gapAt = 27.0)
        val parts = Pieces.cut(s, maxSeconds = 30)
        assertEquals(2, parts.size)
        val cutAt = parts[0].size.toDouble() / Wav.RATE
        assertTrue("cut at $cutAt", abs(cutAt - 27.0) < 0.25)
        assertEquals(s.size, parts.sumOf { it.size })
    }

    @Test
    fun shortStaysWhole() {
        val s = speech(10, gapAt = 5.0)
        assertEquals(1, Pieces.cut(s, 30).size)
    }

    @Test
    fun silenceIsSilence() {
        assertTrue(Pieces.isSilent(FloatArray(Wav.RATE * 3) { 0.001f }))
        assertFalse(Pieces.isSilent(speech(2, gapAt = 10.0)))
    }

    @Test
    fun wavRoundTrip() {
        val f = tmp.newFile("a.wav")
        val s = FloatArray(1600) { (it % 100) / 100f - 0.5f }
        Wav.write(f, s)
        val back = Wav.readFloats(f)
        assertEquals(s.size, back.size)
        assertArrayEquals(s, back, 1f / 16000)
        assertEquals(100L, Wav.durationMs(f))
    }

    @Test
    fun levelsDrawHowLoudAlongTheLength() {
        val f = java.io.File.createTempFile("levels", ".wav")
        // Quiet first half, loud second half.
        Wav.write(f, FloatArray(Wav.RATE) { i -> (if (i < Wav.RATE / 2) 0.05f else 0.5f) * (if (i % 2 == 0) 1f else -1f) })
        val l = Wav.levels(f, 4)
        org.junit.Assert.assertEquals(4, l.size)
        org.junit.Assert.assertEquals(1f, l[3], 0.001f)
        org.junit.Assert.assertEquals(0.1f, l[0], 0.01f)
        f.delete()
    }
}
