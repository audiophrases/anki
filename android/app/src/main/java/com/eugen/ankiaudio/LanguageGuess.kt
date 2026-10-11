package com.eugen.ankiaudio

/**
 * Offline guess of whether a spoken line is Catalan rather than English, so
 * [CardSpeaker] can hand it to a Catalan neural voice instead of reading it
 * with the learner's English one (which mangles it).
 *
 * Decks are mostly English with an occasional Catalan gloss, so the question
 * is only "is this line clearly Catalan?" — anything uncertain stays English.
 * Two kinds of evidence, both cheap and with no network or model:
 *  - characters English never uses: "ç", "l·l", grave/acute vowels like "à ò í";
 *  - Catalan function words that are not also English words ("amb", "són",
 *    "això"), weighed against common English ones ("the", "of", "which").
 */
object LanguageGuess {

    const val CATALAN = "ca-ES"

    /** Blanked words ("wh•••••d") carry no language evidence and would split into stray letters. */
    private val BLANK = Regex("""\S*•+\S*""")
    private val WORD = Regex("""\p{L}+""")

    private val STRONG_CHARS = Regex("""[çàèòïü]|l·l""")
    private val SOFT_CHARS = Regex("""[éíóú]""")

    // Deliberately excludes words English shares or that are too short to trust:
    // a, no, per, me, es, de, en, la, i, si, o, ni, ha, era, fa, com, on, res, pot, fins, hi.
    private val CATALAN_WORDS = setOf(
        "el", "els", "les", "una", "uns", "unes", "és", "són", "està", "estan",
        "amb", "del", "dels", "als", "pel", "pels", "què", "qui", "quan",
        "però", "també", "molt", "molts", "més", "ara", "aquí", "allà", "això",
        "aquest", "aquesta", "aquests", "aquestes", "algú", "alguna", "algun", "ningú",
        "cosa", "coses", "mateix", "sempre", "mai", "tots", "tota", "totes",
        "fer", "fet", "han", "seu", "seva", "seus", "seves", "meu", "meva", "teu", "teva",
        "dir", "diu", "cal", "perquè", "sense", "sobre", "entre", "després", "abans",
        "durant", "doncs", "així", "encara", "només", "bé", "ja",
    )

    private val ENGLISH_WORDS = setOf(
        "the", "of", "and", "to", "is", "that", "with", "for", "or", "who", "which", "something",
        "someone", "somebody", "in", "on", "it", "was", "are", "be", "an", "as", "by", "from",
        "his", "her", "he", "she", "they", "this", "not", "you", "have", "has", "had", "at",
        "when", "what", "one", "used", "about", "very", "can", "will", "would", "your", "my",
    )

    /** "ca-ES" if [text] is clearly Catalan, else null (leave the learner's voice alone). */
    fun localeOf(text: String): String? = if (isCatalan(text)) CATALAN else null

    fun isCatalan(text: String): Boolean {
        val clean = BLANK.replace(text, " ").lowercase()

        var catalan = 0
        var english = 0
        for (m in WORD.findAll(clean)) {
            val w = m.value
            if (w in CATALAN_WORDS) catalan++
            if (w in ENGLISH_WORDS) english++
        }
        catalan += 2 * STRONG_CHARS.findAll(clean).count()
        catalan += SOFT_CHARS.findAll(clean).count()

        // Short glosses ("fer-se càrrec", "amb ganes") have little to go on, so
        // one strong signal is enough there; longer lines need a clear majority.
        return catalan >= 2 && catalan > english * 2
    }
}
