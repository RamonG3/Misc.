"""Card art for the game: same picks and framing as the print sheets, shrunk to small WebP thumbnails.
    python web/art_thumbs.py  ->  web/art.json  {card: {"src": data-uri, "mode": "scene|portrait|tall|item"}}"""
import base64, io, json, os, sys
from PIL import Image
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "print"))
import build_pnp as P

cards = json.load(open(os.path.join(HERE, "cards.json")))["cards"]
names = list(cards) + [c for c in P.CAPE_ART if c not in cards]
out = {}
for n in names:
    f = P.art_file(n)
    if not f or not os.path.exists(os.path.join(P.IMG, f)):
        continue
    im = Image.open(os.path.join(P.IMG, f))
    im = im.convert("RGBA") if im.mode in ("RGBA", "LA", "P") else im.convert("RGB")
    mode = "scene" if n in P.SCENE else "portrait" if n in P.PORTRAIT else "tall" if n in P.TALL else "item"
    w = 360 if mode in ("scene", "tall") else 240
    if im.width > w:
        im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
    if mode == "item" and im.height > 200:
        im = im.resize((round(im.width * 200 / im.height), 200), Image.LANCZOS)
    b = io.BytesIO()
    im.save(b, "WEBP", quality=80, method=6)
    out[n] = {"src": "data:image/webp;base64," + base64.b64encode(b.getvalue()).decode(), "mode": mode}
json.dump(out, open(os.path.join(HERE, "art.json"), "w"), separators=(",", ":"))
print(len(out), "cards with art,", sum(len(v["src"]) for v in out.values()) // 1024, "KB")
