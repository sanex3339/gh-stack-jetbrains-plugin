#!/usr/bin/env python3
"""Writes every first keystroke bound in an IDE installation (keymaps + plugin.xml files), normalized.

Usage: scripts/dump-keystrokes.py /path/to/WebStorm.app/Contents > src/test/resources/fixtures/taken-first-keystrokes.txt
Used by KeymapConflictTest to keep the plugin's default chords conflict-free.
"""
import os, re, sys, zipfile

PATTERN = re.compile(r'(first-keystroke|(?<![-\w])keystroke)="([^"]+)"', re.I)


def normalize(keystroke):
    parts = keystroke.lower().replace("ctrl", "control").split()
    return " ".join(sorted(set(parts[:-1])) + [parts[-1]]) if parts else None


def main(ide_root):
    taken = set()
    for directory, _, files in os.walk(ide_root):
        for name in files:
            if not name.endswith(".jar"):
                continue
            try:
                with zipfile.ZipFile(os.path.join(directory, name)) as jar:
                    for entry in jar.namelist():
                        if entry.endswith(".xml"):
                            text = jar.read(entry).decode("utf-8", "ignore")
                            for _, keystroke in PATTERN.findall(text):
                                value = normalize(keystroke)
                                if value:
                                    taken.add(value)
            except (zipfile.BadZipFile, OSError):
                pass
    print("\n".join(sorted(taken)))


if __name__ == "__main__":
    main(sys.argv[1])
