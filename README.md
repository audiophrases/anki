# anki — eyes-free audio study for AnkiDroid

A companion Android app for **eyes-free, screen-off** Anki review: it speaks the
card aloud, and you advance / reveal / rate using **volume or media buttons**
(and later **voice** commands — "good", "again", "repeat"). Built for studying
while walking, commuting, or exercising.

It talks to **AnkiDroid** through its official ContentProvider API, so card
scheduling and AnkiWeb sync stay correct — AnkiDroid reschedules each card; this
app just supplies the rating.

## Status

The working app is built through **M3** (touch-zone "bed mode", screen-off
session, volume/voice control, Edge neural TTS with prefetch + cache). The
complete, buildable Gradle project is in [`android/`](android/) — that is the
single source of truth that compiles and installs. The `m0`–`m3` folders are
historical per-milestone code snapshots and notes.

To build it and put it on a phone (from this or any other machine), see
**[BUILD.md](BUILD.md)**.

## Roadmap

| Milestone | What |
| --- | --- |
| M0 | Read a due card from AnkiDroid, rate it, write the rating back. |
| M1 | Eyes-free core: foreground audio service speaks Q → pause → A; control via headset/media buttons, screen off. |
| M2 | Interactive scheduling: button = Good/Again → write back → auto-advance. **(MVP)** |
| M3 | Volume-rocker mode (MediaSession + remote VolumeProvider) + voice mode. |

## Getting started

- **Just put the app on a phone (no toolchain):** install the committed
  [`dist/ankiaudio-debug.apk`](dist/ankiaudio-debug.apk) —
  `adb install -r dist/ankiaudio-debug.apk`, or copy it to the phone and tap it.
  See [BUILD.md](BUILD.md).
- **Build from source:** [BUILD.md](BUILD.md) — point at your Android SDK, then
  `./gradlew installDebug` onto the phone (or `./gradlew exportDebugApk` to
  refresh the committed APK).
- **Background / first-time AnkiDroid + emulator set-up:** [SETUP.md](SETUP.md).

## How it connects to Anki

Verified against [FlashCardsContract](https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt):

- Due cards: `content://com.ichi2.anki.flashcards/schedule`
- Card text: `content://com.ichi2.anki.flashcards/notes/{note_id}/cards/{ord}`
- Answer back: `update()` on the schedule URI with `answer_ease` (1–4)
- Permission: `com.ichi2.anki.permission.READ_WRITE_DATABASE`
