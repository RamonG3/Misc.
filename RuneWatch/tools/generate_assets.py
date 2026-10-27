#!/usr/bin/env python3
"""Draws every image RuneWatch uses. No game assets: it is all shapes, gradients and Cinzel.

Run from anywhere:  python3 RuneWatch/tools/generate_assets.py
Needs Pillow and numpy. Writes PNGs into watchface/src/main/res/drawable/.
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "watchface" / "src" / "main" / "res"
OUT = RES / "drawable"
FONT = RES / "font" / "cinzel_bold.ttf"

SIZE = 450
SS = 4  # supersampling factor for small shapes

GOLD_HI = (255, 244, 200)
GOLD = (234, 192, 96)
GOLD_LO = (156, 102, 32)
GOLD_DARK = (88, 56, 16)
INK = (24, 15, 4)


# ---------------------------------------------------------------- helpers

def save(img, name):
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / f"{name}.png", optimize=True)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def vgrad(w, h, stops):
    """Vertical gradient. stops = [(pos 0..1, (r,g,b[,a])), ...]"""
    arr = np.zeros((h, w, 4), dtype=np.float32)
    ys = np.linspace(0, 1, h)
    stops = [(p, c if len(c) == 4 else (*c, 255)) for p, c in stops]
    for ch in range(4):
        arr[:, :, ch] = np.interp(ys, [p for p, _ in stops], [c[ch] for _, c in stops])[:, None]
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def fill_mask(mask, paint):
    """Paint (an RGBA image or a colour) through an L mask."""
    w, h = mask.size
    if not isinstance(paint, Image.Image):
        paint = Image.new("RGBA", (w, h), paint if len(paint) == 4 else (*paint, 255))
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    a = ImageChops.multiply(paint.getchannel("A"), mask)
    out.paste(paint, (0, 0))
    out.putalpha(a)
    return out


def dilate(mask, px):
    return mask.filter(ImageFilter.MaxFilter(px * 2 + 1)) if px > 0 else mask


def erode(mask, px):
    return mask.filter(ImageFilter.MinFilter(px * 2 + 1)) if px > 0 else mask


def shadow(mask, offset=(0, 3), blur=4, alpha=170):
    w, h = mask.size
    m = Image.new("L", (w, h), 0)
    m.paste(mask, offset)
    m = m.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: v * alpha // 255)
    return fill_mask(m, (0, 0, 0))


def down(img, w, h):
    return img.resize((w, h), Image.LANCZOS)


def gold_paint(w, h, top=0.0, bottom=1.0):
    """Polished gold: bright top, warm middle, dark bottom, with a soft shine band."""
    return vgrad(w, h, [
        (0.0, GOLD_HI),
        (top + (bottom - top) * 0.35, (250, 216, 128)),
        (top + (bottom - top) * 0.55, GOLD),
        (top + (bottom - top) * 0.62, (206, 150, 58)),
        (1.0, GOLD_LO),
    ])


def round_rect_mask(w, h, r, inset=0):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((inset, inset, w - 1 - inset, h - 1 - inset), r, fill=255)
    return m


def noise(w, h, amount, seed):
    rng = np.random.default_rng(seed)
    n = rng.normal(0, amount, (h, w)).astype(np.float32)
    return n


# ---------------------------------------------------------------- backgrounds

def compass_rose(size, color, alpha):
    """An 8-point compass star with alternating light and dark halves, minimap style."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = s / 2
    import math

    def star(points, r_out, r_in, rot, shade):
        for i in range(points):
            a = math.radians(rot + i * 360 / points)
            a_l = a - math.pi / points
            a_r = a + math.pi / points
            tip = (c + r_out * math.sin(a), c - r_out * math.cos(a))
            left = (c + r_in * math.sin(a_l), c - r_in * math.cos(a_l))
            right = (c + r_in * math.sin(a_r), c - r_in * math.cos(a_r))
            d.polygon([(c, c), tip, left], fill=(*color, int(alpha * shade[0])))
            d.polygon([(c, c), tip, right], fill=(*color, int(alpha * shade[1])))

    r = s * 0.44
    star(8, r * 0.62, r * 0.16, 22.5, (0.55, 0.3))
    star(4, r, r * 0.2, 0, (1.0, 0.55))
    for rr, wdt in ((r * 1.02, 2), (r * 0.70, 1), (r * 0.30, 1)):
        d.ellipse((c - rr, c - rr, c + rr, c + rr), outline=(*color, int(alpha * 0.55)), width=wdt * SS)
    return down(img, size, size)


def background(name, inner, outer, rose_color, rose_alpha, seed, tint_noise=5.0):
    w = h = SIZE
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # Light source slightly above centre, like the glow of an interface panel.
    dist = np.sqrt((xx - w / 2) ** 2 + (yy - h * 0.42) ** 2) / (w * 0.62)
    t = np.clip(dist, 0, 1) ** 1.25
    arr = np.zeros((h, w, 3), dtype=np.float32)
    for ch in range(3):
        arr[:, :, ch] = inner[ch] + (outer[ch] - inner[ch]) * t
    # Brushed-metal streaks + grain.
    streak = noise(w, 1, 1.0, seed)[0]
    streak = np.convolve(streak, np.ones(9) / 9, mode="same")
    arr += (streak[None, :] * tint_noise * 0.9)[:, :, None]
    arr += noise(w, h, tint_noise * 0.5, seed + 1)[:, :, None]
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    if rose_alpha:
        img.alpha_composite(compass_rose(400, rose_color, rose_alpha), (25, 25))
    save(img.convert("RGB"), name)


# ---------------------------------------------------------------- bezel

def bezel():
    import math
    s = SIZE * SS
    c = s / 2
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    r = np.sqrt((xx - c) ** 2 + (yy - c) ** 2) / SS
    ang = np.arctan2(yy - c, xx - c)

    outer, inner = 225.0, 212.0
    ring = ((r <= outer) & (r >= inner)).astype(np.float32)
    # Bevel profile across the ring: dark edges, bright ridge.
    prof = np.clip((r - inner) / (outer - inner), 0, 1)
    ridge = 1 - np.abs(prof - 0.42) * 1.9
    # Light from the top left.
    light = 0.62 + 0.38 * np.cos(ang - math.radians(-135))
    v = np.clip(ridge * 0.75 + 0.25, 0, 1) * light
    lo, hi = np.array(GOLD_DARK, np.float32), np.array(GOLD_HI, np.float32)
    mid = np.array(GOLD, np.float32)
    col = np.where(v[..., None] < 0.55,
                   lo + (mid - lo) * (v[..., None] / 0.55),
                   mid + (hi - mid) * ((v[..., None] - 0.55) / 0.45))
    arr = np.zeros((s, s, 4), np.float32)
    arr[..., :3] = col
    arr[..., 3] = ring * 255
    # Dark inner groove, then a thin gold lip.
    groove = ((r < inner) & (r >= inner - 2.2)).astype(np.float32)
    arr[..., :3] = np.where(groove[..., None] > 0, np.array(INK, np.float32), arr[..., :3])
    arr[..., 3] = np.maximum(arr[..., 3], groove * 255)
    lip = ((r < inner - 2.2) & (r >= inner - 3.6)).astype(np.float32)
    lipcol = np.array(GOLD_LO, np.float32) * (0.7 + 0.3 * light[..., None])
    arr[..., :3] = np.where(lip[..., None] > 0, lipcol, arr[..., :3])
    arr[..., 3] = np.maximum(arr[..., 3], lip * 200)
    # Soft inner shadow onto the face.
    shade = ((r < inner - 3.6) & (r > inner - 14)).astype(np.float32) * (1 - (inner - 3.6 - r) / 10.4)
    shade = np.clip(shade, 0, 1) ** 2 * 110
    arr[..., 3] = np.where(arr[..., 3] > 0, arr[..., 3], shade)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")

    # Studs at the hours, clasps with gems at 12/3/6/9.
    d = ImageDraw.Draw(img)
    for hour in range(12):
        a = math.radians(hour * 30)
        if hour % 3 == 0:
            continue
        rr = 200 * SS
        x, y = c + rr * math.sin(a), c - rr * math.cos(a)
        k = 3.2 * SS
        d.ellipse((x - k - SS, y - k + SS, x + k - SS, y + k + SS), fill=(0, 0, 0, 120))
        d.ellipse((x - k, y - k, x + k, y + k), fill=GOLD_LO)
        d.ellipse((x - k * 0.75, y - k * 0.85, x + k * 0.55, y + k * 0.45), fill=GOLD)
        d.ellipse((x - k * 0.45, y - k * 0.6, x - k * 0.05, y - k * 0.2), fill=GOLD_HI)
    gems = {0: ((120, 210, 255), (20, 90, 170)), 3: ((255, 120, 110), (140, 20, 20)),
            6: ((140, 255, 170), (20, 120, 50)), 9: ((230, 160, 255), (100, 30, 150))}
    for hour, (g_hi, g_lo) in gems.items():
        a = math.radians(hour * 30)
        rr = 218.5 * SS
        x, y = c + rr * math.sin(a), c - rr * math.cos(a)
        ca, sa = math.cos(a), math.sin(a)

        def pt(u, v):  # u along tangent, v along radius (outwards)
            return (x + u * ca + v * sa, y + u * sa - v * ca)

        L, W = 13 * SS, 8 * SS
        d.polygon([pt(0, W + 2 * SS), pt(L + 2 * SS, 0), pt(0, -W - 2 * SS), pt(-L - 2 * SS, 0)], fill=INK)
        d.polygon([pt(0, W), pt(L, 0), pt(0, -W), pt(-L, 0)], fill=GOLD)
        d.polygon([pt(0, W), pt(-L, 0), pt(0, 0)], fill=GOLD_HI)
        d.polygon([pt(0, -W), pt(L, 0), pt(0, 0)], fill=GOLD_LO)
        g = 4.2 * SS
        d.ellipse((x - g, y - g, x + g, y + g), fill=INK)
        g2 = 3.3 * SS
        d.ellipse((x - g2, y - g2, x + g2, y + g2), fill=g_lo)
        d.ellipse((x - g2 * 0.8, y - g2 * 0.85, x + g2 * 0.3, y + g2 * 0.2), fill=g_hi)
        d.ellipse((x - g2 * 0.55, y - g2 * 0.65, x - g2 * 0.15, y - g2 * 0.25), fill=(255, 255, 255))
    save(down(img, SIZE, SIZE), "bezel")


# ---------------------------------------------------------------- panels

def plaque(name, w, h, r=7, studs=True):
    """RS3-style interface panel: dark glass body, gold rim, studs on the ends."""
    W, H = w * SS, h * SS
    pad = 6 * SS
    cw, ch = W + pad * 2, H + pad * 2
    img = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    outer = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(outer).rounded_rectangle((pad, pad, pad + W - 1, pad + H - 1), r * SS, fill=255)
    img.alpha_composite(shadow(outer, (0, 2 * SS), 3 * SS, 150))
    img.alpha_composite(fill_mask(outer, gold_paint(cw, ch, pad / ch, (pad + H) / ch)))
    rim = 2 * SS
    inner = erode(outer, rim)
    img.alpha_composite(fill_mask(inner, INK))
    body = erode(outer, rim + SS)
    img.alpha_composite(fill_mask(body, vgrad(cw, ch, [(0, (34, 48, 58, 238)), (0.5, (18, 27, 33, 238)), (1, (10, 15, 19, 242))])))
    # Glassy highlight along the top inside edge.
    hl = ImageChops.subtract(body, ImageChops.offset(body, 0, 2 * SS))
    img.alpha_composite(fill_mask(hl.point(lambda v: v * 90 // 255), (160, 200, 220)))
    if studs:
        d = ImageDraw.Draw(img)
        for x in (pad + 1 * SS, pad + W - 1 * SS):
            y = pad + H / 2
            k = 5 * SS
            d.polygon([(x, y - k - SS), (x + k + SS, y), (x, y + k + SS), (x - k - SS, y)], fill=INK)
            d.polygon([(x, y - k), (x + k, y), (x, y + k), (x - k, y)], fill=GOLD)
            d.polygon([(x, y - k), (x - k, y), (x, y)], fill=GOLD_HI)
            d.polygon([(x, y + k), (x + k, y), (x, y)], fill=GOLD_LO)
    save(down(img, w + 12, h + 12), name)


def bar_track(name, w, h):
    W, H = w * SS, h * SS
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    m = round_rect_mask(W, H, 4 * SS)
    img.alpha_composite(fill_mask(m, (120, 92, 44)))
    inner = erode(m, SS)
    img.alpha_composite(fill_mask(inner, vgrad(W, H, [(0, (4, 5, 6)), (1, (18, 22, 26))])))
    save(down(img, w, h), name)


def bar_gloss(name, w, h):
    """Glossy overlay drawn on top of a bar fill."""
    W, H = w * SS, h * SS
    m = round_rect_mask(W, H, 3 * SS)
    paint = vgrad(W, H, [(0, (255, 255, 255, 120)), (0.45, (255, 255, 255, 28)), (0.5, (255, 255, 255, 0)),
                         (0.85, (0, 0, 0, 0)), (1, (0, 0, 0, 70))])
    img = fill_mask(m, paint)
    # Segment ticks every 10%, like the notches on the game's resource bars.
    d = ImageDraw.Draw(img)
    for i in range(1, 10):
        x = W * i / 10
        d.line((x, H * 0.15, x, H * 0.85), fill=(0, 0, 0, 55), width=SS)
    save(down(img, w, h), name)


def glow(name, w, h, color, spread=7):
    W, H = w + spread * 4, h + spread * 4
    m = Image.new("L", (W, H), 0)
    ImageDraw.Draw(m).rounded_rectangle((spread * 2, spread * 2, spread * 2 + w, spread * 2 + h), 6, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(spread))
    save(fill_mask(m, color), name)


# ---------------------------------------------------------------- icons

def heart(d, s):
    import math
    pts = []
    for i in range(200):
        t = math.pi * 2 * i / 200
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((s / 2 + x * s / 38, s / 2 - y * s / 38 - s * 0.02))
    return pts


def icon_canvas(size):
    return Image.new("L", (size * SS, size * SS), 0)


def finish_icon(mask, paint_stops, size, outline=2, highlight=True):
    S_ = size * SS
    img = Image.new("RGBA", (S_, S_), (0, 0, 0, 0))
    ol = dilate(mask, outline * SS)
    img.alpha_composite(fill_mask(ol, INK))
    img.alpha_composite(fill_mask(mask, vgrad(S_, S_, paint_stops)))
    if highlight:
        hl = ImageChops.subtract(mask, ImageChops.offset(mask, SS, 2 * SS))
        img.alpha_composite(fill_mask(hl.point(lambda v: v * 150 // 255), (255, 255, 255)))
    return down(img, size, size)


def icon_heart(size):
    m = icon_canvas(size)
    ImageDraw.Draw(m).polygon(heart(None, size * SS), fill=255)
    return finish_icon(m, [(0.15, (255, 120, 100)), (0.6, (214, 40, 28)), (0.95, (120, 10, 6))], size)


def icon_prayer(size):
    import math
    m = icon_canvas(size)
    d = ImageDraw.Draw(m)
    S_ = size * SS
    c = S_ / 2
    pts = []
    for i in range(16):
        a = math.radians(i * 22.5)
        rr = S_ * (0.47 if i % 4 == 0 else (0.2 if i % 2 == 0 else 0.13))
        pts.append((c + rr * math.sin(a), c - rr * math.cos(a)))
    d.polygon(pts, fill=255)
    d.ellipse((c - S_ * 0.14, c - S_ * 0.14, c + S_ * 0.14, c + S_ * 0.14), fill=255)
    return finish_icon(m, [(0.1, (235, 250, 255)), (0.5, (120, 200, 250)), (1, (30, 110, 200))], size)


def icon_bolt(size):
    m = icon_canvas(size)
    S_ = size * SS
    p = [(0.58, 0.03), (0.2, 0.55), (0.46, 0.55), (0.36, 0.97), (0.8, 0.4), (0.53, 0.4), (0.68, 0.03)]
    ImageDraw.Draw(m).polygon([(x * S_, y * S_) for x, y in p], fill=255)
    return finish_icon(m, [(0.05, (255, 250, 200)), (0.5, (255, 200, 60)), (1, (220, 110, 10))], size)


def icon_runner(size):
    """A little running figure for the Agility XP tracker."""
    m = icon_canvas(size)
    d = ImageDraw.Draw(m)
    S_ = size * SS
    u = S_ / 32

    def line(pts, w):
        d.line([(x * u, y * u) for x, y in pts], fill=255, width=int(w * u), joint="curve")
        for x, y in pts:
            r = w * u / 2
            d.ellipse((x * u - r, y * u - r, x * u + r, y * u + r), fill=255)

    d.ellipse((17 * u, 2.5 * u, 23.5 * u, 9 * u), fill=255)            # head
    line([(18.5, 11), (14.5, 19)], 5)                                    # torso
    line([(17.5, 12.5), (22, 15.5), (26, 13)], 3)                        # front arm
    line([(17, 13), (12.5, 13.5), (9.5, 17)], 3)                         # back arm
    line([(14.5, 19), (20, 22.5), (19, 29)], 3.4)                        # front leg
    line([(14.5, 19), (11, 24.5), (5.5, 25.5)], 3.4)                     # back leg
    return finish_icon(m, [(0, (240, 250, 255)), (0.6, (160, 210, 245)), (1, (80, 140, 200))], size)


def badge(name, size, icon):
    """Round gold-rimmed badge holding an icon (sits at the left end of a bar)."""
    S_ = size * SS
    img = Image.new("RGBA", (S_, S_), (0, 0, 0, 0))
    c = S_ / 2
    full = Image.new("L", (S_, S_), 0)
    ImageDraw.Draw(full).ellipse((SS, SS, S_ - SS, S_ - SS), fill=255)
    img.alpha_composite(shadow(full, (0, SS), 2 * SS, 140))
    img.alpha_composite(fill_mask(full, gold_paint(S_, S_)))
    inner = erode(full, 2 * SS)
    img.alpha_composite(fill_mask(inner, INK))
    body = erode(full, 3 * SS)
    yy, xx = np.mgrid[0:S_, 0:S_].astype(np.float32)
    rr = np.sqrt((xx - c) ** 2 + (yy - c * 0.8) ** 2) / c
    rad = np.zeros((S_, S_, 4), np.float32)
    for ch, (a, b) in enumerate(zip((48, 66, 78), (10, 14, 18))):
        rad[..., ch] = a + (b - a) * np.clip(rr, 0, 1)
    rad[..., 3] = 255
    img.alpha_composite(fill_mask(body, Image.fromarray(rad.astype(np.uint8), "RGBA")))
    small = down(img, size, size)
    isz = int(size * 0.62)
    small.alpha_composite(icon(isz), ((size - isz) // 2, (size - isz) // 2))
    save(small, name)


def ability_slot(name, size, key):
    """An action-bar slot. Complications render inside it."""
    S_ = size * SS
    img = Image.new("RGBA", (S_, S_), (0, 0, 0, 0))
    m = round_rect_mask(S_, S_, 5 * SS, SS)
    img.alpha_composite(shadow(m, (0, SS), 2 * SS, 150))
    img.alpha_composite(fill_mask(m, gold_paint(S_, S_)))
    img.alpha_composite(fill_mask(erode(m, 2 * SS), INK))
    body = erode(m, 3 * SS)
    img.alpha_composite(fill_mask(body, vgrad(S_, S_, [(0, (30, 42, 50)), (1, (8, 12, 15))])))
    hl = ImageChops.subtract(body, ImageChops.offset(body, 0, 2 * SS))
    img.alpha_composite(fill_mask(hl.point(lambda v: v * 70 // 255), (170, 210, 230)))
    small = down(img, size, size)
    # Keybind number in the corner, like the real action bar.
    f = ImageFont.truetype(str(FONT), 11)
    d = ImageDraw.Draw(small)
    d.text((6, 4), key, font=f, fill=(0, 0, 0, 200))
    d.text((5, 3), key, font=f, fill=(225, 215, 190, 230))
    save(small, name)


# ---------------------------------------------------------------- digits

DIGIT_SIZE = 100      # font size the glyphs are rendered at
CELL_H = 100          # bitmap font character height (also the BitmapFont size in the XML)
BASELINE = 86         # baseline position inside the cell


def glyph_mask(ch, cell_w):
    f = ImageFont.truetype(str(FONT), DIGIT_SIZE * SS)
    W, H = cell_w * SS, CELL_H * SS
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    adv = f.getlength(ch)
    d.text(((W - adv) / 2, BASELINE * SS), ch, font=f, fill=255, anchor="ls")
    return m


def digit_gold(ch, cell_w):
    m = glyph_mask(ch, cell_w)
    W, H = m.size
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ol = dilate(m, 3 * SS)
    img.alpha_composite(shadow(ol, (SS, 4 * SS), 3 * SS, 200))
    img.alpha_composite(fill_mask(ol, INK))
    cap_top = (BASELINE - 0.72 * DIGIT_SIZE) / CELL_H
    img.alpha_composite(fill_mask(m, gold_paint(W, H, cap_top, BASELINE / CELL_H)))
    # Bevel: bright top edges, dark bottom edges.
    hi = ImageChops.subtract(m, ImageChops.offset(m, 0, 3 * SS))
    img.alpha_composite(fill_mask(hi.point(lambda v: v * 200 // 255), GOLD_HI))
    lo = ImageChops.subtract(m, ImageChops.offset(m, 0, -3 * SS))
    img.alpha_composite(fill_mask(lo.point(lambda v: v * 150 // 255), GOLD_DARK))
    return down(img, cell_w, CELL_H)


def digit_ambient(ch, cell_w):
    """Hollow outline digits for always-on mode: few lit pixels."""
    m = glyph_mask(ch, cell_w)
    ring = ImageChops.subtract(m, erode(m, 3 * SS))
    img = fill_mask(ring, (226, 190, 110))
    return down(img, cell_w, CELL_H)


def digit_width(ch):
    """Proportional cell width: the glyph's advance plus room for the outline."""
    f = ImageFont.truetype(str(FONT), DIGIT_SIZE)
    return int(round(f.getlength(ch))) + 8


def digits():
    lines = []
    for ch, key in [(str(i), str(i)) for i in range(10)] + [(":", "colon")]:
        w = digit_width(ch)
        save(digit_gold(ch, w), f"gold_{key}")
        save(digit_ambient(ch, w), f"amb_{key}")
        lines.append((ch, key, w))
    # These sizes must match the <BitmapFont> entries in watchface.xml.
    for font in ("gold", "amb"):
        print(f'    <BitmapFont name="{font}_digits">')
        for ch, key, w in lines:
            print(f'      <Character name="{ch}" resource="{font}_{key}" width="{w}" height="{CELL_H}"/>')
        print("    </BitmapFont>")


# ---------------------------------------------------------------- launcher icon

def app_icon():
    size = 192
    S_ = size * SS
    img = Image.new("RGBA", (S_, S_), (0, 0, 0, 0))
    full = Image.new("L", (S_, S_), 0)
    ImageDraw.Draw(full).ellipse((0, 0, S_ - 1, S_ - 1), fill=255)
    img.alpha_composite(fill_mask(full, gold_paint(S_, S_)))
    inner = erode(full, 10 * SS)
    img.alpha_composite(fill_mask(inner, vgrad(S_, S_, [(0, (40, 60, 72)), (1, (10, 15, 19))])))
    small = down(img, size, size)
    small.alpha_composite(compass_rose(150, (234, 192, 96), 255), (21, 21))
    save(small, "ic_launcher")


def main():
    background("bg_steel", (40, 58, 70), (7, 11, 14), (150, 190, 210), 26, seed=1)
    background("bg_legacy", (78, 64, 44), (14, 10, 6), (220, 190, 130), 30, seed=2, tint_noise=6)
    background("bg_abyss", (84, 18, 30), (10, 2, 6), (255, 120, 140), 24, seed=3)
    bezel()
    plaque("plaque_date", 172, 32)
    plaque("plaque_xp", 252, 46)
    plaque("plaque_bars", 318, 76, r=9)
    bar_track("track_half", 126, 26)
    bar_track("track_full", 275, 26)
    bar_gloss("gloss_half", 122, 22)
    bar_gloss("gloss_full", 271, 22)
    glow("glow_full", 275, 26, (255, 200, 60, 255))
    badge("badge_lp", 34, icon_heart)
    badge("badge_prayer", 34, icon_prayer)
    badge("badge_adren", 34, icon_bolt)
    save(icon_runner(30), "icon_agility")
    ability_slot("slot_1", 58, "1")
    ability_slot("slot_2", 58, "2")
    digits()
    app_icon()
    print("wrote", len(list(OUT.glob("*.png"))), "images to", OUT)


if __name__ == "__main__":
    main()
