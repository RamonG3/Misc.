package com.ramong.cyberlauncher.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Palette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The shared look: palette roles, fonts, chamfered panels, segmented bars, glow and
 * RGB-split text, and `<col=..>` rich text for the feed.
 */
object Cy {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFF2F5F7.toInt()
    const val TEXT = 0xFFD5DCE2.toInt()
    const val MUTED = 0xFF8A949C.toInt()
    const val DANGER = 0xFFFF3B3B.toInt()
    const val MAGENTA = 0xFFFF3FD2.toInt()
    const val SPLIT_RED = 0xFFFF2A55.toInt()
    const val SPLIT_CYAN = 0xFF00E5FF.toInt()

    // Corner flags for chamfered rectangles.
    const val TL = 1
    const val TR = 2
    const val BR = 4
    const val BL = 8

    var palette: Palette = Content.palettes[0]
        private set
    var primary = palette.primary
        private set
    var accent = palette.accent
        private set
    var hi = palette.hi
        private set
    var bg = palette.bg
        private set

    /** Panel fill: the palette background, nearly opaque. */
    var panel = 0
        private set
    var panelLight = 0
        private set
    /** Primary at reduced strength, for frames and secondary labels. */
    var dim = 0
        private set
    var faint = 0
        private set

    var density = 2f
        private set
    var px = 2f
        private set

    var font: Typeface = Typeface.DEFAULT
        private set
    var fontBold: Typeface = Typeface.DEFAULT_BOLD
        private set
    var mono: Typeface = Typeface.MONOSPACE
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private var scanPaint = Paint()

    enum class Face { BODY, BOLD, MONO }

    fun init(ctx: Context, body: Typeface? = null, bold: Typeface? = null, monoFace: Typeface? = null) {
        density = ctx.resources.displayMetrics.density
        px = max(1f, (density * 0.75f).roundToInt().toFloat())
        font = body ?: loadFont(ctx.assets, "fonts/rajdhani_medium.ttf") ?: Typeface.DEFAULT
        fontBold = bold ?: loadFont(ctx.assets, "fonts/rajdhani_bold.ttf") ?: Typeface.DEFAULT_BOLD
        mono = monoFace ?: loadFont(ctx.assets, "fonts/share_tech_mono.ttf") ?: Typeface.MONOSPACE
        apply(palette)
    }

    private fun loadFont(assets: AssetManager, path: String): Typeface? = try {
        assets.open(path).close()
        Typeface.createFromAsset(assets, path)
    } catch (e: Exception) {
        null
    }

    fun apply(p: Palette) {
        palette = p
        primary = p.primary
        accent = p.accent
        hi = p.hi
        bg = p.bg
        panel = withAlpha(p.bg, 0xEE)
        panelLight = withAlpha(mix(p.bg, p.primary, 0.07f), 0xF0)
        dim = withAlpha(p.primary, 0x99)
        faint = withAlpha(p.primary, 0x40)
        scanPaint = Paint().apply {
            val n = max(3, dp(3f).roundToInt())
            val pat = IntArray(n)
            pat[0] = 0x16FFFFFF
            shader = BitmapShader(Bitmap.createBitmap(pat, 1, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
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

    /** Battery-style colour: danger when low, primary otherwise. */
    fun healthColor(frac: Float) = if (frac <= 0.15f) DANGER else primary

    // ---------------------------------------------------------------- shapes

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun rect(c: Canvas, r: RectF, color: Int) = rect(c, r.left, r.top, r.right, r.bottom, color)

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float = px) {
        stroke.color = color
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.pathEffect = null
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    /** A rectangle with 45-degree chamfers on the [corners] (TL/TR/BR/BL flags). */
    fun cut(p: Path, r: RectF, cut: Float, corners: Int = TR or BL): Path {
        p.reset()
        val k = minOf(cut, r.width() / 2f, r.height() / 2f)
        val tl = if (corners and TL != 0) k else 0f
        val tr = if (corners and TR != 0) k else 0f
        val br = if (corners and BR != 0) k else 0f
        val bl = if (corners and BL != 0) k else 0f
        p.moveTo(r.left + tl, r.top)
        p.lineTo(r.right - tr, r.top)
        p.lineTo(r.right, r.top + tr)
        p.lineTo(r.right, r.bottom - br)
        p.lineTo(r.right - br, r.bottom)
        p.lineTo(r.left + bl, r.bottom)
        p.lineTo(r.left, r.bottom - bl)
        p.lineTo(r.left, r.top + tl)
        p.close()
        return p
    }

    /** Filled chamfered panel with a hairline frame and a bright tick on the cut corner. */
    fun panel(
        c: Canvas, r: RectF, cutSize: Float = dp(10f), fillColor: Int = panel, frame: Int = dim,
        corners: Int = TR or BL, ticks: Boolean = true,
    ) {
        cut(path, r, cutSize, corners)
        fill.shader = null
        fill.color = fillColor
        c.drawPath(path, fill)
        if (frame != 0) {
            stroke.color = frame
            stroke.strokeWidth = px
            stroke.pathEffect = null
            c.drawPath(path, stroke)
        }
        if (ticks && corners and TR != 0) {
            val k = minOf(cutSize, r.width() / 2f, r.height() / 2f)
            line(c, r.right - k - dp(1.5f), r.top, r.right + dp(0.5f), r.top + k + dp(1.5f), primary, px * 2f)
        }
    }

    /** Thin progress bar with an end notch. */
    fun bar(c: Canvas, r: RectF, frac: Float, color: Int, track: Int = withAlpha(color, 0x33)) {
        rect(c, r, track)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) rect(c, r.left, r.top, r.left + r.width() * f, r.bottom, color)
    }

    /**
     * The game's segmented health bar: [segments] parallelograms, [filled] of them lit
     * (fractional for a partial last segment).
     */
    fun segments(c: Canvas, r: RectF, segments: Int, filled: Float, color: Int, empty: Int, slant: Float = r.height() * 0.6f) {
        val gap = max(px, r.width() * 0.012f)
        val w = (r.width() - slant - gap * (segments - 1)) / segments
        fill.shader = null
        for (i in 0 until segments) {
            val x = r.left + i * (w + gap)
            val f = (filled - i).coerceIn(0f, 1f)
            fill.color = if (f > 0f) color else empty
            path.reset()
            path.moveTo(x + slant, r.top)
            path.lineTo(x + slant + w, r.top)
            path.lineTo(x + w, r.bottom)
            path.lineTo(x, r.bottom)
            path.close()
            c.drawPath(path, fill)
            if (f in 0.01f..0.99f) {
                fill.color = empty
                val cutX = x + w * f
                path.reset()
                path.moveTo(cutX + slant, r.top)
                path.lineTo(x + slant + w, r.top)
                path.lineTo(x + w, r.bottom)
                path.lineTo(cutX, r.bottom)
                path.close()
                c.drawPath(path, fill)
            }
        }
    }

    /** Corner brackets, like the scanner's target box. */
    fun brackets(c: Canvas, r: RectF, color: Int, len: Float = dp(10f), w: Float = px * 2f) {
        stroke.color = color
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.SQUARE
        stroke.pathEffect = null
        path.reset()
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top)
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len)
        path.moveTo(r.right, r.bottom - len); path.lineTo(r.right, r.bottom); path.lineTo(r.right - len, r.bottom)
        path.moveTo(r.left + len, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - len)
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** Faint horizontal scanlines over [r]. */
    fun scanlines(c: Canvas, r: RectF) {
        c.drawRect(r, scanPaint)
    }

    /** Diagonal warning stripes, used for locked items and danger rows. */
    fun stripes(c: Canvas, r: RectF, color: Int, step: Float = dp(7f)) {
        c.save()
        c.clipRect(r)
        stroke.color = color
        stroke.strokeWidth = step * 0.35f
        stroke.pathEffect = null
        var x = r.left - r.height()
        while (x < r.right) {
            c.drawLine(x, r.bottom, x + r.height(), r.top, stroke)
            x += step
        }
        c.restore()
    }

    fun diamond(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean = true) {
        path.reset()
        path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close()
        if (filled) {
            fill.shader = null
            fill.color = color
            c.drawPath(path, fill)
        } else {
            stroke.color = color
            stroke.strokeWidth = px * 1.5f
            c.drawPath(path, stroke)
        }
    }

    // ---------------------------------------------------------------- text

    fun paint(sizeDp: Float, face: Face = Face.BODY, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = when (face) {
                Face.BODY -> font
                Face.BOLD -> fontBold
                Face.MONO -> mono
            }
            textSize = dp(sizeDp)
            textAlign = align
            letterSpacing = spacing
        }

    fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint, color: Int) {
        p.color = color
        c.drawText(s, x, y, p)
    }

    /** Text with a soft neon glow. */
    fun glow(c: Canvas, s: String, x: Float, y: Float, p: Paint, color: Int, radius: Float = dp(6f)) {
        p.color = color
        p.setShadowLayer(radius, 0f, 0f, withAlpha(color, 0xAA))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    /** Text with red/cyan ghosts offset by [split] pixels: the signature chromatic glitch. */
    fun split(c: Canvas, s: String, x: Float, y: Float, p: Paint, color: Int, split: Float, glowRadius: Float = 0f) {
        if (split > 0f) {
            p.color = withAlpha(SPLIT_CYAN, 0x90)
            c.drawText(s, x - split, y, p)
            p.color = withAlpha(SPLIT_RED, 0x90)
            c.drawText(s, x + split, y, p)
        }
        if (glowRadius > 0f) glow(c, s, x, y, p, color, glowRadius) else text(c, s, x, y, p, color)
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

    /** Text with a colour per character, from `<col=rrggbb>` or `<col=hi|ac|pr|red|dim>` tags. */
    class Rich(val text: String, val colors: IntArray)

    fun role(name: String, default: Int): Int = when (name) {
        "hi" -> hi
        "ac" -> accent
        "pr" -> primary
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

    fun strip(s: String): String = rich(s, WHITE).text

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
        // Setting a colour resets alpha, so keep the paint's alpha for fades.
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
