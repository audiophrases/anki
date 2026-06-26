# Setup — Anki eyes-free audio study (Android)

> **Note:** The app is now a committed, ready-to-build Gradle project in
> [`android/`](android/) — you no longer need to hand-create an Android Studio
> project and copy files in. To just build and install it, see
> **[BUILD.md](BUILD.md)**. The steps below remain as background on the AnkiDroid
> side (emulator, test deck, permission) and the project's original bring-up.

Goal of the whole project: an Android app that lets you study AnkiDroid cards
**eyes-free, screen off** — it speaks the card, you advance/rate with volume or
media buttons (later: voice). It talks to AnkiDroid through AnkiDroid's official
ContentProvider API, so scheduling + AnkiWeb sync stay correct (AnkiDroid does
the rescheduling; we just hand it your rating).

This file gets you from **nothing installed** to **M0 running on an emulator**.

---

## 1. Install Android Studio (brings the SDK + emulator)

1. Download Android Studio: https://developer.android.com/studio
2. Run the installer. Accept the default "Standard" setup — it downloads the
   Android SDK, a platform, build tools, and an emulator image. (~a few GB.)
3. Launch it once and let it finish any first-run downloads.

## 2. Create an emulator **with Play Store**

This matters: you want a system image that has the Google Play Store so you can
install AnkiDroid normally.

1. Android Studio → **Device Manager** (the phone icon) → **Add a new device**.
2. Pick e.g. **Pixel 7**.
3. For the system image, choose a recent API (e.g. **API 34**) whose row shows
   the **Play Store icon** (not just "Google APIs"). Download it, finish.
4. Start the emulator (▶). Sign into a Google account inside it (needed for Play).

## 3. Install AnkiDroid in the emulator + get a few cards

1. In the emulator, open **Play Store** → search **AnkiDroid** → Install.
2. Open AnkiDroid once so it creates the collection.
3. **For development, make a tiny test deck** (fast, lightweight):
   - AnkiDroid → Decks → ➕ → create deck "Test".
   - Add ~10 simple notes (Front/Back). They'll be due immediately.
   - *(Optional, heavier:* sign into your AnkiWeb account to sync your real
     collection — but 125k notes into an emulator is slow; do the test deck
     first.)
4. Review one card in AnkiDroid so the collection is fully initialized.

> Eventually you'll want a **real phone** for true headset/volume/voice testing,
> but the emulator is fine for M0–M2 logic.

## 4. Create the M0 project

1. Android Studio → **New Project** → **Empty Views Activity** → Next.
2. Name: `Anki Audio` · Package name: `com.eugen.ankiaudio` · Language: **Kotlin**
   · Minimum SDK: **API 26**. Finish.
   - If you choose a different package name, change the `package` line at the top
     of the two `.kt` files to match.

## 5. Drop in the M0 code

The ready-to-use source is in [`m0/`](m0/). See [m0/README.md](m0/README.md) for
exactly where each file goes in the generated project. In short:

- `MainActivity.kt`, `AnkiDroidApi.kt` → `app/src/main/java/com/eugen/ankiaudio/`
- `activity_main.xml` → `app/src/main/res/layout/`
- Merge `AndroidManifest-additions.xml` into `app/src/main/AndroidManifest.xml`

No extra Gradle dependencies are needed — M0 talks to the ContentProvider
directly and only uses `appcompat` + `core-ktx`, which the template already has.

## 6. Run it

1. Make sure AnkiDroid (with due cards) is installed in the same emulator.
2. Press ▶ Run. On first launch the app requests the AnkiDroid permission —
   **Allow** it.
3. Tap **Load next due card** → you should see the card's text. **Show answer**,
   then **Good**/**Again** → it writes the rating back and loads the next card.
   Verify in AnkiDroid that the card got rescheduled.

If "Load" errors: confirm AnkiDroid is installed in the *same* emulator, you've
opened it once, and you granted the permission.

---

## What's next (roadmap)

- **M0 (this)** — prove the data path: read a due card, rate it, write back. ✅ target
- **M1** — eyes-free core: foreground audio service speaks Q → pause → A,
  controlled by headset/media buttons, screen off.
- **M2** — interactive scheduling: button = Good/Again → write back → auto-advance.
  This is the MVP.
- **M3** — volume-rocker mode (MediaSession + remote VolumeProvider) + voice mode
  (push-to-talk first, then continuous on-device recognition).
