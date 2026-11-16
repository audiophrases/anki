# Third-party notices

`android/app/src/main/java/com/eugen/ankiaudio/EdgeToken.kt` is a Kotlin
adaptation of the Sec-MS-GEC v2 implementation in
[edge-tts drm.py, commit ef51a4d](https://github.com/rany2/edge-tts/blob/ef51a4d/src/edge_tts/drm.py).
Copyright the edge-tts contributors; licensed under LGPL-3.0.
The license is reproduced in `licenses/edge-tts-LGPL-3.0.txt`.
The modified source is included in this repository; build instructions and
the replaceable debug APK are provided in `BUILD.md`.

The `blank_mhm.pcm` audio asset is the user's recording supplied on
16 November 2026 for use as the app's spoken blank. It is 24 kHz mono signed
16-bit little-endian PCM (no header), 8664 samples. Processing: trim 60 ms at
the start, adjust gain by +0.6364 dB, and apply 3 ms edge fades. The 225 ms
post-blank pause is applied by the player, not stored in the asset.
