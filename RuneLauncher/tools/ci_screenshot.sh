#!/usr/bin/env bash
# Runs inside the Pixel 7 Pro emulator job in CI.
#  1. Installs the release APK (shrunk by R8, exactly what users get), makes it the home app,
#     opens the bank and the Adventurer's Log with real swipes, and checks it's still alive.
#  2. Installs the debug APK over it and uses its demo hooks to photograph every screen,
#     then drags one app onto another with real touch input to make a folder.
# Output: shots/*.png, smoke.txt (did it survive?), logcat.txt and touch.txt.
set -uo pipefail

RELEASE="${1:-RuneLauncher.apk}"
DEBUG="${2:-RuneLauncher-debug.apk}"
PKG="com.ramong.runelauncher"
ACT="$PKG/.LauncherActivity"
mkdir -p shots
: > smoke.txt

shot() {
    adb exec-out screencap -p > "shots/$1.png"
    echo "shot $1"
}

alive() {
    [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]
}

demo() {
    adb shell am start -n "$ACT" --es demo "$1" > /dev/null
    sleep "${2:-3}"
}

adb wait-for-device
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 1800000 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
sleep 30
adb shell dumpsys battery unplug || true
adb shell dumpsys battery set level 76 || true

SIZE="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
W="${SIZE%x*}"
H="${SIZE#*x}"
echo "screen ${W}x${H}, density $(adb shell wm density | tr -d '\r')" | tee -a smoke.txt

# ---------------------------------------------------------------- release build
adb install -r "$RELEASE"
adb shell cmd package set-home-activity "$ACT" || true
adb logcat -c || true
adb shell input keyevent KEYCODE_HOME
sleep 9
shot 01-first-run
adb shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 2 / 10)) 250
sleep 2
shot 02-bank
adb shell input keyevent KEYCODE_BACK
sleep 1
# Sideways from mid-screen, away from the back-gesture edges.
adb shell input swipe $((W * 3 / 10)) $((H / 2)) $((W * 8 / 10)) $((H / 2)) 400
sleep 2
shot 03-log
adb shell input keyevent KEYCODE_HOME
sleep 2
if alive; then echo "release build: alive after first run, bank, log" >> smoke.txt; else echo "release build: NOT RUNNING" >> smoke.txt; fi
adb logcat -d > logcat-release.txt || true
if grep -q "FATAL EXCEPTION" logcat-release.txt; then
    echo "release build: CRASHED" >> smoke.txt
    grep -A 40 "FATAL EXCEPTION" logcat-release.txt >> smoke.txt
else
    echo "release build: no crashes in logcat" >> smoke.txt
fi

# ---------------------------------------------------------------- debug build tour
adb install -r -d "$DEBUG"
adb logcat -c || true
adb shell input keyevent KEYCODE_HOME
sleep 6
demo seed 3
demo home 3
shot 10-home
demo drawer 3
shot 11-bank
demo home 2
demo log 3
shot 12-log
demo home 2
demo folder 2
shot 13-folder
demo home 2
demo menu:0 2
shot 14-menu
demo home 2
demo homemenu 2
shot 15-homemenu
demo home 2
demo examine:2 2
shot 16-examine
demo home 2
demo settings 3
shot 17-settings
demo home 2
demo levelup 1
shot 18-levelup
demo theme:necro 3
shot 19-necromancy
demo theme:gold 2
demo home 2
demo rename 3
shot 20-rename
adb shell input keyevent KEYCODE_BACK
sleep 1
demo home 2

# Real touch: hold the second app on the top row and drop it on the first to make a folder.
# The dragged icon floats above the finger, so the finger ends a little below the target.
mark() { adb shell log -t RuneTouch "=== $1" || true; }
mark "drag to make a folder"
adb shell input draganddrop $((W * 38 / 100)) $((H * 299 / 1000)) $((W * 143 / 1000)) $((H * 337 / 1000)) 3000 &
sleep 2
shot 21-drag
wait
sleep 2
shot 22-folder-made
mark "swipe right"
adb shell input swipe $((W * 3 / 10)) $((H / 2)) $((W * 8 / 10)) $((H / 2)) 400
sleep 2
shot 23-swipe-log
adb logcat -d -s RuneTouch > touch.txt || true
LOG_HITS="$(grep -c 'root ACTION_UP.*page=0' touch.txt || true)"
echo "debug build: ${LOG_HITS:-0} sideways swipes reached the log page" >> smoke.txt

if alive; then echo "debug build: alive after the tour" >> smoke.txt; else echo "debug build: NOT RUNNING" >> smoke.txt; fi
adb logcat -d > logcat-full.txt || true
if grep -q "FATAL EXCEPTION" logcat-full.txt; then
    echo "debug build: CRASHED" >> smoke.txt
    grep -A 40 "FATAL EXCEPTION" logcat-full.txt >> smoke.txt
fi
cat logcat-release.txt logcat-full.txt | grep -iE "runelauncher|AndroidRuntime|FATAL" | tail -400 > logcat.txt || true
cat smoke.txt
ls -la shots
