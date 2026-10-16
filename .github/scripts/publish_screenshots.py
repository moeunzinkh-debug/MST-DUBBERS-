#!/usr/bin/env python3
"""Reassembles a screenshot logged by EditorScreenUiTest (logcat tag MSTSHOT) and publishes it as
base64 JPEG notices, readable from the GitHub API without downloading artifacts.

Usage: publish_screenshots.py <screenshots.txt> <screenshot name>
GitHub keeps 4096 characters per annotation and 10 notices per step, so call it once per
screenshot, each from its own step.
"""
import base64
import sys

CHUNK = 4000
path, wanted = sys.argv[1], sys.argv[2]
parts, total = {}, None
try:
    with open(path, errors="replace") as handle:
        for line in handle:
            fields = line.strip().split(" ")
            if len(fields) != 3 or fields[0] != wanted or "/" not in fields[1]:
                continue
            index, total = (int(x) for x in fields[1].split("/"))
            parts[index] = fields[2]
except OSError:
    pass

if total is None or len(parts) != total:
    print(f"Screenshot {wanted} not found or incomplete.")
    sys.exit(0)
data = "".join(parts[i] for i in range(1, total + 1))
size = len(base64.b64decode(data))
pieces = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)]
if len(pieces) > 10:
    print(f"::warning title=Screenshot {wanted}::too large ({size} bytes)")
    sys.exit(0)
for number, piece in enumerate(pieces, start=1):
    print(f"::notice title=Screenshot {wanted} {number}/{len(pieces)}::{piece}")
