package com.eugen.ankiaudio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SentencePcmTest {
    @Test fun pauseIsAfterEachBlankIncludingLastAndNeverBefore() {
        val speech = shortArrayOf(101, 102)
        val mhm = shortArrayOf(201, 202, 203)
        val parts = listOf(SentencePcm.Part(speech), SentencePcm.Part(mhm, true),
            SentencePcm.Part(speech), SentencePcm.Part(mhm, true))
        val result = SentencePcm.assemble(parts, leadIn = false)
        val join = 720 // 30 ms at 24 kHz, retained from the approved first preview
        val after = 5400 // exactly 225 ms, only following each mhm
        val firstBlank = speech.size + join
        assertArrayEquals(mhm, result.copyOfRange(firstBlank, firstBlank + mhm.size))
        val nextSpeech = firstBlank + mhm.size + after + join
        assertArrayEquals(speech, result.copyOfRange(nextSpeech, nextSpeech + speech.size))
        assertEquals(2 * speech.size + 2 * mhm.size + 3 * join + 2 * after, result.size)
        assertTrue(result.takeLast(after).all { it == 0.toShort() })
        assertEquals(203.toShort(), result[result.size - after - 1])
    }

    @Test fun adjacentAndInitialBlanksReceiveTheirOwnPostPause() {
        val result = SentencePcm.assemble(List(2) { SentencePcm.Part(shortArrayOf(500), true) }, false)
        assertEquals(500.toShort(), result[0])
        assertEquals(500.toShort(), result[1 + 5400 + 720])
        assertEquals(2 + 10800 + 720, result.size)
    }

    @Test fun outputWarmupOccursOnlyOnce() {
        val parts = List(3) { SentencePcm.Part(shortArrayOf(500), true) }
        val dry = SentencePcm.assemble(parts, false)
        val warm = SentencePcm.assemble(parts)
        assertEquals(6912, warm.size - dry.size)
        assertTrue(warm.take(6912).all { it == 0.toShort() })
        assertArrayEquals(dry, warm.copyOfRange(6912, warm.size))
    }

    @Test fun trimPreservesInternalSilenceAndQuietSpeech() {
        val active = ShortArray(2400) { if (it < 1200) 5000 else -5000 }
        val quietConsonant = ShortArray(480) { 250 }
        val content = quietConsonant + active + ShortArray(9600) + active
        val source = ShortArray(7200) + content + ShortArray(24000)
        val trimmed = SentencePcm.trim(source)
        assertArrayEquals(ShortArray(480) + content + ShortArray(480), trimmed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun silentClipFailsInsteadOfCreatingAnUnboundedBlank() { SentencePcm.trim(ShortArray(24000)) }

    @Test fun stereoPlatformAudioResamplesToMono() {
        val source = shortArrayOf(1000, 3000, 2000, 4000, 3000, 5000, 4000, 6000)
        assertArrayEquals(shortArrayOf(2000, 4000), SentencePcm.resample(source, 48000, 2))
    }

    @Test fun wavHeaderDescribesTheActualSamples() {
        val bytes = ByteArrayOutputStream().also { SentencePcm.writeWav(it, shortArrayOf(-32768, 32767)) }.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals(bytes.size - 8, b.getInt(4))
        assertEquals(24000, b.getInt(24))
        assertEquals(4, b.getInt(40))
        assertEquals((-32768).toShort(), b.getShort(44))
        assertEquals(32767.toShort(), b.getShort(46))
    }
}
