"""Rendered card HTML → the plain lines the audio script reads.

Mirrors what the Android app gets from AnkiDroid (``HtmlCompat.fromHtml`` on
the rendered question/answer): line breaks at <br> and block elements, tags
dropped, entities decoded, and the answer cut to what follows <hr id=answer>.
"""

from __future__ import annotations

import html
import re
from typing import Tuple

_DROP_BLOCKS = re.compile(r"(?is)<(style|script|head|title)\b.*?</\1\s*>")
_COMMENTS = re.compile(r"(?s)<!--.*?-->")
_AV_TAGS = re.compile(r"\[sound:[^\]]*\]|\[anki:play:[qa]:\d+\]|\[\[type:[^\]]*\]\]|\[/?anki:tts[^\]]*\]")
_HTML_SPACE = re.compile(r"[ \t\r\n\f]+")
_LINE_BREAK = re.compile(r"(?i)<br\b[^>]*>")
_BLOCK = re.compile(
    r"(?i)</?(?:div|p|li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|tfoot|caption|h[1-6]|hr|"
    r"blockquote|pre|section|article|header|footer|aside|nav|main|center|figure|figcaption|details|summary)\b[^>]*>"
)
_TAG = re.compile(r"<[^>]*>")
_ANSWER_DIVIDER = re.compile(r"""(?i)<hr[^>]*id=["']?answer["']?[^>]*>""")


def html_to_text(fragment: str) -> str:
    s = _DROP_BLOCKS.sub("", fragment)
    s = _COMMENTS.sub("", s)
    s = _AV_TAGS.sub("", s)
    s = _HTML_SPACE.sub(" ", s)  # source whitespace is not layout (keeps &nbsp;)
    s = _LINE_BREAK.sub("\n", s)
    s = _BLOCK.sub("\n", s)
    s = _TAG.sub("", s)
    s = html.unescape(s)
    lines = [line.strip(" ") for line in s.split("\n")]
    return "\n".join(lines).strip("\n")


def answer_side_only(answer_html: str) -> str:
    """The rendered answer normally repeats the question above <hr id=answer>."""
    m = _ANSWER_DIVIDER.search(answer_html)
    return answer_html[m.end() :] if m else answer_html


def card_texts(question_html: str, answer_html: str, first_field_html: str) -> Tuple[str, str, str]:
    """(question, answer side only, studied word) as plain text."""
    return (
        html_to_text(question_html),
        html_to_text(answer_side_only(answer_html)),
        html_to_text(first_field_html),
    )
