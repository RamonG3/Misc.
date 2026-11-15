"""Build the Arena client: python web/build_arena.py  ->  web/dist/arena.html

One self-contained file, like dist/game.html: card data, art, fonts, the engine and the bot are all inlined.
Run python web/arena_assets.py first when art or fonts change (this script runs it if its output is missing).
"""
import json
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
if not os.path.exists(os.path.join(HERE, "arena_assets.js")) or not os.path.exists(os.path.join(HERE, "arena_fonts.css")):
    subprocess.run(["python3", os.path.join(HERE, "arena_assets.py")], check=True)

def read(name):
    return open(os.path.join(HERE, name)).read()


def engine_without_waiting(src):
    """engine.js with its async/await words taken out: the same rules, run synchronously, for the hard bot's playouts."""
    src = re.sub(r"\bawait\s+", "", re.sub(r"\basync\s+", "", src))
    return ("(function () {\n  var module = { exports: {} };\n  (function (window) {\n" + src +
            "\n  })({});\n  window.RSTCGSync = module.exports;\n})();")


src = open(os.path.join(HERE, "arena.src.html")).read()
data = json.load(open(os.path.join(HERE, "cards.json")))
parts = {
    "/*__FONTS__*/": open(os.path.join(HERE, "arena_fonts.css")).read(),
    "/*__DATA__*/": "window.RSTCG_DATA = " + json.dumps(data, separators=(",", ":")) + ";",
    "/*__ASSETS__*/": open(os.path.join(HERE, "arena_assets.js")).read(),
    "/*__ENGINE__*/": open(os.path.join(HERE, "engine.js")).read(),
    "/*__BOT__*/": open(os.path.join(HERE, "bot.js")).read(),
    "/*__AUDIO__*/": open(os.path.join(HERE, "arena_audio.js")).read(),
    "/*__WIZ__*/": open(os.path.join(HERE, "deckwiz.js")).read(),
    "/*__ENGINESYNC__*/": engine_without_waiting(read("engine.js")),
    "/*__BOTLEVELS__*/": read("bot_levels.js"),
}
for k, v in parts.items():
    assert src.count(k) == 1, k
    assert "</script" not in v.lower() and "</style" not in v.lower(), k
    src = src.replace(k, v)
os.makedirs(os.path.join(HERE, "dist"), exist_ok=True)
out = os.path.join(HERE, "dist", "arena.html")
open(out, "w").write(src)
print(out, len(src) // 1024, "KB")
