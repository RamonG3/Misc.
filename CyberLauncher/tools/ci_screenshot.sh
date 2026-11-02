#!/usr/bin/env bash
# Runs inside the phone emulator job in CI.
#  1. Installs the release APK (shrunk by R8, exactly what users get), makes it the home
#     app, picks a lifepath, opens the app drawer and the deck, and checks it's still alive.
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
sleep 8
shot 02-home
# Swipe up for all apps, at about the speed of a thumb.
adb shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 2 / 10)) 250
sleep 2
shot 03-drawer
adb shell input keyevent KEYCODE_BACK
sleep 1
# Swipe right for the deck page, then Home goes back to the home page.
adb shell input swipe $((W / 5)) $((H / 2)) $((W * 4 / 5)) $((H / 2)) 400
sleep 2
shot 04-deck
adb shell input keyevent KEYCODE_HOME
sleep 2
if alive; then echo "release build: alive after first run, lifepath, drawer, deck" >> smoke.txt; else echo "release build: NOT RUNNING" >> smoke.txt; fi
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
shot 11-drawer
demo home 2
demo deck 3
shot 12-deck
demo home 2
demo settings 3
shot 13-settings
demo home 2
demo scan:2 3
shot 14-scan
demo home 2
demo menu:1 2
shot 15-menu
demo home 2
demo homemenu 2
shot 16-homemenu
demo home 2
demo levelup 1
shot 17-levelup
demo theme:corpo 4
shot 18-corpo
demo theme:street 2
# Real gestures, with the debug build's touch log (tag CyberTouch) saved to touch.txt.
mark() { adb shell log -t CyberTouch "=== $1" || true; }
demo home 2
mark "swipe right from W/5"
adb shell input swipe $((W / 5)) $((H / 2)) $((W * 4 / 5)) $((H / 2)) 400
sleep 2
shot 19-swipe-edge
demo home 2
mark "swipe right from 2W/5"
adb shell input swipe $((W * 2 / 5)) $((H / 2)) $((W * 9 / 10)) $((H / 2)) 400
sleep 2
shot 20-swipe-mid
# The release run's exact sequence: swipe up, Back, swipe right.
demo home 2
mark "replay: swipe up"
adb shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 2 / 10)) 250
sleep 2
shot 21-replay-drawer
mark "replay: back"
adb shell input keyevent KEYCODE_BACK
sleep 1
mark "replay: swipe right"
adb shell input swipe $((W / 5)) $((H / 2)) $((W * 4 / 5)) $((H / 2)) 400
sleep 2
shot 22-replay-deck
# Hold Gmail (top row, second column) and drag it down the grid, photographed mid-drag.
demo home 2
mark "drag and drop"
adb shell input draganddrop $((W * 38 / 100)) $((H * 287 / 1000)) $((W * 62 / 100)) $((H * 617 / 1000)) 3000 &
sleep 2
shot 23-drag
wait
sleep 2
shot 24-dropped
adb logcat -d -s CyberTouch > touch.txt || true
DECK_HITS="$(grep -c 'root ACTION_UP.*page=0' touch.txt || true)"
echo "debug build: ${DECK_HITS:-0} of 3 sideways swipes reached the deck" >> smoke.txt
echo "--- input settings" >> touch.txt
adb shell settings get secure navigation_mode >> touch.txt 2>&1 || true
adb shell dumpsys input | grep -iE "gesture|exclusion|back" | head -40 >> touch.txt || true

if alive; then echo "debug build: alive after the tour" >> smoke.txt; else echo "debug build: NOT RUNNING" >> smoke.txt; fi
adb logcat -d > logcat-full.txt || true
if grep -q "FATAL EXCEPTION" logcat-full.txt; then
    echo "debug build: CRASHED" >> smoke.txt
    grep -A 40 "FATAL EXCEPTION" logcat-full.txt >> smoke.txt
fi
cat logcat-release.txt logcat-full.txt | grep -iE "cyberlauncher|AndroidRuntime|FATAL" | tail -400 > logcat.txt || true
cat smoke.txt
ls -la shots
