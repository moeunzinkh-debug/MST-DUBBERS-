#!/usr/bin/env python3
"""Counts the JUnit results of this job and shows them as a notice and in the job summary."""
import glob
import os
import xml.etree.ElementTree as ET

paths = glob.glob("app/build/test-results/**/*.xml", recursive=True)
paths += glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True)
passed, failed, skipped = [], [], []
for path in sorted(paths):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        continue
    for case in root.iter("testcase"):
        name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name', '')}"
        if case.find("failure") is not None or case.find("error") is not None:
            failed.append(name)
        elif case.find("skipped") is not None:
            skipped.append(name)
        else:
            passed.append(name)

if paths:
    line = f"{len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped"
    print(f"::notice title=Test results::{line}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as handle:
            handle.write(f"### Test results\n\n{line}\n\n")
            for name in failed:
                handle.write(f"- ❌ `{name}`\n")
            for name in passed:
                handle.write(f"- ✅ `{name}`\n")
            handle.write("\n")
else:
    print("No test results found.")
