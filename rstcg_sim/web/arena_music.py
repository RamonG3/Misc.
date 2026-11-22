"""Pack the recorded music tracks (art/music/*.mp3, Ramon's, never in this public repo) into web/arena_music.js.

    python web/arena_music.py          # needs ffmpeg; then python web/build_arena.py

Each track is the first stretch of its file (long enough for a scene, short enough to keep the page light), with a
soft fade at both ends so it loops without a click, made small (56 kbit/s stereo, 32 kHz). The page plays them
through the music volume (arena_audio.js, RSAudio.track); `gain` (dB) evens them out against the synthesized songs,
which run at about -16 dB RMS (RSAudio.analyse, 2026-11-20).

Where each one plays is the page's business (PLACE_MUSIC in arena.src.html), not this file's.
"""
import base64
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MUSIC = os.path.join(os.path.dirname(HERE), "art", "music")

#            name in the page     file                 seconds  gain dB   (measured RMS of that stretch, 2026-11-20)
TRACKS = {"Bazaar":           ("Bazaar.mp3",          238,     6),      # -25.8 dB: Lumbridge, Varrock
          "Forest Day":       ("Forest_Day_A.mp3",    210,     6),      # -26.0 dB: the ambushes on the roads
          "Swamp Land":       ("Swamp_Land.mp3",      240,     3),      # -22.3 dB: Lumbridge Swamp
          "Ambient Room":     ("Ambient_Room.mp3",    224,    -4)}      # -15.3 dB: the stranger


def encode(path, seconds):
    fade = f"afade=t=in:st=0:d=1.5,afade=t=out:st={seconds - 4}:d=4"
    return subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-t", str(seconds), "-af", fade, "-ac", "2", "-ar", "32000",
                           "-c:a", "libmp3lame", "-b:a", "56k", "-f", "mp3", "-"], capture_output=True, check=True).stdout


def main():
    out, missing = {}, []
    for name, (f, secs, gain) in TRACKS.items():
        p = os.path.join(MUSIC, f)
        if not os.path.exists(p):
            missing.append(f)
            continue
        data = encode(p, secs)
        out[name] = {"src": "data:audio/mpeg;base64," + base64.b64encode(data).decode(), "gain": gain}
    target = os.path.join(HERE, "arena_music.js")
    if not out and os.path.exists(target):
        # no tracks found at all: keep the pack that's there rather than build a page with no music (a PC without
        # art/music, or a cloud copy, would otherwise quietly lose Ramon's tracks)
        print(f"no music found in {MUSIC}; {target} left as it is")
        return 0
    js = "window.ARENA_MUSIC = " + json.dumps(out, separators=(",", ":")) + ";"
    open(target, "w").write(js)
    print(f"{len(out)} music tracks, {len(js) // 1024} KB" + (f" (missing from art/music: {', '.join(missing)})" if missing else ""))


if __name__ == "__main__":
    sys.exit(main())
