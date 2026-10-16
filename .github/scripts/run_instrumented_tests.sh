#!/usr/bin/env bash
# Runs the instrumented tests on the emulator started by android-emulator-runner and keeps
# the device log (the emulator is shut down right after this script).
set -o pipefail

adb logcat -G 16M || true
# Slightly larger text, like many phones are set up.
adb shell settings put system font_scale 1.15 || true
# Don't let "app isn't responding" dialogs of the busy CI emulator cover the screen.
adb shell settings put global hide_error_dialogs 1 || true
status=0
gradle connectedDebugAndroidTest --stacktrace 2>&1 | tee instrumented-tests.log || status=$?
adb logcat -d -v time '*:W' > logcat-warnings.txt 2>/dev/null || true
adb logcat -d -v raw -s MSTSHOT:I > screenshots.txt 2>/dev/null || true
exit "$status"
