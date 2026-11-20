"""Anki glue for AnkiAudio Read Aloud: reviewer hooks, shortcuts, buttons, menu."""

from __future__ import annotations

import json
import logging
import os
import traceback
from logging.handlers import RotatingFileHandler
from typing import Callable, Dict, List, Optional, Tuple

from aqt import gui_hooks, mw
from aqt.qt import QAction, QActionGroup, QMenu, QTimer
from aqt.reviewer import Reviewer, ReviewerBottomBar
from aqt.utils import tooltip

from . import audio_script, card_text
from .audio_script import Segment
from .speaker import Speaker

ADDON_DIR = os.path.dirname(os.path.abspath(__file__))
ADDON_PACKAGE = __name__.split(".")[0]
ADDON_FOLDER = mw.addonManager.addonFromModule(ADDON_PACKAGE)
WEB_ROOT = f"/_addons/{ADDON_FOLDER}/web"

DEFAULTS: Dict = {
    "enabled": True,
    "voice": "en-US-AndrewMultilingualNeural",
    "speed": "+0%",
    "line_pause_ms": 700,
    "lead_in_ms": 150,
    "volume": 1.0,
    "silence_card_audio": True,
    "prefetch_next_cards": 2,
}

VOICES: List[Tuple[str, str]] = [
    ("en-US-AndrewMultilingualNeural", "Andrew (US, multilingual)"),
    ("en-US-AvaMultilingualNeural", "Ava (US, multilingual)"),
    ("en-US-BrianMultilingualNeural", "Brian (US, multilingual)"),
    ("en-US-EmmaMultilingualNeural", "Emma (US, multilingual)"),
    ("en-US-GuyNeural", "Guy (US)"),
    ("en-US-JennyNeural", "Jenny (US)"),
    ("en-GB-RyanNeural", "Ryan (UK)"),
    ("en-GB-SoniaNeural", "Sonia (UK)"),
    ("en-AU-NatashaNeural", "Natasha (Australia)"),
    ("en-IN-PrabhatNeural", "Prabhat (India)"),
]

SPEEDS: List[Tuple[str, str]] = [
    ("-20%", "Slow (-20%)"),
    ("-10%", "A little slower (-10%)"),
    ("+0%", "Normal"),
    ("+10%", "A little faster (+10%)"),
    ("+20%", "Fast (+20%)"),
    ("+35%", "Very fast (+35%)"),
]


def config() -> Dict:
    return {**DEFAULTS, **(mw.addonManager.getConfig(ADDON_PACKAGE) or {})}


def update_config(**changes) -> None:
    cfg = config()
    cfg.update(changes)
    mw.addonManager.writeConfig(ADDON_PACKAGE, cfg)


class ReadAloud:
    def __init__(self) -> None:
        self.speaker: Optional[Speaker] = None
        self.card_id: Optional[int] = None
        self.scripts: Tuple[List[Segment], List[Segment]] = ([], [])

    # --------------------------------------------------------------- setup

    def get_speaker(self) -> Speaker:
        if self.speaker is None:
            self.speaker = Speaker(
                cache_dir=os.path.join(ADDON_DIR, "user_files", "cache"),
                blank_pcm_path=os.path.join(ADDON_DIR, "blank_mhm.pcm"),
                settings=config,
                on_speaking=self.show_speaking,
                on_notice=lambda msg: tooltip(msg, period=4000),
            )
        return self.speaker

    def shutdown(self) -> None:
        if self.speaker is not None:
            self.speaker.shutdown()
            self.speaker = None

    # --------------------------------------------------------------- scripts

    def scripts_for(self, card) -> Tuple[List[Segment], List[Segment]]:
        note = card.note()
        first_field = note.fields[0] if note.fields else ""
        question, answer, word = card_text.card_texts(card.question(), card.answer(), first_field)
        audio_script.LINE_PAUSE_MS = int(config()["line_pause_ms"])
        return (
            audio_script.for_question(question, word),
            audio_script.for_answer(answer, question, word),
        )

    def load(self, card, fresh: bool = False) -> None:
        """Scripts for ``card``. Hooks pass ``fresh``: Anki redraws a side
        after an edit, and the new text must be read, not the cached one."""
        if fresh or card.id != self.card_id or not any(self.scripts):
            self.card_id = card.id
            self.scripts = self.scripts_for(card)

    # --------------------------------------------------------------- hooks

    def on_question(self, card) -> None:
        try:
            self.load(card, fresh=True)
        except Exception:
            traceback.print_exc()
            return
        if not config()["enabled"]:
            return
        speaker = self.get_speaker()
        speaker.speak(self.scripts[0])
        speaker.prefetch(self.scripts[1])
        QTimer.singleShot(500, self.prefetch_upcoming)

    def on_answer(self, card) -> None:
        try:
            self.load(card, fresh=True)
        except Exception:
            traceback.print_exc()
            return
        if config()["enabled"]:
            self.get_speaker().speak(self.scripts[1])

    def prefetch_upcoming(self) -> None:
        """Synthesizes the next cards' audio so they start without delay."""
        limit = int(config()["prefetch_next_cards"])
        if limit <= 0 or mw.col is None or mw.state != "review":
            return
        try:
            queued = mw.col.sched.get_queued_cards(fetch_limit=limit + 1)
            for entry in queued.cards:
                if entry.card.id == self.card_id:
                    continue
                q, a = self.scripts_for(mw.col.get_card(entry.card.id))
                self.get_speaker().prefetch(q)
                self.get_speaker().prefetch(a)
        except Exception:
            traceback.print_exc()

    def silence_card_audio(self, card, tags: list) -> None:
        cfg = config()
        if cfg["enabled"] and cfg["silence_card_audio"]:
            tags.clear()

    def on_state_change(self, new_state: str, old_state: str) -> None:
        if new_state != "review":
            self.stop()

    # --------------------------------------------------------------- actions

    def replay(self) -> None:
        """P: read the current side again (works even with reading turned off)."""
        if mw.state != "review" or mw.reviewer.card is None:
            return
        card = mw.reviewer.card
        try:
            self.load(card)
        except Exception:
            traceback.print_exc()
            return
        side = 1 if mw.reviewer.state == "answer" else 0
        self.get_speaker().speak(self.scripts[side])

    def undo(self) -> None:
        self.stop()
        mw.undo()

    def stop(self) -> None:
        if self.speaker is not None:
            self.speaker.stop()

    def show_speaking(self, on: bool) -> None:
        if mw.state != "review":
            return
        web = getattr(getattr(mw.reviewer, "bottom", None), "web", None)
        if web is not None:
            web.eval(f"window.ankiAudioSpeaking && ankiAudioSpeaking({json.dumps(on)});")

    # --------------------------------------------------------------- reviewer UI

    def add_shortcuts(self, state: str, shortcuts: list) -> None:
        if state != "review":
            return
        for i, (key, fn) in enumerate(shortcuts):
            if key == "e":
                shortcuts[i] = (key, self.before(fn))
        shortcuts.append(("p", self.replay))
        shortcuts.append(("Backspace", self.undo))
        # "u" is already Anki's own undo.

    def before(self, fn: Callable[[], None]) -> Callable[[], None]:
        def wrapped() -> None:
            self.stop()
            fn()

        return wrapped

    def on_js_message(self, handled: Tuple[bool, object], message: str, context) -> Tuple[bool, object]:
        if not isinstance(context, (ReviewerBottomBar, Reviewer)):
            return handled
        if message == "ankiaudio:play":
            self.replay()
            mw.web.setFocus()
            return True, None
        if message == "ankiaudio:undo":
            self.undo()
            mw.web.setFocus()
            return True, None
        if message == "ankiaudio:relayout":
            mw.bottomWeb.adjustHeightToFit()  # Play/Undo wrapped to a second row
            return True, None
        if message == "edit":
            self.stop()  # observe only: Anki still opens the editor
        return handled

    def inject_bottom_bar(self, web_content, context) -> None:
        if not isinstance(context, ReviewerBottomBar):
            return
        keys = {str(i): (mw.pm.get_answer_key(i) or str(i)) for i in (1, 2, 3, 4)}
        web_content.head += f"<script>window.ankiAudioKeys = {json.dumps(keys)};</script>"
        web_content.css.append(f"{WEB_ROOT}/bottom.css")
        web_content.js.append(f"{WEB_ROOT}/bottom.js")

    # --------------------------------------------------------------- menu

    def build_menu(self) -> None:
        menu = QMenu("Read Aloud", mw)
        mw.form.menuTools.addMenu(menu)

        toggle = QAction("Read cards aloud", menu, checkable=True)
        toggle.setChecked(bool(config()["enabled"]))
        toggle.toggled.connect(self.set_enabled)
        menu.addAction(toggle)
        self.toggle_action = toggle

        self.radio_menu(menu, "Voice", VOICES, "voice")
        self.radio_menu(menu, "Speed", SPEEDS, "speed")

        menu.addSeparator()
        clear = QAction("Clear downloaded audio", menu)
        clear.triggered.connect(self.clear_cache)
        menu.addAction(clear)

    def radio_menu(self, parent: QMenu, title: str, options: List[Tuple[str, str]], key: str) -> None:
        sub = parent.addMenu(title)
        group = QActionGroup(sub)
        current = config()[key]
        for value, label in options:
            action = QAction(label, sub, checkable=True)
            action.setChecked(value == current)
            action.triggered.connect(lambda _checked=False, v=value: self.choose(key, v))
            group.addAction(action)
            sub.addAction(action)

    def choose(self, key: str, value: str) -> None:
        update_config(**{key: value})
        self.replay()

    def set_enabled(self, on: bool) -> None:
        update_config(enabled=on)
        if on:
            self.replay()
        else:
            self.stop()
        tooltip("Reading cards aloud" if on else "Read aloud is off (P still reads the card)")

    def clear_cache(self) -> None:
        self.get_speaker().clear_cache()
        tooltip("Downloaded audio cleared")


read_aloud = ReadAloud()


def setup_log() -> None:
    """Edge refusals and fallbacks go to user_files/ankiaudio.log."""
    folder = os.path.join(ADDON_DIR, "user_files")
    os.makedirs(folder, exist_ok=True)
    handler = RotatingFileHandler(
        os.path.join(folder, "ankiaudio.log"), maxBytes=256_000, backupCount=1, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger = logging.getLogger("ankiaudio")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False


def setup() -> None:
    setup_log()
    mw.addonManager.setWebExports(ADDON_PACKAGE, r"web/.*\.(js|css)")
    read_aloud.build_menu()

    gui_hooks.reviewer_did_show_question.append(read_aloud.on_question)
    gui_hooks.reviewer_did_show_answer.append(read_aloud.on_answer)
    gui_hooks.reviewer_will_play_question_sounds.append(read_aloud.silence_card_audio)
    gui_hooks.reviewer_will_play_answer_sounds.append(read_aloud.silence_card_audio)
    gui_hooks.reviewer_will_end.append(read_aloud.stop)
    gui_hooks.state_did_change.append(read_aloud.on_state_change)
    gui_hooks.state_shortcuts_will_change.append(read_aloud.add_shortcuts)
    gui_hooks.webview_did_receive_js_message.append(read_aloud.on_js_message)
    gui_hooks.webview_will_set_content.append(read_aloud.inject_bottom_bar)
    gui_hooks.profile_will_close.append(read_aloud.shutdown)
