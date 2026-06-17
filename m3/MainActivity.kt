package com.eugen.ankiaudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var voiceSpinner: Spinner
    private lateinit var studyButton: Button
    private lateinit var editButton: Button
    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine

    private var decks: List<AnkiDroidApi.Deck> = emptyList()
    private var voices: List<EdgeVoices.Voice> = emptyList()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) populateDecks() else status("Permission denied — can't read AnkiDroid.")
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startSession(voice = false) }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCarMode()
        else status("Microphone permission denied — car mode needs it.")
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
        deckSpinner = findViewById(R.id.deckSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        studyButton = findViewById(R.id.studyButton)
        editButton = findViewById(R.id.editButton)
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
                startSession(voice = false)
            } else {
                requestNotifPermission.launch(perm)
            }
        }
        findViewById<Button>(R.id.carButton).setOnClickListener {
            val mic = android.Manifest.permission.RECORD_AUDIO
            if (checkSelfPermission(mic) == PackageManager.PERMISSION_GRANTED) {
                startCarMode()
            } else {
                requestMicPermission.launch(mic)
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
        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            speaker.speak(listOf(Segment.Speech("This is the voice you will hear while studying.")))
        }
        findViewById<Button>(R.id.seedButton).setOnClickListener {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching { AnkiDroidApi.seedTestDeck(this@MainActivity) }
                        .getOrElse { "Seed failed: ${it.message}" }
                }
                populateDecks() // refresh the spinner so the new deck shows up
                status(msg)     // show the result last (populateDecks sets its own status)
                android.util.Log.i("SeedTestDeck", msg)
            }
        }
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
        editButton.setOnClickListener { editCurrentCard() }

        // Only list the decks on launch — no card is loaded, nothing speaks.
        val perm = AnkiDroidApi.READ_WRITE_PERMISSION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            populateDecks()
        } else {
            requestPermission.launch(perm)
        }

        // Voice list is independent of AnkiDroid — load it either way.
        populateVoices()
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

    // ---- voice picking ----

    /**
     * Fill the voice spinner with the full Edge catalog (English first) and
     * keep the learner's choice. The list loads asynchronously — network on a
     * cold first run, then from the on-disk cache — and applies to every study
     * mode via [EdgeVoices.savedVoice], read by [CardSpeaker] per utterance.
     */
    private fun populateVoices() {
        lifecycleScope.launch {
            voices = EdgeVoices.list(this@MainActivity)
            if (voices.isEmpty()) return@launch
            voiceSpinner.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                voices.map { it.label }
            )
            val saved = EdgeVoices.savedVoice(this@MainActivity)
            val savedIdx = voices.indexOfFirst { it.shortName == saved }
            voiceSpinner.setSelection(savedIdx.coerceAtLeast(0), false)
            // Consume the one programmatic selection above so we never overwrite
            // a saved voice that isn't in this (possibly offline fallback) list.
            var initial = true
            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    if (initial) { initial = false; return }
                    EdgeVoices.saveVoice(this@MainActivity, voices[position].shortName)
                    status("Voice set: ${voices[position].label}. Press Test voice to hear it.")
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

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
        val studying = engine.active
        studyButton.text = if (studying) "■  Stop study" else "▶  Start study (here)"
        // "Edit card" is offered only for the visual in-app round ("Start study
        // (here)") — the eyes-free/touch/car modes run in their own screens.
        editButton.visibility = if (studying) View.VISIBLE else View.GONE
    }

    /**
     * Manual edit of the card on screen during an in-app study round: load its
     * note's fields, let the user edit them in a simple dialog, write them back
     * through AnkiDroid, then re-render so the change is spoken on replay.
     */
    private fun editCurrentCard() {
        val card = engine.current
        if (!engine.active || card == null) {
            status("Edit is available only while studying here.")
            return
        }
        lifecycleScope.launch {
            val fields = withContext(Dispatchers.IO) {
                runCatching { AnkiDroidApi.noteFields(this@MainActivity, card.noteId) }.getOrNull()
            }
            if (fields == null) {
                status("Couldn't load this card's fields.")
                return@launch
            }
            showEditDialog(card.noteId, fields)
        }
    }

    /** One labelled text box per note field; Save writes them back via the provider. */
    private fun showEditDialog(noteId: Long, fields: AnkiDroidApi.NoteFields) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputs = fields.values.mapIndexed { i, value ->
            container.addView(TextView(this).apply {
                text = fields.names.getOrElse(i) { "Field ${i + 1}" }
                textSize = 13f
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            EditText(this).apply {
                setText(value)
                setSelection(text.length)
                container.addView(this)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Edit card")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val newValues = inputs.map { it.text.toString() }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            AnkiDroidApi.updateNoteFields(this@MainActivity, noteId, newValues)
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        engine.reloadCurrent()
                        status("Card saved.")
                    } else {
                        status("Saving the edit failed.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- eyes-free session ----

    /** Car mode: bed mode's dark gesture surface + spoken commands. */
    private fun startCarMode() {
        lifecycleScope.launch { if (engine.active) engine.stop() }
        startActivity(
            Intent(this, TouchStudyActivity::class.java)
                .putExtra(TouchStudyActivity.EXTRA_DECK, selectedDeckName())
                .putExtra(TouchStudyActivity.EXTRA_VOICE, true)
        )
        status(
            "Car mode — speak between playbacks: show · repeat · good · easy · " +
                "hard · again · undo · bookmark · stop. Bed-mode gestures work too."
        )
    }

    private fun startSession(voice: Boolean) {
        lifecycleScope.launch { if (engine.active) engine.stop() }
        startForegroundService(
            Intent(this, StudyService::class.java)
                .setAction(StudyService.ACTION_START)
                .putExtra(StudyService.EXTRA_DECK, selectedDeckName())
                .putExtra(StudyService.EXTRA_VOICE, voice)
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
