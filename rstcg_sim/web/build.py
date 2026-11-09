"""Build the single-file game page: python web/build.py  ->  web/dist/game.html"""
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
subprocess.run(["python3", os.path.join(HERE, "..", "export_cards.py")], check=True, cwd=os.path.join(HERE, ".."))
subprocess.run(["python3", os.path.join(HERE, "art_thumbs.py")], check=True)
src = open(os.path.join(HERE, "game.src.html")).read()
data = json.load(open(os.path.join(HERE, "cards.json")))
parts = {
    "/*__DATA__*/": "window.RSTCG_DATA = " + json.dumps(data, separators=(",", ":")) + ";\nwindow.RSTCG_ART = " + open(os.path.join(HERE, "art.json")).read() + ";",
    "/*__ENGINE__*/": open(os.path.join(HERE, "engine.js")).read(),
    "/*__BOT__*/": open(os.path.join(HERE, "bot.js")).read(),
}
for k, v in parts.items():
    assert src.count(k) == 1, k
    assert "</script" not in v.lower(), k
    src = src.replace(k, v)
os.makedirs(os.path.join(HERE, "dist"), exist_ok=True)
out = os.path.join(HERE, "dist", "game.html")
open(out, "w").write(src)
print(out, len(src) // 1024, "KB")
