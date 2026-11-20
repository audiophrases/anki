# AnkiAudio Read Aloud — desktop

The desktop version of AnkiAudio: an add-on for **Anki desktop** (Windows, also
macOS/Linux) that reads every card aloud while you review.

Anki itself still does the scheduling, AnkiWeb sync, card display, editing and
undo. The add-on contributes the voice. It uses the same Edge neural voice
("Andrew") and the same spoken rendering as the phone app:

- **Recognition** cards (the word is shown): blanks in the example are filled
  back in, so "She wh•••••d him…" is read as "She wheedled him…".
- **Production** cards (the word is hidden): definition, then the letter hint
  ("8 letter word starting with W H and ending with D"), then the example with
  the recorded "mhm" in place of each hidden word.
- The answer side reads only what's new (for production, just the word).
- Dictionary tags are read as words ("v." → "verb", "esp" → "especially").

## Keys

Every button in the review bar shows its key.

| Key | Button | Does |
| --- | --- | --- |
| `1` | Again | grade Again |
| `2` | Hard | grade Hard |
| `3` | Good | grade Good |
| `4` | Easy | grade Easy |
| `Space` / `Enter` | Show Answer, then Good | reveal the answer; on the answer side, grade Good |
| `E` | Edit | edit the note (reading stops) |
| `P` | Play | read the current side again |
| `U` / `Backspace` | Undo | undo the last grade; the previous card is read again |

`R` still replays the card's own `[sound:…]` files, as in plain Anki.

## Install

**On this PC (development):** the add-on folder is linked into Anki, so a
`git pull` takes effect the next time Anki starts:

```powershell
New-Item -ItemType Junction -Path "$env:APPDATA\Anki2\addons21\ankiaudio" -Target "C:\Users\Admin\anki\desktop\ankiaudio"
```

**On any other computer:** run `python desktop/build_ankiaddon.py`, then
double-click `dist/ankiaudio-read-aloud.ankiaddon`, or install it from Anki
with **Tools → Add-ons → Install from file…**. Restart Anki.

To remove the add-on, delete it in **Tools → Add-ons** (for the dev link,
deleting the `addons21\ankiaudio` junction leaves the repo untouched).

## Settings

**Tools → Read Aloud** has an on/off switch, the voice, the speed and
**Clear downloaded audio**. With reading off, `P` still reads on demand.
**Tools → Add-ons**, select the add-on, **Config**, also has the line pause, lead-in,
volume, whether to mute the card's own audio, and how many upcoming cards to
download ahead (see `ankiaudio/config.md`).

## How it works

| File | Role |
| --- | --- |
| `ankiaudio/audio_script.py` | Card text → spoken script. **Python port of `AudioScript.kt`; keep the two in step.** |
| `ankiaudio/card_text.py` | Rendered card HTML → plain lines (as AnkiDroid's API hands the phone app). |
| `ankiaudio/tts.py` | Edge Read Aloud client (stdlib websocket), MP3 cache, offline Windows-voice fallback. |
| `ankiaudio/edge_token.py` | Edge's request token (port of `EdgeToken.kt`, LGPL-3.0, see `THIRD_PARTY_NOTICES.md`). |
| `ankiaudio/speaker.py` | Decodes clips with Qt, trims outer silence, joins lines and blanks into one buffer, plays it. |
| `ankiaudio/addon.py` | Anki hooks, extra keys, review-bar buttons, Tools menu. |
| `ankiaudio/web/` | Review-bar labels and the Play/Undo buttons. |

Edge voices are fetched once per sentence and cached in
`ankiaudio/user_files/cache` (trimmed to ~200 MB). The next couple of cards are
fetched ahead, so cards usually start without delay. Edge connections are kept
open and reused, because Edge refuses bursts of new connections (HTTP 429). If
Edge can't be reached, a local Windows voice reads instead, with a one-time
notice, and Edge is retried shortly after.

## Tests

```powershell
python -m unittest discover -s desktop/tests
```

`test_audio_script.py` mirrors the Android `AudioScriptTest.kt`, and
`test_edge_token.py` checks the token against upstream reference vectors.
