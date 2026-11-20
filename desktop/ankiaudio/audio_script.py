"""Turns a card's text into an eyes-free audio script.

Python port of ``android/app/src/main/java/com/eugen/ankiaudio/AudioScript.kt``.
The two must stay in step: a fix to how blanks, hints or abbreviations are read
belongs in both. ``desktop/tests/test_audio_script.py`` mirrors the Kotlin
``AudioScriptTest`` so the ports can be checked against each other.

The decks store cloze blanks as bullet runs *inside the field data* (example =
"She wh•••••d him into taking her with him."), so the same blanked text appears
in both study directions. The rendering adapts to the direction:

- **Recognition** (the word is visible on this side): every blank is restored
  from the studied word, so the sentence reads naturally. The dedicated hint
  field ("wh•••••") is not read.
- **Production** (the word is hidden): definition, then the letter hint
  ("8 letter word starting with W H and ending with D"), then the example with
  each hidden word replaced by the recorded "mhm".

Dictionary tags ("v.", "inf.", "esp") are expanded so the voice reads them as
words.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Iterable, List, Optional, Sequence, Tuple, Union

# ---------------------------------------------------------------------------
# Segments


@dataclass(frozen=True)
class Speech:
    """Spoken text. ``lead_in`` is False for text continuing after a blank."""

    text: str
    lead_in: bool = True


@dataclass(frozen=True)
class Blank:
    """The learner's recorded "mhm", followed by a short pause."""


@dataclass(frozen=True)
class Pause:
    ms: int


Segment = Union[Speech, Blank, Pause]
BLANK = Blank()

# ---------------------------------------------------------------------------
# Constants

BULLET = "•"

#: A run of bullets with optional visible letters around it, e.g. "wh•••••d".
BLANK_TOKEN = re.compile(r"\S*•+\S*")

#: Words usable for restoring a blank (letters only, at least two).
CANDIDATE_WORD = re.compile(r"[^\W\d_]{2,}")

#: A field with at most this many words is a headword (word/phrase) field rather
#: than a definition: the source for inflected restores.
HEADWORD_MAX_WORDS = 5

#: Silence between lines. The desktop add-on overrides this from its config.
LINE_PAUSE_MS = 400

#: Internal marker content; never sent to TTS.
CODEWORD = "blank"

# Private-use sentinels identify recording positions inside a rendered line.
MARK_OPEN = ""
MARK_CLOSE = ""


def _lines(text: str) -> List[str]:
    """Kotlin's ``String.lines()``: split on \\r\\n, \\n or \\r."""
    return re.split(r"\r\n|\n|\r", text)


def _plain_spaces(s: str) -> str:
    """``&nbsp;`` arrives as U+00A0. Make it a plain space so it can't fuse
    neighbouring words into one blank token."""
    return s.replace(" ", " ")


def _trim(s: str, pred: Callable[[str], bool]) -> str:
    """Strip characters matching ``pred`` from both ends."""
    start, end = 0, len(s)
    while start < end and pred(s[start]):
        start += 1
    while end > start and pred(s[end - 1]):
        end -= 1
    return s[start:end]


def _take_while(s: str, pred: Callable[[str], bool]) -> str:
    i = 0
    while i < len(s) and pred(s[i]):
        i += 1
    return s[:i]


def _take_last_while(s: str, pred: Callable[[str], bool]) -> str:
    i = len(s)
    while i > 0 and pred(s[i - 1]):
        i -= 1
    return s[i:]


def _distinct(items: Iterable[str]) -> List[str]:
    seen = set()
    out = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def _distinct_ci(items: Iterable[str]) -> List[str]:
    """Distinct by lower case, keeping the first spelling."""
    seen = set()
    out = []
    for item in items:
        key = item.lower()
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


def _starts_ci(s: str, prefix: str) -> bool:
    return s.lower().startswith(prefix.lower())


def _ends_ci(s: str, suffix: str) -> bool:
    return s.lower().endswith(suffix.lower())


# ---------------------------------------------------------------------------
# Public API


def for_question(question_text: str, word: str = "") -> List[Segment]:
    question = _plain_spaces(question_text)
    w = _plain_spaces(word)
    production = _is_production(question, w)
    return _render(question, production, _headword(w, production))


def for_answer(answer_text: str, question_text: str = "", word: str = "") -> List[Segment]:
    """Only the *new* information on the answer side is spoken.

    The comparison is on the raw field lines, before blank rendering: the
    example sentence is the same blanked line on both sides, so it is dropped
    from the reveal even though its rendering would differ.
    """
    answer = _plain_spaces(answer_text)
    question = _plain_spaces(question_text)
    w = _plain_spaces(word)
    production = _is_production(question, w)
    head = _headword(w, production)
    if not question:
        return _render(answer, production, head)

    seen = {_normalize(line) for line in _lines(question)}
    fresh = [line for line in _lines(answer) if line.strip() and _normalize(line) not in seen]
    segments = _render("\n".join(fresh), production, head)

    # If everything was redundant, better to repeat than to stay silent.
    if any(isinstance(s, Speech) for s in segments):
        return segments
    return _render(answer, production, head)


def spoken_text(segments: Sequence[Segment]) -> str:
    """What the voice says, one utterance per " | " (for logs and tests)."""
    parts = []
    for s in segments:
        if isinstance(s, Speech):
            parts.append(s.text)
        elif isinstance(s, Blank):
            parts.append("[mhm]")
    return " | ".join(parts)


# ---------------------------------------------------------------------------
# Direction


def _headword(word: str, production: bool) -> Optional[str]:
    """The studied word to restore blanks from, only on a known recognition card."""
    return word if word.strip() and not production else None


def _is_production(question: str, word: str) -> bool:
    """Whether the learner must supply the word: the studied word (the note's
    first field) fills whole line(s) of the recognition side and is absent from
    the production side. Mere occurrence doesn't count: a production definition
    can contain the word itself ("chestnut": "edible nut of a chestnut tree").
    An unknown word (empty) is treated as recognition."""
    word_lines = [n for n in (_normalize(l) for l in _lines(word)) if n]
    if not word_lines:
        return False
    q_lines = [n for n in (_normalize(l) for l in _lines(question)) if n]
    size = len(word_lines)
    windows = (q_lines[i : i + size] for i in range(len(q_lines) - size + 1))
    return not any(win == word_lines for win in windows)


def _normalize(s: str) -> str:
    kept = "".join(c for c in s.lower() if c.isalnum() or c == " ")
    return re.sub(r"\s+", " ", kept).strip()


# ---------------------------------------------------------------------------
# Abbreviations

#: Dictionary tags in the main deck, and how the voice should say them.
SPOKEN_ABBREVIATION = {
    "inf": "informal",
    "adj": "adjective",
    "adv": "adverb",
    "prep": "preposition",
    "conj": "conjunction",
    "esp": "especially",
    "fig": "figurative",
    "lit": "literary",
    "v": "verb",
    "n": "noun",
}

#: Tags that are also ordinary words ("the lamp was lit."): left alone at the
#: end of a line, where they read as sentence words.
WORD_COLLISIONS = {"fig", "lit"}


def _abbreviation_regex() -> "re.Pattern[str]":
    def alt(keys: Iterable[str]) -> str:
        return "|".join(k if len(k) == 1 else f"[{k[0].upper()}{k[0]}]{k[1:]}" for k in keys)

    safe = alt(k for k in SPOKEN_ABBREVIATION if k not in WORD_COLLISIONS)
    risky = alt(k for k in SPOKEN_ABBREVIATION if k in WORD_COLLISIONS)
    return re.compile(rf"\b(?:(?:{safe})\.|(?:{risky})\.(?!\s*$))")


#: A tag plus its dot. Multi-letter tags also match capitalised ("Esp.");
#: single-letter "v."/"n." stay lowercase-only so "N. America" survives.
ABBREVIATION = _abbreviation_regex()

#: Dotless tags: "esp" sometimes drops its dot; "BrE" never has one.
BARE_TAGS = [
    (re.compile(r"\b[Ee]sp\b"), "especially"),
    (re.compile(r"\bBrE\b"), "British English"),
]


def expand_abbreviations(text: str) -> str:
    out = ABBREVIATION.sub(lambda m: SPOKEN_ABBREVIATION[m.group()[:-1].lower()], text)
    for regex, word in BARE_TAGS:
        out = regex.sub(word, out)
    return out


# ---------------------------------------------------------------------------
# Rendering

EXTRA_SPACE = re.compile(r" {2,}")
SPACE_BEFORE_PUNCTUATION = re.compile(r" +([.,!?;:])")


def _blank_emphasised(marked: str) -> List[Segment]:
    """Splits a rendered line at its recording positions."""
    out: List[Segment] = []
    spoke = False  # a clip of this line has played: the output is warm
    i = 0
    while i < len(marked):
        opening = marked.find(MARK_OPEN, i)
        text = marked[i : (len(marked) if opening < 0 else opening)].strip()
        if text:
            out.append(Speech(text, lead_in=not spoke))
            spoke = True
        if opening < 0:
            break
        close = marked.find(MARK_CLOSE, opening)
        out.append(BLANK)
        spoke = True
        i = close + len(MARK_CLOSE)
    return out


def _render(text: str, production: bool, headword: Optional[str] = None) -> List[Segment]:
    """``headword`` is the studied word when the card is KNOWN to be
    recognition: blanks are then filled from it. None means production, or an
    unknown direction whose blanks are guessed from the visible words."""
    lines = [l.strip() for l in _lines(text)]
    lines = [l for l in lines if l]
    plain = [l for l in lines if BULLET not in l]

    # Words that might be the hidden word, visible on this side.
    candidates = _distinct(w for l in plain for w in CANDIDATE_WORD.findall(l))

    # Headword candidates: words from short fields only (the word / phrase
    # field, never a long definition sentence).
    lemmas = _distinct(
        w
        for l in plain
        if 1 <= len(CANDIDATE_WORD.findall(l)) <= HEADWORD_MAX_WORDS
        for w in CANDIDATE_WORD.findall(l)
    )

    out: List[Segment] = []
    examples: List[Segment] = []
    hint_field_phrase: Optional[str] = None
    # Unrestored blanks of the most recent blanked line (the note's hint field
    # is the last such line and has exactly one blank per word to produce).
    last_blanked_line: List[str] = []

    for line in lines:
        tokens = list(BLANK_TOKEN.finditer(line))
        if not tokens:
            out.append(Speech(expand_abbreviations(line)))
            out.append(Pause(LINE_PAUSE_MS))
            continue

        without_tokens = BLANK_TOKEN.sub("", line)
        if not any(c.isalnum() for c in without_tokens):
            # Dedicated hint field ("wh•••••" or "c•t c•••••s"): production
            # keeps the letter hint; recognition stays silent.
            if production and hint_field_phrase is None:
                hint_field_phrase = ", then a ".join(_hint_phrase(t.group()) for t in tokens)
            continue

        token_values = [t.group() for t in tokens]
        if production:
            restored: List[Optional[str]] = [None] * len(tokens)
        elif headword is not None:
            restored = list(_fill_from_headword(tokens, line, headword))
        else:
            restored = _restore_inline(token_values, candidates, lemmas, line)

        line_blanks: List[str] = []
        counter = iter(range(len(tokens)))

        def replace(_: "re.Match[str]") -> str:
            idx = next(counter)
            fill = restored[idx]
            if fill is not None:
                return _keep_punctuation(token_values[idx], fill)
            line_blanks.append(token_values[idx])
            return MARK_OPEN + CODEWORD + MARK_CLOSE

        rendered = BLANK_TOKEN.sub(replace, line)
        rendered = SPACE_BEFORE_PUNCTUATION.sub(r"\1", EXTRA_SPACE.sub(" ", rendered))
        expanded = expand_abbreviations(rendered)
        destination = examples if production else out
        if line_blanks:
            destination.extend(_blank_emphasised(expanded))
            last_blanked_line = line_blanks
        else:
            destination.append(Speech(expanded))
        destination.append(Pause(LINE_PAUSE_MS))

    # Production: definition, letter hint, then examples.
    hint = hint_field_phrase
    if hint is None and last_blanked_line:
        hint = ", then a ".join(_hint_phrase(t) for t in last_blanked_line)
    if hint is not None:
        out.append(Speech(hint))
        out.append(Pause(LINE_PAUSE_MS))
    out.extend(examples)
    return out


def _keep_punctuation(token: str, fill: str) -> str:
    """Puts ``fill`` in place of the token's bullets, keeping the punctuation
    around them ("w••••?" → "weave?")."""

    def outer(c: str) -> bool:
        return not c.isalnum() and c != BULLET

    if all(outer(c) for c in token):
        return fill
    return _take_while(token, outer) + fill + _take_last_while(token, outer)


# ---------------------------------------------------------------------------
# Recognition with a known headword

#: One word of the headword; hyphens, apostrophes and a slash between digits
#: stay inside it ("either-or", "fool's", "20/20").
HEADWORD_UNIT = re.compile(r"[^\W_]+(?:(?:['’-]|(?<=\d)/(?=\d))[^\W_]+)*")

#: The same, split at hyphens ("an ••• of •••• experience" for "out-of-body").
HEADWORD_PART = re.compile(r"[^\W_]+(?:['’][^\W_]+)*")


def _headword_units(headword: str, unit: "re.Pattern[str]") -> List[str]:
    """The headword's words, minus dictionary tags ("prep. from" → "from")."""
    out = []
    for m in unit.finditer(headword):
        tag = m.group().lower() in SPOKEN_ABBREVIATION
        dotted = m.end() < len(headword) and headword[m.end()] == "."
        if not (tag and dotted):
            out.append(m.group())
    return out


@dataclass(frozen=True)
class _Fill:
    text: str
    score: int


# Alignment scores: how well a blank fits a headword word …
FIT_EXACT = 8  # visible letters agree ("st••••" stance)
FIT_INFLECTED = 7  # … in an inflected form ("h•••d" honed)
FIT_LENGTH = 6  # no letters shown, same length ("•••" cot)
FIT_LOOSE = 2  # bullets miscounted ("b•••••thed" betrothed)
FIT_WEAK = 0  # only the first letter agrees ("b•••s" for "burning")
# … how well a visible sentence word matches one …
SAME_WORD = 4  # "on" / "on", "SIGNED" / "sign"
SIMILAR_WORD = 2  # an irregular form one letter off ("came" / "come")
# … and what the alternatives cost.
UNRESOLVED = -6  # no headword word fits the blank
SKIP_MISSING = -4  # a headword word neither masked nor in the sentence
REUSE = -2  # the same word masked twice ("a w••••? … a w••••.")


def _fit(token: str, unit: str) -> Optional[_Fill]:
    """How ``token`` could be filled from the headword word ``unit``, or None
    if its visible letters rule the word out."""
    b = _parse_blank(token)
    if not b.knowns:
        diff = abs(len(unit) - b.length)
        if diff == 0:
            return _Fill(unit, FIT_LENGTH)
        if diff <= 1:
            return _Fill(unit, FIT_LOOSE)
        return None
    if b.matches(unit):
        return _Fill(unit, FIT_EXACT)
    # An inflected spelling only when the blank shows the ending: "h•••d" is
    # "honed", but "b••••••" (brought) must not become an invented "bringed".
    forms = [unit] if not b.suffix else [unit] + _inflected_forms(unit)
    for form in forms:
        if b.matches(form):
            return _Fill(form, FIT_INFLECTED)
    # Visible stem/ending agree but the bullet count is off.
    loose_forms = [
        f
        for f in forms
        if _starts_ci(f, b.prefix) and _ends_ci(f, b.suffix) and len(f) >= len(b.prefix) + len(b.suffix)
    ]
    if loose_forms:
        loose = min(loose_forms, key=lambda f: abs(len(f) - b.length))
        if abs(len(loose) - b.length) <= 1 or len(b.prefix) >= 3:
            return _Fill(loose, FIT_LOOSE)
    # Last resort: the shown first letters are the word's, the form isn't.
    if b.prefix and _starts_ci(unit, b.prefix):
        return _Fill(unit, FIT_WEAK)
    return None


def _word_match(seen: str, unit: str) -> int:
    """How strongly the visible sentence word ``seen`` is the headword word ``unit``."""
    v = seen.lower()
    u = unit.lower()
    if v == u or any(f.lower() == v for f in _inflected_forms(u)):
        return SAME_WORD
    max_edits = 1 if len(u) <= 4 else 2
    if len(v) >= 2 and v[0] == u[0] and _edit_distance(v, u) <= max_edits:
        return SIMILAR_WORD
    return 0


def _edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (0 if a[i - 1] == b[j - 1] else 1))
        prev = cur
    return prev[len(b)]


def _fill_from_headword(tokens: List["re.Match[str]"], line: str, headword: str) -> List[str]:
    """Recognition side with the studied headword known: fills every blank.

    The headword's words are aligned, in order, against the whole sentence (its
    visible words as well as its blanks) and the best-scoring reading wins. A
    blank no headword word fits is replaced, together with the blanks right
    next to it, by the whole headword.
    """
    # Compounds split ("••• of ••••" out of body) or whole ("e•••••-••"
    # either-or): whichever reads the sentence better (split on a tie).
    results = [_align_to_sentence(tokens, line, headword, unit) for unit in (HEADWORD_PART, HEADWORD_UNIT)]
    fills = max(results, key=lambda r: r[0])[1]

    whole = next(l for l in _lines(headword) if l.strip()).strip()

    def adjacent(i: int) -> bool:
        if i <= 0:
            return False
        between = line[tokens[i - 1].end() : tokens[i].start()]
        return not any(c.isalnum() for c in between)

    # Runs of adjacent blanks: an unfilled blank next to a filled one is
    # dropped; a run with nothing filled says the whole headword once, unless
    # it is a single blank that is mostly visible letters (a bullet typed as a
    # dash: "25•minute"), which is read as written.
    out: List[Optional[str]] = list(fills)
    start = 0
    while start < len(tokens):
        end = start + 1
        while end < len(tokens) and adjacent(end):
            end += 1
        any_filled = any(fills[k] is not None for k in range(start, end))
        for k in range(start, end):
            if out[k] is not None:
                continue
            core = _parse_blank(tokens[k].group())
            if any_filled or k > start:
                out[k] = ""
            elif end - start == 1 and len(core.knowns) * 2 > core.length:
                out[k] = _trim(tokens[k].group().replace(BULLET, " "), lambda c: not c.isalnum())
            else:
                out[k] = whole
        start = end
    return [o if o is not None else "" for o in out]


def _align_to_sentence(
    tokens: List["re.Match[str]"],
    line: str,
    headword: str,
    unit: "re.Pattern[str]",
) -> Tuple[int, List[Optional[str]]]:
    """Aligns the headword's words with ``line``: the total score and, per
    blank, its fill (None: no headword word fits)."""
    units = _headword_units(headword, unit)

    # The sentence in order: visible words (blank = -1) and blanks (their index).
    items: List[Tuple[str, int]] = []
    pos = 0
    for t, m in enumerate(tokens):
        items.extend((w.group(), -1) for w in unit.finditer(line[pos : m.start()]))
        items.append((m.group(), t))
        pos = m.end()
    items.extend((w.group(), -1) for w in unit.finditer(line[pos:]))

    fits = [[_fit(t.group(), u) for u in units] for t in tokens]
    same = [[_word_match(text, u) if t < 0 else 0 for u in units] for text, t in items]
    skip_cost = [0 if u.lower() in PHRASE_PLACEHOLDER else SKIP_MISSING for u in units]

    memo: dict = {}

    def best(s: int, h: int) -> Tuple[int, List[Optional[str]]]:
        if s == len(items):
            return sum(skip_cost[h:]), []
        key = (s, h)
        if key in memo:
            return memo[key]
        t = items[s][1]
        top: List[Optional[Tuple[int, List[Optional[str]]]]] = [None]

        def offer(score: int, fill: Optional[str], s2: int, h2: int) -> None:
            rest, rest_fills = best(s2, h2)
            total = score + rest
            if top[0] is None or total > top[0][0]:
                chosen = [fill] + rest_fills if (s2 > s and t >= 0) else rest_fills
                top[0] = (total, chosen)

        if t >= 0:
            if h < len(units) and fits[t][h] is not None:
                f = fits[t][h]
                offer(f.score, f.text, s + 1, h + 1)
            earlier = [fits[t][k] for k in range(h) if fits[t][k] is not None]
            if earlier:
                f = max(earlier, key=lambda x: x.score)
                offer(f.score + REUSE, f.text, s + 1, h)
            offer(UNRESOLVED, None, s + 1, h)
        else:
            if h < len(units) and same[s][h] > 0:
                offer(same[s][h], None, s + 1, h + 1)
            offer(0, None, s + 1, h)  # a sentence word that isn't in the headword
        if h < len(units):
            offer(skip_cost[h], None, s, h + 1)
        memo[key] = top[0]
        return top[0]  # type: ignore[return-value]

    return best(0, 0)


#: Dictionary lemma markers that head a phrase but never appear literally in
#: its example sentence ("be sb out of sth").
PHRASE_PLACEHOLDER = {
    "be", "sb", "sth", "one", "ones", "one's", "oneself", "your", "yours", "yourself",
    "somebody", "somebody's", "someone", "someone's", "something", "somewhere",
}


# ---------------------------------------------------------------------------
# Unknown direction: restore from visible words


def _restore_inline(
    tokens: List[str],
    candidates: List[str],
    lemmas: List[str],
    line: str,
) -> List[Optional[str]]:
    """Restores every blank token in one example line from the visible words,
    aligning blanks in order to the candidate phrase words only with hard
    evidence that this is the recognition side."""
    per_token = [_restore(t, candidates, lemmas) for t in tokens]
    if not candidates or len(tokens) > len(candidates):
        return per_token
    if all(r is not None for r in per_token):
        return per_token

    # Exact length first; if that fails, allow a 1-off slip.
    assigned = _align_to_phrase(tokens, candidates, 0)
    if assigned is None:
        assigned = _align_to_phrase(tokens, candidates, 1)
    if assigned is None:
        return per_token
    slot = []
    for i, t in enumerate(tokens):
        one = [candidates[assigned[i]]]
        slot.append(_restore(t, one, one))

    if not all(per_token[i] is None or per_token[i] == slot[i] for i in range(len(tokens))):
        return per_token

    visible = _visible_words(line)
    masked = set(assigned)
    gaps_present = all(
        j in masked or candidates[j].lower() in visible or candidates[j].lower() in PHRASE_PLACEHOLDER
        for j in range(len(candidates))
    )
    if not gaps_present:
        return per_token

    has_strict_anchor = any(s is not None for s in slot)
    has_phrase_anchor = any(j not in masked and candidates[j].lower() in visible for j in range(len(candidates)))
    if not has_strict_anchor and not has_phrase_anchor:
        return per_token

    return [r if r is not None else candidates[assigned[i]] for i, r in enumerate(per_token)]


def _align_to_phrase(tokens: List[str], candidates: List[str], len_tolerance: int) -> Optional[List[int]]:
    """Greedily maps each blank, left to right, to the next candidate word it
    could be; None if any blank has no fitting word."""
    out = []
    nxt = 0
    for t in tokens:
        j = nxt
        while j < len(candidates) and not _blank_fits_word(t, candidates[j], len_tolerance):
            j += 1
        if j >= len(candidates):
            return None
        out.append(j)
        nxt = j + 1
    return out


def _visible_words(line: str) -> set:
    return {w.lower() for w in CANDIDATE_WORD.findall(BLANK_TOKEN.sub(" ", line))}


def _blank_fits_word(token: str, word: str, len_tolerance: int = 0) -> bool:
    b = _parse_blank(token)
    if not b.knowns:
        return abs(len(word) - b.length) <= len_tolerance
    if b.matches(word) or b.matches_stem(word):
        return True
    if b.prefix and not _starts_ci(word, b.prefix):
        return False
    if b.suffix and not _ends_ci(word, b.suffix):
        return False
    return len(word) >= len(b.prefix) + len(b.suffix)


class _BlankShape:
    """One blank decomposed by position. Each bullet hides exactly one
    character, so every visible letter sits at a known index
    ("f••b••••••e" → forbearance)."""

    def __init__(self, core: str) -> None:
        def letter_or_digit(c: str) -> bool:
            return c.isalnum()

        def not_bullet(c: str) -> bool:
            return c != BULLET

        self.prefix = "".join(c for c in _take_while(core, not_bullet) if letter_or_digit(c))
        self.suffix = "".join(c for c in _take_last_while(core, not_bullet) if letter_or_digit(c))
        self.knowns = [(i, c) for i, c in enumerate(core) if c != BULLET]
        self.length = len(core)

    def matches(self, word: str) -> bool:
        """Same length, every visible letter in its place."""
        return len(word) == self.length and all(word[i].lower() == c.lower() for i, c in self.knowns)

    def matches_stem(self, word: str) -> bool:
        """``word`` is the stem and ``suffix`` an inflectional ending."""
        stem_len = self.length - len(self.suffix)
        return len(word) == stem_len and all(i >= stem_len or word[i].lower() == c.lower() for i, c in self.knowns)


def _parse_blank(token: str) -> _BlankShape:
    return _BlankShape(_trim(token, lambda c: not c.isalnum() and c != BULLET))


def _restore(token: str, candidates: List[str], lemmas: List[str]) -> Optional[str]:
    """Tries to put the hidden word back using words visible on the same side."""
    b = _parse_blank(token)
    if not b.knowns:
        return None  # nothing but bullets: unrecoverable

    # (1) An unambiguous whole-word match.
    fits = [c for c in candidates if b.matches(c)]
    if fits and len(_distinct_ci(fits)) == 1:
        return fits[0]

    # (2) The example conjugates the headword: inflect the lemma fields only.
    forms = [f for lemma in lemmas for f in _inflected_forms(lemma)]
    exact = _distinct_ci(f for f in forms if b.matches(f))
    if len(exact) == 1:
        return exact[0]
    # Miscounted bullets: shown stem AND ending still pin one inflected form.
    if b.prefix and b.suffix:
        loose = _distinct_ci(
            f
            for f in forms
            if _starts_ci(f, b.prefix) and _ends_ci(f, b.suffix) and abs(len(f) - b.length) <= 1
        )
        if len(loose) == 1:
            return loose[0]

    # (3) Shown stem and ending around a hidden middle, among the lemmas.
    if len(b.prefix) >= 2:
        fuzzy = [
            l
            for l in lemmas
            if _starts_ci(l, b.prefix) and _ends_ci(l, b.suffix) and abs(len(l) - b.length) <= 1
        ]
        if len(_distinct_ci(fuzzy)) == 1:
            return fuzzy[0]
    return None


#: Inflectional endings tried on a headword to match an example's conjugation.
INFLECTION_ENDINGS = ["s", "es", "d", "ed", "ing", "er", "est", "ly"]


def _inflected_forms(lemma: str) -> List[str]:
    """Every regular inflected spelling of ``lemma`` (over-generates on purpose;
    the caller keeps only a form that exactly fits the blank)."""
    lower = lemma.lower()
    drop_e = lemma[:-1] if lower.endswith("e") else None
    y_stem = lemma[:-1] + "i" if len(lower) >= 2 and lower.endswith("y") else None
    consonant_y = y_stem is not None and lower[-2] not in "aeiou"
    # A final hard "c" takes a "k": frolic → frolicked, panic → panicked.
    ck_stem = lemma + "k" if lower.endswith("c") else None
    doubled = lemma + lemma[-1] if ck_stem is None and _ends_cvc(lower) else None
    forms: List[str] = []
    for e in INFLECTION_ENDINGS:
        if y_stem is not None and ((e != "s" and e != "ing") if consonant_y else e == "d"):
            forms.append(y_stem + e)
        forms.append(lemma + e)
        # The stem changes only before a vowel.
        if e[0] in "aeiou":
            if drop_e is not None:
                forms.append(drop_e + e)
            if doubled is not None:
                forms.append(doubled + e)
            if ck_stem is not None:
                forms.append(ck_stem + e)
    return _distinct(forms)


def _ends_cvc(word: str) -> bool:
    """"snap", "run", "acquit": the shape whose final consonant doubles."""
    if len(word) < 3:
        return False
    w = word.lower()

    def vowel(i: int) -> bool:
        return w[i] in "aeiou" and not (w[i] == "u" and i > 0 and w[i - 1] == "q")

    n = len(w)
    return w[n - 1].isalpha() and w[n - 1] not in "wxy" and not vowel(n - 1) and vowel(n - 2) and not vowel(n - 3)


def _hint_phrase(token: str) -> str:
    """"m••e" → "4 letter word starting with M and ending with E"."""
    b = _parse_blank(token)
    s = f"{b.length} letter word"
    if b.prefix:
        s += " starting with " + _spell(b.prefix)
    if b.suffix:
        s += (" and ending with " if b.prefix else " ending with ") + _spell(b.suffix)
    return s


def _spell(s: str) -> str:
    """"wh" → "W H" so the voice says the letter names."""
    return " ".join(s.upper())
