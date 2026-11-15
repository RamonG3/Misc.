"""Build the Arena client: python web/build_arena.py  ->  web/dist/arena.html

One self-contained file, like dist/game.html: card data, art, fonts, the engine and the bot are all inlined.
Run python web/arena_assets.py first when art or fonts change (this script runs it if its output is missing).
"""
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
if not os.path.exists(os.path.join(HERE, "arena_assets.js")) or not os.path.exists(os.path.join(HERE, "arena_fonts.css")):
    subprocess.run(["python3", os.path.join(HERE, "arena_assets.py")], check=True)

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
}
for k, v in parts.items():
    assert src.count(k) == 1, k
    assert "</script" not in v.lower() and "</style" not in v.lower(), k
    src = src.replace(k, v)
os.makedirs(os.path.join(HERE, "dist"), exist_ok=True)
out = os.path.join(HERE, "dist", "arena.html")
open(out, "w").write(src)
print(out, len(src) // 1024, "KB")
