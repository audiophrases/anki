"""AnkiAudio Read Aloud: Anki desktop reads your cards aloud.

The desktop counterpart of the Android AnkiAudio app. Anki keeps doing the
scheduling, sync, card display, editing and undo; this add-on speaks each card
side with Edge neural voices (the same rendering of blanks, hints and
dictionary tags as the phone app) and labels the reviewer's buttons with their
shortcut keys:

    1 Again   2 Hard   3 Good   4 Easy   E Edit   P Play again
    U / Backspace Undo   Space / Enter  show answer, then Good
"""

from aqt import mw

if mw is not None:  # importable outside Anki for tests
    from . import addon

    addon.setup()
