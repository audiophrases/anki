package com.eugen.ankiaudio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.text.HtmlCompat

/**
 * Thin wrapper over AnkiDroid's ContentProvider API (FlashCardsContract).
 *
 * No external dependency required — we talk to the provider directly using the
 * documented URIs and column names.
 *   https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API
 *   https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt
 */
object AnkiDroidApi {

    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** content://com.ichi2.anki.flashcards/schedule — query for due cards, update to answer. */
    val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")

    /** content://com.ichi2.anki.flashcards/selected_deck — update to change AnkiDroid's selected deck. */
    val SELECTED_DECK_URI: Uri = Uri.parse("content://$AUTHORITY/selected_deck")

    // Ease values the scheduler accepts (AnkiDroid maps these to Again/Hard/Good/Easy).
    const val EASE_AGAIN = 1
    const val EASE_HARD = 2
    const val EASE_GOOD = 3
    const val EASE_EASY = 4

    data class DueCard(
        val noteId: Long,
        val ord: Int,
        val buttonCount: Int,
        val question: String,
        val answer: String,
    )

    /**
     * Makes [deckId] AnkiDroid's globally selected deck.
     *
     * Required before answering: the provider answers against the *selected*
     * deck's queue (its schedule query only selects a deck temporarily, and
     * answering with a mismatched queue crashes AnkiDroid with
     * "card was modified").
     */
    fun selectDeck(context: Context, deckId: Long): Boolean {
        val values = ContentValues().apply { put("deck_id", deckId) }
        return context.contentResolver.update(SELECTED_DECK_URI, values, null, null) > 0
    }

    data class Deck(val id: Long, val name: String)

    /** All decks, sorted by name ("::" in names = AnkiDroid sub-deck hierarchy). */
    fun decks(context: Context): List<Deck> {
        val out = mutableListOf<Deck>()
        val decksUri = Uri.parse("content://$AUTHORITY/decks")
        context.contentResolver.query(decksUri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow("deck_name")
            val idIdx = c.getColumnIndexOrThrow("deck_id")
            while (c.moveToNext()) {
                out += Deck(c.getLong(idIdx), c.getString(nameIdx))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Looks up a deck's ID by its name (e.g. "Test"). Returns null if no such deck. */
    fun deckIdByName(context: Context, name: String): Long? {
        val decksUri = Uri.parse("content://$AUTHORITY/decks")
        context.contentResolver.query(decksUri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow("deck_name")
            val idIdx = c.getColumnIndexOrThrow("deck_id")
            while (c.moveToNext()) {
                if (c.getString(nameIdx).equals(name, ignoreCase = true)) {
                    return c.getLong(idIdx)
                }
            }
        }
        return null
    }

    /**
     * Returns the next due card from [deckId] (or the last-selected deck if null),
     * or null if nothing is due.
     */
    fun nextDueCard(context: Context, deckId: Long? = null): DueCard? {
        val selection = if (deckId != null) "limit=?, deckID=?" else "limit=?"
        val args = if (deckId != null) arrayOf("1", deckId.toString()) else arrayOf("1")

        context.contentResolver.query(SCHEDULE_URI, null, selection, args, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val noteId = c.getLong(c.getColumnIndexOrThrow("note_id"))
            val ord = c.getInt(c.getColumnIndexOrThrow("ord"))
            val buttonCount = c.getInt(c.getColumnIndexOrThrow("button_count"))
            val (q, a) = cardText(context, noteId, ord)
            return DueCard(noteId, ord, buttonCount, q, a)
        }
        return null
    }

    /**
     * Rendered question/answer for a specific card of a note.
     * Columns actually available (verified on AnkiDroid 2.2x, Android 16):
     * [_id, note_id, ord, card_name, deck_id, question, answer] — the *_simple
     * variants from older docs don't exist; question/answer are full HTML.
     */
    private fun cardText(context: Context, noteId: Long, ord: Int): Pair<String, String> {
        val cardUri = Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord")
        context.contentResolver.query(cardUri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val q = c.getString(c.getColumnIndexOrThrow("question")) ?: ""
                val a = c.getString(c.getColumnIndexOrThrow("answer")) ?: ""
                return stripHtml(q) to stripHtml(answerSideOnly(a))
            }
        }
        return "" to ""
    }

    /**
     * The rendered answer normally embeds the whole question above an
     * <hr id=answer> divider; keep only what's below it.
     */
    private fun answerSideOnly(html: String): String {
        val marker = Regex("<hr[^>]*id=\"?answer\"?[^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?: return html
        return html.substring(marker.range.last + 1)
    }

    /**
     * Sends a rating back to AnkiDroid, which reschedules the card itself
     * (FSRS / SM-2 handled by AnkiDroid; sync stays consistent).
     */
    fun answerCard(context: Context, noteId: Long, ord: Int, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put("note_id", noteId)
            put("ord", ord)
            put("answer_ease", ease)
            put("time_taken", timeTakenMs)
        }
        context.contentResolver.update(SCHEDULE_URI, values, null, null)
    }

    private fun stripHtml(html: String): String {
        // fromHtml() would render <style>/<script> contents as visible text.
        val cleaned = html
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?is)<script.*?</script>"), "")
        return HtmlCompat.fromHtml(cleaned, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
