package com.eugen.ankiaudio

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** PCM assembly shared by Edge and platform TTS. Timings match the listening preview. */
internal object SentencePcm {
    const val RATE = 24_000
    const val AFTER_BLANK_MS = 300
    const val JOIN_MS = 30
    const val LEAD_IN_MS = 288

    data class Part(val samples: ShortArray, val blank: Boolean = false)

    /** Trim only outer silence, retaining quiet consonants and all internal pauses. */
    fun trim(samples: ShortArray): ShortArray {
        require(samples.isNotEmpty()) { "Empty synthesized audio" }
        val hop = RATE / 200
        val rms = (samples.indices step hop).map { start ->
            val end = minOf(start + hop, samples.size)
            sqrt((start until end).sumOf { samples[it].toDouble().pow(2) } / (end - start))
        }
        val threshold = max(32768 * 10.0.pow(-45.0 / 20), rms.max() * 10.0.pow(-35.0 / 20))
        val first = rms.indexOfFirst { it > threshold }
        require(first >= 0) { "Synthesized audio is silent" }
        val last = rms.indexOfLast { it > threshold }
        val pad = RATE * 20 / 1000
        return samples.copyOfRange(maxOf(0, first * hop - pad), minOf(samples.size, (last + 1) * hop + pad))
    }

    fun fade(samples: ShortArray): ShortArray = samples.copyOf().apply {
        val n = minOf(RATE * 3 / 1000, size / 2)
        for (i in 0 until n) {
            this[i] = (this[i] * i.toDouble() / n).roundToInt().toShort()
            this[lastIndex - i] = (this[lastIndex - i] * i.toDouble() / n).roundToInt().toShort()
        }
    }

    /** Only blanks get extra silence, always AFTER the recording, even at line end. */
    fun assemble(parts: List<Part>, leadIn: Boolean = true): ShortArray {
        require(parts.isNotEmpty())
        val lead = if (leadIn) RATE * LEAD_IN_MS / 1000 else 0
        val join = RATE * JOIN_MS / 1000
        val after = RATE * AFTER_BLANK_MS / 1000
        val size = lead + parts.sumOf { it.samples.size + if (it.blank) after else 0 } +
            (parts.size - 1) * join
        val output = ShortArray(size)
        var offset = lead
        for ((i, part) in parts.withIndex()) {
            if (i > 0) offset += join
            part.samples.copyInto(output, offset)
            offset += part.samples.size
            if (part.blank) offset += after
        }
        return output
    }

    /** Platform engines can emit other sample rates or stereo. Convert to our mono rate. */
    fun resample(samples: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        require(sampleRate > 0 && channels > 0 && samples.size >= channels)
        val frames = samples.size / channels
        fun mono(frame: Int) = (0 until channels).sumOf { samples[frame * channels + it].toDouble() } / channels
        return ShortArray((frames.toLong() * RATE / sampleRate).toInt()) { i ->
            val pos = i.toDouble() * sampleRate / RATE
            val a = pos.toInt().coerceAtMost(frames - 1)
            val b = minOf(a + 1, frames - 1)
            (mono(a) + (mono(b) - mono(a)) * (pos - a)).roundToInt().toShort()
        }
    }

    fun writeWav(output: OutputStream, samples: ShortArray) {
        fun int(value: Int) { repeat(4) { output.write(value ushr (8 * it) and 255) } }
        fun short(value: Int) { output.write(value and 255); output.write(value ushr 8 and 255) }
        output.write("RIFF".toByteArray()); int(36 + samples.size * 2)
        output.write("WAVEfmt ".toByteArray()); int(16); short(1); short(1)
        int(RATE); int(RATE * 2); short(2); short(16)
        output.write("data".toByteArray()); int(samples.size * 2)
        samples.forEach { short(it.toInt()) }
    }
}
