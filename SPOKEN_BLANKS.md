# Spoken blanks

Production cards read **definition → word shape → example**. In the example,
each hidden word is replaced with the supplied "mhm" recording. Add **225 ms
after each recording**, including adjacent blanks and a blank at the end.
The 30 ms joins from the listening preview remain unchanged.

The player prepares the entire sentence before playback. Each TTS fragment is
decoded to mono PCM at 24 kHz, its outside silence is trimmed, and it is joined
to the recorded blanks. Internal word pauses remain intact. Trimming uses
5 ms RMS windows and retains 20 ms at each edge; 3 ms fades prevent clicks.
One 288 ms warm-up at the beginning protects the first syllable on Bluetooth.
The offline platform TTS fallback uses the same assembly path. Cancelling or
replacing playback releases the player and deletes temporary WAV files.

The recording is bundled locally, so blanks never require a network request.
Prefetch still caches the text fragments. Existing cached MP3s can be reused:
their added warm-up and original TTS silence are removed during assembly.

## Why the old blanks paused

The existing player skipped only its own MP3 warm-up on mid-sentence clips;
it retained the TTS service's silence. In the listening prototype:

| TTS fragment | Leading silence removed | Trailing silence removed |
| --- | ---: | ---: |
| I still need to | 125 ms | 792 ms |
| things | 165 ms | 1177 ms |
| blank (slowed 10%) | 140 ms | 1242 ms |

The untrimmed "I still need to [mhm] things [mhm]" was 4.586 seconds.
Trimming reduced it to 2.297 seconds; adding the requested 225 ms after each
recording makes it **2.747 seconds**, before the app's single route warm-up.
The old path also created a player and potentially waited for synthesis
between fragments. Those delays were not included in these measurements.

## Validation

From `android/` run `gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
Install each APK with `adb install -r`, checking that both commands succeed,
then run `adb shell am instrument -w -r com.eugen.ankiaudio.test/androidx.test.runner.AndroidJUnitRunner`.
To also check the live TTS endpoint, add `-e liveEdge true` before the runner name.
Use direct instrumentation on a personal phone: Gradle's connected-test cleanup
can uninstall the target app even after an install failure. A signing mismatch
must be resolved before updating; preserve app data before any reinstall.
Device tests use the exact preview speech fixtures, verify offline fallback
and playback replacement, and write `cache/spoken-blanks-device-preview.wav`
in the app's private directory for listening checks. No cards are changed.

The Edge client now uses the upstream v2 request token that succeeded during
preview synthesis; see `THIRD_PARTY_NOTICES.md` for source and license details.
