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
 *   visible word: "She wheedled him into taking her with him."
 * - **PRODUCTION** (meaning given, produce the word — the word is hidden):
 *   the blank is spoken inline, in position, as a natural hint:
 *   "She, 8 letter word starting with W H and ending with D, him into
 *   taking her with him."
 *
 * A line consisting of nothing but a blanked token (a dedicated hint field
 * like "wh•••••") is skipped when a blank was already rendered inline in an
 * earlier line; otherwise it is spoken as a standalone hint.
 */
object AudioScript {

    /** A run of bullets with optional visible letters around it, e.g. "wh•••••d". */
    private val BLANK_TOKEN = Regex("""\S*•+\S*""")

    /** Words usable for restoring a blank (from lines without bullets). */
    private val CANDIDATE_WORD = Regex("""\p{L}{2,}""")

    private const val LINE_PAUSE_MS = 400L

    fun forQuestion(questionText: String): List<Segment> = render(questionText)

    fun forAnswer(answerText: String): List<Segment> = render(answerText)

    private fun render(text: String): List<Segment> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Candidate words that might be the hidden word, visible on this side.
        val candidates = lines.filterNot { it.contains('•') }
            .flatMap { CANDIDATE_WORD.findAll(it).map { m -> m.value } }
            .distinct()

        val out = mutableListOf<Segment>()
        var renderedInline = false
        var standaloneHint: String? = null

        for (line in lines) {
            val tokens = BLANK_TOKEN.findAll(line).toList()
            if (tokens.isEmpty()) {
                out += Segment.Speech(line)
                out += Segment.Pause(LINE_PAUSE_MS)
                continue
            }

            val withoutTokens = line.replace(BLANK_TOKEN, "")
            if (withoutTokens.none { it.isLetterOrDigit() }) {
                // Hint-only line (dedicated hint field like "wh•••••").
                if (standaloneHint == null) {
                    standaloneHint = restore(tokens.first().value, candidates)
                        ?: hintPhrase(tokens.first().value)
                }
                continue
            }

            out += Segment.Speech(renderLineWithBlanks(line, tokens, candidates))
            out += Segment.Pause(LINE_PAUSE_MS)
            renderedInline = true
        }

        if (!renderedInline && standaloneHint != null) {
            out += Segment.Speech(standaloneHint)
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
     * Renders a sentence containing blank tokens. Restored words drop into
     * place silently; unrestorable blanks become spoken hints, and runs of
     * blanks separated by little words are connected the way a person would
     * say them (Eugen's phrasing):
     *
     *   "barely m••e a d••t in the backlog" (word hidden) →
     *   "barely, 4 letter word starting with M and ending with E, then the
     *    word a, then a 4 letter word starting with D and ending with T,
     *    in the backlog"
     */
    private fun renderLineWithBlanks(
        line: String,
        tokens: List<MatchResult>,
        candidates: List<String>,
    ): String {
        val restored = tokens.map { restore(it.value, candidates) }
        val sb = StringBuilder()
        var idx = 0
        var i = 0
        while (i < tokens.size) {
            sb.append(line, idx, tokens[i].range.first)
            if (restored[i] != null) {
                sb.append(restored[i])
                idx = tokens[i].range.last + 1
                i++
                continue
            }
            // A hint, possibly chained with following hints across small gaps.
            sb.append(", ").append(hintPhrase(tokens[i].value))
            idx = tokens[i].range.last + 1
            var j = i + 1
            while (j < tokens.size && restored[j] == null) {
                val between = line.substring(tokens[j - 1].range.last + 1, tokens[j].range.first)
                val words = between.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val plain = between.none { it in ".,;:!?" }
                if (!plain || words.size > 2) break
                if (words.isEmpty()) {
                    sb.append(", then a ").append(hintPhrase(tokens[j].value))
                } else {
                    sb.append(", then the word ").append(words.joinToString(" "))
                        .append(", then a ").append(hintPhrase(tokens[j].value))
                }
                idx = tokens[j].range.last + 1
                j++
            }
            sb.append(",")
            i = j
        }
        sb.append(line, idx, line.length)
        return sb.toString()
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
