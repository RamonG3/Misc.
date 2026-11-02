package com.ramong.cyberlauncher.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Line-art icons, designed on a 24x24 grid and drawn as strokes at any size. */
enum class Glyph {
    INVENTORY, CHARACTER, CYBERWARE, HACKS, JOURNAL, PHONE, RADIO, SETTINGS,
    SEARCH, SORT, CLOSE, PLUS, CHECK, STAR, LOCK, POWER, TAG,
    BODY, REFLEXES, TECH, INT, COOL,
    PING, OPTICS, SONIC, WIPE, CRIPPLE, MALFUNCTION, OVERHEAT, BACKUP, RESET, BREACH,
    DIAL, TEXT, CAMERA, NET, MAP, PREV, PLAY, PAUSE, NEXT, EYE, INFO,
}

object Glyphs {
    private val paths = HashMap<Glyph, Path>()
    private val filled = setOf(Glyph.PLAY, Glyph.STAR)
    private val m = Matrix()
    private val tmp = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, weight: Float = 1.8f, glow: Boolean = false) {
        val p = paths.getOrPut(g) { build(g) }
        val s = size / 24f
        m.setScale(s, s)
        m.postTranslate(cx - size / 2f, cy - size / 2f)
        p.transform(m, tmp)
        paint.color = color
        paint.strokeWidth = weight * s
        paint.style = if (g in filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        if (glow) paint.setShadowLayer(size * 0.18f, 0f, 0f, Cy.withAlpha(color, 0x99))
        c.drawPath(tmp, paint)
        if (glow) paint.clearShadowLayer()
    }

    // ---------------------------------------------------------------- builders

    private fun Path.poly(vararg v: Float): Path {
        moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) {
            lineTo(v[i], v[i + 1])
            i += 2
        }
        return this
    }

    private fun Path.shape(vararg v: Float): Path {
        poly(*v)
        close()
        return this
    }

    private fun Path.circle(cx: Float, cy: Float, r: Float): Path {
        addCircle(cx, cy, r, Path.Direction.CW)
        return this
    }

    private fun Path.arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float): Path {
        val o = RectF(cx - r, cy - r, cx + r, cy + r)
        arcTo(o, start, sweep, true)
        return this
    }

    private fun Path.box(l: Float, t: Float, r: Float, b: Float): Path = shape(l, t, r, t, r, b, l, b)

    private fun build(g: Glyph): Path {
        val p = Path()
        when (g) {
            Glyph.INVENTORY -> {
                p.box(4f, 4f, 11f, 11f); p.box(13f, 4f, 20f, 11f); p.box(4f, 13f, 11f, 20f)
                p.shape(13f, 13f, 17f, 13f, 20f, 16f, 20f, 20f, 13f, 20f)
            }
            Glyph.CHARACTER -> {
                p.shape(9f, 4f, 15f, 4f, 16.5f, 6.5f, 16.5f, 10f, 14f, 12.5f, 10f, 12.5f, 7.5f, 10f, 7.5f, 6.5f)
                p.poly(4f, 21f, 6f, 16f, 9.5f, 14.5f, 14.5f, 14.5f, 18f, 16f, 20f, 21f)
            }
            Glyph.CYBERWARE -> {
                p.box(7f, 7f, 17f, 17f); p.box(10f, 10f, 14f, 14f)
                for (k in floatArrayOf(9.5f, 14.5f)) {
                    p.poly(k, 3.5f, k, 7f); p.poly(k, 17f, k, 20.5f)
                    p.poly(3.5f, k, 7f, k); p.poly(17f, k, 20.5f, k)
                }
            }
            Glyph.HACKS -> {
                p.poly(7.5f, 6f, 2.5f, 12f, 7.5f, 18f)
                p.poly(16.5f, 6f, 21.5f, 12f, 16.5f, 18f)
                p.poly(13.5f, 4.5f, 10f, 12.5f, 14f, 12.5f, 10.5f, 19.5f)
            }
            Glyph.JOURNAL -> {
                for ((i, y) in floatArrayOf(6f, 12f, 18f).withIndex()) {
                    p.shape(5f, y - 2f, 7f, y, 5f, y + 2f, 3f, y)
                    p.poly(10f, y, if (i == 2) 16f else 21f, y)
                }
            }
            Glyph.PHONE -> {
                p.shape(7f, 2.5f, 17f, 2.5f, 17f, 21.5f, 7f, 21.5f)
                p.poly(10f, 5.5f, 14f, 5.5f)
                p.poly(11f, 18.5f, 13f, 18.5f)
            }
            Glyph.RADIO -> {
                p.circle(12f, 12f, 1.6f)
                p.arc(12f, 12f, 5f, 140f, 80f); p.arc(12f, 12f, 5f, -40f, 80f)
                p.arc(12f, 12f, 9f, 145f, 70f); p.arc(12f, 12f, 9f, -35f, 70f)
            }
            Glyph.SETTINGS -> {
                p.circle(12f, 12f, 3.2f)
                p.circle(12f, 12f, 6.6f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    p.poly(12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat(), 12f + 9.6f * cos(a).toFloat(), 12f + 9.6f * sin(a).toFloat())
                }
            }
            Glyph.SEARCH -> {
                p.circle(10f, 10f, 6f)
                p.poly(14.5f, 14.5f, 20.5f, 20.5f)
            }
            Glyph.SORT -> {
                p.poly(4f, 6.5f, 20f, 6.5f); p.poly(4f, 12f, 15f, 12f); p.poly(4f, 17.5f, 10f, 17.5f)
            }
            Glyph.CLOSE -> {
                p.poly(6f, 6f, 18f, 18f); p.poly(18f, 6f, 6f, 18f)
            }
            Glyph.PLUS -> {
                p.poly(12f, 5f, 12f, 19f); p.poly(5f, 12f, 19f, 12f)
            }
            Glyph.CHECK -> p.poly(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
            Glyph.STAR -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9f else 3.8f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    pts[i * 2] = 12f + r * cos(a).toFloat()
                    pts[i * 2 + 1] = 12.8f + r * sin(a).toFloat()
                }
                p.shape(*pts)
            }
            Glyph.LOCK -> {
                p.box(5f, 11f, 19f, 21f)
                p.poly(8f, 11f, 8f, 7f, 10f, 4f, 14f, 4f, 16f, 7f, 16f, 11f)
                p.poly(12f, 14.5f, 12f, 17.5f)
            }
            Glyph.POWER -> {
                p.arc(12f, 13f, 7.5f, -60f, 300f)
                p.poly(12f, 3f, 12f, 11.5f)
            }
            Glyph.TAG -> {
                p.shape(3f, 12f, 10f, 4f, 21f, 4f, 21f, 20f, 10f, 20f)
                p.circle(15.5f, 12f, 1.8f)
            }
            Glyph.BODY -> p.poly(2f, 12.5f, 7f, 12.5f, 9f, 6f, 12.5f, 19f, 15f, 9.5f, 16.8f, 12.5f, 22f, 12.5f)
            Glyph.REFLEXES -> {
                p.poly(7f, 17f, 20f, 4f)
                p.poly(5f, 13.5f, 10.5f, 19f)
                p.poly(3.5f, 20.5f, 7.5f, 16.5f)
            }
            Glyph.TECH -> {
                val pts = FloatArray(12)
                for (i in 0 until 6) {
                    val a = Math.toRadians(30.0 + i * 60.0)
                    pts[i * 2] = 12f + 8.5f * cos(a).toFloat()
                    pts[i * 2 + 1] = 12f + 8.5f * sin(a).toFloat()
                }
                p.shape(*pts)
                p.circle(12f, 12f, 3.2f)
            }
            Glyph.INT -> {
                for ((x, y) in listOf(5f to 5f, 19f to 5f, 5f to 19f, 19f to 19f)) {
                    p.circle(x, y, 2f)
                    p.poly(x + (12f - x) * 0.25f, y + (12f - y) * 0.25f, 12f + (x - 12f) * 0.3f, 12f + (y - 12f) * 0.3f)
                }
                p.box(9.5f, 9.5f, 14.5f, 14.5f)
            }
            Glyph.COOL -> {
                p.circle(12f, 12f, 7f)
                p.poly(12f, 2f, 12f, 7.5f); p.poly(12f, 16.5f, 12f, 22f)
                p.poly(2f, 12f, 7.5f, 12f); p.poly(16.5f, 12f, 22f, 12f)
                p.circle(12f, 12f, 0.8f)
            }
            Glyph.PING -> {
                p.circle(12f, 18f, 1.5f)
                p.arc(12f, 18f, 5f, 225f, 90f)
                p.arc(12f, 18f, 9.5f, 225f, 90f)
                p.arc(12f, 18f, 14f, 232f, 76f)
            }
            Glyph.OPTICS, Glyph.EYE -> {
                p.poly(2f, 12f, 7f, 6.5f, 17f, 6.5f, 22f, 12f, 17f, 17.5f, 7f, 17.5f, 2f, 12f)
                p.circle(12f, 12f, 3.2f)
                if (g == Glyph.OPTICS) p.poly(19f, 3f, 21.5f, 5.5f)
            }
            Glyph.SONIC -> {
                p.shape(3f, 9f, 7f, 9f, 12f, 4.5f, 12f, 19.5f, 7f, 15f, 3f, 15f)
                p.arc(12f, 12f, 4.5f, -50f, 100f)
                p.arc(12f, 12f, 8.5f, -50f, 100f)
            }
            Glyph.WIPE -> {
                p.arc(12f, 12f, 8.5f, 0f, 359f)
                p.poly(6f, 18f, 18f, 6f)
                p.poly(8.5f, 11f, 11f, 8.5f)
            }
            Glyph.CRIPPLE, Glyph.MAP -> {
                p.shape(12f, 21.5f, 6.5f, 13f, 6.5f, 8.5f, 9f, 4.5f, 15f, 4.5f, 17.5f, 8.5f, 17.5f, 13f)
                p.circle(12f, 9.5f, 2f)
                if (g == Glyph.CRIPPLE) p.poly(3.5f, 20.5f, 20.5f, 3.5f)
            }
            Glyph.MALFUNCTION -> p.poly(7f, 7.5f, 16.5f, 16f, 12f, 20.5f, 12f, 3.5f, 16.5f, 8f, 7f, 16.5f)
            Glyph.OVERHEAT -> {
                p.shape(12f, 2.5f, 17.5f, 10f, 17.5f, 15.5f, 12f, 21.5f, 6.5f, 15.5f, 6.5f, 10f, 9f, 12.5f)
                p.poly(12f, 13f, 14f, 16f, 12f, 18.5f, 10f, 16f, 12f, 13f)
            }
            Glyph.BACKUP -> {
                p.poly(12f, 12f, 12f, 21.5f)
                p.poly(8.5f, 21.5f, 15.5f, 21.5f)
                p.circle(12f, 10f, 1.6f)
                p.arc(12f, 10f, 5f, 200f, 140f)
                p.arc(12f, 10f, 9f, 205f, 130f)
            }
            Glyph.RESET -> {
                p.poly(12f, 2.5f, 13.5f, 4.5f, 13.5f, 9.5f, 21.5f, 14f, 21.5f, 16f, 13.5f, 13.5f, 13.5f, 18.5f, 16f, 20.5f, 16f, 21.5f,
                    12f, 20.5f, 8f, 21.5f, 8f, 20.5f, 10.5f, 18.5f, 10.5f, 13.5f, 2.5f, 16f, 2.5f, 14f, 10.5f, 9.5f, 10.5f, 4.5f, 12f, 2.5f)
            }
            Glyph.BREACH -> {
                p.box(2.5f, 4f, 21.5f, 20f)
                p.poly(6f, 9f, 9.5f, 12f, 6f, 15f)
                p.poly(11.5f, 15.5f, 17.5f, 15.5f)
            }
            Glyph.DIAL -> {
                for (yy in 0..2) for (xx in 0..2) p.circle(6f + xx * 6f, 5f + yy * 6f, 1.4f)
                p.circle(12f, 21f, 1.4f)
            }
            Glyph.TEXT -> p.shape(3.5f, 4.5f, 20.5f, 4.5f, 20.5f, 16f, 12.5f, 16f, 7.5f, 20.5f, 7.5f, 16f, 3.5f, 16f)
            Glyph.CAMERA -> {
                p.shape(2.5f, 7.5f, 7.5f, 7.5f, 9.5f, 4.5f, 14.5f, 4.5f, 16.5f, 7.5f, 21.5f, 7.5f, 21.5f, 19.5f, 2.5f, 19.5f)
                p.circle(12f, 13.3f, 3.6f)
            }
            Glyph.NET -> {
                p.circle(12f, 12f, 9f)
                p.addOval(RectF(8f, 3f, 16f, 21f), Path.Direction.CW)
                p.poly(3f, 12f, 21f, 12f)
            }
            Glyph.PREV -> {
                p.poly(6f, 6f, 6f, 18f)
                p.shape(18.5f, 6f, 9f, 12f, 18.5f, 18f)
            }
            Glyph.PLAY -> p.shape(8f, 5f, 19f, 12f, 8f, 19f)
            Glyph.PAUSE -> {
                p.box(7f, 5f, 10f, 19f)
                p.box(14f, 5f, 17f, 19f)
            }
            Glyph.NEXT -> {
                p.shape(5.5f, 6f, 15f, 12f, 5.5f, 18f)
                p.poly(18f, 6f, 18f, 18f)
            }
            Glyph.INFO -> {
                p.circle(12f, 12f, 9f)
                p.poly(12f, 10.5f, 12f, 17f)
                p.poly(12f, 7f, 12f, 7.6f)
            }
        }
        return p
    }
}
