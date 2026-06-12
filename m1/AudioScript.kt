package com.eugen.ankiaudio

/** One playable unit of a card's audio rendering. */
sealed interface Segment {
    data class Speech(val text: String) : Segment
    data class Bleep(val durationMs: Int = 500) : Segment
    data class Pause(val durationMs: Long) : Segment
}

/**
 * Turns a card's rendered text into an eyes-free audio script.
 *
 * Eugen's decks store cloze blanks as bullet runs *inside the field data*
 * (e.g. example = "She wh•••••d him into taking her with him."), so the same
 * blanked text appears on both study directions. The audio rendering adapts
 * to the direction:
 *
 * - **RECOGNITION** (word given, recall the meaning — the word is visible on
 *   this side): a blank is pointless by ear, so it is RESTORED from the
 *   visible word ("She wheedled him into taking her with him.") and the
 *   dedicated hint field (a line like "wh•••••") is NOT read at all.
 * - **PRODUCTION** (meaning given, produce the word — the word is hidden):
 *   in the example sentence each hidden word becomes the codeword "blank"
 *   so the sentence keeps its natural flow ("She blank him into taking her
 *   with him."), and the detailed letter hint is spoken once, separately,
 *   from the hint field: "8 letter word starting with W H and ending
 *   with D."
 *
 * A line consisting of nothing but a blanked token (a dedicated hint field
 * like "wh•••••") is skipped when a blank was already rendered inline in an
 * earlier line; otherwise it is spoken as a standalone hint.
 *
 * Dictionary tags common in the deck ("v.", "inf.", "esp") are expanded to
 * full words ("verb", "informal", "especially") so the voice reads them
 * naturally instead of guessing at the abbreviation.
 */
object AudioScript {

    /** A run of bullets with optional visible letters around it, e.g. "wh•••••d". */
    private val BLANK_TOKEN = Regex("""\S*•+\S*""")

    /** Words usable for restoring a blank (from lines without bullets). */
    private val CANDIDATE_WORD = Regex("""\p{L}{2,}""")

    private const val LINE_PAUSE_MS = 400L

    fun forQuestion(questionText: String): List<Segment> = render(questionText)

    /**
     * The answer side often repeats question content (word, example,
     * definition, depending on the template). On reveal only the *new*
     * information should be spoken — for a production card just the word
     * itself. The comparison is on the raw field lines, BEFORE blank
     * rendering: the example sentence is the same blanked line on both
     * sides, so it is dropped from the reveal even though its rendering
     * would differ ("blank blank" vs "do blank"). Hearing the sentence
     * again — half-restored, plus a leftover letter hint — wastes the
     * driver's time; replaying the question is one swipe/word away.
     */
    fun forAnswer(answerText: String, questionText: String = ""): List<Segment> {
        if (questionText.isEmpty()) return render(answerText)

        val seen = questionText.lines().mapTo(HashSet()) { normalize(it) }
        val fresh = answerText.lines()
            .filter { it.isNotBlank() && normalize(it) !in seen }
        val segments = render(fresh.joinToString("\n"))

        // If everything was redundant, better to repeat than to stay silent.
        return if (segments.any { it is Segment.Speech }) segments else render(answerText)
    }

    private fun normalize(s: String): String = s.lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' }
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Stand-in spoken for a hidden word inside a production sentence. */
    private const val CODEWORD = "blank"

    /** Dictionary tags in Eugen's main deck → how the voice should say them. */
    private val SPOKEN_ABBREVIATION = mapOf(
        "inf" to "informal",
        "adj" to "adjective",
        "adv" to "adverb",
        "prep" to "preposition",
        "conj" to "conjunction",
        "esp" to "especially",
        "fig" to "figurative",
        "lit" to "literary",
        "v" to "verb",
        "n" to "noun",
    )

    /** Tags that are also ordinary words ("the lamp was lit."): these are
     *  left alone at the end of a line, where they read as sentence words. */
    private val WORD_COLLISIONS = setOf("fig", "lit")

    /**
     * Matches a tag plus its dot. Multi-letter tags also match capitalised
     * at the start of a line ("Esp."); single-letter "v."/"n." stay
     * lowercase-only so initials like "N. America" survive.
     */
    private val ABBREVIATION = run {
        fun alt(keys: Collection<String>) = keys.joinToString("|") {
            if (it.length == 1) it else "[${it[0].uppercaseChar()}${it[0]}]${it.drop(1)}"
        }
        val safe = alt(SPOKEN_ABBREVIATION.keys - WORD_COLLISIONS)
        val risky = alt(WORD_COLLISIONS)
        Regex("""\b(?:(?:$safe)\.|(?:$risky)\.(?!\s*$))""")
    }

    /** Dotless tags: "esp" sometimes drops its dot; "BrE" never has one
     *  (exact case so unrelated lowercase "bre" sequences can't match). */
    private val BARE_TAGS = listOf(
        Regex("""\b[Ee]sp\b""") to "especially",
        Regex("""\bBrE\b""") to "British English",
    )

    private fun expandAbbreviations(text: String): String {
        val dotted = ABBREVIATION.replace(text) { m ->
            SPOKEN_ABBREVIATION.getValue(m.value.dropLast(1).lowercase())
        }
        return BARE_TAGS.fold(dotted) { acc, (regex, word) -> regex.replace(acc, word) }
    }

    private fun render(text: String): List<Segment> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Candidate words that might be the hidden word, visible on this side.
        val candidates = lines.filterNot { it.contains('•') }
            .flatMap { CANDIDATE_WORD.findAll(it).map { m -> m.value } }
            .distinct()

        val out = mutableListOf<Segment>()
        var hintFieldPhrase: String? = null
        val unrestoredInline = mutableListOf<String>()

        for (line in lines) {
            val tokens = BLANK_TOKEN.findAll(line).toList()
            if (tokens.isEmpty()) {
                out += Segment.Speech(expandAbbreviations(line))
                out += Segment.Pause(LINE_PAUSE_MS)
                continue
            }

            val withoutTokens = line.replace(BLANK_TOKEN, "")
            if (withoutTokens.none { it.isLetterOrDigit() }) {
                // Dedicated hint field (e.g. "wh•••••" or "c•t c•••••s").
                // Recognition (restorable) → stay silent; production → keep
                // the detailed letter hint for the end of the script.
                if (hintFieldPhrase == null &&
                    restore(tokens.first().value, candidates) == null
                ) {
                    hintFieldPhrase = tokens.joinToString(", then a ") { hintPhrase(it.value) }
                }
                continue
            }

            // Example sentence: recognition words drop back in restored;
            // production words become the codeword so the sentence flows.
            val rendered = line.replace(BLANK_TOKEN) { m ->
                restore(m.value, candidates) ?: run {
                    unrestoredInline += m.value
                    CODEWORD
                }
            }
            out += Segment.Speech(expandAbbreviations(rendered))
            out += Segment.Pause(LINE_PAUSE_MS)
        }

        // Production: speak the letter hint once, after the sentence —
        // from the hint field, or derived from the blanks themselves when
        // the note has no hint field.
        val hint = hintFieldPhrase
            ?: unrestoredInline.takeIf { it.isNotEmpty() }
                ?.joinToString(", then a ") { hintPhrase(it) }
        if (hint != null) {
            out += Segment.Speech(hint)
            out += Segment.Pause(LINE_PAUSE_MS)
        }
        return out
    }

    private data class Blank(val prefix: String, val suffix: String, val bullets: Int) {
        val length get() = prefix.length + bullets + suffix.length
    }

    private fun parseBlank(token: String): Blank {
        val core = token.trim { !it.isLetterOrDigit() && it != '•' }
        return Blank(
            prefix = core.takeWhile { it != '•' }.filter { it.isLetterOrDigit() },
            suffix = core.takeLastWhile { it != '•' }.filter { it.isLetterOrDigit() },
            bullets = core.count { it == '•' },
        )
    }

    /**
     * Tries to put the hidden word back using words visible on the same side.
     * Two blank shapes exist in Eugen's decks:
     *  - whole word, middle hidden:  "m••e"    + "make"    → "make"
     *  - stem + inflection:          "wh•••••d" + "wheedle" → "wheedled"
     */
    private fun restore(token: String, candidates: List<String>): String? {
        val b = parseBlank(token)
        if (b.prefix.isEmpty() && b.suffix.isEmpty()) return null

        candidates.firstOrNull {
            it.length == b.length &&
                it.startsWith(b.prefix, ignoreCase = true) &&
                it.endsWith(b.suffix, ignoreCase = true)
        }?.let { return it }

        candidates.firstOrNull {
            it.length == b.prefix.length + b.bullets &&
                b.prefix.isNotEmpty() &&
                it.startsWith(b.prefix, ignoreCase = true)
        }?.let { return it + b.suffix }

        if (b.prefix.length >= 2) {
            candidates.firstOrNull {
                it.startsWith(b.prefix, ignoreCase = true) &&
                    it.endsWith(b.suffix, ignoreCase = true) &&
                    kotlin.math.abs(it.length - b.length) <= 1
            }?.let { return it }
        }
        return null
    }

    /** "m••e" → "4 letter word starting with M and ending with E". */
    private fun hintPhrase(token: String): String {
        val b = parseBlank(token)
        val sb = StringBuilder("${b.length} letter word")
        if (b.prefix.isNotEmpty()) sb.append(" starting with ").append(spell(b.prefix))
        if (b.suffix.isNotEmpty()) {
            sb.append(if (b.prefix.isNotEmpty()) " and ending with " else " ending with ")
            sb.append(spell(b.suffix))
        }
        return sb.toString()
    }

    /** "wh" → "W H" so the voice says the letter names. */
    private fun spell(s: String): String =
        s.uppercase().toCharArray().joinToString(" ")
}
