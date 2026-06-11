# M1 — eyes-free audio core ✅ VERIFIED 2026-06-11

On top of M0's data path, the card is now **spoken aloud**:

- **Edge neural TTS** (`en-US-AndrewMultilingualNeural`) via the Read-Aloud
  websocket endpoint — `EdgeTts.kt`. MP3s cached in `cacheDir/tts/` keyed by
  (voice, text): each text costs network once, replays are instant/offline.
- **Fallback chain:** Edge fails (offline / endpoint change) → device TTS
  (Google), automatically, per segment — `CardSpeaker.kt`.
- **Cloze blanks** (`m••e`, `wh•••••d`) are read according to the study
  direction — `AudioScript.kt`:
  - **Recognition** (word given → recall the meaning; the word is visible on
    this card side): blanks are **restored** and the sentence reads naturally
    — "The contractor **cut corners**, and the repairs…". Two blank shapes
    supported: whole word with hidden middle (`m••e` → make) and
    stem+inflection (`wh•••••d` → wheedle+d → wheedled).
  - **Production** (definition given → produce the word; the word is hidden):
    blanks become natural inline hints, in position — "…but barely,
    **4 letter word starting with M and ending with E**, then the word a,
    then a **4 letter word starting with D and ending with T**, in the
    backlog." Consecutive blanks are chained with "then the word …, then…".
  - The direction is inferred per card side: if a visible word matches the
    blank's shape, it's recognition; otherwise production. A line that is
    *only* a blanked token (dedicated hint field like `wh•••••`) is never
    read as a sentence.
- Question auto-plays on load; **Replay question** button; answer side is
  spoken on reveal.

## Files

Same drop-in scheme as m0 (see ../m0/README.md). New since m0:
`EdgeTts.kt`, `AudioScript.kt`, `CardSpeaker.kt`; `MainActivity.kt` and
`activity_main.xml` replaced; manifest needs `android.permission.INTERNET`
(full manifest included as `AndroidManifest-full.xml`). Gradle additions:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
```

## Edge TTS protocol notes (hard-won)

- 403 Forbidden = your protocol constants are stale. Mirror the current
  `edge-tts` PyPI package (`constants.py`, `drm.py`):
  `CHROMIUM_FULL_VERSION` (143.0.3650.75 as of 2026-06), matching
  `Sec-MS-GEC-Version=1-<ver>`, UA `Edg/<major>`, and a random
  `Cookie: muid=<32 uppercase hex>;`.
- `Sec-MS-GEC` = uppercase SHA-256 hex of
  `<windows_ticks_rounded_down_to_300s × 10^7><TrustedClientToken>`.
- Requires accurate device clock (5-minute window).

## Device gotchas

- Custom-ROM "Restricted networking mode" blocks new apps' network silently
  (DNS fails app-side while shell works). Allow the app's UID:
  `adb shell settings put global uids_allowed_on_restricted_networks <uid>`
  (UID via `pm list packages -U com.eugen.ankiaudio`; changes on reinstall,
  not on update).
