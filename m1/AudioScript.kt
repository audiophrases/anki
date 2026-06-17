package com.eugen.ankiaudio

/** One playable unit of a card's audio rendering. */
sealed interface Segment {
    /**
     * Spoken text. [text] is the plain form (used for the platform-TTS fallback
     * and logging); [ssml] is an optional pre-built SSML inner fragment for the
     * Edge engine — set when a line needs prosody (e.g. a production blank that
     * should be slowed and bracketed with pauses).
     */
    data class Speech(val text: String, val ssml: String? = null) : Segment
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

    /** A field with at most this many words counts as a headword (word/phrase)
     *  field rather than a definition — the source for inflected restores. */
    private const val HEADWORD_MAX_WORDS = 5

    private const val LINE_PAUSE_MS = 400L

    fun forQuestion(questionText: String, word: String = ""): List<Segment> =
        render(questionText, isProduction(questionText, word))

    /**
     * Whether this is a PRODUCTION card (learner must supply the word) — known,
     * not guessed. The studied [word] (the note's first field) is shown on the
     * recognition side and hidden on production, so its absence from the rendered
     * [question] means production. An empty [word] (unknown) falls back to the old
     * restore-from-visible-words behaviour, i.e. treated as recognition.
     */
    private fun isProduction(question: String, word: String): Boolean {
        if (word.isBlank()) return false
        // Newlines must separate words here (normalize() drops them), or the word
        // line "stance" would fuse with the next line ("stanceWhat…") and never match.
        val q = " " + normalize(question.replace('\n', ' ')) + " "
        val w = " " + normalize(word.replace('\n', ' ')) + " "
        return !q.contains(w)
    }

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
    fun forAnswer(answerText: String, questionText: String = "", word: String = ""): List<Segment> {
        val production = isProduction(questionText, word)
        if (questionText.isEmpty()) return render(answerText, production)

        val seen = questionText.lines().mapTo(HashSet()) { normalize(it) }
        val fresh = answerText.lines()
            .filter { it.isNotBlank() && normalize(it) !in seen }
        val segments = render(fresh.joinToString("\n"), production)

        // If everything was redundant, better to repeat than to stay silent.
        return if (segments.any { it is Segment.Speech }) segments else render(answerText, production)
    }

    private fun normalize(s: String): String = s.lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' }
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Stand-in spoken for a hidden word inside a production sentence. */
    private const val CODEWORD = "blank"

    // Private-use sentinels that bracket a codeword inside a rendered line, so we
    // can later wrap just those spans in SSML (they never occur in card text).
    private const val MARK_OPEN = "\uE000"
    private const val MARK_CLOSE = "\uE001"

    /** How much Edge slows the spoken blank, and the silence bracketing it. */
    private const val BLANK_RATE = "-20%"
    private const val BLANK_PAUSE_MS = 100L

    /**
     * Splits a rendered line whose codewords are [MARK_OPEN]…[MARK_CLOSE]-marked
     * into segments that make each production blank stand out: the surrounding
     * text plays normally, and every blank becomes its own utterance — slowed via
     * SSML and bracketed by short [BLANK_PAUSE_MS] silences — so the learner
     * clearly hears *where* the missing word goes instead of it flashing by.
     *
     * The blank's SSML is a whole-utterance `<prosody pitch rate volume>` wrapper,
     * the only shape Edge's read-aloud endpoint accepts (the same one edge-tts
     * uses); the pauses are real silence segments, not `<break>` (which Edge
     * rejects). The platform-TTS fallback ignores the SSML and reads "blank".
     */
    private fun blankEmphasised(marked: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < marked.length) {
            val open = marked.indexOf(MARK_OPEN, i)
            if (open < 0) {
                marked.substring(i).trim().takeIf { it.isNotEmpty() }?.let { out += Segment.Speech(it) }
                break
            }
            marked.substring(i, open).trim().takeIf { it.isNotEmpty() }?.let { out += Segment.Speech(it) }
            val close = marked.indexOf(MARK_CLOSE, open)
            val word = marked.substring(open + MARK_OPEN.length, close)
            out += Segment.Pause(BLANK_PAUSE_MS)
            out += Segment.Speech(word, ssml = slowProsody(word))
            out += Segment.Pause(BLANK_PAUSE_MS)
            i = close + MARK_CLOSE.length
        }
        return out
    }

    /** A whole-utterance prosody wrapper (edge-tts's exact shape) that slows [word]. */
    private fun slowProsody(word: String): String =
        "<prosody pitch='+0Hz' rate='$BLANK_RATE' volume='+0%'>${escapeXml(word)}</prosody>"

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    private fun render(text: String, production: Boolean): List<Segment> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Candidate words that might be the hidden word, visible on this side.
        val candidates = lines.filterNot { it.contains('•') }
            .flatMap { CANDIDATE_WORD.findAll(it).map { m -> m.value } }
            .distinct()

        // Headword candidates: words from short fields only (the word / phrase
        // field, never a long definition sentence). Inflected restores draw from
        // these, so the example's conjugated headword ("hone" → "h•••d" honed) is
        // restored, while a definition word ("etc") can't become "etcs".
        val lemmas = lines.filterNot { it.contains('•') }
            .filter { CANDIDATE_WORD.findAll(it).count() in 1..HEADWORD_MAX_WORDS }
            .flatMap { CANDIDATE_WORD.findAll(it).map { m -> m.value } }
            .distinct()

        val out = mutableListOf<Segment>()
        var hintFieldPhrase: String? = null
        // Unrestored blanks of the most recent blanked line. The note's hint field
        // (e.g. "be •• •• something") is the last such line and carries exactly one
        // blank per word to produce — so its blanks, in order, give the right
        // letter hint ("2 letter word, then a 2 letter word") where accumulating
        // every line's blanks would double-count and a dedupe would wrongly merge
        // two distinct same-length words (in / on).
        var lastBlankedLine: List<String> = emptyList()

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
                // Production → keep the detailed letter hint; recognition → stay
                // silent (the word is shown, so the field is redundant).
                if (production && hintFieldPhrase == null) {
                    hintFieldPhrase = tokens.joinToString(", then a ") { hintPhrase(it.value) }
                }
                continue
            }

            // Example sentence: on recognition the visible word drops back into the
            // blanks; on production the word is hidden, so every blank becomes the
            // spoken codeword (never a look-alike like the definition's "stated").
            val tokenValues = tokens.map { it.value }
            val restored: List<String?> =
                if (production) List(tokenValues.size) { null }
                else restoreInline(tokenValues, candidates, lemmas, line)
            var idx = -1
            val lineBlanks = mutableListOf<String>()
            val rendered = line.replace(BLANK_TOKEN) {
                idx++
                restored[idx] ?: run {
                    lineBlanks += tokenValues[idx]
                    "$MARK_OPEN$CODEWORD$MARK_CLOSE"
                }
            }
            val expanded = expandAbbreviations(rendered)
            if (lineBlanks.isNotEmpty()) {
                out += blankEmphasised(expanded)
                lastBlankedLine = lineBlanks
            } else {
                out += Segment.Speech(expanded)
            }
            out += Segment.Pause(LINE_PAUSE_MS)
        }

        // Production: speak the letter hint after the sentence — from a dedicated
        // (blanks-only) hint field, else from the last blanked line's blanks.
        val hint = hintFieldPhrase
            ?: lastBlankedLine.map { hintPhrase(it) }.takeIf { it.isNotEmpty() }
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
     *   - the phrase words we did *not* mask actually occur in the sentence
     *     (a placeholder lemma like "be"/"sth" is allowed to be absent), and
     *   - there is an anchor: either a blank with visible letters lands on its
     *     slot, or an unmasked phrase word is present in the sentence (which is
     *     enough to place even a fully-hidden blank like "•••" in "get in").
     * Otherwise each token keeps its independent result (null → codeword).
     */
    private fun restoreInline(
        tokens: List<String>,
        candidates: List<String>,
        lemmas: List<String>,
        line: String,
    ): List<String?> {
        val perToken = tokens.map { restore(it, candidates, lemmas) }
        if (candidates.isEmpty() || tokens.size > candidates.size) return perToken
        if (perToken.none { it == null }) return perToken

        // Exact length first; if that fails, allow a 1-off slip (decks sometimes
        // mis-count bullets, e.g. "no •••••, no ••••" for the 4-letter "harm").
        val assigned = (alignToPhrase(tokens, candidates, 0)
            ?: alignToPhrase(tokens, candidates, 1)) ?: return perToken
        val slot = tokens.mapIndexed { i, t ->
            val one = listOf(candidates[assigned[i]])
            restore(t, one, one)
        }

        val consistent = perToken.indices.all { i -> perToken[i] == null || perToken[i] == slot[i] }
        if (!consistent) return perToken

        val visible = visibleWords(line)
        val masked = assigned.toHashSet()
        val gapsPresent = candidates.indices.all { j ->
            j in masked ||
                candidates[j].lowercase() in visible ||
                candidates[j].lowercase() in PHRASE_PLACEHOLDER
        }
        if (!gapsPresent) return perToken

        // Proof this is the masked phrase (recognition), not the definition
        // (production), so a fully-hidden blank may be filled from the phrase:
        //   - a blank with visible letters lands on its slot (strict anchor), OR
        //   - an *unmasked* phrase word actually appears in the sentence — e.g.
        //     "in" of the headword "get in" in "Her plane ••• in", pinning the
        //     blank to the missing "get" even though it shows no letters. (The
        //     parser has no grammar, so it restores the word-field form "get",
        //     not the sentence's "got" — which is fine on the recognition side.)
        val hasStrictAnchor = slot.any { it != null }
        val hasPhraseAnchor = candidates.indices.any { j -> j !in masked && candidates[j].lowercase() in visible }
        if (!hasStrictAnchor && !hasPhraseAnchor) return perToken

        return perToken.mapIndexed { i, r -> r ?: candidates[assigned[i]] }
    }

    /** Greedily maps each blank, left to right, to the next candidate word it
     *  could be ([blankFitsWord], within [lenTolerance] letters); null if any
     *  blank has no fitting word. */
    private fun alignToPhrase(tokens: List<String>, candidates: List<String>, lenTolerance: Int): List<Int>? {
        val out = ArrayList<Int>(tokens.size)
        var next = 0
        for (t in tokens) {
            var j = next
            while (j < candidates.size && !blankFitsWord(t, candidates[j], lenTolerance)) j++
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
    private fun blankFitsWord(token: String, word: String, lenTolerance: Int = 0): Boolean {
        val b = parseBlank(token)
        if (b.knowns.isEmpty()) return kotlin.math.abs(word.length - b.length) <= lenTolerance
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
    private fun restore(token: String, candidates: List<String>, lemmas: List<String>): String? {
        val b = parseBlank(token)
        if (b.knowns.isEmpty()) return null   // nothing but bullets — unrecoverable

        // (1) A whole-word match must be UNAMBIGUOUS. A weakly-revealed blank like
        // "w••••" (only the first letter shown) fits many words: on a production
        // card its only visible "matches" come from the *definition* ("woven",
        // "woman"), never the hidden answer "weave". If more than one distinct
        // visible word fits, we can't tell which is meant, so decline rather than
        // speak a wrong word. On the recognition side the studied word itself is
        // visible and is the unique fit, so it still restores there.
        val fits = candidates.filter { b.matches(it) }
        if (fits.isNotEmpty() && fits.distinctBy { it.lowercase() }.size == 1) return fits.first()

        // (2) The example conjugates the headword. Inflect the LEMMA fields only
        // (the word/phrase field, never definition prose) with the regular English
        // spelling changes, and accept a unique form that fits the blank exactly.
        // Restores "hone" → "h•••d" (honed) and "hone" → "h••ing" (honing — silent
        // e dropped); the definition's "etc" is never a lemma, so it can't become
        // "etcs". Drawing from the headword is also what makes this safe: on a
        // production card the headword is hidden, so there are no lemmas and the
        // blank stays the spoken codeword.
        run {
            val inflected = lemmas.flatMap { inflectedForms(it) }.filter { b.matches(it) }
            if (inflected.distinctBy { it.lowercase() }.size == 1) return inflected.first()
        }

        // (3) Shown stem and ending around a hidden middle (e.g. "f••b••••••e"),
        // among the headword lemmas, length within one.
        if (b.prefix.length >= 2) {
            val fuzzy = lemmas.filter {
                it.startsWith(b.prefix, ignoreCase = true) &&
                    it.endsWith(b.suffix, ignoreCase = true) &&
                    kotlin.math.abs(it.length - b.length) <= 1
            }
            if (fuzzy.distinctBy { it.lowercase() }.size == 1) return fuzzy.first()
        }
        return null
    }

    /** Inflectional endings tried on a headword to match an example's conjugation. */
    private val INFLECTION_ENDINGS = listOf("s", "es", "d", "ed", "ing", "ies", "ied", "er", "est")

    /**
     * Every regular inflected spelling of [lemma]: each ending in
     * [INFLECTION_ENDINGS] applied plain ("walk"→"walking"), with a dropped silent
     * final e ("hone"→"honing", "proscribe"→"proscribed"), with a doubled final
     * consonant ("run"→"running"), and with y→i ("carry"→"carried"). Generated
     * from a fixed ending set rather than the blank's revealed tail, because that
     * tail can include stem letters ("••••••ibed" shows the "ib" of proscribe, not
     * just the "-d"). Over-generates on purpose — the caller keeps only a form that
     * exactly fits the blank.
     */
    private fun inflectedForms(lemma: String): List<String> {
        val dropE = if (lemma.endsWith("e", ignoreCase = true)) lemma.dropLast(1) else null
        val yToI = if (lemma.length >= 2 && lemma.endsWith("y", ignoreCase = true)) lemma.dropLast(1) + "i" else null
        val doubled = if (lemma.length >= 3) lemma + lemma.last() else null
        val forms = mutableListOf<String>()
        for (e in INFLECTION_ENDINGS) {
            forms += lemma + e
            dropE?.let { forms += it + e }
            yToI?.let { forms += it + e }
            doubled?.let { forms += it + e }
        }
        return forms.distinct()
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
