"""Packages desktop/ankiaudio into dist/ankiaudio-read-aloud.ankiaddon.

Double-clicking the .ankiaddon file (or Tools → Add-ons → Install from file)
installs it into Anki on any computer.

    python desktop/build_ankiaddon.py
"""

import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "ankiaudio")
TARGET = os.path.join(HERE, "..", "dist", "ankiaudio-read-aloud.ankiaddon")

SKIP_DIRS = {"__pycache__", "user_files"}
SKIP_FILES = {"meta.json"}


def main() -> None:
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    count = 0
    with zipfile.ZipFile(TARGET, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(SOURCE):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                if name in SKIP_FILES or name.endswith(".pyc"):
                    continue
                path = os.path.join(root, name)
                # An .ankiaddon has the add-on's files at the zip root.
                z.write(path, os.path.relpath(path, SOURCE).replace(os.sep, "/"))
                count += 1
    print(f"wrote {os.path.normpath(TARGET)} ({count} files, {os.path.getsize(TARGET) // 1024} KB)")


if __name__ == "__main__":
    main()
