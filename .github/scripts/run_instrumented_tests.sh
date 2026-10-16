#!/usr/bin/env bash
# Runs the instrumented tests on the emulator started by android-emulator-runner and keeps
# the device log (the emulator is shut down right after this script).
set -o pipefail

adb logcat -G 16M || true
status=0
gradle connectedDebugAndroidTest --stacktrace 2>&1 | tee instrumented-tests.log || status=$?
adb logcat -d -v time '*:W' > logcat-warnings.txt 2>/dev/null || true
adb logcat -d -v raw -s MSTSHOT:I > screenshots.txt 2>/dev/null || true
exit "$status"
