package com.ramong.runelauncher.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ramong.runelauncher.core.Content
import com.ramong.runelauncher.core.Palette
import com.ramong.runelauncher.core.Skill
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The RuneScape 3 look: dark glass panels in a metal frame with corner studs, Cinzel titles,
 * ornament dividers, gold progress bars, skill artwork and `<col=..>` rich text.
 */
object R3 {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFF4EEDF.toInt()
    const val TEXT = 0xFFE2D9C3.toInt()
    const val MUTED = 0xFF9C9482.toInt()
    const val DANGER = 0xFFE0553D.toInt()
    const val LIFE = 0xFFC8322A.toInt()
    const val LIFE_OK = 0xFF46B04A.toInt()

    var palette: Palette = Content.palettes[0]
        private set
    var frame = palette.frame
        private set
    var hi = palette.hi
        private set
    var accent = palette.accent
        private set
    var bg = palette.bg
        private set
    var frameDark = 0
        private set
    var frameLight = 0
        private set
    var glass = 0
        private set

    var density = 2f
        private set
    var px = 2f
        private set

    var title: Typeface = Typeface.SERIF
        private set
    var titleBold: Typeface = Typeface.SERIF
        private set
    var body: Typeface = Typeface.DEFAULT
        private set
    var bodyBold: Typeface = Typeface.DEFAULT_BOLD
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val tmp = RectF()

    enum class Face { TITLE, TITLE_BOLD, BODY, BOLD }

    /** [loader] reads a bundled file (tests read from disk instead of assets). */
    fun init(ctx: Context, loader: ((String) -> ByteArray?)? = null) {
        density = ctx.resources.displayMetrics.density
        px = max(1f, (density * 0.6f).roundToInt().toFloat())
        val a = ctx.assets
        val read = loader ?: { p: String -> try { a.open(p).use { it.readBytes() } } catch (e: Exception) { null } }
        title = font(a, "fonts/cinzel_semibold.ttf", loader) ?: Typeface.SERIF
        titleBold = font(a, "fonts/cinzel_extrabold.ttf", loader) ?: title
        body = font(a, "fonts/alegreya_sans_medium.ttf", loader) ?: Typeface.DEFAULT
        bodyBold = font(a, "fonts/alegreya_sans_extrabold.ttf", loader) ?: Typeface.DEFAULT_BOLD
        SkillArt.init(read)
        apply(palette)
    }

    private fun font(assets: AssetManager, path: String, loader: ((String) -> ByteArray?)?): Typeface? = try {
        if (loader != null) {
            val bytes = loader(path) ?: return null
            val f = java.io.File.createTempFile("font", ".ttf")
            f.writeBytes(bytes)
            Typeface.createFromFile(f)
        } else {
            assets.open(path).close()
            Typeface.createFromAsset(assets, path)
        }
    } catch (e: Exception) {
        null
    }

    fun apply(p: Palette) {
        palette = p
        frame = p.frame
        hi = p.hi
        accent = p.accent
        bg = p.bg
        frameDark = mix(p.frame, BLACK, 0.55f)
        frameLight = mix(p.frame, 0xFFFFFFFF.toInt(), 0.4f)
        glass = withAlpha(p.bg, 0xE8)
    }

    fun dp(v: Float) = v * density
    fun dp(v: Int) = v * density

    // ---------------------------------------------------------------- colours

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).roundToInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).roundToInt(),
        )
    }

    /** Life points colour: green when healthy, through yellow, to red. */
    fun lifeColor(frac: Float): Int = when {
        frac > 0.5f -> mix(0xFFE2C23A.toInt(), LIFE_OK, (frac - 0.5f) * 2f)
        else -> mix(LIFE, 0xFFE2C23A.toInt(), frac * 2f)
    }

    // ---------------------------------------------------------------- shapes

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun rect(c: Canvas, r: RectF, color: Int) = rect(c, r.left, r.top, r.right, r.bottom, color)

    fun round(c: Canvas, r: RectF, radius: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawRoundRect(r, radius, radius, fill)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float = px) {
        stroke.shader = null
        stroke.color = color
        stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    fun diamond(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean = true) {
        path.reset()
        path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close()
        if (filled) {
            fill.shader = null
            fill.color = color
            c.drawPath(path, fill)
        } else {
            stroke.shader = null
            stroke.color = color
            stroke.strokeWidth = px * 1.4f
            c.drawPath(path, stroke)
        }
    }

    /** A gold stud: a small diamond with a dark edge, as seen on every RS3 frame corner. */
    fun stud(c: Canvas, cx: Float, cy: Float, r: Float = dp(3.2f)) {
        diamond(c, cx, cy, r + px, frameDark)
        diamond(c, cx, cy, r, frameLight)
        diamond(c, cx - r * 0.15f, cy - r * 0.15f, r * 0.45f, withAlpha(0xFFFFFFFF.toInt(), 0xB0))
    }

    /**
     * The RS3 window: rounded dark glass, a two-tone metal frame, an inner hairline and studs
     * at the corners. Pass [titleText] for a title bar with an ornament underneath.
     */
    fun panel(c: Canvas, r: RectF, fillColor: Int = glass, radius: Float = dp(8f), studs: Boolean = true,
              titleText: String? = null, titlePaint: Paint? = null, titleH: Float = dp(40f)) {
        fill.shader = null
        fill.color = fillColor
        c.drawRoundRect(r, radius, radius, fill)
        if (titleText != null) {
            tmp.set(r.left, r.top, r.right, r.top + titleH)
            fill.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, withAlpha(mix(frame, bg, 0.62f), 0xF0), withAlpha(mix(frame, bg, 0.86f), 0xF0), Shader.TileMode.CLAMP)
            c.save()
            c.clipRect(tmp)
            c.drawRoundRect(r, radius, radius, fill)
            c.restore()
            fill.shader = null
            ornament(c, r.left + dp(10f), r.right - dp(10f), tmp.bottom)
            val p = titlePaint ?: titleP
            p.textAlign = Paint.Align.CENTER
            text(c, ellipsize(titleText, p, r.width() - dp(70f)), r.centerX(), baseline(p, tmp.top, tmp.bottom), p, hi, shadow = true)
        }
        stroke.shader = LinearGradient(0f, r.top, 0f, r.bottom, frameLight, frameDark, Shader.TileMode.CLAMP)
        stroke.strokeWidth = dp(1.6f)
        c.drawRoundRect(r, radius, radius, stroke)
        stroke.shader = null
        stroke.color = withAlpha(BLACK, 0xA0)
        stroke.strokeWidth = px
        tmp.set(r)
        tmp.inset(dp(3f), dp(3f))
        c.drawRoundRect(tmp, max(0f, radius - dp(3f)), max(0f, radius - dp(3f)), stroke)
        if (studs) {
            val k = dp(2.5f)
            stud(c, r.left + k, r.top + k)
            stud(c, r.right - k, r.top + k)
            stud(c, r.left + k, r.bottom - k)
            stud(c, r.right - k, r.bottom - k)
        }
    }

    private val titleP by lazy { paint(16f, Face.TITLE_BOLD, Paint.Align.CENTER, 0.04f) }

    /** A horizontal divider: a fading metal line with a diamond in the middle. */
    fun ornament(c: Canvas, l: Float, r: Float, y: Float) {
        val cx = (l + r) / 2f
        stroke.shader = LinearGradient(l, 0f, cx, 0f, withAlpha(frame, 0), frame, Shader.TileMode.MIRROR)
        stroke.strokeWidth = px
        c.drawLine(l, y, r, y, stroke)
        stroke.shader = null
        diamond(c, cx, y, dp(3.5f), frameDark)
        diamond(c, cx, y, dp(2.6f), frameLight)
    }

    /** A stone button with a metal edge. [lit] marks the selected or active one. */
    fun button(c: Canvas, r: RectF, pressed: Boolean = false, lit: Boolean = false, radius: Float = dp(6f)) {
        val top = if (pressed) mix(frame, bg, 0.55f) else mix(frame, bg, 0.78f)
        val bottom = if (pressed) mix(frame, bg, 0.7f) else mix(frame, bg, 0.92f)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, withAlpha(top, 0xF2), withAlpha(bottom, 0xF2), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        stroke.shader = null
        stroke.color = if (lit) hi else withAlpha(frame, 0xC0)
        stroke.strokeWidth = if (lit) dp(1.6f) else px * 1.3f
        c.drawRoundRect(r, radius, radius, stroke)
    }

    /** A progress bar: dark track, gradient fill, metal edge. */
    fun bar(c: Canvas, r: RectF, frac: Float, color: Int) {
        val rad = r.height() / 2f
        round(c, r, rad, withAlpha(BLACK, 0xA0))
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            tmp.set(r.left, r.top, r.left + max(r.height(), r.width() * f), r.bottom)
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(color, 0xFFFFFFFF.toInt(), 0.3f), mix(color, BLACK, 0.25f), Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp, rad, rad, fill)
            fill.shader = null
        }
        stroke.shader = null
        stroke.color = withAlpha(frame, 0xB0)
        stroke.strokeWidth = px
        c.drawRoundRect(r, rad, rad, stroke)
    }

    /** A skill's artwork in a round dark socket with a metal rim. */
    fun skillBadge(c: Canvas, s: Skill, cx: Float, cy: Float, size: Float, rim: Boolean = true) {
        if (rim) {
            fill.shader = null
            fill.color = withAlpha(BLACK, 0x90)
            c.drawCircle(cx, cy, size / 2f, fill)
            stroke.shader = LinearGradient(0f, cy - size / 2f, 0f, cy + size / 2f, frameLight, frameDark, Shader.TileMode.CLAMP)
            stroke.strokeWidth = max(px, size / 22f)
            c.drawCircle(cx, cy, size / 2f - stroke.strokeWidth / 2f, stroke)
            stroke.shader = null
        }
        skill(c, s, cx, cy, if (rim) size * 0.74f else size)
    }

    fun skill(c: Canvas, s: Skill, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val b = SkillArt.get(s) ?: return
        tmp.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        bmp.alpha = alpha
        c.drawBitmap(b, null, tmp, bmp)
        bmp.alpha = 255
    }

    // ---------------------------------------------------------------- text

    fun paint(sizeDp: Float, face: Face = Face.BODY, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = when (face) {
                Face.TITLE -> title
                Face.TITLE_BOLD -> titleBold
                Face.BODY -> body
                Face.BOLD -> bodyBold
            }
            textSize = dp(sizeDp)
            textAlign = align
            letterSpacing = spacing
        }

    /** Text with an optional soft drop shadow, the way RS3 sets text over the game world. */
    fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint, color: Int, shadow: Boolean = false) {
        p.color = color
        if (shadow) p.setShadowLayer(dp(2.5f), 0f, dp(1f), 0xE0000000.toInt())
        c.drawText(s, x, y, p)
        if (shadow) p.clearShadowLayer()
    }

    fun baseline(p: Paint, top: Float, bottom: Float): Float {
        val fm = p.fontMetrics
        return (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
    }

    fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var n = s.length
        val dots = p.measureText("…")
        while (n > 0 && p.measureText(s, 0, n) + dots > maxW) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    fun wrap(s: String, p: Paint, maxW: Float): List<String> = wrapRich(rich(s, WHITE), p, maxW).map { it.text }

    /** Text with a colour per character, from `<col=rrggbb>` or `<col=hi|ac|red|dim>` tags. */
    class Rich(val text: String, val colors: IntArray)

    fun role(name: String, default: Int): Int = when (name) {
        "hi" -> hi
        "ac" -> accent
        "fr" -> frameLight
        "red" -> DANGER
        "dim" -> MUTED
        "white" -> WHITE
        else -> name.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: default
    }

    fun rich(s: String, default: Int): Rich {
        val sb = StringBuilder()
        val cols = ArrayList<Int>()
        var color = default
        var i = 0
        while (i < s.length) {
            if (s.startsWith("<col=", i)) {
                val end = s.indexOf('>', i)
                if (end > i) {
                    color = role(s.substring(i + 5, end), default)
                    i = end + 1
                    continue
                }
            }
            if (s.startsWith("</col>", i)) {
                color = default
                i += 6
                continue
            }
            sb.append(s[i])
            cols.add(color)
            i++
        }
        return Rich(sb.toString(), cols.toIntArray())
    }

    fun wrapRich(r: Rich, p: Paint, maxW: Float): List<Rich> {
        val out = ArrayList<Rich>()
        val t = r.text
        var start = 0
        while (start < t.length) {
            while (start < t.length && t[start] == ' ') start++
            if (start >= t.length) break
            var end = t.length
            if (p.measureText(t, start, end) > maxW) {
                var lastSpace = -1
                var i = start
                while (i < t.length && p.measureText(t, start, i + 1) <= maxW) {
                    if (t[i] == ' ') lastSpace = i
                    i++
                }
                end = if (lastSpace > start) lastSpace else max(i, start + 1)
            }
            var e = end
            while (e > start && t[e - 1] == ' ') e--
            out += Rich(t.substring(start, e), r.colors.copyOfRange(start, e))
            start = end
        }
        if (out.isEmpty()) out += Rich("", IntArray(0))
        return out
    }

    fun drawRich(c: Canvas, r: Rich, x: Float, y: Float, p: Paint) {
        val t = r.text
        if (t.isEmpty()) return
        val baseAlpha = p.alpha
        val align = p.textAlign
        p.textAlign = Paint.Align.LEFT
        val startX = when (align) {
            Paint.Align.CENTER -> x - p.measureText(t) / 2f
            Paint.Align.RIGHT -> x - p.measureText(t)
            else -> x
        }
        var i = 0
        while (i < t.length) {
            var j = i + 1
            while (j < t.length && r.colors[j] == r.colors[i]) j++
            p.color = r.colors[i]
            if (baseAlpha < 255) p.alpha = Color.alpha(r.colors[i]) * baseAlpha / 255
            c.drawText(t, i, j, startX + p.measureText(t, 0, i), y, p)
            i = j
        }
        p.alpha = baseAlpha
        p.textAlign = align
    }
}

/** The bundled skill artwork, decoded once on first use. */
object SkillArt {
    private var read: (String) -> ByteArray? = { null }
    private val cache = HashMap<Skill, Bitmap?>()

    fun init(loader: (String) -> ByteArray?) {
        read = loader
        cache.clear()
    }

    fun get(s: Skill): Bitmap? = cache.getOrPut(s) {
        read("skills/${s.name.lowercase()}.webp")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}
