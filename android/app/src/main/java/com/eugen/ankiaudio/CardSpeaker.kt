package com.eugen.ankiaudio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays an audio script (speech / bleep / pause) for one card.
 *
 * Speech goes through Edge neural TTS (cached MP3s) in the voice the learner
 * picked on the start screen ([EdgeVoices.savedVoice]); if synthesis fails
 * (offline, endpoint change) it falls back to the device's own TTS engine so
 * studying never hard-stops.
 */
class CardSpeaker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val edgeSynthesis: suspend (String, String, String?) -> File = { text, voice, ssml ->
        EdgeTts.synthesize(context, text, voice, ssml)
    },
) {

    private companion object {
        const val TAG = "CardSpeaker"
    }

    /** Hooks for coordinating other audio users (e.g. pausing the mic). */
    var onPlaybackStart: (() -> Unit)? = null
    var onPlaybackEnd: (() -> Unit)? = null

    private var job: Job? = null
    private var player: MediaPlayer? = null

    private var fallbackReady = false
    private val fallbackInitialized = CompletableDeferred<Boolean>()
    private val fallbackTts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        fallbackReady = status == TextToSpeech.SUCCESS
        fallbackInitialized.complete(fallbackReady)
        if (!fallbackReady) Log.w(TAG, "platform TTS init failed: $status")
    }

    /** Starts playing [segments], cancelling whatever was playing before. */
    fun speak(segments: List<Segment>) {
        stop()
        Log.i(TAG, "speak: ${segments.size} segments")
        job = scope.launch {
            onPlaybackStart?.invoke()
            try {
                var i = 0
                while (i < segments.size) {
                    val seg = segments[i]
                    Log.i(TAG, "segment $i: $seg")
                    if (seg is Segment.Speech || seg is Segment.Blank) {
                        var end = i + 1
                        while (end < segments.size &&
                            (segments[end] is Segment.Speech || segments[end] is Segment.Blank)) end++
                        val line = segments.subList(i, end)
                        if (line.any { it is Segment.Blank }) {
                            speakSentence(line)
                            i = end
                            continue
                        }
                    }
                    when (seg) {
                        is Segment.Speech -> speakText(seg.text, seg.ssml, seg.leadIn)
                        is Segment.Blank -> error("Blank must be assembled with its sentence")
                        is Segment.Bleep -> bleep(seg.durationMs)
                        is Segment.Pause -> delay(seg.durationMs)
                    }
                    i++
                }
                Log.i(TAG, "script done")
                onPlaybackEnd?.invoke()
            } catch (e: CancellationException) {
                // Cancelled because a new script starts — its own
                // onPlaybackStart keeps the mic paused; no end signal here.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "playback failed", e)
                onPlaybackEnd?.invoke()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // playFile owns release in its finally block, including cancellation during prepare.
        player?.let { runCatching { it.stop() } }
        player = null
        if (fallbackReady) fallbackTts.stop()
    }

    fun shutdown() {
        stop()
        fallbackTts.shutdown()
    }

    private suspend fun speakSentence(line: List<Segment>) {
        val voice = EdgeVoices.savedVoice(context)
        suspend fun parts(platform: Boolean): List<SentencePcm.Part> = line.map { segment ->
            when (segment) {
                is Segment.Blank -> SentencePcm.Part(SentenceAudio.blank(context), blank = true)
                is Segment.Speech -> {
                    val temporary = if (platform) File.createTempFile("platform-tts-", ".wav", context.cacheDir) else null
                    try {
                        val file = if (temporary != null) {
                            synthesizeWithPlatformTts(segment.text, temporary)
                            temporary
                        } else withContext(Dispatchers.IO) {
                            edgeSynthesis(segment.text, voice, segment.ssml)
                        }
                        withContext(Dispatchers.IO) {
                            SentencePcm.Part(SentencePcm.fade(SentencePcm.trim(SentenceAudio.decode(file))))
                        }
                    } finally {
                        temporary?.delete()
                    }
                }
                else -> error("Unexpected sentence part: $segment")
            }
        }
        val prepared = try {
            parts(platform = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sentence synthesis failed (${e.message}); assembling with platform TTS")
            parts(platform = true)
        }
        val file = File.createTempFile("sentence-", ".wav", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                file.outputStream().buffered().use { SentencePcm.writeWav(it, SentencePcm.assemble(prepared)) }
            }
            playFile(file, leadIn = true)
        } finally {
            file.delete()
        }
    }

    private suspend fun synthesizeWithPlatformTts(text: String, file: File): Unit = withTimeoutOrNull(15_000) {
        check(fallbackInitialized.await()) { "Platform TTS unavailable" }
        suspendCancellableCoroutine<Unit> { cont ->
            val id = UUID.randomUUID().toString()
            fallbackTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive)
                        cont.resumeWithException(IllegalStateException("Platform synthesis failed"))
                }
            })
            val result = fallbackTts.synthesizeToFile(text, android.os.Bundle(), file, id)
            if (result == TextToSpeech.ERROR && cont.isActive)
                cont.resumeWithException(IllegalStateException("Platform synthesis rejected"))
            cont.invokeOnCancellation { fallbackTts.stop() }
        }
    } ?: error("Platform synthesis timed out")

    private suspend fun speakText(text: String, ssml: String? = null, leadIn: Boolean = true) {
        val file: File? = try {
            // Read the chosen voice per-utterance so changing it on the start
            // screen takes effect on the next card without a restart.
            val voice = EdgeVoices.savedVoice(context)
            withContext(Dispatchers.IO) { edgeSynthesis(text, voice, ssml) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Edge TTS failed (${e.message}); using platform TTS")
            null
        }
        if (file != null) playFile(file, leadIn) else speakWithPlatformTts(text)
    }

    private suspend fun playFile(file: File, leadIn: Boolean) {
        val mp = MediaPlayer()
        try {
            withContext(Dispatchers.IO) {
                mp.apply {
                    // Assembled sentences already contain a single warm-up lead-in.
                    val skip = if (leadIn) 0L else EdgeTts.leadInLength(file)
                    if (skip > 0) {
                        FileInputStream(file).use { setDataSource(it.fd, skip, file.length() - skip) }
                    } else {
                        setDataSource(file.path)
                    }
                    prepare()
                }
            }
            player = mp
            suspendCancellableCoroutine { cont ->
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
                    true
                }
                mp.start()
            }
        } finally {
            if (player === mp) player = null
            mp.release()
        }
    }

    private suspend fun speakWithPlatformTts(text: String) {
        if (!fallbackReady) {
            Log.w(TAG, "no TTS available for: ${text.take(40)}")
            return
        }
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            fallbackTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            fallbackTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { fallbackTts.stop() }
        }
    }

    private suspend fun bleep(durationMs: Int) {
        // Max volume + a sharp alert tone: the bleep marks the blanked word's
        // position inside the sentence, so it must be impossible to miss.
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        try {
            tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, durationMs)
            delay(durationMs + 80L)
        } finally {
            tg.release()
        }
    }
}
