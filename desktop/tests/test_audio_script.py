"""Mirror of android/app/src/test/java/com/eugen/ankiaudio/AudioScriptTest.kt.

Card texts are shaped like the rendered English::Main cards:
Recognize = "word\\n\\nexample", Produce = "definition\\n\\nexample\\n\\nhint".

Run from the repo root:  python -m unittest discover -s desktop/tests
"""

import os
import re
import sys
import textwrap
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ankiaudio"))

import audio_script as A  # noqa: E402

b = "•"


def blanks(n):
    return b * n


def spoken(segments):
    return A.spoken_text(segments)


def recognition(word, example):
    return spoken(A.for_question(f"{word}\n\n{example}", word))


def production(word, definition, example, hint):
    return spoken(A.for_question(f"{definition}\n\n{example}\n\n{hint}", word))


def production_segments(word, definition, example, hint):
    return A.for_question(f"{definition}\n\n{example}\n\n{hint}", word)


class Recognition(unittest.TestCase):
    """The word is read back into the sentence, never "blank"."""

    def test_restores_visible_letters(self):
        self.assertEqual(
            "stance | What is your stance on environmental issues?",
            recognition("stance", f"What is your st{blanks(4)} on environmental issues?"),
        )

    def test_restores_fully_hidden_word(self):
        self.assertEqual(
            "cot | She just slept on the cot in her office.",
            recognition("cot", f"She just slept on the {blanks(3)} in her office."),
        )

    def test_inflects_with_doubled_consonant(self):
        self.assertEqual(
            "Snap off | He Snapped OFF a bit of chocolate.",
            recognition("Snap off", f"He SN{blanks(3)}ED OFF a bit of chocolate."),
        )

    def test_places_phrase_words_by_sentence(self):
        self.assertEqual(
            "be in on something | Susan was the only one who wasn't in on the plan.",
            recognition("be in on something", f"Susan was the only one who wasn't {blanks(2)} {blanks(2)} the plan."),
        )
        self.assertEqual(
            "come down on somebody | management came down on him",
            recognition("come down on somebody", f"management came {blanks(4)} on him"),
        )

    def test_fills_verb_shown_inflected(self):
        self.assertEqual(
            "Sign out | I SIGNED out and then shut the computer down.",
            recognition("Sign out", f"I SIGNED {blanks(3)} and then shut the computer down."),
        )

    def test_tolerates_miscounted_bullets(self):
        self.assertEqual(
            "be betrothed to somebody | She was betrothed to her cousin.",
            recognition("be betrothed to somebody", f"She was b{blanks(5)}thed to her cousin."),
        )

    def test_treats_nbsp_as_space(self):
        self.assertEqual(
            "thunderstruck | Ruth was thunderstruck when he proposed.",
            recognition("thunderstruck", f"Ruth was th{blanks(5)}st{blanks(2)}ck when he proposed."),
        )

    def test_splits_compound_written_apart(self):
        self.assertEqual(
            "catch-22 | The permit problem became a catch 22.",
            recognition("catch-22", f"The permit problem became a c{blanks(4)} {blanks(2)}."),
        )

    def test_says_whole_headword_when_nothing_fits(self):
        self.assertEqual(
            "kick the bucket | He finally kick the bucket yesterday.",
            recognition("kick the bucket", f"He finally {blanks(13)} yesterday."),
        )

    def test_never_says_blank_or_letter_hint(self):
        said = recognition("spree", f"they went on a drinking {blanks(5)}\na shopping {blanks(5)}")
        self.assertFalse(re.search(r"\bblank\b", said), said)
        self.assertNotIn("letter word", said)


class Production(unittest.TestCase):
    """The word stays hidden."""

    def test_definition_then_hint_then_recorded_example(self):
        self.assertEqual(
            "sharpen | 4 letter word starting with H | The bone had been | [mhm] | to a point.",
            production("hone", "sharpen", f"The bone had been h{blanks(3)}d to a point.", f"h{blanks(3)}"),
        )

    def test_stays_production_when_definition_contains_word(self):
        said = production(
            "chestnut", "edible nut of a chestnut tree", f"She roasted a c{blanks(7)} over the fire.", f"c{blanks(7)}"
        )
        self.assertIn("She roasted a | [mhm] | over the fire.", said)

    def test_uses_recording_without_spoken_blank_or_pre_pause(self):
        segments = production_segments(
            "hone", "sharpen", f"The bone had been h{blanks(3)}d to a point.", f"h{blanks(3)}"
        )
        i = next(k for k, s in enumerate(segments) if isinstance(s, A.Blank))
        self.assertEqual(A.Speech("The bone had been"), segments[i - 1])
        self.assertFalse(any(isinstance(s, A.Speech) and s.text == "blank" for s in segments))
        self.assertEqual(A.Speech("to a point.", lead_in=False), segments[i + 1])

    def test_adjacent_blanks_each_keep_a_recording(self):
        segments = production_segments(
            "be in on something",
            "involved in a secret",
            f"She wasn't {blanks(2)} {blanks(2)} the plan.",
            f"{blanks(2)} {blanks(2)}",
        )
        i = next(k for k, s in enumerate(segments) if isinstance(s, A.Blank))
        self.assertEqual(A.Speech("She wasn't"), segments[i - 1])
        self.assertEqual(A.BLANK, segments[i + 1])
        self.assertEqual(A.Speech("the plan.", lead_in=False), segments[i + 2])

    def test_figure_out_example_follows_definition_and_shape(self):
        self.assertEqual(
            "understand or solve | 6 letter word starting with F, then a 3 letter word starting with O"
            " | I still need to | [mhm] | things | [mhm]",
            production("figure out", "understand or solve", "I still need to f••••• things o••.", "f••••• o••"),
        )

    def test_hint_without_dedicated_field_still_precedes_example(self):
        self.assertEqual(
            "understand | 6 letter word, then a 3 letter word | I still need to | [mhm] | things | [mhm]",
            spoken(A.for_question("understand\nI still need to •••••• things •••.", "figure out")),
        )

    def test_answer_does_not_repeat_examples_or_hints(self):
        self.assertEqual(
            "figure out",
            spoken(
                A.for_answer(
                    "figure out\nunderstand\nI still need to f••••• things o••.\nf••••• o••",
                    "understand\nI still need to f••••• things o••.\nf••••• o••",
                    "figure out",
                )
            ),
        )


class CkInflection(unittest.TestCase):
    """The "-c" → "-ck" family (frolic → frolicked / frolicking)."""

    FROLIC = textwrap.dedent(
        """\
        frolic
        play actively, happily
        Lambs f•••cked in the next field.
        f•••c"""
    )

    def test_frolicked_restored_on_recognition(self):
        text = spoken(A.for_question(self.FROLIC, word="frolic"))
        self.assertIn("Lambs frolicked in the next field.", text)
        self.assertNotIn("blank", text)
        self.assertNotIn("letter word", text)

    def test_frolicked_restored_without_known_word(self):
        text = spoken(A.for_question(self.FROLIC))
        self.assertIn("Lambs frolicked in the next field.", text)

    def test_panicked_restored_with_exact_bullets(self):
        question = "panic\nfeel sudden fear\nThey p••icked and ran."
        text = spoken(A.for_question(question, word="panic"))
        self.assertIn("They panicked and ran.", text)

    def test_frolicked_still_blank_on_production(self):
        question = "play actively, happily\nLambs f•••cked in the next field.\nf•••c"
        text = spoken(A.for_question(question, word="frolic"))
        self.assertNotIn("frolicked", text)
        self.assertIn("[mhm]", text)


class Abbreviations(unittest.TestCase):
    def test_dictionary_tags_are_spoken_as_words(self):
        self.assertEqual("verb informal, especially British English", A.expand_abbreviations("v. inf., esp BrE"))

    def test_initials_and_sentence_end_words_survive(self):
        self.assertEqual("N. America", A.expand_abbreviations("N. America"))
        self.assertEqual("the room was dimly lit.", A.expand_abbreviations("the room was dimly lit."))


if __name__ == "__main__":
    unittest.main()
