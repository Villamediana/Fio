package com.villamediana.fio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The microphone at 16 kHz mono, straight to a WAV file, with the voice level (0..1, every 20 ms)
 * for the button's animation. One recording at a time.
 */
class Recorder {
    private val _level = MutableStateFlow(0f)
    /** How loud the voice is right now (0 silence … 1 loud), smoothed a little. */
    val level: StateFlow<Float> = _level

    @Volatile private var running = false
    private var thread: Thread? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = running

    /** Starts writing to [target]. False if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // asked for before the button records
    fun start(target: File): Boolean {
        if (running) return false
        val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Wav.RATE))
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        target.parentFile?.mkdirs()
        file = target
        running = true
        startedAt = System.currentTimeMillis()
        thread = Thread({ loop(record, target) }, "fio-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stops and closes the file. Returns it with its duration, or null if nothing was recorded. */
    fun stop(): Pair<File, Long>? {
        if (!running) return null
        running = false
        thread?.join(1_500)
        thread = null
        _level.value = 0f
        val f = file ?: return null
        file = null
        return runCatching { Wav.finish(f); f to Wav.durationMs(f) }.getOrNull()
    }

    fun elapsedMs(): Long = if (running) System.currentTimeMillis() - startedAt else 0L

    private fun loop(record: AudioRecord, target: File) {
        val frame = Wav.RATE / 50 // 20 ms
        val buf = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        var smooth = 0f
        try {
            BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { out ->
                out.write(Wav.header(0))
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, frame)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += (s * s).toDouble()
                        bytes[2 * i] = (s and 0xFF).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes, 0, n * 2)
                    val rms = sqrt(sum / n) / 32768.0
                    val db = 20 * log10(rms.coerceAtLeast(1e-6))
                    val target01 = ((db + 58) / 46).toFloat().coerceIn(0f, 1f)
                    // Rises fast, falls slower: the orb follows the voice without flicker.
                    smooth += (target01 - smooth) * (if (target01 > smooth) 0.55f else 0.18f)
                    _level.value = smooth
                }
            }
        } catch (_: Exception) {
            running = false
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
