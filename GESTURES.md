# Anki Audio — controls cheat sheet

## 🔊 Eyes-free session (screen off, phone in pocket)

Start: app → pick deck → **▶ Start eyes-free session** → set volume → lock screen.

| Key | During question | After reveal |
| --- | --- | --- |
| **Volume UP** | Reveal answer | **Good** |
| **Volume DOWN** | Replay question | **Again** |

Wrong press? **Wake the phone** → notification → **Undo**.
Stop: notification → **Stop**. Volume keys can't change loudness during a
session — set it before starting.

## 🖐 Touch study / bed mode (eyes closed, fingers on screen)

Start: app → pick deck → **▶ Start touch study (bed mode)**. Pin the app
(screen pinning) so gestures can't leave it. Screen stays on, all black.
Bottom = positive, top = negative · **single tap = mild, double tap = extreme**.

| Gesture | During question | After reveal |
| --- | --- | --- |
| Tap anywhere | Reveal answer | — |
| Tap **bottom** half | — | **Good** |
| Double-tap **bottom** | — | **Easy** |
| Tap **top** half | — | **Hard** |
| Double-tap **top** | — | **Again** |

Any time, **anywhere on the screen** (top/bottom zones don't apply to these):

| Gesture | Action |
| --- | --- |
| **Two-finger tap** | Undo last rating |
| **Four-finger tap** | Stop study & return to the start menu (saves the pending rating, confirms "Study stopped.") |
| **Long-press** | Bookmark card (tag `audio-bookmark` — find later on desktop with `tag:audio-bookmark`) |
| **Swipe down** | Replay question (also works after reveal) |

Tip: keep swipes and long-presses away from the very top/bottom screen
edges — those strips can trigger Android's own status-bar/nav gestures
(mostly suppressed when the app is pinned).

Volume keys = normal volume here.

## 🚗 Car mode (voice — no hands, no look)

Start: app → pick deck → **▶ Start car mode (voice)**. Speak **between**
playbacks (the mic is off while Andrew talks). Volume keys = normal volume
here — turn it up or down any time.

| Say | Action |
| --- | --- |
| **"show"** / "answer" / "reveal" / "flip" | Reveal answer |
| **"repeat"** / "replay" | Replay (question — or answer, if revealed) |
| **"good"** · **"easy"** · **"hard"** · **"again"** (or "wrong") | Rate the card |
| **"undo"** | Undo last rating |
| **"bookmark"** / "mark" | Bookmark card (tag `audio-bookmark`) |
| **"stop"** / "finish" | End the session |

Every command is confirmed by voice. Ratings only count after the answer
was revealed; "again/wrong" while the question plays does nothing.

## 📱 In-app buttons (screen on)

**▶ Start study (here)** / **■ Stop study**, then Replay · Show answer ·
Good · Again · **↩ Undo last rating** · **✏ Edit card**.

**✏ Edit card** appears only during an in-app round (it needs the card on
screen). It opens a box per note field, writes your edits straight back to
AnkiDroid, and re-reads the card so a replay reflects the change.

## Terminology (how cards are read aloud)

- **Recognition** — the word is given, you recall the meaning. Blanked words
  in the example are **restored**: "The contractor *cut corners*, and…"
- **Production** — the definition is given, you produce the word. Blanks are
  read as **hints**: "…barely, *4 letter word starting with M and ending
  with E*, then the word a, then…"

The app infers the direction per card side: word visible → recognition,
word hidden → production.

Dictionary tags are spoken as full words: *inf.* → informal, *v.* → verb,
*n.* → noun, *adj.* → adjective, *adv.* → adverb, *prep.* → preposition,
*conj.* → conjunction, *esp./esp* → especially, *fig.* → figurative,
*lit.* → literary, *BrE* → British English. *fig.*/*lit.* at the very end
of a line are left alone — there they're usually real sentence words
("the room was dimly lit.").

## Everywhere

- Every action is confirmed by voice (and vibration in touch mode).
- Undo covers the **last rating only**, until the next rating is given —
  ratings are written to AnkiDroid when the following rating lands or when
  you stop studying.
- Exiting (stop buttons, app close) always saves the pending rating first.
