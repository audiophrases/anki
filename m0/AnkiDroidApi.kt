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

    /** A note's field values are stored joined by this separator inside `flds`. */
    private const val FIELD_SEPARATOR = "\u001f"

    data class DueCard(
        val noteId: Long,
        val ord: Int,
        val buttonCount: Int,
        val question: String,
        val answer: String,
        /** The note's first field — the word/phrase being studied. Lets the audio
         *  renderer KNOW the card direction (the word is shown on the recognition
         *  side, hidden on production) instead of guessing from the blanks. */
        val word: String = "",
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
    fun nextDueCard(context: Context, deckId: Long? = null): DueCard? =
        nextDueCards(context, deckId, 1).firstOrNull()

    /**
     * Returns up to [limit] upcoming due cards. Used by the undo machinery,
     * which needs to look one card past a pending (uncommitted) rating.
     */
    fun nextDueCards(context: Context, deckId: Long?, limit: Int): List<DueCard> {
        val selection = if (deckId != null) "limit=?, deckID=?" else "limit=?"
        val args = if (deckId != null) {
            arrayOf(limit.toString(), deckId.toString())
        } else {
            arrayOf(limit.toString())
        }

        val out = mutableListOf<DueCard>()
        context.contentResolver.query(SCHEDULE_URI, null, selection, args, null)?.use { c ->
            val noteIdx = c.getColumnIndexOrThrow("note_id")
            val ordIdx = c.getColumnIndexOrThrow("ord")
            val buttonIdx = c.getColumnIndexOrThrow("button_count")
            while (c.moveToNext()) {
                val noteId = c.getLong(noteIdx)
                val ord = c.getInt(ordIdx)
                val (q, a) = cardText(context, noteId, ord)
                out += DueCard(noteId, ord, c.getInt(buttonIdx), q, a, studiedWord(context, noteId))
            }
        }
        return out
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

    /** The note's first field (the studied word/phrase), HTML stripped. */
    private fun studiedWord(context: Context, noteId: Long): String {
        val uri = Uri.parse("content://$AUTHORITY/notes/$noteId")
        context.contentResolver.query(uri, arrayOf("flds"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val flds = c.getString(0) ?: return ""
                val first = flds.split(FIELD_SEPARATOR).firstOrNull() ?: return ""
                return stripHtml(first)
            }
        }
        return ""
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

    /**
     * Appends [tag] to a note's tags (no-op if already present). Used by the
     * bookmark gesture so cards can be found later in Anki via tag search.
     */
    fun addTagToNote(context: Context, noteId: Long, tag: String): Boolean {
        val uri = Uri.parse("content://$AUTHORITY/notes/$noteId")
        var tags = ""
        context.contentResolver.query(uri, arrayOf("tags"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return false
            tags = c.getString(0) ?: ""
        } ?: return false
        if (tag in tags.split(' ', ',')) return true
        val values = ContentValues().apply { put("tags", "$tags $tag".trim()) }
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    /** A note's editable fields: the model's field names paired with current values. */
    data class NoteFields(val names: List<String>, val values: List<String>)

    /**
     * Reads a note's raw field values (and the model's field names) for manual
     * editing. Values come from the note's `flds`, split on the 0x1f separator;
     * names from the note's model. Returns null if the note can't be read.
     */
    fun noteFields(context: Context, noteId: Long): NoteFields? {
        val noteUri = Uri.parse("content://$AUTHORITY/notes/$noteId")
        var mid = -1L
        var flds: String? = null
        context.contentResolver.query(noteUri, arrayOf("mid", "flds"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            mid = c.getLong(c.getColumnIndexOrThrow("mid"))
            flds = c.getString(c.getColumnIndexOrThrow("flds"))
        } ?: return null
        val values = (flds ?: "").split(FIELD_SEPARATOR)
        val names = modelFieldNames(context, mid) ?: List(values.size) { "Field ${it + 1}" }
        return NoteFields(names, values)
    }

    /** Field names declared by a note type (model), in display order. */
    private fun modelFieldNames(context: Context, modelId: Long): List<String>? {
        val modelUri = Uri.parse("content://$AUTHORITY/models/$modelId")
        context.contentResolver.query(modelUri, arrayOf("field_names"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val raw = c.getString(c.getColumnIndexOrThrow("field_names")) ?: return null
            return raw.split(FIELD_SEPARATOR)
        }
        return null
    }

    /**
     * Writes edited field [values] back to a note (joined by the 0x1f separator).
     * AnkiDroid rewrites the note and re-renders its cards; the field count must
     * match the note's existing fields. Returns true if a row was updated.
     */
    fun updateNoteFields(context: Context, noteId: Long, values: List<String>): Boolean {
        val noteUri = Uri.parse("content://$AUTHORITY/notes/$noteId")
        val cv = ContentValues().apply { put("flds", values.joinToString(FIELD_SEPARATOR)) }
        return context.contentResolver.update(noteUri, cv, null, null) > 0
    }

    /** Re-reads a card's rendered question/answer (e.g. after editing its note). */
    fun reloadCard(context: Context, card: DueCard): DueCard {
        val (q, a) = cardText(context, card.noteId, card.ord)
        return card.copy(question = q, answer = a)
    }

    private fun stripHtml(html: String): String {
        // fromHtml() would render <style>/<script> contents as visible text.
        val cleaned = html
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?is)<script.*?</script>"), "")
        return HtmlCompat.fromHtml(cleaned, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
