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
 * blanked text appears on both card directions. The audio rendering is
 * therefore direction-aware:
 *
 * - **Word visible on this side** (studying word → meaning): the blank is
 *   pointless by ear, so it is RESTORED from the visible word:
 *   "She wheedled him into taking her with him."
 * - **Word hidden** (studying meaning → word): the blank is spoken inline,
 *   in position, as a compact spelled hint (Eugen's design):
 *   "She W, H, 8 letter word, him into taking her with him."
 *   (the voice says "double-u aitch, eight letter word")
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
                    standaloneHint = renderBlank(tokens.first().value, candidates)
                }
                continue
            }

            out += Segment.Speech(line.replace(BLANK_TOKEN) { m ->
                renderBlank(m.value, candidates)
            })
            out += Segment.Pause(LINE_PAUSE_MS)
            renderedInline = true
        }

        if (!renderedInline && standaloneHint != null) {
            out += Segment.Speech(standaloneHint)
            out += Segment.Pause(LINE_PAUSE_MS)
        }
        return out
    }

    /**
     * "wh•••••d" + candidates containing "wheedle" → "wheedled" (restored).
     * "wh•••••d" + no matching candidate → "W, H, 8 letter word," (hint).
     */
    private fun renderBlank(token: String, candidates: List<String>): String {
        val core = token.trim { !it.isLetterOrDigit() && it != '•' }
        val prefix = core.takeWhile { it != '•' }.filter { it.isLetterOrDigit() }
        val suffix = core.takeLastWhile { it != '•' }.filter { it.isLetterOrDigit() }
        val bullets = core.count { it == '•' }

        if (prefix.isNotEmpty()) {
            // Exact shape: visible word length == prefix + hidden letters.
            val exact = candidates.firstOrNull {
                it.length == prefix.length + bullets &&
                    it.startsWith(prefix, ignoreCase = true)
            }
            // Tolerant shape for slightly irregular inflections.
            val loose = if (prefix.length >= 2) {
                candidates.firstOrNull {
                    it.startsWith(prefix, ignoreCase = true) &&
                        kotlin.math.abs(it.length - (prefix.length + bullets)) <= 2
                }
            } else null
            val match = exact ?: loose
            if (match != null) return match + suffix
        }

        return spokenHint(prefix, suffix, prefix.length + bullets + suffix.length)
    }

    /** "wh", "d", 8 → "W, H, 8 letter word ending in D,". */
    private fun spokenHint(prefix: String, suffix: String, length: Int): String {
        val sb = StringBuilder()
        if (prefix.isNotEmpty()) sb.append(spell(prefix)).append(", ")
        sb.append("$length letter word")
        if (suffix.isNotEmpty()) sb.append(" ending in ").append(spell(suffix))
        sb.append(",")
        return sb.toString()
    }

    /** "wh" → "W, H" so the voice says the letter names. */
    private fun spell(s: String): String =
        s.uppercase().toCharArray().joinToString(", ")
}
