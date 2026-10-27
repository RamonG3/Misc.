#!/usr/bin/env bash
# Runs inside the Wear OS emulator job in CI: installs the APK, makes RuneWatch the active
# watch face and takes a screenshot. Output: screenshot.png and logcat.txt.
set -uo pipefail

APK="${1:-RuneWatch.apk}"
PKG="com.ramong.runewatch"

adb wait-for-device
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 1800000 || true
adb install -r "$APK"
sleep 5
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
    --es operation set-watchface --es watchFaceId "$PKG"
sleep 20
adb shell input keyevent KEYCODE_WAKEUP || true
sleep 5
adb exec-out screencap -p > screenshot.png
adb logcat -d > logcat-full.txt || true
grep -iE "watchface|wff|runewatch|DEBUG_SURFACE" logcat-full.txt | tail -300 > logcat.txt || true
ls -la screenshot.png logcat.txt
