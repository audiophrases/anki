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
            val tokenValues = tokens.map { it.value }
            val restored = restoreInline(tokenValues, candidates, line)
            var idx = -1
            val rendered = line.replace(BLANK_TOKEN) {
                idx++
                restored[idx] ?: run { unrestoredInline += tokenValues[idx]; CODEWORD }
            }
            out += Segment.Speech(expandAbbreviations(rendered))
            out += Segment.Pause(LINE_PAUSE_MS)
        }

        // Production: speak the letter hint once, after the sentence —
        // from the hint field, or derived from the blanks themselves when
        // the note has no hint field.
        // Dedupe on the spoken phrase, not the raw token: the same blank can
        // appear twice with different trailing punctuation ("w••••?" vs "w••••")
        // yet should be hinted once ("5 letter word starting with W").
        val hint = hintFieldPhrase
            ?: unrestoredInline.map { hintPhrase(it) }.distinct().takeIf { it.isNotEmpty() }
                ?.joinToString(", then a ")
        if (hint != null) {
            out += Segment.Speech(hint)
            out += Segment.Pause(LINE_PAUSE_MS)
        }
        return out
    }

    /** Dictionary lemma markers that head a phrase but never appear literally
     *  in its example sentence ("be sb out of sth"), so their absence from the
     *  sentence must not veto a restore. */
    private val PHRASE_PLACEHOLDER = setOf("be", "sb", "sth", "one", "ones", "oneself", "your", "yours")

    /**
     * Restores every blank token in one example line.
     *
     * Each token is first matched independently against the visible words
     * ([restore]). That alone fails for a word hidden with no visible letters —
     * "put" in "stay put", rendered "•••" — so even on the recognition side
     * (the whole phrase is on screen) it would be spoken as the codeword.
     *
     * Recovery: the example masks a subsequence of the visible phrase
     * ("That's b••••• the •••••" hides "beside" and "point" out of "That's
     * beside the point"), so the blanks are aligned in order to the candidate
     * words they fit, and the unfilled slots are taken straight from the phrase.
     * The alignment is trusted only with hard evidence it is the recognition
     * side, never the production side where the candidates would be the
     * definition:
     *   - every confident per-token match lands on its aligned slot,
     *   - at least one blank *strictly* restores to its slot, and
     *   - the phrase words we did *not* mask actually occur in the sentence
     *     (a placeholder lemma like "be"/"sth" is allowed to be absent).
     * Otherwise each token keeps its independent result (null → codeword).
     */
    private fun restoreInline(tokens: List<String>, candidates: List<String>, line: String): List<String?> {
        val perToken = tokens.map { restore(it, candidates) }
        if (candidates.isEmpty() || tokens.size > candidates.size) return perToken
        if (perToken.none { it == null }) return perToken

        val assigned = alignToPhrase(tokens, candidates) ?: return perToken
        val slot = tokens.mapIndexed { i, t -> restore(t, listOf(candidates[assigned[i]])) }

        val consistent = perToken.indices.all { i -> perToken[i] == null || perToken[i] == slot[i] }
        val hasStrictAnchor = slot.any { it != null }
        if (!consistent || !hasStrictAnchor) return perToken

        val visible = visibleWords(line)
        val masked = assigned.toHashSet()
        val gapsPresent = candidates.indices.all { j ->
            j in masked ||
                candidates[j].lowercase() in visible ||
                candidates[j].lowercase() in PHRASE_PLACEHOLDER
        }
        if (!gapsPresent) return perToken

        return perToken.mapIndexed { i, r -> r ?: candidates[assigned[i]] }
    }

    /** Greedily maps each blank, left to right, to the next candidate word it
     *  could be ([blankFitsWord]); null if any blank has no fitting word. */
    private fun alignToPhrase(tokens: List<String>, candidates: List<String>): List<Int>? {
        val out = ArrayList<Int>(tokens.size)
        var next = 0
        for (t in tokens) {
            var j = next
            while (j < candidates.size && !blankFitsWord(t, candidates[j])) j++
            if (j >= candidates.size) return null
            out += j
            next = j + 1
        }
        return out
    }

    /** Lower-cased fully-visible words of [line] (blank tokens removed first, so
     *  a masked word's stray letters don't count as visible). */
    private fun visibleWords(line: String): Set<String> =
        CANDIDATE_WORD.findAll(BLANK_TOKEN.replace(line, " "))
            .mapTo(HashSet()) { it.value.lowercase() }

    /**
     * True when [token]'s visible letters don't contradict [word], used to
     * confirm a positional fill. A fully hidden blank must match the word's
     * length exactly; otherwise the shown stem and/or ending must agree.
     */
    private fun blankFitsWord(token: String, word: String): Boolean {
        val b = parseBlank(token)
        if (b.knowns.isEmpty()) return word.length == b.length
        if (b.matches(word) || b.matchesStem(word)) return true
        if (b.prefix.isNotEmpty() && !word.startsWith(b.prefix, ignoreCase = true)) return false
        if (b.suffix.isNotEmpty() && !word.endsWith(b.suffix, ignoreCase = true)) return false
        return word.length >= b.prefix.length + b.suffix.length
    }

    /**
     * One blank decomposed by position. Each bullet hides exactly one
     * character, so every visible letter sits at a known index. Tracking those
     * positions — not just a leading/trailing run — is what lets us restore a
     * word masked with visible letters in the middle, e.g.
     * "f••b••••••e" → "forbearance" or "th•••••st••ck" → "thunderstruck".
     */
    private class Blank(core: String) {
        val length: Int
        val knowns: List<Pair<Int, Char>>
        val prefix: String = core.takeWhile { it != '•' }.filter { it.isLetterOrDigit() }
        val suffix: String = core.takeLastWhile { it != '•' }.filter { it.isLetterOrDigit() }

        init {
            val ks = mutableListOf<Pair<Int, Char>>()
            var pos = 0
            for (ch in core) {
                if (ch != '•') ks += pos to ch
                pos++
            }
            length = pos
            knowns = ks
        }

        /** [word] fits exactly: same length, every visible letter in its place. */
        fun matches(word: String): Boolean =
            word.length == length && knowns.all { (i, c) -> word[i].equals(c, ignoreCase = true) }

        /** [word] is the stem and [suffix] an inflectional ending added in the
         *  sentence ("wh•••••d": stem "wheedle" + ending "d"). */
        fun matchesStem(word: String): Boolean {
            val stemLen = length - suffix.length
            return word.length == stemLen &&
                knowns.all { (i, c) -> i >= stemLen || word[i].equals(c, ignoreCase = true) }
        }
    }

    private fun parseBlank(token: String): Blank =
        Blank(token.trim { !it.isLetterOrDigit() && it != '•' })

    /**
     * Tries to put the hidden word back using words visible on the same side.
     * Blank shapes seen in Eugen's decks:
     *  - whole word, letters at fixed spots:  "m••e" / "f••b••••••e" → match in place
     *  - stem + inflectional ending:          "wh•••••d" + "wheedle" → "wheedled"
     */
    private fun restore(token: String, candidates: List<String>): String? {
        val b = parseBlank(token)
        if (b.knowns.isEmpty()) return null   // nothing but bullets — unrecoverable

        // A whole-word match must be UNAMBIGUOUS. A weakly-revealed blank like
        // "w••••" (only the first letter shown) fits many words: on a production
        // card its only visible "matches" come from the *definition* ("woven",
        // "woman"), never the hidden answer "weave". If more than one distinct
        // visible word fits, we can't tell which is meant, so decline rather than
        // speak a wrong word. On the recognition side the studied word itself is
        // visible and is the unique fit, so it still restores there.
        val fits = candidates.filter { b.matches(it) }
        if (fits.isNotEmpty() && fits.distinctBy { it.lowercase() }.size == 1) return fits.first()

        if (b.prefix.isNotEmpty() && b.suffix.isNotEmpty()) {
            candidates.firstOrNull { b.matchesStem(it) }?.let { return it + b.suffix }
        }

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
