#!/usr/bin/env bash
# Runs inside the Wear OS emulator job in CI: installs the APK, makes RuneWatch the active
# watch face and takes a screenshot. Output: screenshot.png, logcat.txt, wff-runtime.txt.
set -uo pipefail

APK="${1:-RuneWatch.apk}"
PKG="com.ramong.runewatch"

adb wait-for-device
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 1800000 || true

# A fresh emulator spends its first minute setting up its own watch faces, and will
# happily switch back to one of them. Let it finish first.
sleep 60

# The emulator says it is on a charger, and Wear OS then covers every watch face with
# its charging clock. Unplug it (and give Prayer something to show).
adb shell dumpsys battery unplug || true
adb shell dumpsys battery set status 3 || true
adb shell dumpsys battery set level 68 || true

adb install -r "$APK"
sleep 5

active_face() {
    adb shell dumpsys wallpaper | grep -m1 -oE "mWallpaperComponent=[^ ]+|component=[^ ]+" || true
}

for attempt in 1 2 3; do
    echo "== set-watchface attempt $attempt"
    adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
        --es operation set-watchface --es watchFaceId "$PKG"
    sleep 25
    adb shell input keyevent KEYCODE_WAKEUP || true
    sleep 5
    face="$(active_face)"
    echo "active: $face"
    adb exec-out screencap -p > "screenshot-$attempt.png"
    cp "screenshot-$attempt.png" screenshot.png
    # The WFF runtime renders every Watch Face Format face, so RuneWatch shows up as the
    # runtime's wallpaper service.
    if echo "$face" | grep -qiE "watchface.runtime|$PKG"; then
        break
    fi
done

adb shell dumpsys wallpaper > wallpaper.txt || true
adb logcat -d > logcat-full.txt || true
grep -iE "runewatch|DEBUG_SURFACE|watchface.runtime|WatchFaceRuntime|wff|WatchFaceFormat" logcat-full.txt \
    | grep -viE "flavor" | tail -400 > logcat.txt || true
RUNTIME_PID="$(adb shell pidof -s com.google.wear.watchface.runtime 2>/dev/null | tr -d '\r')"
if [ -n "$RUNTIME_PID" ]; then
    adb logcat -d --pid="$RUNTIME_PID" > wff-runtime.txt || true
else
    echo "WFF runtime process not running" > wff-runtime.txt
fi
ls -la screenshot*.png logcat.txt wff-runtime.txt wallpaper.txt
