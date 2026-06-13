package com.eugen.ankiaudio

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Warms the [EdgeTts] cache for the cards the learner is about to reach, so a
 * card's audio is already on disk by the time it's presented — no mid-session
 * network wait, and the loop keeps flowing through brief connectivity drops.
 *
 * Driven by [StudyEngine]: each time a card is shown it hands over the next few
 * due cards. Synthesis runs in the background, **one request at a time** (a
 * [Mutex] serializes it — gentle on the unofficial endpoint, and one worker is
 * plenty to stay ahead of a human reader), is de-duplicated against what's
 * already cached or queued, and is cancelled wholesale when the session stops.
 * The playback path never waits on this lock, so a real card is never delayed
 * by prefetching.
 *
 * Best-effort throughout: a failed prefetch (offline, endpoint hiccup) is
 * swallowed — playback synthesizes on demand and falls back to platform TTS on
 * its own. Each finished batch also [TtsCache.trim]s, keeping the cache bounded.
 */
class TtsPrefetcher(private val context: Context) {

    private companion object { const val TAG = "TtsPrefetcher" }

    /** Serializes synthesis so at most one prefetch request is in flight. */
    private val gate = Mutex()

    /** Cache keys synthesized or queued this session — skip repeats. */
    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** Recreated lazily after [stop]; nulled so a stopped session leaks nothing. */
    private var scope: CoroutineScope? = null

    /**
     * Queues background synthesis of every speech line in [cards]' question and
     * answer scripts, in [voice], then trims the cache. Returns immediately.
     */
    @Synchronized
    fun prefetch(cards: List<AnkiDroidApi.DueCard>, voice: String) {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        for (card in cards) {
            s.launch {
                for (text in scriptTexts(card)) {
                    val key = "$voice|$text"
                    if (TtsCache.fileFor(context, voice, text).length() > 0) {
                        seen.add(key); continue
                    }
                    if (!seen.add(key)) continue // already cached this session or queued
                    gate.withLock {
                        try {
                            EdgeTts.synthesize(context, text, voice)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            seen.remove(key) // let a later batch (or playback) retry
                            Log.d(TAG, "prefetch miss for \"${text.take(30)}\": ${e.message}")
                        }
                    }
                }
            }
        }
        s.launch { TtsCache.trim(context) }
    }

    /** Cancels in-flight and queued work. The cached files stay on disk. */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        seen.clear()
    }

    /** Every distinct speech string the engine will play for [card] (Q then A). */
    private fun scriptTexts(card: AnkiDroidApi.DueCard): List<String> =
        (AudioScript.forQuestion(card.question) +
            AudioScript.forAnswer(card.answer, card.question))
            .filterIsInstance<Segment.Speech>()
            .map { it.text }
}
