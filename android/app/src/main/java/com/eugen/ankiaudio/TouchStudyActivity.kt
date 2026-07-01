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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 *   Any phase:       two-finger tap = Undo · three-finger tap = show the
 *                    gesture chart · long-press = bookmark-tag the card
 *                    (tag "audio-bookmark", for later desktop editing)
 *
 * The gesture chart is on demand only (three-finger tap, or "gestures" in car
 * mode) — never forced at the start of a session, so a user who knows the
 * gestures never sees it. It is a visual overlay (colour-coded tap zones and a
 * legend) that briefly restores screen brightness, then dims back on dismiss;
 * card audio keeps playing behind it.
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

        /** Backlight for eyes-free study vs. while the gesture chart is up. */
        private const val DIM_BRIGHTNESS = 0.01f
        private const val CHART_BRIGHTNESS = 0.6f
    }

    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine
    private lateinit var root: FrameLayout
    private lateinit var stateView: TextView
    private var voice: VoiceControl? = null

    /** Multi-finger handling: suppress single-finger gestures around it. */
    private var twoFingerDownAt = 0L
    private var threeFingerDownAt = 0L
    private var fourFingerDownAt = 0L
    private var suppressSingleUntil = 0L
    private var exiting = false

    /** The on-demand gesture chart overlay, non-null only while it is showing. */
    private var chartOverlay: View? = null

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
        setBrightness(DIM_BRIGHTNESS)
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
                        threeFingerDownAt = 0
                        twoFingerDownAt = 0
                        suppressSingleUntil = fourFingerDownAt + 900
                    }
                    ev.pointerCount == 3 -> {
                        // Three fingers: speak the gesture reminder on demand.
                        threeFingerDownAt = SystemClock.elapsedRealtime()
                        twoFingerDownAt = 0
                        suppressSingleUntil = threeFingerDownAt + 800
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
                        threeFingerDownAt > 0 && now - threeFingerDownAt < 500 -> {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            showGestureChart()
                        }
                        twoFingerDownAt > 0 && now - twoFingerDownAt < 450 -> {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            lifecycleScope.launch { engine.undo() }
                        }
                    }
                    twoFingerDownAt = 0
                    threeFingerDownAt = 0
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
                VoiceControl.Command.GESTURES -> showGestureChart()
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

    /**
     * On-demand visual gesture chart (three-finger tap, or "gestures" in car
     * mode). A full-screen overlay of colour-coded tap zones — red top half =
     * Again/Hard, green bottom half = Good/Easy, mirroring where you actually
     * tap — plus a legend of the whole-screen gestures. Restores readable
     * brightness while up; a tap anywhere dismisses it and dims back down.
     * Purely visual, so the card audio keeps playing behind it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showGestureChart() {
        if (chartOverlay != null) return
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun line(text: String, size: Float, color: String, top: Int, bottom: Int) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.parseColor(color))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(top), dp(20), dp(bottom))
            }

        // A rating half: heading + single-tap action + double-tap action.
        fun zone(bg: String, heading: String, tap: String, doubleTap: String) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor(bg))
                addView(TextView(context).apply {
                    text = heading
                    textSize = 12f
                    setTextColor(Color.parseColor("#99FFFFFF"))
                    gravity = Gravity.CENTER
                    letterSpacing = 0.2f
                })
                addView(TextView(context).apply {
                    text = tap
                    textSize = 25f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                addView(TextView(context).apply {
                    text = doubleTap
                    textSize = 16f
                    setTextColor(Color.parseColor("#CCFFFFFF"))
                    gravity = Gravity.CENTER
                })
            }

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5000000"))
            isClickable = true // consume touches so nothing leaks to the study surface
            setOnClickListener { hideGestureChart() }

            addView(line("Gestures", 20f, "#FFFFFFFF", top = 18, bottom = 2), matchWrap())
            addView(
                line("When the answer is showing, tap the half you mean",
                    13f, "#AAFFFFFF", top = 0, bottom = 10),
                matchWrap()
            )
            addView(zone("#4A1A1E", "TOP HALF", "Again", "double-tap: Hard"), matchWeight())
            addView(zone("#12331F", "BOTTOM HALF", "Good", "double-tap: Easy"), matchWeight())
            addView(
                line("During the question, a tap anywhere reveals the answer",
                    13f, "#CCFFFFFF", top = 12, bottom = 8),
                matchWrap()
            )
            addView(
                line(
                    "Swipe down — replay\n" +
                        "Two fingers — undo\n" +
                        "Long-press — bookmark\n" +
                        "Three fingers — this chart\n" +
                        "Four fingers — stop",
                    15f, "#DDFFFFFF", top = 0, bottom = 8
                ).apply { setLineSpacing(dp(5).toFloat(), 1f) },
                matchWrap()
            )
            addView(line("Tap anywhere to close", 12f, "#88FFFFFF", top = 6, bottom = 18), matchWrap())
        }

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        chartOverlay = overlay
        setBrightness(CHART_BRIGHTNESS)
    }

    private fun hideGestureChart() {
        val overlay = chartOverlay ?: return
        chartOverlay = null
        root.removeView(overlay)
        setBrightness(DIM_BRIGHTNESS)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun matchWeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
    )

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun suppressed(): Boolean = SystemClock.elapsedRealtime() < suppressSingleUntil

    private fun haptic(constant: Int) {
        root.performHapticFeedback(constant)
    }
}
