package com.eugen.ankiaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card texts are shaped like AnkiDroid's rendered English::Main cards:
 * Recognize = "word\n\nexample", Produce = "definition\n\nexample\n\nhint".
 */
class AudioScriptTest {

    private val b = "•"
    private fun blanks(n: Int) = b.repeat(n)

    /** What the voice says, one utterance per " | ". */
    private fun spoken(segments: List<Segment>) =
        segments.filterIsInstance<Segment.Speech>().joinToString(" | ") { it.text }

    private fun recognition(word: String, example: String) =
        spoken(AudioScript.forQuestion("$word\n\n$example", word))

    private fun production(word: String, definition: String, example: String, hint: String) =
        spoken(AudioScript.forQuestion("$definition\n\n$example\n\n$hint", word))

    // ---- recognition: the word is read back into the sentence, never "blank" ----

    @Test fun recognitionRestoresVisibleLetters() = assertEquals(
        "stance | What is your stance on environmental issues?",
        recognition("stance", "What is your st${blanks(4)} on environmental issues?"),
    )

    @Test fun recognitionRestoresFullyHiddenWord() = assertEquals(
        "cot | She just slept on the cot in her office.",
        recognition("cot", "She just slept on the ${blanks(3)} in her office."),
    )

    @Test fun recognitionInflectsWithDoubledConsonant() = assertEquals(
        "Snap off | He Snapped OFF a bit of chocolate.",
        recognition("Snap off", "He SN${blanks(3)}ED OFF a bit of chocolate."),
    )

    @Test fun recognitionPlacesPhraseWordsBySentence() {
        assertEquals(
            "be in on something | Susan was the only one who wasn't in on the plan.",
            recognition("be in on something", "Susan was the only one who wasn't ${blanks(2)} ${blanks(2)} the plan."),
        )
        assertEquals(
            "come down on somebody | management came down on him",
            recognition("come down on somebody", "management came ${blanks(4)} on him"),
        )
    }

    @Test fun recognitionFillsVerbShownInflected() = assertEquals(
        "Sign out | I SIGNED out and then shut the computer down.",
        recognition("Sign out", "I SIGNED ${blanks(3)} and then shut the computer down."),
    )

    @Test fun recognitionToleratesMiscountedBullets() = assertEquals(
        "be betrothed to somebody | She was betrothed to her cousin.",
        recognition("be betrothed to somebody", "She was b${blanks(5)}thed to her cousin."),
    )

    @Test fun recognitionTreatsNbspAsSpace() = assertEquals(
        "thunderstruck | Ruth was thunderstruck when he proposed.",
        recognition("thunderstruck", "Ruth was\u00A0th${blanks(5)}st${blanks(2)}ck\u00A0when he proposed."),
    )

    @Test fun recognitionSplitsCompoundWrittenApart() = assertEquals(
        "catch-22 | The permit problem became a catch 22.",
        recognition("catch-22", "The permit problem became a c${blanks(4)} ${blanks(2)}."),
    )

    @Test fun recognitionSaysWholeHeadwordWhenNothingFits() = assertEquals(
        "kick the bucket | He finally kick the bucket yesterday.",
        recognition("kick the bucket", "He finally ${blanks(13)} yesterday."),
    )

    @Test fun recognitionNeverSaysBlankOrLetterHint() {
        val said = recognition("spree", "they went on a drinking ${blanks(5)}\na shopping ${blanks(5)}")
        assertFalse(said, Regex("\\bblank\\b").containsMatchIn(said))
        assertFalse(said, said.contains("letter word"))
    }

    // ---- production: the word stays hidden ----

    @Test fun productionSaysBlankAndHint() = assertEquals(
        "sharpen | The bone had been | blank | to a point. | 4 letter word starting with H",
        production("hone", "sharpen", "The bone had been h${blanks(3)}d to a point.", "h${blanks(3)}"),
    )

    @Test fun productionStaysProductionWhenDefinitionContainsWord() {
        val said = production(
            "chestnut", "edible nut of a chestnut tree", "She roasted a c${blanks(7)} over the fire.", "c${blanks(7)}",
        )
        assertTrue(said, said.contains("She roasted a | blank | over the fire."))
    }
}
