package com.ramong.runelauncher.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The shared look of the launcher: palette, fonts, generated stone/parchment textures,
 * bevelled boxes and RuneScape-style text (hard black drop shadow, `<col=rrggbb>` markup).
 */
object Rs {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val ORANGE = 0xFFFF981F.toInt()
    const val ITEM = 0xFFFF9040.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val QUEST_RED = 0xFFEF1020.toInt()
    const val CHAT_BLUE = 0xFF0000FF.toInt()
    const val DIALOGUE_BLUE = 0xFF000080.toInt()
    const val DARK_RED = 0xFF800000.toInt()
    const val TOOLTIP = 0xFFFFFFA0.toInt()
    const val MENU_BG = 0xFF5D5447.toInt()
    const val STONE = 0xFF3E3529.toInt()
    const val STONE_LIGHT = 0xFF574C3D.toInt()
    const val EDGE_HI = 0xFF7A6B55.toInt()
    const val EDGE_LO = 0xFF1E1A14.toInt()
    const val PARCHMENT = 0xFFCFC194.toInt()
    const val SLOT = 0xFF2B251D.toInt()

    var density = 2f
        private set

    /** Size of one "art pixel" (borders, sprite pixels) in device pixels. */
    var px = 2f
        private set

    var font: Typeface = Typeface.DEFAULT
        private set
    var fontBold: Typeface = Typeface.DEFAULT_BOLD
        private set

    val stonePaint = Paint().apply { isFilterBitmap = false }
    val stoneLightPaint = Paint().apply { isFilterBitmap = false }
    val parchmentPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint()

    /**
     * Fonts: drop a `runescape.ttf` (and optionally `runescape_bold.ttf`) into assets/fonts to
     * use one of the fan-made RuneScape fonts. Otherwise the bundled Pixelify Sans (OFL) is used.
     */
    fun init(ctx: Context, regular: Typeface? = null, bold: Typeface? = null) {
        density = ctx.resources.displayMetrics.density
        px = max(1f, density.roundToInt().toFloat())
        val custom = loadFont(ctx.assets, "fonts/runescape.ttf")
        font = regular ?: custom ?: loadFont(ctx.assets, "fonts/pixelify_sans_regular.ttf") ?: Typeface.DEFAULT
        fontBold = bold ?: loadFont(ctx.assets, "fonts/runescape_bold.ttf") ?: custom
            ?: loadFont(ctx.assets, "fonts/pixelify_sans_bold.ttf") ?: Typeface.DEFAULT_BOLD
        stonePaint.shader = noiseTile(STONE, 5, 11, 9)
        stoneLightPaint.shader = noiseTile(STONE_LIGHT, 5, 29, 6)
        parchmentPaint.shader = noiseTile(PARCHMENT, 4, 47, 0)
    }

    private fun loadFont(assets: AssetManager, path: String): Typeface? = try {
        assets.open(path).close()
        Typeface.createFromAsset(assets, path)
    } catch (e: Exception) {
        null
    }

    fun dp(v: Float) = v * density
    fun dp(v: Int) = v * density

    // ---------------------------------------------------------------- textures

    private fun noiseTile(base: Int, amp: Int, seed: Int, cracks: Int): Shader {
        val size = 48
        val cell = 8
        val n = size / cell
        val rnd = Random(seed)
        val lf = Array(n) { FloatArray(n) { rnd.nextFloat() * 2f - 1f } }
        val pix = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val gx = x / cell.toFloat()
            val gy = y / cell.toFloat()
            val x0 = gx.toInt() % n
            val y0 = gy.toInt() % n
            val x1 = (x0 + 1) % n
            val y1 = (y0 + 1) % n
            val fx = smooth(gx - gx.toInt())
            val fy = smooth(gy - gy.toInt())
            val top = lf[y0][x0] + (lf[y0][x1] - lf[y0][x0]) * fx
            val bottom = lf[y1][x0] + (lf[y1][x1] - lf[y1][x0]) * fx
            val v = top + (bottom - top) * fy
            val d = (v * amp * 1.5f + (rnd.nextFloat() * 2f - 1f) * amp * 0.6f).roundToInt()
            pix[y * size + x] = shade(base, d)
        }
        repeat(cracks) {
            var x = rnd.nextInt(size)
            var y = rnd.nextInt(size)
            repeat(5 + rnd.nextInt(9)) {
                pix[y * size + x] = shade(base, -amp * 3)
                if (rnd.nextBoolean()) x = (x + if (rnd.nextBoolean()) 1 else size - 1) % size
                y = (y + 1) % size
            }
        }
        val bmp = Bitmap.createBitmap(pix, size, size, Bitmap.Config.ARGB_8888)
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(px, px) })
        }
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    fun shade(c: Int, d: Int): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) + d).coerceIn(0, 255),
        (Color.green(c) + d).coerceIn(0, 255),
        (Color.blue(c) + d).coerceIn(0, 255),
    )

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Green when full, yellow at half, red when empty - like the orbs next to the minimap. */
    fun pctColor(frac: Float): Int {
        val f = frac.coerceIn(0f, 1f)
        return if (f >= 0.5f) {
            Color.rgb(((1f - f) * 2f * 255).roundToInt(), 255, 0)
        } else {
            Color.rgb(255, (f * 2f * 255).roundToInt(), 0)
        }
    }

    // ---------------------------------------------------------------- boxes

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun rect(c: Canvas, r: RectF, color: Int) = rect(c, r.left, r.top, r.right, r.bottom, color)

    fun outline(c: Canvas, r: RectF, color: Int, w: Float = px) {
        rect(c, r.left, r.top, r.right, r.top + w, color)
        rect(c, r.left, r.bottom - w, r.right, r.bottom, color)
        rect(c, r.left, r.top, r.left + w, r.bottom, color)
        rect(c, r.right - w, r.top, r.right, r.bottom, color)
    }

    /** One-pixel black outline plus a light/dark bevel just inside it. */
    fun bevel(c: Canvas, r: RectF, sunken: Boolean = false) {
        val p = px
        outline(c, r, BLACK, p)
        val hi = if (sunken) EDGE_LO else EDGE_HI
        val lo = if (sunken) EDGE_HI else EDGE_LO
        rect(c, r.left + p, r.top + p, r.right - p, r.top + 2 * p, hi)
        rect(c, r.left + p, r.top + p, r.left + 2 * p, r.bottom - p, hi)
        rect(c, r.left + p, r.bottom - 2 * p, r.right - p, r.bottom - p, lo)
        rect(c, r.right - 2 * p, r.top + p, r.right - p, r.bottom - p, lo)
    }

    fun stone(c: Canvas, r: RectF, light: Boolean = false) {
        c.drawRect(r, if (light) stoneLightPaint else stonePaint)
    }

    fun button(c: Canvas, r: RectF, selected: Boolean = false, pressed: Boolean = false) {
        stone(c, r, light = true)
        if (selected) rect(c, r, 0x998A1C10.toInt())
        if (pressed) rect(c, r, 0x40000000)
        bevel(c, r, sunken = selected || pressed)
    }

    /** A darker recessed well, used behind lists and slots. */
    fun well(c: Canvas, r: RectF, alpha: Int = 0x50) {
        rect(c, r, alpha shl 24)
        bevel(c, r, sunken = true)
    }

    // ---------------------------------------------------------------- text

    fun paint(sizeDp: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) fontBold else font
            textSize = dp(sizeDp)
            textAlign = align
            // The bundled font's "fl"/"fi" ligatures aren't in the subset and render as garbage.
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }

    fun shadowOf(p: Paint) = max(1f, (p.textSize / 14f).roundToInt().toFloat())

    fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint, color: Int, shadow: Boolean = true) {
        if (shadow) {
            val o = shadowOf(p)
            p.color = BLACK
            c.drawText(s, x + o, y + o, p)
        }
        p.color = color
        c.drawText(s, x, y, p)
    }

    /** Baseline that vertically centres a line of text between [top] and [bottom]. */
    fun baseline(p: Paint, top: Float, bottom: Float): Float {
        val fm = p.fontMetrics
        return (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
    }

    fun lineHeight(p: Paint): Float = p.fontSpacing

    fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var n = s.length
        val dots = p.measureText("...")
        while (n > 0 && p.measureText(s, 0, n) + dots > maxW) n--
        return s.substring(0, n).trimEnd() + "..."
    }

    fun wrap(s: String, p: Paint, maxW: Float): List<String> = wrapRich(rich(s, WHITE), p, maxW).map { it.text }

    /** Text with a colour per character, parsed from RuneScape's `<col=rrggbb>...</col>` tags. */
    class Rich(val text: String, val colors: IntArray)

    fun rich(s: String, default: Int): Rich {
        val sb = StringBuilder()
        val cols = ArrayList<Int>()
        var color = default
        var i = 0
        while (i < s.length) {
            if (s.startsWith("<col=", i)) {
                val end = s.indexOf('>', i)
                if (end > i) {
                    color = s.substring(i + 5, end).toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: default
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

    fun richWidth(r: Rich, p: Paint) = p.measureText(r.text)

    fun drawRich(c: Canvas, r: Rich, x: Float, y: Float, p: Paint, shadow: Boolean = true) {
        val t = r.text
        if (t.isEmpty()) return
        val align = p.textAlign
        p.textAlign = Paint.Align.LEFT
        val startX = when (align) {
            Paint.Align.CENTER -> x - p.measureText(t) / 2f
            Paint.Align.RIGHT -> x - p.measureText(t)
            else -> x
        }
        if (shadow) {
            val o = shadowOf(p)
            p.color = BLACK
            c.drawText(t, startX + o, y + o, p)
        }
        var i = 0
        while (i < t.length) {
            var j = i + 1
            while (j < t.length && r.colors[j] == r.colors[i]) j++
            p.color = r.colors[i]
            c.drawText(t, i, j, startX + p.measureText(t, 0, i), y, p)
            i = j
        }
        p.textAlign = align
    }

    fun number(n: Long): String = String.format(java.util.Locale.US, "%,d", n)
}
