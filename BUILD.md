# Build & install on a phone

The complete, buildable Android app lives in [`android/`](android/) — a normal
Gradle project (package `com.eugen.ankiaudio`) with the Gradle wrapper committed,
so any machine that has the prerequisites can clone the repo and build the APK
**without Android Studio set-up steps**. The `m0`–`m3` folders are historical
per-milestone code snapshots/notes; `android/` is the single source of truth that
actually compiles.

## Quickest path: install the committed APK (no Android toolchain)

A ready-to-install debug APK is committed at
[`dist/ankiaudio-debug.apk`](dist/ankiaudio-debug.apk), rebuilt from the current
code on the machine that has the toolchain. A second machine can install it with
**no Android SDK or JDK**:

- **With `adb`** (needs only platform-tools, ~10 MB — not the full SDK):
  ```sh
  git pull
  adb install -r dist/ankiaudio-debug.apk
  ```
- **With no tools at all:** copy `dist/ankiaudio-debug.apk` to the phone (USB,
  cloud, email), tap it in the phone's Files app, and allow "install unknown
  apps".

Then launch the app once and **grant the AnkiDroid permission**. AnkiDroid must
already be installed on that phone.

### Rebuilding the committed APK (on the machine that has the toolchain)

After changing code, regenerate and commit it in one step:
```sh
cd android
./gradlew exportDebugApk      # builds + copies to ../dist/ankiaudio-debug.apk
cd ..
git add dist/ankiaudio-debug.apk && git commit -m "Update prebuilt APK"
```
It's a **debug** build, signed with the standard debug key, so it installs on any
phone (a release build would need its own signing config). The APK is a ~13 MB
binary committed for convenience — fine for personal syncing; if git history ever
grows too heavy, switch it to Git LFS or stop committing it and build from source.

## What each machine needs (one time)

> Only needed if you want to **build from source** on this machine (the section
> above avoids all of this).

1. **A JDK 17+** (the Gradle wrapper pulls Gradle itself, but it needs a JVM to
   start). If you have Android Studio, its bundled JDK works — point `JAVA_HOME`
   at it, e.g. on Windows: `C:\Program Files\Android\Android Studio\jbr`.
2. **The Android SDK** with platform **android-36** and recent build-tools.
   Installing Android Studio gets these; opening `android/` in Android Studio will
   offer to download anything missing.
3. **`android/local.properties`** pointing at that SDK. Copy the template:
   ```sh
   cp android/local.properties.sample android/local.properties
   # then edit sdk.dir to your SDK path
   ```
   (Or set the `ANDROID_HOME` env var instead. Android Studio writes this file
   automatically when you open the project. It is git-ignored on purpose.)

## Build the APK (command line)

From the `android/` folder:

```sh
# Windows (PowerShell / cmd)
cd android
.\gradlew.bat assembleDebug

# macOS / Linux
cd android
./gradlew assembleDebug
```

The debug APK lands at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle 9.4.1 and the dependencies, so it needs network
and a few minutes; later builds are fast.

## Install on the phone

1. On the phone: **Settings → About → tap Build number 7×** to unlock Developer
   options, then **Developer options → enable USB debugging**. Plug it into the
   computer over USB and accept the "Allow USB debugging" prompt.
2. `adb` ships with the SDK platform-tools (e.g.
   `…/Android/Sdk/platform-tools/adb`). Confirm the phone is visible:
   ```sh
   adb devices
   ```
3. Build straight onto the phone, or install the APK you already built:
   ```sh
   # build + install in one step
   ./gradlew installDebug

   # or install a prebuilt APK (use -r to replace an existing copy)
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch it once and **grant the AnkiDroid permission prompt**. AnkiDroid must be
   installed on the same phone and opened at least once (see the app's
   [README](README.md) / [SETUP](SETUP.md) for the AnkiDroid side).

## Wireless install (no cable)

After at least one USB pairing:

```sh
adb tcpip 5555
adb connect <phone-ip>:5555      # phone IP from Settings → Wi-Fi → network details
./gradlew installDebug
```

## Release / shareable APK

`assembleDebug` is fine for your own phone. For a signed, installable-anywhere
build, configure a `signingConfig` in `android/app/build.gradle.kts` and run
`./gradlew assembleRelease`. Not needed just to study on your own device.

## Editing going forward

Open the **`android/`** folder in Android Studio (not the repo root, and not the
old `AndroidStudioProjects/AnkiAudio` copy) so edits land in version control. Commit
and `git push`; on the other machine `git pull` and rebuild.
