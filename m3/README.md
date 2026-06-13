# M3 — touch-zone study (bed mode) ✅ core verified 2026-06-11

Eyes-closed study with fingers instead of volume keys: full-screen black
surface (`TouchStudyActivity`), bezel as tactile reference, every action
confirmed by haptic + speech. Designed for resting from screens with the app
pinned (enable Android screen pinning) and orientation locked to portrait.

## Gesture map (chosen by Eugen: halves + double-tap)

| Phase | Gesture | Action |
| --- | --- | --- |
| Question | tap anywhere | Reveal answer |
| Answer | tap **bottom** half | **Good** |
| Answer | double-tap bottom | **Easy** |
| Answer | tap **top** half | **Hard** |
| Answer | double-tap top | **Again** |
| Any | swipe down | Replay question |
| Any | two-finger tap | **Undo** last rating |
| Any | four-finger tap | **Stop & exit** to the start menu (commits pending) |
| Any | long-press | **Bookmark**: tags the note `audio-bookmark` |

Mnemonics: bottom = positive (near the thumb), top = negative;
**single tap = mild (Good/Hard), double tap = extreme (Easy/Again)**.

The bookmark tag is written through the notes URI (`addTagToNote`) — find the
cards later on desktop with `tag:audio-bookmark`.

Volume keys deliberately stay ordinary volume keys in this mode.

## Voice selector

The start screen has a **Voice** dropdown listing the full Edge neural-TTS
catalog (~320 voices, every language — English sorted to the top) plus a
**🔊 Test voice** button to hear the current pick. The choice is saved and
applies to every mode (in-app, screen-off, bed, car) on the next card — no
restart needed.

- `EdgeVoices.kt` (new) fetches the catalog once from the Read-Aloud
  `voices/list` endpoint (plain GET, no token dance), caches it in
  `cacheDir/edge-voices.json` (refreshed monthly, works offline after the
  first load), and falls back to a short bundled list if a cold first run has
  no network. It also owns the saved-voice preference (`savedVoice` /
  `saveVoice`, prefs key `voice`).
- `CardSpeaker.kt` is **replaced**: `speakText` now reads `EdgeVoices.savedVoice`
  per utterance and passes it to `EdgeTts.synthesize` (which already took a
  `voice` argument — `EdgeTts.kt` is unchanged). All three study modes share
  `CardSpeaker`, so none of them needed touching.
- `MainActivity.kt` / `activity_main.xml` gain the spinner + test button.

No new Gradle dependencies (OkHttp + `org.json` are already available).

## Shares everything with the other modes

Same `StudyEngine` as the in-app buttons and the screen-off session:
lazy-commit ratings (undo-able until the next rating), recognition/production
blank reading (restored words vs spoken hints — see ../GESTURES.md), Edge TTS
with cache + fallback.

## Verified on-device

start → tap reveal → bottom-tap Good (pending) → next card → swipe-down
replay → long-press bookmark (`ok=true`). Double-tap and two-finger tap can't
be simulated through `adb input` — verified manually.

## Implementation notes

- `GestureDetector.onSingleTapConfirmed` (not `onSingleTapUp`) so single taps
  wait out the double-tap window.
- Two-finger tap detected manually via `ACTION_POINTER_DOWN` count == 2 +
  quick `ACTION_UP`; single-finger gestures are suppressed for 700 ms around
  it so the detector doesn't misread the second finger.
- `FLAG_KEEP_SCREEN_ON` + immersive (hidden system bars, transient by swipe).
- Engine state strings still mention volume keys ("up: reveal") — cosmetic,
  invisible eyes-closed; parameterize if it ever matters.
