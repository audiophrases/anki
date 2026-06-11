package com.eugen.ankiaudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Control panel: pick a deck, then either start an in-app study round
 * (buttons on screen) or hand off to [StudyService] for the eyes-free,
 * screen-off session. Nothing speaks until a start button is pressed.
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
    private lateinit var studyButton: Button
    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine

    private var decks: List<AnkiDroidApi.Deck> = emptyList()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) populateDecks() else status("Permission denied — can't read AnkiDroid.")
    }

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
        studyButton = findViewById(R.id.studyButton)
        speaker = CardSpeaker(this, lifecycleScope)
        engine = StudyEngine(this, speaker) { msg ->
            runOnUiThread {
                status(msg)
                questionView.text = engine.current?.question ?: questionView.text
                answerView.text = if (engine.answerShown) engine.current?.answer ?: "" else ""
                updateStudyButton()
            }
        }
        engine.onFinished = { runOnUiThread { updateStudyButton() } }

        findViewById<Button>(R.id.sessionButton).setOnClickListener {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                requestNotifPermission.launch(perm)
            }
        }
        findViewById<Button>(R.id.touchButton).setOnClickListener {
            lifecycleScope.launch { if (engine.active) engine.stop() }
            startActivity(
                Intent(this, TouchStudyActivity::class.java)
                    .putExtra(TouchStudyActivity.EXTRA_DECK, selectedDeckName())
            )
        }
        studyButton.setOnClickListener { toggleStudy() }
        findViewById<Button>(R.id.replayButton).setOnClickListener { engine.replayQuestion() }
        findViewById<Button>(R.id.showButton).setOnClickListener {
            lifecycleScope.launch { engine.reveal() }
        }
        findViewById<Button>(R.id.goodButton).setOnClickListener {
            lifecycleScope.launch { engine.rate(AnkiDroidApi.EASE_GOOD) }
        }
        findViewById<Button>(R.id.againButton).setOnClickListener {
            lifecycleScope.launch { engine.rate(AnkiDroidApi.EASE_AGAIN) }
        }
        findViewById<Button>(R.id.undoButton).setOnClickListener {
            lifecycleScope.launch { engine.undo() }
        }

        // Only list the decks on launch — no card is loaded, nothing speaks.
        val perm = AnkiDroidApi.READ_WRITE_PERMISSION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            populateDecks()
        } else {
            requestPermission.launch(perm)
        }
    }

    override fun onDestroy() {
        engine.stopBlocking() // commits a pending rating
        speaker.shutdown()
        super.onDestroy()
    }

    // ---- deck picking ----

    private fun populateDecks() {
        try {
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
            deckSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val name = decks[position].name
                    if (name != prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)) {
                        prefs.edit().putString(PREF_DECK, name).apply()
                        lifecycleScope.launch { if (engine.active) engine.stop() }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            status("Ready — pick a deck and press start.")
        } catch (e: Exception) {
            status("Error: ${e.message} (is AnkiDroid installed and opened once?)")
        }
    }

    private fun selectedDeckName(): String = prefs.getString(PREF_DECK, DEFAULT_DECK_NAME)!!

    // ---- in-app study ----

    private fun toggleStudy() {
        if (engine.active) {
            lifecycleScope.launch {
                engine.stop()
                questionView.text = ""
                answerView.text = ""
                updateStudyButton()
            }
        } else {
            stopService(Intent(this, StudyService::class.java)) // no two engines at once
            lifecycleScope.launch {
                engine.start(selectedDeckName())
                updateStudyButton()
            }
        }
    }

    private fun updateStudyButton() {
        studyButton.text = if (engine.active) "■ Stop study" else "▶ Start study (here)"
    }

    // ---- eyes-free session ----

    private fun startSession() {
        lifecycleScope.launch { if (engine.active) engine.stop() }
        startForegroundService(
            Intent(this, StudyService::class.java)
                .setAction(StudyService.ACTION_START)
                .putExtra(StudyService.EXTRA_DECK, selectedDeckName())
        )
        status(
            "Session running — set your volume now, then lock the screen. " +
                "Vol-up: reveal/Good · vol-down: replay/Again. Undo is in the notification."
        )
    }

    private fun status(msg: String) {
        statusView.text = msg
    }
}
