# M0 — prove the AnkiDroid data path ✅ VERIFIED 2026-06-11

Minimal app that reads a due card from AnkiDroid, shows its text, and writes your
Good/Again rating back through the official API. No audio yet — this just proves
the pipe works before we build the eyes-free experience.

**Status: working end-to-end on a real device** (Xiaomi M2007J20CG, Android 16,
current AnkiDroid from Play Store, against the real synced collection, restricted
to the "Test" deck).

## Where each file goes

After you create the project (see [../SETUP.md](../SETUP.md), step 4), copy these
into the generated project. Paths assume package `com.eugen.ankiaudio`:

| This file | Goes to |
| --- | --- |
| `AnkiDroidApi.kt` | `app/src/main/java/com/eugen/ankiaudio/AnkiDroidApi.kt` |
| `MainActivity.kt` | `app/src/main/java/com/eugen/ankiaudio/MainActivity.kt` (replace the generated one) |
| `activity_main.xml` | `app/src/main/res/layout/activity_main.xml` (replace the generated one) |
| `AndroidManifest-additions.xml` | merge into `app/src/main/AndroidManifest.xml` (see comments in that file) |

If you used a different package name, update the `package` line at the top of both
`.kt` files to match, and adjust the folder path accordingly.

## No extra dependencies

M0 uses only `androidx.appcompat` and `androidx.core` (HtmlCompat), both already
present in the Empty Views Activity template. Nothing to add to `build.gradle`.

## How it works (the verified API surface)

- **Due card:** query `content://com.ichi2.anki.flashcards/schedule` with
  `selection = "limit=?, deckID=?"` → columns `note_id`, `ord`, `button_count`.
- **Card text:** query `content://com.ichi2.anki.flashcards/notes/{note_id}/cards/{ord}`
  → columns `question`, `answer` (full rendered HTML — strip `<style>` blocks,
  cut the answer at `<hr id=answer>`, then `HtmlCompat.fromHtml`).
  ⚠️ The `question_simple`/`answer_simple` columns from older docs **do not exist**
  in current AnkiDroid; reading them throws.
- **Select deck (required!):** before answering, `update()` on
  `content://com.ichi2.anki.flashcards/selected_deck` with `deck_id`.
  The provider answers against the **globally selected deck's queue** — its
  schedule query only selects a deck *temporarily*. Answering while another deck
  is selected crashes AnkiDroid's provider with a Rust-backend
  `"card was modified"` state-mismatch error.
- **Answer:** `ContentResolver.update()` on the schedule URI with
  `note_id`, `ord`, `answer_ease` (1=Again … 4=Easy), `time_taken` (ms).
  AnkiDroid does the rescheduling (FSRS/SM-2 + revlog + sync state).

## Gotchas (all hit and solved during bring-up)

- AnkiDroid must be **installed on the same device** and opened once.
- The `<queries>` manifest block is **required** on Android 11+ or the provider
  is invisible and queries return null with no error.
- Grant the permission prompt on first launch.
- The safety rail in `MainActivity` (`TEST_DECK_NAME`) restricts all reads and
  writes to a deck named **Test** — keep it until the app grows real deck
  selection UI.
- AnkiDroid's process may be cold-started by Android just to serve our provider
  calls — never assume prior state (e.g. a deck selected in an earlier call)
  survives between calls. Select the deck on every load.
