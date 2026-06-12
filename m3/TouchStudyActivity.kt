package com.eugen.ankiaudio

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M3 — touch-zone study for eyes-closed use (in bed, resting from screens).
 *
 * Full-screen black surface; the bezel is the tactile reference. Pin the app
 * (screen pinning) so stray gestures can't leave it.
 *
 *   Question phase:  tap anywhere = reveal · swipe down = replay
 *   Answer phase:    tap BOTTOM half = Good   (double-tap = Easy)
 *                    tap TOP half    = Again  (double-tap = Hard)
 *   Any phase:       two-finger tap = Undo · long-press = bookmark-tag the
 *                    card (tag "audio-bookmark", for later desktop editing)
 *
 * Every action answers back with haptics + speech, so eyes stay closed.
 * Volume keys stay ordinary volume keys here — touch has enough inputs.
 *
 * Car mode ([EXTRA_VOICE]) runs this same surface with spoken commands on
 * top of the gestures, so the driver can keep both hands on the wheel but
 * still tap blindly when voice is awkward. The backlight is forced to
 * minimum either way — the panel is LCD, so black alone still glows.
 */
class TouchStudyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DECK = "deck"
        const val EXTRA_VOICE = "voice"
        const val BOOKMARK_TAG = "audio-bookmark"
    }

    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine
    private lateinit var root: FrameLayout
    private lateinit var stateView: TextView
    private var voice: VoiceControl? = null

    /** Multi-finger handling: suppress single-finger gestures around it. */
    private var twoFingerDownAt = 0L
    private var fourFingerDownAt = 0L
    private var suppressSingleUntil = 0L
    private var exiting = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stateView = TextView(this).apply {
            setTextColor(Color.parseColor("#555555"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
            text = "…"
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                stateView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        speaker = CardSpeaker(this, lifecycleScope)
        engine = StudyEngine(this, speaker) { msg -> runOnUiThread { stateView.text = msg } }
        engine.onFinished = {
            lifecycleScope.launch {
                delay(5000) // let the closing speech play out
                finish()
            }
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (suppressed()) return true
                haptic(HapticFeedbackConstants.CONFIRM)
                lifecycleScope.launch {
                    if (!engine.answerShown) {
                        engine.reveal()
                    } else {
                        // Single tap = mild rating: bottom Good, top Hard.
                        val bottom = e.y > root.height / 2f
                        engine.rate(
                            if (bottom) AnkiDroidApi.EASE_GOOD else AnkiDroidApi.EASE_HARD
                        )
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (suppressed()) return true
                haptic(HapticFeedbackConstants.CONFIRM)
                lifecycleScope.launch {
                    if (!engine.answerShown) {
                        engine.reveal()
                    } else {
                        // Double tap = extreme rating: bottom Easy, top Again.
                        val bottom = e.y > root.height / 2f
                        engine.rate(
                            if (bottom) AnkiDroidApi.EASE_EASY else AnkiDroidApi.EASE_AGAIN
                        )
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (suppressed()) return
                haptic(HapticFeedbackConstants.LONG_PRESS)
                lifecycleScope.launch { engine.bookmark(BOOKMARK_TAG) }
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (suppressed()) return true
                if (velocityY > 1200 && abs(velocityY) > 2 * abs(velocityX)) {
                    haptic(HapticFeedbackConstants.CONFIRM)
                    engine.replayQuestion()
                }
                return true
            }
        })

        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> when {
                    ev.pointerCount >= 4 -> {
                        // Four fingers trump everything: stop & exit gesture.
                        fourFingerDownAt = SystemClock.elapsedRealtime()
                        twoFingerDownAt = 0
                        suppressSingleUntil = fourFingerDownAt + 900
                    }
                    ev.pointerCount == 2 -> {
                        twoFingerDownAt = SystemClock.elapsedRealtime()
                        suppressSingleUntil = twoFingerDownAt + 700
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.elapsedRealtime()
                    when {
                        fourFingerDownAt > 0 && now - fourFingerDownAt < 600 -> {
                            haptic(HapticFeedbackConstants.LONG_PRESS)
                            stopAndExit()
                        }
                        twoFingerDownAt > 0 && now - twoFingerDownAt < 450 -> {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            lifecycleScope.launch { engine.undo() }
                        }
                    }
                    twoFingerDownAt = 0
                    fourFingerDownAt = 0
                }
            }
            detector.onTouchEvent(ev)
            true
        }

        // Only one engine may study at a time.
        stopService(Intent(this, StudyService::class.java))

        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            voice = VoiceControl(this) { cmd -> onVoiceCommand(cmd) }
            // Mic listens only between TTS playbacks (it would hear Andrew).
            speaker.onPlaybackStart = { voice?.pause() }
            speaker.onPlaybackEnd = { voice?.resume() }
            voice?.start()
        }

        val deckName = intent.getStringExtra(EXTRA_DECK) ?: MainActivity.DEFAULT_DECK_NAME
        lifecycleScope.launch { if (!engine.start(deckName)) finish() }
    }

    override fun onDestroy() {
        voice?.stop()
        engine.stopBlocking() // commits a pending rating
        speaker.shutdown()
        super.onDestroy()
    }

    private fun onVoiceCommand(cmd: VoiceControl.Command) {
        lifecycleScope.launch {
            when (cmd) {
                VoiceControl.Command.REVEAL ->
                    if (!engine.answerShown) engine.reveal()
                VoiceControl.Command.REPEAT -> engine.replay()
                VoiceControl.Command.GOOD ->
                    if (engine.answerShown) engine.rate(AnkiDroidApi.EASE_GOOD)
                VoiceControl.Command.EASY ->
                    if (engine.answerShown) engine.rate(AnkiDroidApi.EASE_EASY)
                VoiceControl.Command.HARD ->
                    if (engine.answerShown) engine.rate(AnkiDroidApi.EASE_HARD)
                VoiceControl.Command.AGAIN ->
                    if (engine.answerShown) engine.rate(AnkiDroidApi.EASE_AGAIN)
                VoiceControl.Command.UNDO -> engine.undo()
                VoiceControl.Command.BOOKMARK -> engine.bookmark(BOOKMARK_TAG)
                VoiceControl.Command.STOP -> stopAndExit()
            }
        }
    }

    /** Four-finger tap: commit, say goodbye, return to the start screen. */
    private fun stopAndExit() {
        if (exiting) return
        exiting = true
        lifecycleScope.launch {
            engine.stop() // commits any pending rating
            speaker.speak(listOf(Segment.Speech("Study stopped.")))
            delay(1500) // let the confirmation play before tearing down
            finish()
        }
    }

    private fun suppressed(): Boolean = SystemClock.elapsedRealtime() < suppressSingleUntil

    private fun haptic(constant: Int) {
        root.performHapticFeedback(constant)
    }
}
