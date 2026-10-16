#!/usr/bin/env python3
"""Reassembles the screenshots logged by EditorScreenUiTest (tag MSTSHOT) and publishes each
one as a base64 JPEG notice, readable from the GitHub API without downloading artifacts."""
import base64
import sys
from collections import defaultdict

chunks = defaultdict(dict)
totals = {}
try:
    with open(sys.argv[1] if len(sys.argv) > 1 else "screenshots.txt", errors="replace") as handle:
        for line in handle:
            parts = line.strip().split(" ")
            if len(parts) != 3 or "/" not in parts[1]:
                continue
            name, position, data = parts
            index, total = (int(x) for x in position.split("/"))
            chunks[name][index] = data
            totals[name] = total
except OSError:
    pass

for name, parts in chunks.items():
    if len(parts) != totals[name]:
        print(f"::warning title=Screenshot {name}::incomplete ({len(parts)}/{totals[name]} chunks)")
        continue
    data = "".join(parts[i] for i in range(1, totals[name] + 1))
    size = len(base64.b64decode(data))
    print(f"Screenshot {name}: {size} bytes")
    print(f"::notice title=Screenshot {name} ({size} bytes)::{data}")
if not chunks:
    print("No screenshots found.")
