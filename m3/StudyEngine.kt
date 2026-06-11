package com.eugen.ankiaudio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The study loop shared by the in-app screen and the eyes-free session
 * service: load → speak → reveal → rate → next, plus **undo**.
 *
 * AnkiDroid's API has no undo, so ratings are not written immediately: the
 * latest rating is held as [pending] and only committed when the *next*
 * rating happens (or the session ends). Undo simply discards the pending
 * rating and re-presents that card. One-step undo, which matches the real
 * mistake: "that key press a moment ago was wrong."
 */
class StudyEngine(
    private val context: Context,
    private val speaker: CardSpeaker,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "StudyEngine"
    }

    private data class Pending(val card: AnkiDroidApi.DueCard, val ease: Int, val timeTakenMs: Long)

    var deck: AnkiDroidApi.Deck? = null
        private set
    var current: AnkiDroidApi.DueCard? = null
        private set
    var answerShown = false
        private set
    var active = false
        private set

    /** Called when the deck runs out of due cards. */
    var onFinished: (() -> Unit)? = null

    private var pending: Pending? = null
    private var shownAt = 0L
    private var questionScript: List<Segment> = emptyList()
    private var answerScript: List<Segment> = emptyList()

    /** Resolves the deck and presents the first card. False if deck not found. */
    suspend fun start(deckName: String): Boolean {
        deck = withContext(Dispatchers.IO) {
            runCatching {
                AnkiDroidApi.decks(context).firstOrNull { it.name.equals(deckName, ignoreCase = true) }
            }.getOrNull()
        }
        if (deck == null) {
            speaker.speak(listOf(Segment.Speech("Deck $deckName not found.")))
            return false
        }
        active = true
        loadNext(confirm = "Studying ${deck!!.name}.")
        return true
    }

    /** Stops speaking and commits any pending rating. */
    suspend fun stop() {
        active = false
        speaker.stop()
        commitPending()
        current = null
        answerShown = false
        onState("Stopped.")
    }

    /** Synchronous variant for Service.onDestroy — must not lose a rating. */
    fun stopBlocking() {
        active = false
        speaker.stop()
        runBlocking { commitPending() }
    }

    suspend fun reveal() {
        if (!active) return
        val card = current ?: return
        answerShown = true
        speaker.speak(answerScript)
        onState("Answer · up: Good · down: Again")
        Log.i(TAG, "revealed note=${card.noteId}")
    }

    fun replayQuestion() {
        if (!active || current == null) return
        speaker.speak(questionScript)
    }

    suspend fun rate(ease: Int) {
        if (!active) return
        val card = current ?: return
        commitPending()
        pending = Pending(card, ease, SystemClock.elapsedRealtime() - shownAt)
        Log.i(TAG, "pending ease=$ease note=${card.noteId}")
        loadNext(confirm = confirmWord(ease))
    }

    private fun confirmWord(ease: Int): String = when (ease) {
        AnkiDroidApi.EASE_AGAIN -> "Again."
        AnkiDroidApi.EASE_HARD -> "Hard."
        AnkiDroidApi.EASE_EASY -> "Easy."
        else -> "Good."
    }

    /** Tags the current card's note (e.g. for later editing on the desktop). */
    suspend fun bookmark(tag: String) {
        val card = current ?: return
        val ok = withContext(Dispatchers.IO) {
            runCatching { AnkiDroidApi.addTagToNote(context, card.noteId, tag) }.getOrDefault(false)
        }
        Log.i(TAG, "bookmark note=${card.noteId} ok=$ok")
        speaker.speak(listOf(Segment.Speech(if (ok) "Bookmarked." else "Bookmark failed.")))
    }

    /** Reverts the last rating, if it hasn't been committed yet. */
    suspend fun undo() {
        if (!active) return
        val p = pending
        if (p == null) {
            speaker.speak(listOf(Segment.Speech("Nothing to undo.")))
            return
        }
        pending = null
        current = p.card
        answerShown = false
        shownAt = SystemClock.elapsedRealtime()
        questionScript = AudioScript.forQuestion(p.card.question)
        answerScript = AudioScript.forAnswer(p.card.answer)
        speaker.speak(
            listOf(Segment.Speech("Undone."), Segment.Pause(300)) + questionScript
        )
        onState("Question (undone) · up: reveal · down: replay")
        Log.i(TAG, "undo note=${p.card.noteId}")
    }

    private suspend fun loadNext(confirm: String? = null) {
        speaker.stop()
        answerShown = false
        val d = deck ?: return
        val excluded = pending?.card

        var next = withContext(Dispatchers.IO) {
            AnkiDroidApi.selectDeck(context, d.id)
            AnkiDroidApi.nextDueCards(context, d.id, if (excluded == null) 1 else 2)
        }.firstOrNull { excluded == null || it.noteId != excluded.noteId || it.ord != excluded.ord }

        if (next == null && excluded != null) {
            // Only the pending card remains in the queue: commit it (losing the
            // undo window for it) and re-query — it may come straight back as a
            // learning step.
            commitPending()
            next = withContext(Dispatchers.IO) {
                AnkiDroidApi.nextDueCards(context, d.id, 1).firstOrNull()
            }
        }

        current = next
        if (next == null) {
            active = false
            onState("No cards due 🎉")
            speaker.speak(listOf(Segment.Speech("Congratulations, no more cards due.")))
            onFinished?.invoke()
            return
        }

        shownAt = SystemClock.elapsedRealtime()
        questionScript = AudioScript.forQuestion(next.question)
        answerScript = AudioScript.forAnswer(next.answer)
        val script = buildList {
            if (confirm != null) {
                add(Segment.Speech(confirm))
                add(Segment.Pause(300))
            }
            addAll(questionScript)
        }
        speaker.speak(script)
        onState("Question · up: reveal · down: replay")
        Log.i(TAG, "question note=${next.noteId} ord=${next.ord}")
    }

    private suspend fun commitPending() {
        val p = pending ?: return
        pending = null
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                AnkiDroidApi.answerCard(context, p.card.noteId, p.card.ord, p.ease, p.timeTakenMs)
            }.isSuccess
        }
        Log.i(TAG, "committed ease=${p.ease} note=${p.card.noteId} ok=$ok")
        if (!ok) speaker.speak(listOf(Segment.Speech("Warning: saving a rating failed.")))
    }
}
