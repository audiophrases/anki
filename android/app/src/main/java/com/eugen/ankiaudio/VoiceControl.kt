package com.eugen.ankiaudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Spoken-command input for car mode: chains short recognizer sessions into a
 * listening loop. The loop must be paused while TTS is playing (the mic would
 * hear Andrew, especially through car speakers) and resumed when playback
 * ends — the service wires that to CardSpeaker's playback callbacks.
 *
 * Prefers the on-device recognizer (fast, offline, no chimes); falls back to
 * the default service.
 */
class VoiceControl(
    private val context: Context,
    private val onCommand: (Command) -> Unit,
) {

    enum class Command { REVEAL, REPEAT, GOOD, EASY, HARD, AGAIN, UNDO, BOOKMARK, GESTURES, STOP }

    private companion object {
        const val TAG = "VoiceControl"
        const val RESTART_DELAY_MS = 300L
        const val COMMAND_DEBOUNCE_MS = 1200L

        /**
         * Some ROMs advertise on-device recognition but every session fails
         * (e.g. TOO_MANY_REQUESTS with Android System Intelligence missing).
         * After this many hard errors with no successful mic-open, switch to
         * the default recognition service.
         */
        const val FALLBACK_AFTER_ERRORS = 3

        /**
         * Sticky across sessions (process-wide): once the on-device recognizer
         * proves broken, later instances go straight to the default service
         * instead of spending ~15s rediscovering the failure.
         */
        var onDeviceBroken = false

        val WORD_TO_COMMAND = mapOf(
            "show" to Command.REVEAL,
            "answer" to Command.REVEAL,
            "reveal" to Command.REVEAL,
            "flip" to Command.REVEAL,
            "repeat" to Command.REPEAT,
            "replay" to Command.REPEAT,
            "good" to Command.GOOD,
            "easy" to Command.EASY,
            "hard" to Command.HARD,
            "again" to Command.AGAIN,
            "wrong" to Command.AGAIN,
            "undo" to Command.UNDO,
            "bookmark" to Command.BOOKMARK,
            "mark" to Command.BOOKMARK,
            "gestures" to Command.GESTURES,
            "help" to Command.GESTURES,
            "commands" to Command.GESTURES,
            "stop" to Command.STOP,
            "finish" to Command.STOP,
        )

        val WORD_REGEX = Regex("[a-z']+")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var listening = false
    private var paused = false
    private var lastCommandAt = 0L
    private var usingOnDevice = false
    private var hardErrors = 0

    fun start() {
        if (running) return
        recognizer = when {
            !onDeviceBroken && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                Log.i(TAG, "using on-device recognizer")
                usingOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }
            SpeechRecognizer.isRecognitionAvailable(context) -> {
                Log.i(TAG, "using default recognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            else -> {
                Log.w(TAG, "no speech recognition available on this device")
                return
            }
        }
        recognizer?.setRecognitionListener(listener)
        running = true
        listen()
    }

    /** The on-device recognizer is broken here; retry with the default service. */
    private fun fallBackToDefaultService() {
        onDeviceBroken = true
        usingOnDevice = false
        hardErrors = 0
        recognizer?.destroy()
        recognizer = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no default recognition service either; voice control off")
            running = false
            return
        }
        Log.w(TAG, "on-device recognizer keeps failing; switching to default service")
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        relistenSoon()
    }

    fun stop() {
        running = false
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    /** Mic off while the TTS is talking. */
    fun pause() {
        paused = true
        if (listening) {
            listening = false
            recognizer?.cancel()
        }
    }

    /** Mic back on (playback ended). */
    fun resume() {
        paused = false
        listen()
    }

    private fun listen() {
        val r = recognizer ?: return
        if (!running || paused || listening) return
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                ArrayList(WORD_TO_COMMAND.keys)
            )
        }
        r.startListening(intent)
    }

    private fun relistenSoon() {
        if (!running || paused) return
        handler.postDelayed({ listen() }, RESTART_DELAY_MS)
    }

    /** Latest command word in the recognized text wins. */
    private fun match(texts: List<String>?): Command? {
        val text = texts?.firstOrNull()?.lowercase() ?: return null
        return WORD_REGEX.findAll(text).toList().asReversed()
            .firstNotNullOfOrNull { WORD_TO_COMMAND[it.value] }
    }

    private fun fire(texts: List<String>?): Boolean {
        val cmd = match(texts) ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastCommandAt < COMMAND_DEBOUNCE_MS) return true
        lastCommandAt = now
        Log.i(TAG, "command: $cmd (heard: ${texts?.firstOrNull()})")
        onCommand(cmd)
        return true
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.i(TAG, "results: ${texts?.firstOrNull() ?: "(empty)"}")
            fire(texts)
            relistenSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Act on partials so commands land fast.
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (fire(texts)) {
                listening = false
                recognizer?.cancel()
                relistenSoon()
            }
        }

        override fun onError(error: Int) {
            listening = false
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.w(TAG, "missing RECORD_AUDIO permission; voice control off")
                running = false
                return
            }
            // Timeouts / no-match are the normal idle case — keep looping.
            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NO_MATCH
            ) {
                Log.i(TAG, "idle: ${errorName(error)}")
            } else {
                Log.w(TAG, "recognizer error: ${errorName(error)}")
                if (usingOnDevice && ++hardErrors >= FALLBACK_AFTER_ERRORS) {
                    fallBackToDefaultService()
                    return
                }
            }
            relistenSoon()
        }

        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "mic open")
            hardErrors = 0
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "speech detected")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        else -> "code $error"
    }
}
