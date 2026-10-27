#!/usr/bin/env python3
"""A tiny Watch Face Format renderer, just big enough for RuneWatch's watchface.xml.

It reads the real XML, evaluates the real expressions against sample data and draws
the result with Pillow. Two jobs:
  * make the picker preview (res/drawable/preview.png) and the README screenshots;
  * catch expression typos, unknown data sources and missing images before they
    reach a watch, where the only symptom is a blank space.

It is an approximation (fonts and text metrics differ a little from Wear OS), not a
replacement for testing on a device.

    python3 RuneWatch/tools/render_preview.py            # writes previews
    python3 RuneWatch/tools/render_preview.py --check    # only checks, exits 1 on problems
"""
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "watchface" / "src" / "main" / "res"
XML = RES / "raw" / "watchface.xml"
DOCS = ROOT / "docs"
SYSTEM_FONTS = {
    "NORMAL": ["/usr/share/fonts/opentype/inter/Inter-Regular.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
    "BOLD": ["/usr/share/fonts/opentype/inter/Inter-Bold.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
}
SS = 3  # supersampling for vector shapes

# Every data source WFF v1 defines (minus a few we will never need). Using anything
# else in an expression is reported as an error.
KNOWN_SOURCES = set("""
MILLISECOND SECOND SECOND_Z MINUTE MINUTE_Z AMPM_STATE DAY DAY_Z MONTH MONTH_Z MONTH_F MONTH_S YEAR YEAR_S
UTC_TIMESTAMP SECOND_MILLISECOND SECONDS_IN_DAY MINUTE_SECOND HOUR_0_11 HOUR_0_11_Z HOUR_0_11_MINUTE HOUR_1_12
HOUR_1_12_Z HOUR_1_12_MINUTE HOUR_0_23 HOUR_0_23_Z HOUR_0_23_MINUTE HOUR_1_24 HOUR_1_24_Z HOUR_1_24_MINUTE
DAY_HOUR DAY_0_30 DAY_0_30_HOUR DAY_OF_YEAR DAY_OF_WEEK DAY_OF_WEEK_F DAY_OF_WEEK_S DAYS_IN_MONTH MONTH_DAY
MONTH_0_11 MONTH_0_11_DAY YEAR_MONTH YEAR_MONTH_DAY WEEK_IN_MONTH WEEK_IN_YEAR IS_24_HOUR_MODE
IS_DAYLIGHT_SAVING_TIME TIMEZONE TIMEZONE_ABB TIMEZONE_ID TIMEZONE_OFFSET TIMEZONE_OFFSET_DST AMPM_POSITION
AMPM_STRING AMPM_STRING_ENG AMPM_STRING_SHORT LANGUAGE_CODE LANGUAGE_COUNTRY_CODE LANGUAGE_LOCALE_NAME
LANGUAGE_TEXT_DIRECTION BATTERY_PERCENT BATTERY_CHARGING_STATUS BATTERY_IS_LOW BATTERY_TEMPERATURE_CELSIUS
BATTERY_TEMPERATURE_FAHRENHEIT MOON_PHASE_POSITION MOON_PHASE_TYPE MOON_PHASE_TYPE_STRING STEP_COUNT STEP_GOAL
STEP_PERCENT HEART_RATE HEART_RATE_Z UNREAD_NOTIFICATION_COUNT ACCELEROMETER_IS_SUPPORTED ACCELEROMETER_X
ACCELEROMETER_Y ACCELEROMETER_Z ACCELEROMETER_ANGLE_X ACCELEROMETER_ANGLE_Y ACCELEROMETER_ANGLE_Z
ACCELEROMETER_ANGLE_XY
""".split())
COMPLICATION_FIELDS = set("""TEXT TITLE MONOCHROMATIC_IMAGE SMALL_IMAGE PHOTO_IMAGE RANGED_VALUE_VALUE
RANGED_VALUE_MIN RANGED_VALUE_MAX""".split())

problems = []


def problem(msg):
    if msg not in problems:
        problems.append(msg)


# ============================================================== expressions

TOKEN = re.compile(r"""\s*(?:
    (?P<num>\d+\.\d*|\.\d+|\d+) |
    (?P<str>"[^"]*") |
    (?P<src>\[[A-Z0-9_.]+\]) |
    (?P<name>[A-Za-z_][A-Za-z0-9_]*) |
    (?P<op>>=|<=|==|!=|&&|\|\||[-+*/%!?:(),<>])
)""", re.X)


def tokenize(text):
    pos, out = 0, []
    text = text.strip()
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            raise SyntaxError(f"unexpected character at {pos}: {text[pos:pos + 20]!r}")
        kind = m.lastgroup
        out.append((kind, m.group(kind)))
        pos = m.end()
        while pos < len(text) and text[pos].isspace():
            pos += 1
    return out


def fmt_number(pattern, value):
    """Enough of java.text.DecimalFormat for patterns like '#,##0' and '0.0'."""
    decimals = len(pattern.split(".")[1]) if "." in pattern else 0
    s = f"{float(value):,.{decimals}f}" if "," in pattern else f"{float(value):.{decimals}f}"
    return s


FUNCS = {
    "round": lambda v: int(math.floor(float(v) + 0.5)),
    "floor": lambda v: float(math.floor(float(v))),
    "ceil": lambda v: float(math.ceil(float(v))),
    "fract": lambda v: float(v) - math.floor(float(v)),
    "abs": lambda v: abs(float(v)),
    "clamp": lambda v, lo, hi: min(max(float(v), float(lo)), float(hi)),
    "log": lambda v: math.log(v), "log2": lambda v: math.log2(v), "log10": lambda v: math.log10(v),
    "sqrt": lambda v: math.sqrt(v), "pow": lambda a, b: float(a) ** float(b),
    "sin": math.sin, "cos": math.cos, "tan": math.tan, "deg": math.degrees, "rad": math.radians,
    "numberFormat": fmt_number,
    "textLength": lambda s: len(str(s)),
    "subText": lambda s, a, b: str(s)[int(a):int(b)],
}


class Expr:
    """Pratt parser + evaluator for WFF arithmetic expressions."""

    BINARY = {"||": 1, "&&": 2, "==": 3, "!=": 3, "<": 4, "<=": 4, ">": 4, ">=": 4,
              "+": 5, "-": 5, "*": 6, "/": 6, "%": 6}

    def __init__(self, text, data, strings):
        self.text, self.data, self.strings = text, data, strings
        self.toks = tokenize(text)
        self.i = 0

    def peek(self):
        return self.toks[self.i] if self.i < len(self.toks) else (None, None)

    def take(self, value=None):
        tok = self.peek()
        if value is not None and tok[1] != value:
            raise SyntaxError(f"expected {value!r}, got {tok[1]!r}")
        self.i += 1
        return tok

    def parse(self):
        v = self.ternary()
        if self.i != len(self.toks):
            raise SyntaxError(f"trailing tokens: {self.toks[self.i:][:3]}")
        return v

    def ternary(self):
        cond = self.binary(0)
        if self.peek()[1] == "?":
            self.take("?")
            a = self.ternary()
            self.take(":")
            b = self.ternary()
            return a if truthy(cond) else b
        return cond

    def binary(self, min_prec):
        left = self.unary()
        while True:
            op = self.peek()[1]
            prec = self.BINARY.get(op)
            if prec is None or prec <= min_prec:
                return left
            self.take()
            right = self.binary(prec)
            left = apply(op, left, right)

    def unary(self):
        op = self.peek()[1]
        if op in ("-", "+", "!"):
            self.take()
            v = self.unary()
            return -num(v) if op == "-" else (num(v) if op == "+" else not truthy(v))
        return self.primary()

    def primary(self):
        kind, val = self.take()
        if kind == "num":
            return float(val) if "." in val else int(val)
        if kind == "str":
            return val[1:-1]
        if kind == "src":
            key = val[1:-1]
            base = key.split(".")[0]
            if base == "COMPLICATION":
                if key.split(".", 1)[1] not in COMPLICATION_FIELDS:
                    problem(f"unknown complication field {val} in: {self.text[:80]}")
            elif base == "CONFIGURATION":
                pass
            elif key not in KNOWN_SOURCES:
                problem(f"unknown data source {val} in: {self.text[:80]}")
            return self.data.get(key)
        if kind == "name":
            if self.peek()[1] == "(":
                if val not in FUNCS:
                    raise SyntaxError(f"unknown function {val}()")
                self.take("(")
                args = []
                if self.peek()[1] != ")":
                    args.append(self.ternary())
                    while self.peek()[1] == ",":
                        self.take(",")
                        args.append(self.ternary())
                self.take(")")
                return FUNCS[val](*args)
            if val in ("null", "true", "false"):
                return {"null": None, "true": True, "false": False}[val]
            if val in self.strings:
                return self.strings[val]
            raise SyntaxError(f"unknown name {val!r}")
        if val == "(":
            v = self.ternary()
            self.take(")")
            return v
        raise SyntaxError(f"unexpected {val!r}")


def num(v):
    if isinstance(v, bool):
        return int(v)
    if v is None:
        return 0
    if isinstance(v, str):
        return float(v)
    return v


def truthy(v):
    if isinstance(v, str):
        return v != ""
    return bool(v)


def apply(op, a, b):
    if op == "&&":
        return truthy(a) and truthy(b)
    if op == "||":
        return truthy(a) or truthy(b)
    if op in ("==", "!="):
        eq = (a == b) if (a is None or b is None) else (num(a) == num(b) if not isinstance(a, str) else a == b)
        return eq if op == "==" else not eq
    a, b = num(a), num(b)
    if op == "<": return a < b
    if op == "<=": return a <= b
    if op == ">": return a > b
    if op == ">=": return a >= b
    if op == "+": return a + b
    if op == "-": return a - b
    if op == "*": return a * b
    if op == "/":
        if b == 0:
            return 0
        r = a / b
        return int(r) if isinstance(a, int) and isinstance(b, int) and r == int(r) else r
    if op == "%": return a % b if b else 0
    raise SyntaxError(op)


def evaluate(text, ctx):
    try:
        return Expr(text, ctx.data, ctx.strings).parse()
    except (SyntaxError, TypeError, ValueError, ZeroDivisionError) as e:
        problem(f"bad expression ({e}): {text[:100]}")
        return 0


# ============================================================== rendering

class Ctx:
    def __init__(self, data, config, ambient, complications, strings, bitmap_fonts):
        self.data, self.config, self.ambient = data, config, ambient
        self.complications, self.strings, self.bitmap_fonts = complications, strings, bitmap_fonts

    def with_data(self, extra):
        c = Ctx({**self.data, **extra}, self.config, self.ambient, self.complications, self.strings, self.bitmap_fonts)
        return c


def color(value, ctx=None):
    if value is None:
        return None
    if value.startswith("[") and ctx:
        value = str(ctx.data.get(value[1:-1], "#FFFFFFFF"))
    v = value.lstrip("#")
    if len(v) == 6:
        return (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16), 255)
    return (int(v[2:4], 16), int(v[4:6], 16), int(v[6:8], 16), int(v[0:2], 16))


def element_alpha(el, ctx):
    a = int(el.get("alpha", 255))
    for v in el.findall("Variant"):
        if ctx.ambient and v.get("mode") == "AMBIENT" and v.get("target") == "alpha":
            a = int(num(evaluate(v.get("value"), ctx)))
    return a


def geom(el):
    return (int(float(el.get("x", 0))), int(float(el.get("y", 0))),
            int(float(el.get("width", 0))), int(float(el.get("height", 0))))


def paste(canvas, layer, x, y, alpha=255):
    if alpha <= 0:
        return
    if alpha < 255:
        layer = layer.copy()
        layer.putalpha(layer.getchannel("A").point(lambda v: v * alpha // 255))
    canvas.alpha_composite(layer, (x, y)) if x >= 0 and y >= 0 else canvas.paste(layer, (x, y), layer)


def load_image(resource, ctx):
    if resource.startswith("["):
        img = ctx.data.get(resource[1:-1])
        return img if isinstance(img, Image.Image) else None
    path = RES / "drawable" / f"{resource}.png"
    if not path.exists():
        problem(f"missing image resource: {resource}")
        return None
    return Image.open(path).convert("RGBA")


def tint(img, rgba):
    solid = Image.new("RGBA", img.size, rgba[:3] + (255,))
    solid.putalpha(ImageChops.multiply(img.getchannel("A"), Image.new("L", img.size, rgba[3])))
    return solid


def font_for(family, size, weight):
    if family == "SYNC_TO_DEVICE":
        key = "BOLD" if weight in ("BOLD", "SEMI_BOLD", "EXTRA_BOLD", "ULTRA_BOLD", "BLACK", "EXTRA_BLACK") else "NORMAL"
        for p in SYSTEM_FONTS[key]:
            if Path(p).exists():
                return ImageFont.truetype(p, round(size))
        return ImageFont.load_default()
    path = RES / "font" / f"{family}.ttf"
    if not path.exists():
        problem(f"missing font resource: {family}")
        return ImageFont.load_default()
    return ImageFont.truetype(str(path), round(size))


def template_text(node, ctx):
    """Collects the text of a Font/decoration node, expanding Templates."""
    out = node.text or ""
    for child in node:
        if child.tag == "Template":
            fmt = child.text or ""
            params = [evaluate(p.get("expression"), ctx) for p in child.findall("Parameter")]
            for p in child.findall("Parameter"):
                fmt += p.tail or ""
            vals = []
            for v in params:
                if isinstance(v, float) and v.is_integer():
                    v = repr(v)  # WFF prints floats with a .0, like Java
                vals.append("null" if v is None else v)
            try:
                out += fmt % tuple(vals)
            except (TypeError, ValueError) as e:
                problem(f"template {fmt!r} does not fit its parameters: {e}")
        elif child.tag in ("Outline", "Shadow", "OutGlow", "Upper", "Lower", "Underline", "StrikeThrough"):
            t = template_text(child, ctx)
            out += t.upper() if child.tag == "Upper" else (t.lower() if child.tag == "Lower" else t)
        out += child.tail or ""
    return out.strip()


def find_decoration(node, tag):
    for el in node.iter(tag):
        return el
    return None


def render_text(el, ctx, canvas, ox, oy):
    x, y, w, h = geom(el)
    text_el = el.find("Text")
    font_el = text_el.find("Font")
    family, size = font_el.get("family"), float(font_el.get("size"))
    fnt = font_for(family, size, font_el.get("weight", "NORMAL"))
    fill = color(font_el.get("color", "#FFFFFF"), ctx)
    text = template_text(font_el, ctx)
    outline = find_decoration(font_el, "Outline")
    stroke = round(float(outline.get("width", 2))) if outline is not None else 0
    stroke_fill = color(outline.get("color"), ctx) if outline is not None else None
    layer = Image.new("RGBA", (w + 40, h + 40), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ascent, descent = fnt.getmetrics()
    tw = d.textlength(text, font=fnt)
    if text_el.get("ellipsis") == "TRUE" and tw > w:
        while text and d.textlength(text + "…", font=fnt) > w:
            text = text[:-1]
        text += "…"
        tw = d.textlength(text, font=fnt)
    align = text_el.get("align", "CENTER")
    tx = {"START": 0, "CENTER": (w - tw) / 2, "END": w - tw}[align] + 20
    baseline = 20 + (h - (ascent + descent)) / 2 + ascent
    d.text((tx, baseline), text, font=fnt, fill=fill, anchor="ls", stroke_width=stroke, stroke_fill=stroke_fill)
    if tint_c := color(el.get("tintColor"), ctx):
        layer = tint(layer, tint_c)
    paste(canvas, layer, ox + x - 20, oy + y - 20, element_alpha(el, ctx))


def render_image(el, ctx, canvas, ox, oy):
    x, y, w, h = geom(el)
    img_el = el.find("Image")
    img = load_image(img_el.get("resource"), ctx)
    if img is None:
        return
    img = img.resize((w, h), Image.LANCZOS) if img.size != (w, h) else img
    if tc := color(el.get("tintColor"), ctx):
        img = tint(img, tc)
    paste(canvas, img, ox + x, oy + y, element_alpha(el, ctx))


def shape_attrs(shape, ctx):
    attrs = {k: float(v) if re.fullmatch(r"-?[\d.]+", v) else v for k, v in shape.attrib.items()}
    for t in shape.findall("Transform"):
        attrs[t.get("target")] = float(num(evaluate(t.get("value"), ctx)))
    return attrs


def paint_for(style, w, h, ctx):
    """A full-size RGBA paint for a Fill/Stroke (solid or linear gradient)."""
    grad = style.find("LinearGradient")
    if grad is None:
        return Image.new("RGBA", (w, h), color(style.get("color"), ctx))
    cols = [color("#" + c.lstrip("#")) for c in grad.get("colors").split()]
    pos = [float(p) for p in grad.get("positions").split()]
    sx, sy = float(grad.get("startX")) * SS, float(grad.get("startY")) * SS
    ex, ey = float(grad.get("endX")) * SS, float(grad.get("endY")) * SS
    import numpy as np
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = ex - sx, ey - sy
    t = ((xx - sx) * dx + (yy - sy) * dy) / max(dx * dx + dy * dy, 1e-6)
    t = np.clip(t, 0, 1)
    arr = np.zeros((h, w, 4), np.float32)
    for ch in range(4):
        arr[..., ch] = np.interp(t, pos, [c[ch] for c in cols])
    return Image.fromarray(arr.astype("uint8"), "RGBA")


def render_draw(el, ctx, canvas, ox, oy):
    x, y, w, h = geom(el)
    W, H = w * SS, h * SS
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for shape in el:
        if shape.tag in ("Variant", "Transform"):
            continue
        a = shape_attrs(shape, ctx)
        for style in (shape.find("Fill"), shape.find("Stroke")):
            if style is None:
                continue
            mask = Image.new("L", (W, H), 0)
            md = ImageDraw.Draw(mask)
            is_stroke = style.tag == "Stroke"
            th = float(style.get("thickness", 1)) * SS
            if shape.tag in ("Rectangle", "RoundRectangle", "Ellipse"):
                box = (a["x"] * SS, a["y"] * SS, (a["x"] + a["width"]) * SS - 1, (a["y"] + a["height"]) * SS - 1)
                if box[2] <= box[0] or box[3] <= box[1]:
                    continue
                kw = {"outline": 255, "width": max(1, int(th))} if is_stroke else {"fill": 255}
                if shape.tag == "Rectangle":
                    md.rectangle(box, **kw)
                elif shape.tag == "RoundRectangle":
                    md.rounded_rectangle(box, a["cornerRadiusX"] * SS, **kw)
                else:
                    md.ellipse(box, **kw)
            elif shape.tag == "Arc":
                cx, cy = a["centerX"] * SS, a["centerY"] * SS
                rw, rh = a["width"] * SS / 2, a["height"] * SS / 2
                start, end = a["startAngle"], a["endAngle"]
                if end > start:
                    # WFF: 0 degrees is 12 o'clock. Pillow: 0 degrees is 3 o'clock.
                    md.arc((cx - rw, cy - rh, cx + rw, cy + rh), start - 90, end - 90, fill=255, width=max(1, int(th)))
                    if style.get("cap") == "ROUND":
                        for ang in (start, end):
                            r_ = math.radians(ang)
                            px, py = cx + (rw - th / 2) * math.sin(r_), cy - (rh - th / 2) * math.cos(r_)
                            md.ellipse((px - th / 2, py - th / 2, px + th / 2, py + th / 2), fill=255)
            elif shape.tag == "Line":
                md.line((a["startX"] * SS, a["startY"] * SS, a["endX"] * SS, a["endY"] * SS), fill=255, width=max(1, int(th)))
            paint = paint_for(style, W, H, ctx)
            paint.putalpha(ImageChops.multiply(paint.getchannel("A"), mask))
            layer.alpha_composite(paint)
    layer = layer.resize((w, h), Image.LANCZOS)
    paste(canvas, layer, ox + x, oy + y, element_alpha(el, ctx))


def time_string(fmt, ctx):
    h24, minute, second = ctx.data["HOUR_0_23"], ctx.data["MINUTE"], ctx.data["SECOND"]
    hour = h24 if ctx.data["IS_24_HOUR_MODE"] else (h24 % 12 or 12)
    out = fmt
    out = out.replace("hh", f"{hour:02d}") if "hh" in out else out.replace("h", str(hour))
    out = out.replace("mm", f"{minute:02d}").replace("ss", f"{second:02d}")
    return out


def render_clock(el, ctx, canvas, ox, oy):
    cx, cy, _, _ = geom(el)
    clock_alpha = element_alpha(el, ctx)
    for tt in el.findall("TimeText"):
        x, y, w, h = geom(tt)
        bf = tt.find("BitmapFont")
        text = time_string(tt.get("format"), ctx)
        if bf is None:
            problem("TimeText without BitmapFont is not supported by the preview")
            continue
        chars = ctx.bitmap_fonts.get(bf.get("family"))
        if chars is None:
            problem(f"unknown bitmap font {bf.get('family')}")
            continue
        size = float(bf.get("size"))
        imgs = []
        for ch in text:
            if ch not in chars:
                problem(f"bitmap font {bf.get('family')} has no character {ch!r}")
                continue
            res, cw, ch_h = chars[ch]
            scale = size / ch_h
            img = load_image(res, ctx)
            if img is not None:
                imgs.append(img.resize((round(cw * scale), round(ch_h * scale)), Image.LANCZOS))
        total = sum(i.width for i in imgs)
        align = tt.get("align", "CENTER")
        px = {"START": 0, "CENTER": (w - total) // 2, "END": w - total}[align]
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        for img in imgs:
            layer.alpha_composite(img, (px, (h - img.height) // 2))
            px += img.width
        alpha = element_alpha(tt, ctx) * clock_alpha // 255
        paste(canvas, layer, ox + cx + x, oy + cy + y, alpha)


def render_complication_slot(el, ctx, canvas, ox, oy):
    x, y, w, h = geom(el)
    sample = ctx.complications.get(el.get("slotId"), {"type": "EMPTY"})
    for comp in el.findall("Complication"):
        if comp.get("type") == sample["type"]:
            sub = ctx.with_data({f"COMPLICATION.{k}": v for k, v in sample.items() if k != "type"})
            layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
            render_children(comp, sub, layer, ox + x, oy + y)
            paste(canvas, layer, 0, 0, element_alpha(el, ctx))
            return


def render_condition(el, ctx, canvas, ox, oy):
    exprs = {e.get("name"): e.text for e in el.find("Expressions").findall("Expression")}
    for cmp in el.findall("Compare"):
        if truthy(evaluate(exprs[cmp.get("expression")], ctx)):
            render_children(cmp, ctx, canvas, ox, oy)
            return
    default = el.find("Default")
    if default is not None:
        render_children(default, ctx, canvas, ox, oy)


def render_children(node, ctx, canvas, ox, oy):
    for child in node:
        tag = child.tag
        if tag == "Group":
            x, y, w, h = geom(child)
            layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
            render_children(child, ctx, layer, ox + x, oy + y)
            paste(canvas, layer, 0, 0, element_alpha(child, ctx))
        elif tag == "ListConfiguration":
            want = str(ctx.config.get(child.get("id"), "0"))
            for opt in child.findall("ListOption"):
                if opt.get("id") == want:
                    render_children(opt, ctx, canvas, ox, oy)
        elif tag == "Condition":
            render_condition(child, ctx, canvas, ox, oy)
        elif tag == "PartImage":
            render_image(child, ctx, canvas, ox, oy)
        elif tag == "PartText":
            render_text(child, ctx, canvas, ox, oy)
        elif tag == "PartDraw":
            render_draw(child, ctx, canvas, ox, oy)
        elif tag == "DigitalClock":
            render_clock(child, ctx, canvas, ox, oy)
        elif tag == "ComplicationSlot":
            render_complication_slot(child, ctx, canvas, ox, oy)
        elif tag in ("Variant", "Transform", "Expressions", "BoundingRoundBox", "BoundingBox", "BoundingOval",
                     "DefaultProviderPolicy", "ScreenReader"):
            pass
        else:
            problem(f"preview does not know how to draw <{tag}>")


# ============================================================== sample data

def sample_icon(kind, size=48):
    img = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size * 4
    if kind == "sun":
        d.ellipse((s * .3, s * .3, s * .7, s * .7), fill="white")
        for i in range(8):
            a = math.radians(i * 45)
            d.line((s / 2 + s * .3 * math.cos(a), s / 2 + s * .3 * math.sin(a),
                    s / 2 + s * .46 * math.cos(a), s / 2 + s * .46 * math.sin(a)), fill="white", width=s // 14)
    else:  # bell
        d.pieslice((s * .2, s * .12, s * .8, s * .72), 180, 360, fill="white")
        d.rectangle((s * .2, s * .42, s * .8, s * .72), fill="white")
        d.polygon([(s * .12, s * .78), (s * .88, s * .78), (s * .8, s * .68), (s * .2, s * .68)], fill="white")
        d.ellipse((s * .42, s * .76, s * .58, s * .92), fill="white")
    return img.resize((size, size), Image.LANCZOS)


def base_data(**over):
    d = {
        "HOUR_0_23": 10, "MINUTE": 42, "SECOND": 0, "IS_24_HOUR_MODE": False,
        "DAY_OF_WEEK_S": "Tue", "DAY": 27, "MONTH_S": "Oct",
        "STEP_COUNT": 8_421, "STEP_GOAL": 10_000, "STEP_PERCENT": 84,
        "HEART_RATE": 72.0, "BATTERY_PERCENT": 68, "BATTERY_CHARGING_STATUS": False,
        "UNREAD_NOTIFICATION_COUNT": 3,
    }
    d.update(over)
    return d


SAMPLE_COMPLICATIONS = {
    "1": {"type": "SHORT_TEXT", "TEXT": "6:12", "TITLE": None, "MONOCHROMATIC_IMAGE": sample_icon("sun")},
    "2": {"type": "SHORT_TEXT", "TEXT": "3", "TITLE": None, "MONOCHROMATIC_IMAGE": sample_icon("bell")},
}


def load_strings():
    tree = ET.parse(RES / "values" / "strings.xml")
    return {s.get("name"): s.text for s in tree.getroot().findall("string")}


def load_bitmap_fonts(root):
    fonts = {}
    bfs = root.find("BitmapFonts")
    for bf in (bfs if bfs is not None else []):
        chars = {}
        for c in bf:
            chars[c.get("name")] = (c.get("resource"), int(c.get("width")), int(c.get("height")))
            img = RES / "drawable" / f"{c.get('resource')}.png"
            if img.exists():
                size = Image.open(img).size
                if size != (int(c.get("width")), int(c.get("height"))):
                    problem(f"{c.get('resource')}.png is {size}, XML says {c.get('width')}x{c.get('height')}")
        fonts[bf.get("name")] = chars
    return fonts


def check_static(root, strings):
    """Checks that do not depend on sample data."""
    for el in root.iter():
        for attr in ("displayName", "screenReaderText"):
            name = el.get(attr)
            if name and el.tag in ("ListConfiguration", "ListOption", "ComplicationSlot") and name not in strings:
                problem(f"{el.tag} {attr}={name!r} is not in strings.xml")
        if el.tag in ("Image",) and not el.get("resource", "").startswith("["):
            if not (RES / "drawable" / f"{el.get('resource')}.png").exists():
                problem(f"missing image resource: {el.get('resource')}")
        for attr in ("expression", "value"):
            v = el.get(attr)
            if v and el.tag in ("Parameter", "Transform", "Variant"):
                tokenize(v)
        if el.tag == "Expression":
            tokenize(el.text)


def render(root, data, config=None, ambient=False, complications=None):
    strings = load_strings()
    ctx = Ctx(data, config or {}, ambient, complications if complications is not None else SAMPLE_COMPLICATIONS,
              strings, load_bitmap_fonts(root))
    w, h = int(root.get("width")), int(root.get("height"))
    scene = root.find("Scene")
    canvas = Image.new("RGBA", (w, h), color(scene.get("backgroundColor", "#000000")))
    render_children(scene, ctx, canvas, 0, 0)
    return circle(canvas)


def circle(img):
    w, h = img.size
    m = Image.new("L", (w * 4, h * 4), 0)
    ImageDraw.Draw(m).ellipse((0, 0, w * 4 - 1, h * 4 - 1), fill=255)
    out = img.copy()
    out.putalpha(ImageChops.multiply(img.getchannel("A"), m.resize((w, h), Image.LANCZOS)))
    return out


def montage(images, labels, cols, bg=(18, 18, 20, 255), label_h=34):
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    pad = 16
    sheet = Image.new("RGBA", (cols * (w + pad) + pad, rows * (h + label_h + pad) + pad), bg)
    d = ImageDraw.Draw(sheet)
    f = font_for("SYNC_TO_DEVICE", 20, "BOLD")
    for i, (img, label) in enumerate(zip(images, labels)):
        x = pad + (i % cols) * (w + pad)
        y = pad + (i // cols) * (h + label_h + pad)
        sheet.alpha_composite(img, (x, y))
        d.text((x + w / 2, y + h + 8), label, font=f, fill=(225, 215, 190), anchor="mt")
    return sheet


def main():
    check_only = "--check" in sys.argv
    root = ET.parse(XML).getroot()
    strings = load_strings()
    try:
        check_static(root, strings)
    except SyntaxError as e:
        problem(f"expression does not tokenize: {e}")

    # Render a range of states; every branch of every Condition gets exercised.
    states = [
        ("Steel", base_data(), {"skin": 0}),
        ("Legacy", base_data(STEP_COUNT=13_500, STEP_PERCENT=100), {"skin": 1}),
        ("Abyss", base_data(HEART_RATE=164.0, BATTERY_PERCENT=12, STEP_COUNT=2_350, STEP_PERCENT=23), {"skin": 2}),
        ("Obsidian", base_data(HEART_RATE=0, HOUR_0_23=21, MINUTE=7, IS_24_HOUR_MODE=True), {"skin": 3}),
    ]
    images = [render(root, d, cfg) for _, d, cfg in states]
    ambient = render(root, base_data(), {"skin": 0}, ambient=True)
    # Edge cases that must not crash or misbehave.
    for steps in (0, 1, 82, 83, 13_034_431, 20_000_000):
        render(root, base_data(STEP_COUNT=steps), {"skin": 0}, complications={})

    if problems:
        print("Problems found:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("watchface.xml: no problems found")
    if check_only:
        return

    DOCS.mkdir(exist_ok=True)
    images[0].save(RES / "drawable" / "preview.png", optimize=True)
    images[0].save(DOCS / "preview.png", optimize=True)
    labels = ["Steel", "Legacy · ultimate ready", "Abyss · low prayer, high HR", "Obsidian · 24h, no HR"]
    montage(images, labels, 2).save(DOCS / "skins.png", optimize=True)
    montage([images[0], ambient], ["Active", "Always-on"], 2).save(DOCS / "ambient.png", optimize=True)
    print("wrote previews to", DOCS)


if __name__ == "__main__":
    main()
