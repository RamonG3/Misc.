"""Recover the generated build inputs (arena_fonts.css, arena_assets.js, arena_sounds.js) from a built Arena page.

build_arena.py replaces each /*__NAME__*/ placeholder in arena.src.html with a file's contents. Given the
source and a built page (web/dist/arena.html, or the published claude.ai artifact saved as HTML), this
works backwards: the text between placeholders is fixed, so whatever sits between those fixed pieces in
the built page is what was inlined.

    python web/recover_generated.py path/to/built.html            # writes web/arena_fonts.css, arena_assets.js, arena_sounds.js
    python web/recover_generated.py path/to/built.html --all      # also writes the other inlined parts to web/recovered/ to compare

The published artifact has its <!doctype>/<html>/<head>/<body>/<meta> lines stripped; that's handled.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "arena.src.html"), encoding="utf-8").read()
built = open(sys.argv[1], encoding="utf-8").read()
names = re.findall(r"/\*__([A-Z]+)__\*/", src)
pieces = re.split(r"/\*__[A-Z]+__\*/", src)

def find(hay, needle, start):
    """Find a fixed piece; if the page lost its doctype/head lines, fall back to the piece's longest line run."""
    i = hay.find(needle, start)
    if i >= 0:
        return i, i + len(needle)
    lines = [l for l in needle.split("\n") if not re.match(r"\s*(<!doctype|<html|</html>|<head>|</head>|<body|</body>|<meta )", l, re.I)]
    core = "\n".join(lines).strip("\n")
    i = hay.find(core, start)
    if i < 0:
        sys.exit(f"could not line up the built page with arena.src.html near: {needle[:80]!r}")
    return i, i + len(core)

out, pos = {}, 0
_, pos = find(built, pieces[0], 0)
for name, nxt in zip(names, pieces[1:]):
    if nxt.strip() == "":
        out[name] = built[pos:]
        break
    # the next fixed piece is short and may repeat; anchor on its first 200 characters
    j, _ = find(built, nxt[:200] if len(nxt) > 200 else nxt, pos)
    out[name] = built[pos:j]
    _, pos = find(built, nxt, j)

targets = {"FONTS": "arena_fonts.css", "ASSETS": "arena_assets.js", "SOUNDS": "arena_sounds.js"}
for name, fn in targets.items():
    if name not in out:              # a page built before that part existed (spell sounds came in November 2026)
        print(f"web/{fn}: not in this page; left alone")
        continue
    open(os.path.join(HERE, fn), "w", encoding="utf-8").write(out[name])
    print(f"wrote web/{fn} ({len(out[name]) // 1024} KB)")
if "--all" in sys.argv:
    d = os.path.join(HERE, "recovered")
    os.makedirs(d, exist_ok=True)
    for name, v in out.items():
        open(os.path.join(d, name.lower() + ".txt"), "w", encoding="utf-8").write(v)
    print("other parts in web/recovered/:", ", ".join(n for n in out if n not in targets))
