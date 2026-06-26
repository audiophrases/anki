package com.eugen.ankiaudio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Plays an audio script (speech / bleep / pause) for one card.
 *
 * Speech goes through Edge neural TTS (cached MP3s) in the voice the learner
 * picked on the start screen ([EdgeVoices.savedVoice]); if synthesis fails
 * (offline, endpoint change) it falls back to the device's own TTS engine so
 * studying never hard-stops.
 */
class CardSpeaker(private val context: Context, private val scope: CoroutineScope) {

    private companion object {
        const val TAG = "CardSpeaker"
    }

    /** Hooks for coordinating other audio users (e.g. pausing the mic). */
    var onPlaybackStart: (() -> Unit)? = null
    var onPlaybackEnd: (() -> Unit)? = null

    private var job: Job? = null
    private var player: MediaPlayer? = null

    private var fallbackReady = false
    private val fallbackTts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        fallbackReady = status == TextToSpeech.SUCCESS
        if (!fallbackReady) Log.w(TAG, "platform TTS init failed: $status")
    }

    /** Starts playing [segments], cancelling whatever was playing before. */
    fun speak(segments: List<Segment>) {
        stop()
        Log.i(TAG, "speak: ${segments.size} segments")
        job = scope.launch {
            onPlaybackStart?.invoke()
            try {
                for ((i, seg) in segments.withIndex()) {
                    Log.i(TAG, "segment $i: $seg")
                    when (seg) {
                        is Segment.Speech -> speakText(seg.text, seg.ssml)
                        is Segment.Bleep -> bleep(seg.durationMs)
                        is Segment.Pause -> delay(seg.durationMs)
                    }
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
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        if (fallbackReady) fallbackTts.stop()
    }

    fun shutdown() {
        stop()
        fallbackTts.shutdown()
    }

    private suspend fun speakText(text: String, ssml: String? = null) {
        val file: File? = try {
            // Read the chosen voice per-utterance so changing it on the start
            // screen takes effect on the next card without a restart.
            val voice = EdgeVoices.savedVoice(context)
            withContext(Dispatchers.IO) { EdgeTts.synthesize(context, text, voice, ssml) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Edge TTS failed (${e.message}); using platform TTS")
            null
        }
        if (file != null) playFile(file) else speakWithPlatformTts(text)
    }

    private suspend fun playFile(file: File) {
        val mp = withContext(Dispatchers.IO) {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
            }
        }
        player = mp
        suspendCancellableCoroutine { cont ->
            mp.setOnCompletionListener {
                if (player === mp) player = null
                mp.release()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                if (player === mp) player = null
                p.release()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            cont.invokeOnCancellation {
                if (player === mp) player = null
                runCatching { mp.stop() }
                mp.release()
            }
            mp.start()
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
