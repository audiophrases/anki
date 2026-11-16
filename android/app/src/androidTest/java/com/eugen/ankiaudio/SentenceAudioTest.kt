package com.eugen.ankiaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceAudioTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun decodeAndAssembleApprovedSentenceOnDevice() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val parts = mutableListOf<SentencePcm.Part>()
        for (name in listOf("prefix", "things")) {
            val mp3 = File.createTempFile("fixture-", ".mp3", context.cacheDir)
            try {
                testContext.assets.open("$name.mp3").use { input -> mp3.outputStream().use { input.copyTo(it) } }
                val pcm = SentencePcm.fade(SentencePcm.trim(SentenceAudio.decode(mp3)))
                assertTrue(pcm.size in 8000..26000)
                parts += SentencePcm.Part(pcm)
                parts += SentencePcm.Part(SentenceAudio.blank(context), true)
            } finally { mp3.delete() }
        }
        val result = SentencePcm.assemble(parts)
        assertEquals(8664, parts[1].samples.size)
        assertTrue(result.takeLast(7200).all { it == 0.toShort() })
        assertTrue(result.size / 24000.0 in 3.0..3.4) // includes one 288 ms route warm-up
        File(context.cacheDir, "spoken-blanks-device-preview.wav").outputStream().buffered().use {
            SentencePcm.writeWav(it, result)
        }
    }

    @Test fun offlinePlatformTtsStillUsesTheRecordedBlank() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ended = CompletableDeferred<Unit>()
        var edgeRequests = 0
        val speaker = withContext(Dispatchers.Main) {
            CardSpeaker(context, scope) { _, _, _ -> edgeRequests++; error("Simulated network failure") }.apply {
                onPlaybackEnd = { ended.complete(Unit) }
                speak(listOf(Segment.Speech("I still need to"), Segment.Blank,
                    Segment.Speech("things", leadIn = false), Segment.Blank))
            }
        }
        try {
            withTimeout(25000) {
                while (context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("sentence-") && it.length() > 44 }) {
                    check(!ended.isCompleted) { "Fallback failed before generating its sentence" }
                    delay(25)
                }
                ended.await()
            }
            assertEquals(1, edgeRequests)
            assertFalse(context.cacheDir.listFiles().orEmpty().any {
                it.name.startsWith("platform-tts-") || it.name.startsWith("sentence-")
            })
        } finally {
            withContext(Dispatchers.Main) { speaker.shutdown() }
            scope.cancel()
        }
    }

    @Test fun replacingPlaybackCancelsAndCleansTheOldSentence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ended = CompletableDeferred<Unit>()
        var finishes = 0
        val speaker = withContext(Dispatchers.Main) {
            CardSpeaker(context, scope).apply {
                onPlaybackEnd = { finishes++; ended.complete(Unit) }
                speak(List(20) { Segment.Blank })
            }
        }
        try {
            delay(500)
            withContext(Dispatchers.Main) { speaker.speak(listOf(Segment.Blank)) }
            withTimeout(5000) { ended.await() }
            assertEquals(1, finishes)
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("sentence-") })
        } finally {
            withContext(Dispatchers.Main) { speaker.shutdown() }
            scope.cancel()
        }
    }

    /** Explicit opt-in integration check; ordinary tests never require the remote service. */
    @Test fun liveEdgeV2Synthesis() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEdge") == "true")
        val file = EdgeTts.synthesize(context, "Spoken blanks connection test, version two.")
        assertTrue(SentencePcm.trim(SentenceAudio.decode(file)).size > 24000)
    }
}
