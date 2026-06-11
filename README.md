# anki — eyes-free audio study for AnkiDroid

A companion Android app for **eyes-free, screen-off** Anki review: it speaks the
card aloud, and you advance / reveal / rate using **volume or media buttons**
(and later **voice** commands — "good", "again", "repeat"). Built for studying
while walking, commuting, or exercising.

It talks to **AnkiDroid** through its official ContentProvider API, so card
scheduling and AnkiWeb sync stay correct — AnkiDroid reschedules each card; this
app just supplies the rating.

## Status

- **M0 (in progress)** — prove the data path: read a due card, rate it, write
  back. Source in [`m0/`](m0/).

## Roadmap

| Milestone | What |
| --- | --- |
| M0 | Read a due card from AnkiDroid, rate it, write the rating back. |
| M1 | Eyes-free core: foreground audio service speaks Q → pause → A; control via headset/media buttons, screen off. |
| M2 | Interactive scheduling: button = Good/Again → write back → auto-advance. **(MVP)** |
| M3 | Volume-rocker mode (MediaSession + remote VolumeProvider) + voice mode. |

## Getting started

See [SETUP.md](SETUP.md) — goes from nothing installed to M0 running on an
Android emulator with AnkiDroid.

## How it connects to Anki

Verified against [FlashCardsContract](https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt):

- Due cards: `content://com.ichi2.anki.flashcards/schedule`
- Card text: `content://com.ichi2.anki.flashcards/notes/{note_id}/cards/{ord}`
- Answer back: `update()` on the schedule URI with `answer_ease` (1–4)
- Permission: `com.ichi2.anki.permission.READ_WRITE_DATABASE`
