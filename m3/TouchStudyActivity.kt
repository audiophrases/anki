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
 */
class TouchStudyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DECK = "deck"
        const val BOOKMARK_TAG = "audio-bookmark"
    }

    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine
    private lateinit var root: FrameLayout
    private lateinit var stateView: TextView

    /** Two-finger handling: suppress single-finger gestures around it. */
    private var twoFingerDownAt = 0L
    private var suppressSingleUntil = 0L

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
                        val bottom = e.y > root.height / 2f
                        engine.rate(
                            if (bottom) AnkiDroidApi.EASE_GOOD else AnkiDroidApi.EASE_AGAIN
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
                        val bottom = e.y > root.height / 2f
                        engine.rate(
                            if (bottom) AnkiDroidApi.EASE_EASY else AnkiDroidApi.EASE_HARD
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
                MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                    twoFingerDownAt = SystemClock.elapsedRealtime()
                    suppressSingleUntil = twoFingerDownAt + 700
                }
                MotionEvent.ACTION_UP -> {
                    val downAt = twoFingerDownAt
                    if (downAt > 0 && SystemClock.elapsedRealtime() - downAt < 450) {
                        twoFingerDownAt = 0
                        haptic(HapticFeedbackConstants.CONFIRM)
                        lifecycleScope.launch { engine.undo() }
                    }
                    twoFingerDownAt = 0
                }
            }
            detector.onTouchEvent(ev)
            true
        }

        // Only one engine may study at a time.
        stopService(Intent(this, StudyService::class.java))

        val deckName = intent.getStringExtra(EXTRA_DECK) ?: MainActivity.DEFAULT_DECK_NAME
        lifecycleScope.launch { if (!engine.start(deckName)) finish() }
    }

    override fun onDestroy() {
        engine.stopBlocking() // commits a pending rating
        speaker.shutdown()
        super.onDestroy()
    }

    private fun suppressed(): Boolean = SystemClock.elapsedRealtime() < suppressSingleUntil

    private fun haptic(constant: Int) {
        root.performHapticFeedback(constant)
    }
}
