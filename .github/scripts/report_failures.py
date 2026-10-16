#!/usr/bin/env python3
"""Turns test failures and build errors into GitHub annotations, so they show up on the
pull request and the run summary instead of being buried in the log.

Usage: report_failures.py [log files...]
  - JUnit XML results of unit tests and instrumented tests are read automatically.
  - Each log file is scanned for Kotlin / Java compiler errors and crashes.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 10
MAX_LENGTH = 3500
emitted = 0


def escape_data(text):
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def emit(title, message):
    global emitted
    if emitted >= MAX_ANNOTATIONS:
        return
    emitted += 1
    print(f"::error title={escape_property(title)}::{escape_data(message.strip()[:MAX_LENGTH])}")


def report_test_results():
    paths = glob.glob("app/build/test-results/**/*.xml", recursive=True)
    paths += glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True)
    for path in sorted(paths):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in root.iter("testcase"):
            for problem in case.findall("failure") + case.findall("error"):
                name = f"{case.get('classname', '')}.{case.get('name', '')}"
                text = (problem.get("message") or "") + "\n" + (problem.text or "")
                emit(f"Test failed: {name}", text)


def report_log(path):
    try:
        with open(path, errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return
    found = False
    for index, line in enumerate(lines):
        if line.startswith("e: ") or re.search(r"\.(java|kt):\d+(:\d+)?: error: ", line):
            emit("Compile error", line)
            found = True
        elif "FATAL EXCEPTION" in line or "VideoFrameProcessingException" in line or "ExportException" in line:
            emit(f"Crash in {path}", "\n".join(lines[index:index + 30]))
            found = True
    if not found:
        for index, line in enumerate(lines):
            if "What went wrong" in line:
                emit(f"Build failed ({path})", "\n".join(lines[index:index + 25]))
                break


if __name__ == "__main__":
    report_test_results()
    for log_path in sys.argv[1:]:
        report_log(log_path)
    if emitted == 0:
        print("No test failures or compiler errors found to report.")
