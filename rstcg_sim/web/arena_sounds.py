"""Pack the recorded sounds for the Arena: python web/arena_sounds.py  ->  web/arena_sounds.js

A spell that has a recording makes that sound when it is cast, in place of the synthesized one, a rune that
has one (its runecrafting sound) makes it when it is played, and a prayer makes its own when it goes up (a Protect
prayer: when it blocks). The recordings are Ramon's files in art/sounds/
(kept out of the repo, like the art), each named after its card: "Air Bolt.ogg" is what Air Bolt plays. ALIAS
lists the ones whose file goes by another name. A file with no card of its name is
left alone and listed, so when a card of that name is added it picks its sound up with no change here.

Not every browser plays Ogg (Safari on older Macs and iPhones doesn't), so each sound is converted once to a small
mono MP3 and kept in art/sounds/game/. Its level is left as recorded and only the silence ahead of it is taken
off: the sounds keep the loudness they have next to each other, and the game sets one volume for them all
(CLIP_GAIN in arena_audio.js). ffmpeg does the converting and is only needed for a sound that is new or has
changed; build_arena.py inlines the result like the art.

    python web/arena_sounds.py            # pack art/sounds/
    RSTCG_SOUNDS=some/folder python web/arena_sounds.py   # pack another folder (the tests use made-up sounds)
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SOUNDS = os.environ.get("RSTCG_SOUNDS", os.path.join(ROOT, "art", "sounds"))
GAME = os.path.join(SOUNDS, "game")
CARDS = json.load(open(os.path.join(HERE, "cards.json"), encoding="utf-8"))["cards"]

# card -> the name its sound file goes by, where that isn't the card's own name
ALIAS = {
    "Superheat": "Superheat Item",            # the spell's full name in RuneScape (its icon file is named the same way)
    "Varrock Teleport": "Standard Teleport",  # every standard-spellbook teleport makes this sound
    # runes: the sound of crafting them (Ramon, 2026-11-17); the combination runes have none and keep the thud
    **{f"{r} Rune": f"Crafting_{r.lower()}_runes" for r in ("Air", "Water", "Earth", "Fire", "Chaos", "Death", "Blood")},
    # prayers (Ramon, 2026-11-22): a buff prayer makes its sound as it goes up, a Protect prayer as it blocks
    "Protect from Missiles": "Protect from Ranged",   # the prayer's newer name in RuneScape
    # the three level-1 prayers have no recording of their own and borrow their family's top prayer (Ramon's call)
    "Burst of Strength": "Ultimate Strength",
    "Mystic Will": "Mystic Might",
    "Sharp Eye": "Eagle Eye",
    "Unholy Wrath": "Wrath",                          # chapter 3, Lord Daquarius's Zamorakian prayer (2026-11-22)
}
SOURCE_TYPES = (".ogg", ".mp3", ".wav", ".flac", ".m4a")
# Mono (the game pans a sound itself): the plain average of left and right, so the level stays as recorded (left
# to itself, ffmpeg adds 3 dB when it folds stereo down). Then the silence ahead of the sound comes off, so it
# starts with the cast. MP3 at VBR quality 5, about 64 kbit/s, with no tags and no version stamps, so the same
# sound comes out as the same bytes whichever ffmpeg made it.
MONO = "pan=mono|c0=0.5*c0+0.5*c1"
TRIM = "silenceremove=start_periods=1:start_threshold=-60dB"
ENCODE = ["-codec:a", "libmp3lame", "-q:a", "5", "-map_metadata", "-1", "-id3v2_version", "0", "-fflags", "+bitexact", "-flags:a", "+bitexact"]
SETTINGS = " ".join([MONO, TRIM] + ENCODE)


def sources():
    """file name without its ending, lower case -> path, for every sound in the folder (not its game/ copies)."""
    found = {}
    if os.path.isdir(SOUNDS):
        for f in sorted(os.listdir(SOUNDS)):
            stem, ext = os.path.splitext(f)
            if ext.lower() in SOURCE_TYPES and os.path.isfile(os.path.join(SOUNDS, f)):
                found.setdefault(stem.lower(), os.path.join(SOUNDS, f))
    return found


def game_copy(card, src):
    """The MP3 the game plays for a card: converted when it is missing, older than its source, or made another way."""
    os.makedirs(GAME, exist_ok=True)
    out = os.path.join(GAME, card + ".mp3")
    how = os.path.join(GAME, ".made-with")
    same_way = os.path.exists(how) and open(how, encoding="utf-8").read() == SETTINGS
    if os.path.exists(out) and same_way and os.path.getmtime(out) >= os.path.getmtime(src):
        return out
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        sys.exit(f"{os.path.basename(src)} is new or has changed and ffmpeg isn't installed to convert it. "
                 f"Install ffmpeg, or ask Claude to convert it; the sounds already in {GAME} are unchanged.")
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=channels", "-of", "csv=p=0", src],
                           capture_output=True, text=True, check=True).stdout.strip()
    fold = [MONO, TRIM] if probe == "2" else [TRIM]              # a mono recording is left as it is
    # made in the system's temporary folder and then written over the old copy in place: the art folder on
    # Ramon's PC may be somewhere a file can be rewritten but not deleted or renamed over
    with tempfile.TemporaryDirectory() as tmp:
        made = os.path.join(tmp, "sound.mp3")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-af", ",".join(fold)] + ENCODE + [made], check=True)
        with open(made, "rb") as f, open(out, "wb") as o:
            o.write(f.read())
    return out


def main():
    have = sources()
    packed, used = {}, set()
    for card in sorted(CARDS):
        key = ALIAS.get(card, card).lower()
        if key in have:
            packed[card] = have[key]
            used.add(key)
    files = {card: game_copy(card, src) for card, src in packed.items()}
    if files:
        open(os.path.join(GAME, ".made-with"), "w", encoding="utf-8").write(SETTINGS)
    data = {card: "data:audio/mpeg;base64," + base64.b64encode(open(f, "rb").read()).decode() for card, f in files.items()}
    js = "window.ARENA_SOUNDS = " + json.dumps(data, separators=(",", ":"), sort_keys=True) + ";"
    assert "</script" not in js.lower()
    open(os.path.join(HERE, "arena_sounds.js"), "w", encoding="utf-8").write(js)

    spells = sorted(n for n, c in CARDS.items() if c["kind"] == "spell")
    quiet = [n for n in spells if n not in data]
    runes = sorted(n for n, c in CARDS.items() if c["kind"] == "material" and n.endswith(" Rune"))
    spare = sorted(os.path.basename(p) for k, p in have.items() if k not in used)
    prayers = sorted(n for n, c in CARDS.items() if c["kind"] in ("buff", "prayer") and (c.get("prayer") or c.get("effect")))
    n_r, n_p = sum(n in data for n in runes), sum(n in data for n in prayers)
    print(f"{len(data)} sounds ({len(data) - n_r - n_p} spells, {n_r} runes, {n_p} prayers), {len(js) // 1024} KB packed, from {SOUNDS}")
    if not os.path.isdir(SOUNDS):
        print("  (no such folder: the game will use its synthesized spell sound for everything)")
    if data:
        print("  with a sound: " + ", ".join(sorted(data)))
    if quiet:
        print("  spells without one (they keep the synthesized sound): " + ", ".join(quiet))
    if [n for n in runes if n not in data]:
        print("  runes without one (they keep the card's thud): " + ", ".join(n for n in runes if n not in data))
    if [n for n in prayers if n not in data]:
        print("  prayers without one (they keep the card's thud, or the block sound): " + ", ".join(n for n in prayers if n not in data))
    if spare:
        print(f"  {len(spare)} sounds with no card of that name: " + ", ".join(spare))


if __name__ == "__main__":
    main()
