package com.eugen.ankiaudio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope

/**
 * M1 — eyes-free audio core on top of the proven M0 data path:
 * pick a deck, hear the card (Edge neural TTS, direction-aware blank
 * reading), rate it. Buttons still on screen; background/screen-off
 * playback arrives in M2.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Deck studied on first launch, before the user picks another. */
        const val DEFAULT_DECK_NAME = "Test"
        private const val PREFS = "ankiaudio"
        private const val PREF_DECK = "deck"
    }

    private lateinit var questionView: TextView
    private lateinit var answerView: TextView
    private lateinit var statusView: TextView
    private lateinit var deckSpinner: Spinner
    private lateinit var speaker: CardSpeaker

    private var decks: List<AnkiDroidApi.Deck> = emptyList()
    private var current: AnkiDroidApi.DueCard? = null
    private var shownAt: Long = 0L
    private var questionScript: List<Segment> = emptyList()
    private var answerScript: List<Segment> = emptyList()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStudying() else status("Permission denied — can't read AnkiDroid.")
    }

    // Notification permission is nice-to-have (lock-screen controls); the
    // session starts either way.
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startSession() }

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
        deckSpinner = findViewById(R.id.deckSpinner)
        speaker = CardSpeaker(this, lifecycleScope)

        findViewById<Button>(R.id.sessionButton).setOnClickListener {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                requestNotifPermission.launch(perm)
            }
        }
        findViewById<Button>(R.id.loadButton).setOnClickListener { ensurePermissionThenStart() }
        findViewById<Button>(R.id.replayButton).setOnClickListener {
            if (questionScript.isNotEmpty()) speaker.speak(questionScript)
        }
        findViewById<Button>(R.id.showButton).setOnClickListener { revealAnswer() }
        findViewById<Button>(R.id.goodButton).setOnClickListener { answer(AnkiDroidApi.EASE_GOOD) }
        findViewById<Button>(R.id.againButton).setOnClickListener { answer(AnkiDroidApi.EASE_AGAIN) }

        ensurePermissionThenStart()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun ensurePermissionThenStart() {
        val perm = AnkiDroidApi.READ_WRITE_PERMISSION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            startStudying()
        } else {
            requestPermission.launch(perm)
        }
    }

    /** Populates the deck picker (once), then loads the first card. */
    private fun startStudying() {
        try {
            if (decks.isEmpty()) {
                decks = AnkiDroidApi.decks(this)
                if (decks.isEmpty()) {
                    status("No decks found in AnkiDroid.")
                    return
                }
                val names = decks.map { it.name }
                deckSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, names
                )
                val savedName = prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)
                val savedIdx = names.indexOfFirst { it.equals(savedName, ignoreCase = true) }
                deckSpinner.setSelection(savedIdx.coerceAtLeast(0), false)
                deckSpinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?, view: View?, position: Int, id: Long
                        ) {
                            val name = decks[position].name
                            if (name != prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)) {
                                prefs.edit().putString(PREF_DECK, name).apply()
                                loadNext()
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
            }
            loadNext()
        } catch (e: Exception) {
            status("Error: ${e.message} (is AnkiDroid installed and opened once?)")
        }
    }

    private fun selectedDeck(): AnkiDroidApi.Deck? {
        val name = prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)!!
        return decks.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: decks.firstOrNull()
    }

    private fun loadNext() {
        speaker.stop()
        answerView.text = ""
        try {
            val deck = selectedDeck()
            if (deck == null) {
                status("Pick a deck first.")
                return
            }
            // Answering runs against the selected deck's queue, so select every
            // load (survives AnkiDroid process restarts between our calls).
            AnkiDroidApi.selectDeck(this, deck.id)
            val card = AnkiDroidApi.nextDueCard(this, deck.id)
            current = card
            if (card == null) {
                questionView.text = "🎉 No cards due."
                questionScript = emptyList()
                answerScript = emptyList()
                status("Nothing due in “${deck.name}”.")
            } else {
                questionView.text = card.question
                shownAt = SystemClock.elapsedRealtime()
                questionScript = AudioScript.forQuestion(card.question)
                answerScript = AudioScript.forAnswer(card.answer)
                speaker.speak(questionScript)
                status("Deck “${deck.name}” · note=${card.noteId} ord=${card.ord}")
            }
        } catch (e: SecurityException) {
            status("Permission problem: ${e.message}")
        } catch (e: Exception) {
            status("Error: ${e.message} (is AnkiDroid installed and opened once?)")
        }
    }

    private fun revealAnswer() {
        val card = current ?: return
        answerView.text = card.answer
        if (answerScript.isNotEmpty()) speaker.speak(answerScript)
    }

    private fun answer(ease: Int) {
        val card = current ?: return
        speaker.stop()
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

    private fun startSession() {
        speaker.stop()
        val deckName = prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)!!
        startForegroundService(
            android.content.Intent(this, StudyService::class.java)
                .setAction(StudyService.ACTION_START)
                .putExtra(StudyService.EXTRA_DECK, deckName)
        )
        status(
            "Session running — set your volume now, then lock the screen. " +
                "Vol-up: reveal/Good · vol-down: replay/Again."
        )
    }

    private fun status(msg: String) {
        statusView.text = msg
    }
}
