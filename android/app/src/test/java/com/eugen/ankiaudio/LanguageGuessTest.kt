package com.eugen.ankiaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGuessTest {

    private fun ca(text: String) = assertTrue("expected Catalan: $text", LanguageGuess.isCatalan(text))
    private fun notCa(text: String) = assertFalse("expected not Catalan: $text", LanguageGuess.isCatalan(text))

    // ---- Catalan glosses are recognised ----

    @Test fun sentence() = ca("Una persona que no es queixa mai de res i és molt amable.")
    @Test fun definitionWithMarkers() = ca("Fer-se càrrec d'alguna cosa amb molta cura.")
    @Test fun shortGlossByCharacters() = ca("Això és un càrrec")
    @Test fun geminateL() = ca("Una col·lecció d'objectes")
    @Test fun cedilla() = ca("Tenir força i coratge")
    @Test fun blankedWordsAreIgnored() = ca("Aquesta cosa és molt wh${"•".repeat(5)}d i fa por")

    // ---- English stays English ----

    @Test fun plainEnglish() = notCa("She wheedled him into taking her with him.")
    @Test fun englishDefinition() = notCa("to persuade someone to do something by being nice to them")
    @Test fun englishWithAccentedLoanword() = notCa("A small café where you can eat a very nice crème brûlée.")
    @Test fun englishWithFewCatalanLookalikes() = notCa("The el nino effect is when the sea is warmer than usual.")
    @Test fun singleWords() { notCa("stance"); notCa("cot"); notCa("") }
    @Test fun hintText() = notCa("8 letter word starting with W H and ending with D")

    // ---- voice selection ----

    @Test fun englishKeepsSavedVoice() =
        assertEquals("en-GB-RyanNeural", EdgeVoices.voiceFor("en-GB-RyanNeural", null))

    @Test fun catalanSwitchesEnglishVoice() =
        assertEquals(EdgeVoices.CATALAN_VOICE, EdgeVoices.voiceFor("en-US-AndrewMultilingualNeural", "ca-ES"))

    @Test fun savedCatalanVoiceIsKept() =
        assertEquals("ca-ES-EnricNeural", EdgeVoices.voiceFor("ca-ES-EnricNeural", "ca-ES"))

    @Test fun localeOnlyForCatalan() {
        assertEquals("ca-ES", LanguageGuess.localeOf("Una persona que no es queixa mai de res."))
        assertNull(LanguageGuess.localeOf("She wheedled him into taking her with him."))
    }

    // ---- scripts tag the right clips ----

    @Test fun catalanLineIsTaggedAndEnglishLinesAreNot() {
        val segments = AudioScript.forQuestion(
            "fer-se càrrec\n\nAquesta cosa és molt important per a ell.\n\nHe took charge of it.",
            word = "take charge",
        )
        val speech = segments.filterIsInstance<Segment.Speech>()
        assertEquals(
            listOf(
                "fer-se càrrec" to "ca-ES",
                "Aquesta cosa és molt important per a ell." to "ca-ES",
                "He took charge of it." to null,
            ),
            speech.map { it.text to it.locale },
        )
    }

    @Test fun catalanBlankedSentenceTagsEveryClipOfThatLine() {
        val blank = "•".repeat(5)
        val segments = AudioScript.forQuestion(
            "Una persona que no és gens amable i $blank molt.\n\nw$blank",
            word = "",
        )
        val speech = segments.filterIsInstance<Segment.Speech>()
        // Sentence before the blank, the slowed "blank" codeword, the rest of
        // the sentence — all Catalan — then the English letter hint.
        val hint = speech.last()
        val sentence = speech.dropLast(1)
        assertTrue(hint.text.contains("letter word"))
        assertNull(hint.locale)
        assertTrue(sentence.size >= 3)
        assertTrue(sentence.all { it.locale == "ca-ES" })
    }

    @Test fun letterHintStaysEnglishAfterCatalanLine() {
        val blank = "•".repeat(5)
        val segments = AudioScript.forQuestion(
            "Aquesta cosa és molt important per a ell.\n\nHe took w$blank of it.\n\nw$blank",
            word = "weigh",
        )
        val speech = segments.filterIsInstance<Segment.Speech>()
        assertEquals("ca-ES", speech.first().locale)
        assertNull(speech.last().locale)
    }
}
