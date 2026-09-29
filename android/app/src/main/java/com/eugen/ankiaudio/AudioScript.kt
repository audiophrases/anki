package com.eugen.ankiaudio

/** One playable unit of a card's audio rendering. */
sealed interface Segment {
    /**
     * Spoken text. [text] is the plain form (used for the platform-TTS fallback
     * and logging); [ssml] is an optional pre-built SSML inner fragment for the
     * Edge engine — set when a line needs prosody (e.g. a production blank that
     * should be slowed). [leadIn] is false when the utterance carries straight
     * on from the one before (the rest of a sentence after a blank), so the
     * player skips the clip's warm-up silence instead of leaving a gap.
     */
    data class Speech(val text: String, val ssml: String? = null, val leadIn: Boolean = true) : Segment
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
 *   studied word ("She wheedled him into taking her with him.") and the
 *   dedicated hint field (a line like "wh•••••") is NOT read at all. Every
 *   blank is filled — with the whole word/phrase when its exact form can't be
 *   worked out — so a recognition card never says "blank".
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

    fun forQuestion(questionText: String, word: String = ""): List<Segment> {
        val question = plainSpaces(questionText)
        val w = plainSpaces(word)
        val production = isProduction(question, w)
        return render(question, production, headword(w, production))
    }

    /**
     * Anki fields are full of `&nbsp;`, which arrives as U+00A0. It isn't `\s` to
     * the regexes here, so it would fuse neighbouring words into one blank token
     * ("was th•••••st••ck when" → a single 22-letter blank). Make it a plain space.
     */
    private fun plainSpaces(s: String): String = s.replace('\u00A0', ' ')

    /** The studied word to restore blanks from — only on a known recognition card. */
    private fun headword(word: String, production: Boolean): String? =
        word.takeIf { it.isNotBlank() && !production }

    /**
     * Whether this is a PRODUCTION card (learner must supply the word) — known,
     * not guessed. The studied [word] (the note's first field) is shown on the
     * recognition side and hidden on production, so its absence from the rendered
     * [question] means production. An empty [word] (unknown) falls back to the old
     * restore-from-visible-words behaviour, i.e. treated as recognition.
     *
     * The word must fill whole line(s) of the question — its own field — not merely
     * occur in it: a production definition can contain the word itself ("chestnut":
     * "edible nut of a chestnut tree"), which must not flip the card to recognition
     * and speak the answer into the example.
     */
    private fun isProduction(question: String, word: String): Boolean {
        val wordLines = word.lines().map { normalize(it) }.filter { it.isNotEmpty() }
        if (wordLines.isEmpty()) return false
        val questionLines = question.lines().map { normalize(it) }.filter { it.isNotEmpty() }
        return questionLines.windowed(wordLines.size).none { it == wordLines }
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
        val answer = plainSpaces(answerText)
        val question = plainSpaces(questionText)
        val w = plainSpaces(word)
        val production = isProduction(question, w)
        val head = headword(w, production)
        if (question.isEmpty()) return render(answer, production, head)

        val seen = question.lines().mapTo(HashSet()) { normalize(it) }
        val fresh = answer.lines()
            .filter { it.isNotBlank() && normalize(it) !in seen }
        val segments = render(fresh.joinToString("\n"), production, head)

        // If everything was redundant, better to repeat than to stay silent.
        return if (segments.any { it is Segment.Speech }) segments else render(answer, production, head)
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

    /** How much Edge slows the spoken blank, and the silence before it. */
    private const val BLANK_RATE = "-10%"
    private const val BLANK_PAUSE_MS = 100L

    /**
     * Splits a rendered line whose codewords are [MARK_OPEN]…[MARK_CLOSE]-marked
     * into segments that make each production blank stand out: the surrounding
     * text plays normally, and every blank becomes its own utterance — slowed via
     * SSML after a short [BLANK_PAUSE_MS] silence — so the learner clearly hears
     * *where* the missing word goes instead of it flashing by. After the blank
     * the sentence carries straight on: no pause, and the next clip skips its
     * warm-up lead-in ([Segment.Speech.leadIn]); a blank right after a blank
     * ("blank blank") follows on the same way.
     *
     * The blank's SSML is a whole-utterance `<prosody pitch rate volume>` wrapper,
     * the only shape Edge's read-aloud endpoint accepts (the same one edge-tts
     * uses); the pause is a real silence segment, not `<break>` (which Edge
     * rejects). The platform-TTS fallback ignores the SSML and reads "blank".
     */
    private fun blankEmphasised(marked: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var afterBlank = false
        var i = 0
        while (i < marked.length) {
            val open = marked.indexOf(MARK_OPEN, i)
            val text = marked.substring(i, if (open < 0) marked.length else open).trim()
            if (text.isNotEmpty()) {
                out += Segment.Speech(text, leadIn = !afterBlank)
                afterBlank = false
            }
            if (open < 0) break
            val close = marked.indexOf(MARK_CLOSE, open)
            val word = marked.substring(open + MARK_OPEN.length, close)
            if (!afterBlank) out += Segment.Pause(BLANK_PAUSE_MS)
            out += Segment.Speech(word, ssml = slowProsody(word), leadIn = !afterBlank)
            afterBlank = true
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

    /**
     * [headword] is the studied word when the card is KNOWN to be recognition:
     * blanks are then filled from it ([fillFromHeadword]). Null means production,
     * or a direction that is unknown (no word) and has to be guessed from the
     * visible words ([restoreInline]).
     */
    private fun render(text: String, production: Boolean, headword: String? = null): List<Segment> {
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
            val restored: List<String?> = when {
                production -> List(tokenValues.size) { null }
                headword != null -> fillFromHeadword(tokens, line, headword)
                else -> restoreInline(tokenValues, candidates, lemmas, line)
            }
            var idx = -1
            val lineBlanks = mutableListOf<String>()
            val rendered = line.replace(BLANK_TOKEN) {
                idx++
                restored[idx]?.let { keepPunctuation(tokenValues[idx], it) } ?: run {
                    lineBlanks += tokenValues[idx]
                    "$MARK_OPEN$CODEWORD$MARK_CLOSE"
                }
            }.replace(EXTRA_SPACE, " ").replace(SPACE_BEFORE_PUNCTUATION, "$1")
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

    private val EXTRA_SPACE = Regex(" {2,}")
    private val SPACE_BEFORE_PUNCTUATION = Regex(""" +([.,!?;:])""")

    /** Puts [fill] in place of [token]'s bullets, keeping the punctuation the
     *  token carries around them ("w••••?" → "weave?"), so the voice still
     *  hears the question mark / sentence end. */
    private fun keepPunctuation(token: String, fill: String): String {
        fun outer(c: Char) = !c.isLetterOrDigit() && c != '•'
        if (token.all(::outer)) return fill
        return token.takeWhile(::outer) + fill + token.takeLastWhile(::outer)
    }

    /** One word of the studied headword; hyphens, apostrophes and a slash between
     *  digits stay inside it ("either-or", "fool's", "20/20"), since the example
     *  usually masks such words whole. */
    private val HEADWORD_UNIT = Regex("""[\p{L}\p{N}]+(?:(?:['’-]|(?<=\p{N})/(?=\p{N}))[\p{L}\p{N}]+)*""")

    /** The same, split at hyphens — for examples that write the compound apart
     *  ("an ••• of •••• experience" for "out-of-body experience"). */
    private val HEADWORD_PART = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")

    /** The headword's words, minus dictionary tags ("prep. from" → "from"). */
    private fun headwordUnits(headword: String, unit: Regex): List<String> =
        unit.findAll(headword)
            .filterNot { it.value.lowercase() in SPOKEN_ABBREVIATION && headword.getOrNull(it.range.last + 1) == '.' }
            .map { it.value }
            .toList()

    /** A headword word that can stand in for a blank, and how well it fits. */
    private class Fill(val text: String, val score: Int)

    // Alignment scores: how well a blank fits a headword word …
    private const val FIT_EXACT = 8        // visible letters agree ("st••••" stance)
    private const val FIT_INFLECTED = 7    // … in an inflected form ("h•••d" honed)
    private const val FIT_LENGTH = 6       // no letters shown, same length ("•••" cot)
    private const val FIT_LOOSE = 2        // bullets miscounted ("b•••••thed" betrothed)
    private const val FIT_WEAK = 0         // only the first letter agrees ("b•••s" for "burning")
    // … how well a visible sentence word matches one …
    private const val SAME_WORD = 4        // "on" / "on", "SIGNED" / "sign"
    private const val SIMILAR_WORD = 2     // an irregular form one letter off ("came" / "come")
    // … and what the alternatives cost.
    private const val UNRESOLVED = -6      // no headword word fits the blank
    private const val SKIP_MISSING = -4    // a headword word neither masked nor in the sentence
    private const val REUSE = -2           // the same word masked twice ("a w••••? … a w••••.")

    /**
     * How [token] could be filled from the headword word [unit] (in the form the
     * example uses), or null if its visible letters rule the word out.
     */
    private fun fit(token: String, unit: String): Fill? {
        val b = parseBlank(token)
        if (b.knowns.isEmpty()) {
            val diff = kotlin.math.abs(unit.length - b.length)
            return when {
                diff == 0 -> Fill(unit, FIT_LENGTH)
                diff <= 1 -> Fill(unit, FIT_LOOSE)
                else -> null
            }
        }
        if (b.matches(unit)) return Fill(unit, FIT_EXACT)
        // An inflected spelling only when the blank shows the ending: "h•••d" is
        // "honed", but "b••••••" (brought) must not become an invented "bringed".
        val forms = if (b.suffix.isEmpty()) listOf(unit) else listOf(unit) + inflectedForms(unit)
        forms.firstOrNull { b.matches(it) }?.let { return Fill(it, FIT_INFLECTED) }
        // Visible stem/ending agree but the bullet count is off: a slip by one,
        // or a longer inflection behind a clearly shown stem ("rec••••••d" for
        // "reciprocated").
        val loose = forms
            .filter {
                it.startsWith(b.prefix, ignoreCase = true) && it.endsWith(b.suffix, ignoreCase = true) &&
                    it.length >= b.prefix.length + b.suffix.length
            }
            .minByOrNull { kotlin.math.abs(it.length - b.length) }
        if (loose != null && (kotlin.math.abs(loose.length - b.length) <= 1 || b.prefix.length >= 3)) {
            return Fill(loose, FIT_LOOSE)
        }
        // Last resort: the shown first letters are the word's, the form isn't
        // ("the money b•••s a hole" → "burning") — still better than no word.
        if (b.prefix.isNotEmpty() && unit.startsWith(b.prefix, ignoreCase = true)) return Fill(unit, FIT_WEAK)
        return null
    }

    /** How strongly the visible sentence word [seen] is the headword word [unit]. */
    private fun wordMatch(seen: String, unit: String): Int {
        val v = seen.lowercase()
        val u = unit.lowercase()
        if (v == u || inflectedForms(u).any { it.equals(v, ignoreCase = true) }) return SAME_WORD
        val maxEdits = if (u.length <= 4) 1 else 2
        if (v.length >= 2 && v[0] == u[0] && editDistance(v, u) <= maxEdits) return SIMILAR_WORD
        return 0
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Recognition side with the studied [headword] known: fills every blank of
     * [line] from it. Unlike [restoreInline] (which must guess the direction, so
     * it only trusts matches with hard evidence), here the headword IS the hidden
     * text, so fully hidden blanks ("•••" → "cot"), inflections ("SN•••ED OFF" →
     * "Snapped"), miscounted bullets and phrases whose other words are paraphrased
     * in the sentence can all be filled.
     *
     * The headword's words are aligned, in order, against the whole sentence —
     * its visible words as well as its blanks — and the best-scoring reading
     * wins. The visible words pin the blanks down: in "management came •••• on
     * him" ("come down on somebody") "came" takes "come" and "on" takes "on",
     * so the blank is "down"; in "wasn't •• •• the plan" ("be in on something")
     * the blanks are "in on", not "be in". A blank no headword word fits (e.g.
     * "catch-22" masked as "c•••• ••") is replaced, together with the blanks
     * right next to it, by the whole headword — the learner hears the word in
     * the sentence rather than the production codeword.
     */
    private fun fillFromHeadword(tokens: List<MatchResult>, line: String, headword: String): List<String> {
        // Compounds split ("••• of ••••" out of body) or whole ("e•••••-••"
        // either-or): whichever reads the sentence better (split on a tie).
        val fills = listOf(HEADWORD_PART, HEADWORD_UNIT)
            .map { alignToSentence(tokens, line, headword, it) }
            .maxBy { it.first }
            .second

        val whole = headword.lines().first { it.isNotBlank() }.trim()
        val adjacent = { i: Int ->
            i > 0 && line.substring(tokens[i - 1].range.last + 1, tokens[i].range.first).none { it.isLetterOrDigit() }
        }
        // Runs of adjacent blanks ("c•••• •• / c•••• •••"): an unfilled blank next
        // to a filled one is dropped (its neighbour already says the word); a run
        // with nothing filled says the whole headword once — unless it is a single
        // blank that is mostly visible letters, i.e. a bullet typed as a dash in
        // the deck ("25•minute", "2•4"), which is just read as written.
        val out = MutableList(tokens.size) { fills[it] }
        var start = 0
        while (start < tokens.size) {
            var end = start + 1
            while (end < tokens.size && adjacent(end)) end++
            val anyFilled = (start until end).any { fills[it] != null }
            for (i in start until end) {
                if (out[i] != null) continue
                val core = parseBlank(tokens[i].value)
                out[i] = when {
                    anyFilled || i > start -> ""
                    end - start == 1 && core.knowns.size * 2 > core.length ->
                        tokens[i].value.replace('•', ' ').trim { !it.isLetterOrDigit() }
                    else -> whole
                }
            }
            start = end
        }
        return out.map { it!! }
    }

    /**
     * Aligns [headword]'s words (as cut by [unit]) with [line] (see
     * [fillFromHeadword]): the total score and, per blank, its fill (null: no
     * headword word fits).
     */
    private fun alignToSentence(
        tokens: List<MatchResult>,
        line: String,
        headword: String,
        unit: Regex,
    ): Pair<Int, List<String?>> {
        val units = headwordUnits(headword, unit)

        // The sentence in order: visible words (blank = -1) and blanks (their index).
        val items = mutableListOf<Pair<String, Int>>()
        var pos = 0
        tokens.forEachIndexed { t, m ->
            unit.findAll(line.substring(pos, m.range.first)).forEach { items += it.value to -1 }
            items += m.value to t
            pos = m.range.last + 1
        }
        unit.findAll(line.substring(pos)).forEach { items += it.value to -1 }

        val fits = tokens.map { t -> units.map { u -> fit(t.value, u) } }
        val same = items.map { (text, t) -> units.map { u -> if (t < 0) wordMatch(text, u) else 0 } }
        val skipCost = IntArray(units.size) { j ->
            if (units[j].lowercase() in PHRASE_PLACEHOLDER) 0 else SKIP_MISSING
        }

        // best(s, h): best score for sentence items s.. with headword words h.. still
        // unplaced, plus the fill chosen for each blank among them (null: none fits).
        val memo = HashMap<Pair<Int, Int>, Pair<Int, List<String?>>>()
        fun best(s: Int, h: Int): Pair<Int, List<String?>> {
            if (s == items.size) return (h until units.size).sumOf { skipCost[it] } to emptyList()
            memo[s to h]?.let { return it }
            val t = items[s].second
            var top: Pair<Int, List<String?>>? = null
            fun offer(score: Int, fill: String?, s2: Int, h2: Int) {
                val (rest, fills) = best(s2, h2)
                val total = score + rest
                if (top == null || total > top!!.first) {
                    top = total to (if (s2 > s && t >= 0) listOf(fill) + fills else fills)
                }
            }
            if (t >= 0) {
                if (h < units.size) fits[t][h]?.let { offer(it.score, it.text, s + 1, h + 1) }
                (0 until h).mapNotNull { k -> fits[t][k] }.maxByOrNull { it.score }
                    ?.let { offer(it.score + REUSE, it.text, s + 1, h) }
                offer(UNRESOLVED, null, s + 1, h)
            } else {
                if (h < units.size && same[s][h] > 0) offer(same[s][h], null, s + 1, h + 1)
                offer(0, null, s + 1, h)   // a sentence word that isn't in the headword
            }
            if (h < units.size) offer(skipCost[h], null, s, h + 1)
            memo[s to h] = top!!
            return top!!
        }
        return best(0, 0)
    }

    /** Dictionary lemma markers that head a phrase but never appear literally
     *  in its example sentence ("be sb out of sth"), so their absence from the
     *  sentence must not veto a restore. */
    private val PHRASE_PLACEHOLDER = setOf(
        "be", "sb", "sth", "one", "ones", "one's", "oneself", "your", "yours", "yourself",
        "somebody", "somebody's", "someone", "someone's", "something", "somewhere",
    )

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
            val forms = lemmas.flatMap { inflectedForms(it) }
            val exact = forms.filter { b.matches(it) }.distinctBy { it.lowercase() }
            if (exact.size == 1) return exact.first()
            // Some notes mis-count the bullets ("f•••cked" for the 9-letter
            // "frolicked"): if the shown stem AND ending still pin one inflected
            // form, within a letter of the blank's length, accept it. Both ends
            // must be visible so the definition's words can't sneak in.
            if (b.prefix.isNotEmpty() && b.suffix.isNotEmpty()) {
                val loose = forms.filter {
                    it.startsWith(b.prefix, ignoreCase = true) &&
                        it.endsWith(b.suffix, ignoreCase = true) &&
                        kotlin.math.abs(it.length - b.length) <= 1
                }.distinctBy { it.lowercase() }
                if (loose.size == 1) return loose.first()
            }
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
    // No "ies"/"ied": the y→i stem plus "es"/"ed" already spells "carries"/"carried",
    // and on any other word they only invent look-alikes — "Snapied" fits
    // "SN•••ED" as well as "Snapped" does, and that ambiguity blocked the restore.
    // "ly" makes adverbs ("d•••fully" dutifully, "happily").
    private val INFLECTION_ENDINGS = listOf("s", "es", "d", "ed", "ing", "er", "est", "ly")

    /**
     * Every regular inflected spelling of [lemma]: each ending in
     * [INFLECTION_ENDINGS] applied plain ("walk"→"walking"), with a dropped silent
     * final e ("hone"→"honing", "proscribe"→"proscribed"), with a doubled final
     * consonant after a single short vowel ("run"→"running", "snap"→"snapped" —
     * never "head"→"headdes" or "bring"→"bringgs", junk that can shadow the
     * real match), and with y→i ("carry"→"carried"). Generated
     * from a fixed ending set rather than the blank's revealed tail, because that
     * tail can include stem letters ("••••••ibed" shows the "ib" of proscribe, not
     * just the "-d"). Over-generates on purpose — the caller keeps only a form that
     * exactly fits the blank.
     */
    private fun inflectedForms(lemma: String): List<String> {
        val lower = lemma.lowercase()
        val dropE = if (lower.endsWith("e")) lemma.dropLast(1) else null
        // y → i after a consonant, listed first ("shied", not "shyed"), for every
        // ending but -s/-ing ("carries" comes from -es, "carrying" is plain);
        // after a vowel only for the -d past ("laid", "paid").
        val yStem = if (lower.length >= 2 && lower.endsWith("y")) lemma.dropLast(1) + "i" else null
        val consonantY = yStem != null && lower[lower.length - 2] !in "aeiou"
        // A final hard "c" takes a "k" instead of doubling: frolic→frolicked,
        // frolicking; panic→panicked; mimic→mimicking (never a junk "frolicced",
        // which fits the same blanks as the real form).
        val ckStem = if (lower.endsWith("c")) lemma + "k" else null
        val doubled = if (ckStem == null && endsConsonantVowelConsonant(lower)) lemma + lemma.last() else null
        val forms = mutableListOf<String>()
        for (e in INFLECTION_ENDINGS) {
            if (yStem != null && (if (consonantY) e != "s" && e != "ing" else e == "d")) forms += yStem + e
            forms += lemma + e
            // The stem changes only before a vowel: "honed" but not "hond" (a junk
            // "reciprocatd" beat "reciprocated" to a blank).
            if (e[0] in "aeiou") {
                dropE?.let { forms += it + e }
                doubled?.let { forms += it + e }
                ckStem?.let { forms += it + e }
            }
        }
        return forms.distinct()
    }

    /** "snap", "run", "acquit": the shape whose final consonant doubles before an
     *  ending ("u" after "q" is a consonant). */
    private fun endsConsonantVowelConsonant(word: String): Boolean {
        if (word.length < 3) return false
        val w = word.lowercase()
        fun vowel(i: Int) = w[i] in "aeiou" && !(w[i] == 'u' && i > 0 && w[i - 1] == 'q')
        val n = w.length
        return w[n - 1].isLetter() && w[n - 1] !in "wxy" && !vowel(n - 1) && vowel(n - 2) && !vowel(n - 3)
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
