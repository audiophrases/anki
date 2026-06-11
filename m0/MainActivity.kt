package com.eugen.ankiaudio

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * M0 — prove the AnkiDroid data path end to end:
 *   read a due card -> show its text -> rate it (Good/Again) -> write back -> next.
 * No audio yet; that arrives in M1/M2.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** The only deck M0 is allowed to study/rate. */
        const val TEST_DECK_NAME = "Test"
    }

    private lateinit var questionView: TextView
    private lateinit var answerView: TextView
    private lateinit var statusView: TextView

    private var current: AnkiDroidApi.DueCard? = null
    private var shownAt: Long = 0L

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadNext() else status("Permission denied — can't read AnkiDroid.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        questionView = findViewById(R.id.questionView)
        answerView = findViewById(R.id.answerView)
        statusView = findViewById(R.id.statusView)

        findViewById<Button>(R.id.loadButton).setOnClickListener { ensurePermissionThenLoad() }
        findViewById<Button>(R.id.showButton).setOnClickListener { revealAnswer() }
        findViewById<Button>(R.id.goodButton).setOnClickListener { answer(AnkiDroidApi.EASE_GOOD) }
        findViewById<Button>(R.id.againButton).setOnClickListener { answer(AnkiDroidApi.EASE_AGAIN) }

        ensurePermissionThenLoad()
    }

    private fun ensurePermissionThenLoad() {
        val perm = AnkiDroidApi.READ_WRITE_PERMISSION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            loadNext()
        } else {
            requestPermission.launch(perm)
        }
    }

    private fun loadNext() {
        answerView.text = ""
        try {
            // Safety rail while testing against the real collection: only ever
            // touch the deck named "Test". Refuse to run without it.
            val testDeckId = AnkiDroidApi.deckIdByName(this, TEST_DECK_NAME)
            if (testDeckId == null) {
                questionView.text = ""
                status("No deck named \"$TEST_DECK_NAME\" found in AnkiDroid — create it first.")
                return
            }
            // Answering runs against the selected deck's queue, so select every
            // load (survives AnkiDroid process restarts between our calls).
            AnkiDroidApi.selectDeck(this, testDeckId)
            val card = AnkiDroidApi.nextDueCard(this, testDeckId)
            current = card
            if (card == null) {
                questionView.text = "🎉 No cards due."
                status("Nothing due in the current deck.")
            } else {
                questionView.text = card.question
                shownAt = SystemClock.elapsedRealtime()
                status("Loaded. note=${card.noteId} ord=${card.ord} buttons=${card.buttonCount}")
            }
        } catch (e: SecurityException) {
            status("Permission problem: ${e.message}")
        } catch (e: Exception) {
            status("Error: ${e.message} (is AnkiDroid installed and opened once?)")
        }
    }

    private fun revealAnswer() {
        answerView.text = current?.answer ?: ""
    }

    private fun answer(ease: Int) {
        val card = current ?: return
        val elapsed = SystemClock.elapsedRealtime() - shownAt
        try {
            AnkiDroidApi.answerCard(this, card.noteId, card.ord, ease, elapsed)
            Toast.makeText(
                this,
                if (ease == AnkiDroidApi.EASE_AGAIN) "Again" else "Good",
                Toast.LENGTH_SHORT
            ).show()
            loadNext()
        } catch (e: Exception) {
            status("Answer failed: ${e.message}")
        }
    }

    private fun status(msg: String) {
        statusView.text = msg
    }
}
