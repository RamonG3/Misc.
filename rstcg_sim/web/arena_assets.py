"""Assets for the Arena client, embedded so dist/arena.html is one self-contained file.

    python web/arena_assets.py  ->  web/arena_assets.js   (window.ARENA_ASSETS = {...})
                                    web/arena_fonts.css   (@font-face with the print fonts)

Same art picks and framing as the print sheets (print/build_pnp.py), but bigger than the old game's
thumbnails because the Arena shows cards at print size on hover.
"""
import base64
import io
import json
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "print"))
import build_pnp as P  # noqa: E402

CARDS = json.load(open(os.path.join(HERE, "cards.json")))["cards"]
SET1 = json.load(open(os.path.join(ROOT, "print", "set1.json")))
OWN = os.path.join(HERE, "arena_img")


def webp(im, quality=82):
    b = io.BytesIO()
    im.save(b, "WEBP", quality=quality, method=6)
    return "data:image/webp;base64," + base64.b64encode(b.getvalue()).decode()


def load(path):
    im = Image.open(path)
    return im.convert("RGBA") if im.mode in ("RGBA", "LA", "P") else im.convert("RGB")


def fit(im, w, h):
    if im.width > w or im.height > h:
        im = im.copy()
        im.thumbnail((w, h), Image.LANCZOS)
    return im


def trim(im):
    return im.crop(im.getbbox()) if im.mode == "RGBA" and im.getbbox() else im


# ---------- card art ----------
art = {}
for n in list(CARDS) + [c for c in P.CAPE_ART if c not in CARDS]:
    f = P.art_file(n)
    if not f or not os.path.exists(os.path.join(P.IMG, f)):
        continue
    path = os.path.join(P.IMG, f)
    im = load(path)
    small = P.is_pixel_icon(n, f)                 # a tiny wiki icon (spells, prayers): told by its size in pixels
    mode = ("scene" if n in P.SCENE else "portrait" if n in P.PORTRAIT else "tall" if n in P.TALL
            else "px" if small else "item")
    if mode == "px":
        pass                                       # pixel icons stay tiny and are scaled up crisp
    elif mode in ("scene", "tall", "portrait"):
        im = fit(im, 560, 560)
    else:
        im = fit(trim(im), 320, 220)
    art[n] = {"src": webp(im), "mode": mode}

# ---------- capes: full cape pictures, or the skill icon ----------
cape_pic = {}
for cape in json.load(open(os.path.join(HERE, "cards.json")))["capes"]:
    f = P.CAPE_PIC.get(cape)
    skill = cape.replace(" Cape", "")
    icon = os.path.join(P.IMG, f"{skill}_detail.png")
    if f and os.path.exists(os.path.join(P.IMG, f)):
        cape_pic[cape] = {"src": webp(fit(trim(load(os.path.join(P.IMG, f))), 220, 420)), "mode": "cape"}
    elif os.path.exists(icon):
        cape_pic[cape] = {"src": webp(fit(trim(load(icon)), 120, 120)), "mode": "icon"}

# ---------- Location backdrops: the board's background while that Location is in play ----------
BACKDROP = {"Wilderness": "wilderness.png", "Varrock": "varrock.jpg", "Grand Exchange": "grand_exchange.jpg",
            "Wizards' Tower": "wizards_tower.jpg", "Lumbridge": "lumbridge_wide.jpg",
            # Story Mode's boss Locations (2026-11-18): Count Draynor's cellar, and the stone circle
            "Draynor Manor": "draynor_manor_cellar.png", "Moonlit Circle": "scene_stone_circle.png"}
backdrop = {}
for loc, f in BACKDROP.items():
    p = os.path.join(P.IMG, f)
    if not os.path.exists(p):
        p = os.path.join(ROOT, "art", "scenes", f)
    if os.path.exists(p):
        im = load(p).convert("RGB")
        backdrop[loc] = webp(fit(im, 1280, 1280), 68)

# ---------- Story Mode (STORY_BRIEF.md): the places scenes happen in, and who speaks ----------
# art/scenes/<file>: a painting behind the dialogue box, by the scene's name in web/story.js.
# art/faces/<speaker>.png: a chathead, by the speaker's face name in web/story.js (square, see-through).
STORY_SCENE = {"Lumbridge Swamp": "scene_lumbridge_swamp.png", "Draynor Village": "scene_draynor_village.png",
               "Draynor Manor": "scene_draynor_manor_outside.png", "Draynor Manor stairs": "scene_draynor_manor_hall.png",
               "Draynor Manor cellar": "scene_draynor_manor_cellar.png", "The stone circle": "scene_stone_circle.png"}
SCENES = os.path.join(ROOT, "art", "scenes")
FACES = os.path.join(ROOT, "art", "faces")
scene = {}
for name, f in STORY_SCENE.items():
    p = os.path.join(SCENES, f)
    if os.path.exists(p):
        scene[name] = webp(fit(load(p).convert("RGB"), 1280, 1280), 68)
face = {}
if os.path.isdir(FACES):
    for f in sorted(os.listdir(FACES)):
        if f.lower().endswith(".png"):
            face[os.path.splitext(f)[0]] = webp(fit(load(os.path.join(FACES, f)), 256, 256), 85)

# ---------- icons ----------
icons = {}
for k, f in {"melee": "Attack_detail.png", "ranged": "Ranged_detail.png", "magic": "Magic_detail.png"}.items():
    icons[k] = webp(fit(trim(load(os.path.join(P.IMG, f))), 96, 96))
for k, f in {"punch": "punch.png", "quest": "quest.png", "gp50": "gp_50_scattered.png", "gp100": "gp_100_stacked.png"}.items():
    p = os.path.join(OWN, f)
    if os.path.exists(p):
        icons[k] = webp(fit(trim(load(p)), 400, 400))
# the logo inside the Crest card back
back = open(os.path.join(ROOT, "print", "back_crest.html")).read()
uris = re.findall(r'src="(data:image/[a-z]+;base64,[^"]+)"', back)
if uris:
    head, data = uris[0].split(",", 1)
    icons["logo"] = webp(fit(load(io.BytesIO(base64.b64decode(data))), 600, 200))

out = {"art": art, "cape": cape_pic, "backdrop": backdrop, "icons": icons, "scene": scene, "face": face,
       "set1": {n: {"num": v["num"], "rarity": v["rarity"]} for n, v in SET1.items()},
       "frame": P.FRAME, "matColor": P.MAT_COLOR}
js = "window.ARENA_ASSETS = " + json.dumps(out, separators=(",", ":")) + ";"
open(os.path.join(HERE, "arena_assets.js"), "w").write(js)

# ---------- fonts ----------
FONTS = os.path.join(ROOT, "print", "fonts")
ff = [("Cinzel", 600, "normal", "fontsource-cinzel-5.2.8/files/cinzel-latin-600-normal.woff2"),
      ("Cinzel", 700, "normal", "fontsource-cinzel-5.2.8/files/cinzel-latin-700-normal.woff2"),
      ("Alegreya Sans", 400, "normal", "fontsource-alegreya-sans-5.2.8/files/alegreya-sans-latin-400-normal.woff2"),
      ("Alegreya Sans", 400, "italic", "fontsource-alegreya-sans-5.2.8/files/alegreya-sans-latin-400-italic.woff2"),
      ("Alegreya Sans", 500, "normal", "fontsource-alegreya-sans-5.2.8/files/alegreya-sans-latin-500-normal.woff2"),
      ("Alegreya Sans", 700, "normal", "fontsource-alegreya-sans-5.2.8/files/alegreya-sans-latin-700-normal.woff2")]
css = "".join(
    f"@font-face{{font-family:'{f}';font-weight:{w};font-style:{s};font-display:block;"
    f"src:url(data:font/woff2;base64,{base64.b64encode(open(os.path.join(FONTS, p), 'rb').read()).decode()}) format('woff2')}}"
    for f, w, s, p in ff if os.path.exists(os.path.join(FONTS, p)))
open(os.path.join(HERE, "arena_fonts.css"), "w").write(css)

print(f"{len(art)} card arts, {len(cape_pic)} capes, {len(backdrop)} backdrops, {len(icons)} icons, "
      f"{len(scene)} story scenes, {len(face)} chatheads, "
      f"{len(js) // 1024} KB assets, {len(css) // 1024} KB fonts")
