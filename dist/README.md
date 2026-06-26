# dist/

Prebuilt, ready-to-install app binary, committed so a machine with **no Android
toolchain** can `git pull` and put it on a phone.

- **`ankiaudio-debug.apk`** — debug build of [`../android/`](../android/) (package
  `com.eugen.ankiaudio`), signed with the standard debug key so it installs on any
  phone.

Install:

```sh
adb install -r dist/ankiaudio-debug.apk      # needs platform-tools only
# or copy the .apk to the phone and tap it (allow "install unknown apps")
```

Regenerate after code changes (on the machine that has the SDK/JDK):

```sh
cd android && ./gradlew exportDebugApk
```

Full instructions: [../BUILD.md](../BUILD.md).
