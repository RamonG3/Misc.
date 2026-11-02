#!/usr/bin/env bash
# Runs inside the phone emulator job in CI.
#  1. Installs the release APK (shrunk by R8, exactly what users get), makes it the home
#     app, picks a lifepath, opens the stash, and checks the process is still alive.
#  2. Installs the debug APK over it and uses its demo hooks to photograph every screen.
# Output: shots/*.png, smoke.txt (did it survive?) and logcat.txt.
set -uo pipefail

RELEASE="${1:-CyberLauncher.apk}"
DEBUG="${2:-CyberLauncher-debug.apk}"
PKG="com.ramong.cyberlauncher"
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
# Give a fresh emulator a moment to finish its own first-boot chores.
sleep 30
adb shell dumpsys battery unplug || true
adb shell dumpsys battery set level 76 || true

SIZE="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
W="${SIZE%x*}"
H="${SIZE#*x}"
echo "screen ${W}x${H}"

# ---------------------------------------------------------------- release build
adb install -r "$RELEASE"
adb shell cmd package set-home-activity "$ACT" || true
adb logcat -c || true
adb shell input keyevent KEYCODE_HOME
sleep 9
shot 01-first-run
# The middle of the screen is the Street Kid card.
adb shell input tap $((W / 2)) $((H / 2))
sleep 4
shot 02-home
# Swipe up on the city for the stash, at about the speed of a thumb.
adb shell input swipe $((W / 2)) $((H * 3 / 10)) $((W / 2)) $((H / 10)) 250
sleep 2
shot 03-stash
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_HOME
sleep 2
if alive; then echo "release build: alive after first run, lifepath, stash" >> smoke.txt; else echo "release build: NOT RUNNING" >> smoke.txt; fi
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
demo home 2
shot 10-home
demo stash 3
shot 11-stash
demo home 2
demo tab:1 2
shot 12-character
demo tab:2 2
shot 13-cyberware
demo tab:3 2
shot 14-quickhacks
demo tab:4 2
shot 15-journal
demo tab:6 2
shot 16-radio
demo tab:0 2
demo scan:2 3
shot 17-scan
demo home 2
demo menu:1 2
shot 18-menu
demo home 2
demo levelup 1
shot 19-levelup
demo theme:corpo 3
shot 20-corpo
demo theme:street 2

if alive; then echo "debug build: alive after the tour" >> smoke.txt; else echo "debug build: NOT RUNNING" >> smoke.txt; fi
adb logcat -d > logcat-full.txt || true
if grep -q "FATAL EXCEPTION" logcat-full.txt; then
    echo "debug build: CRASHED" >> smoke.txt
    grep -A 40 "FATAL EXCEPTION" logcat-full.txt >> smoke.txt
fi
cat logcat-release.txt logcat-full.txt | grep -iE "cyberlauncher|AndroidRuntime|FATAL" | tail -400 > logcat.txt || true
cat smoke.txt
ls -la shots
