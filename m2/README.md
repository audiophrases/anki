# M2 — screen-off eyes-free session ✅ VERIFIED 2026-06-11

The original product vision, working: a **foreground media service** owns the
review loop, audio keeps playing with the screen off/locked, and the
**hardware volume keys** drive the whole session:

| State | Volume-up | Volume-down |
| --- | --- | --- |
| Question playing/idle | Reveal answer | Replay question |
| Answer playing/idle | **Good** | **Again** |

Each rating speaks a one-word confirmation ("Good." / "Again.") and the next
question follows automatically. Notification (media-style, on the lock screen)
mirrors the state and has Replay/Again, Reveal/Good and Stop buttons.

## How volume-key capture works

`StudyService` holds an active `MediaSession` whose playback is set **to
remote** (`setPlaybackToRemote(VolumeProvider)`) with `PlaybackState.PLAYING`.
The system then routes volume-key presses to `VolumeProvider.onAdjustVolume`
instead of changing the real volume — including with the screen off (verified
via `MediaSessionService: Adjusting com.eugen.ankiaudio … by 1` in system
logs).

### Gotchas (cost us a debugging session)

- **You MUST call `session.setCallback(object : MediaSession.Callback() {})`**
  even though it's empty. The framework only creates its callback handler when
  a callback is set; without it, adjust-volume messages are **silently
  dropped** between the system and your VolumeProvider.
- While our own activity is foreground with the screen on, the activity window
  consumes volume keys before media-session dispatch — keys only become study
  controls once the screen is off or another app is in front. (Could be fixed
  with `activity.setMediaController` if in-app capture is ever wanted.)
- Volume keys can't change the actual volume during a session — set your
  listening volume before starting.
- Debounce key events (~450 ms): the rocker auto-repeats when held.

## Other plumbing

- Foreground service type `mediaPlayback`; permissions: `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (runtime),
  `WAKE_LOCK`.
- Partial wake lock (4 h cap) keeps TTS/network alive between cards in Doze.
- Audio focus requested as `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH` (pauses your
  podcast app for the session).
- AnkiDroid ContentProvider calls moved off the main thread
  (`Dispatchers.IO`) in the service.

## Not yet done (M3 candidates)

- Headset/Bluetooth media buttons; tilt gestures (in-hand mode); voice
  commands ("good"/"again"/"repeat").
- Auto-advance timers (speak question → auto-reveal after N s).
- Pre-fetching next card's TTS while the current one plays.
- Audio-focus loss handling (pause on phone call).
