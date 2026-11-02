package com.ramong.runelauncher.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Interface icons, designed on a 24x24 grid and drawn as rounded strokes at any size. */
enum class Glyph {
    SEARCH, CLOSE, PLUS, CHECK, STAR, INFO, EYE, PREV, PLAY, PAUSE, NEXT, NOTE, GEAR, PENCIL,
    FOLDER, HOME, WIFI, TORCH, BLUETOOTH, MOON, VOLUME, PIN, BATTERY, HOTSPOT, PLANE, GLOBE, BACK, HEART, BOOK,
}

object Glyphs {
    private val paths = HashMap<Glyph, Path>()
    private val filled = setOf(Glyph.PLAY, Glyph.STAR, Glyph.HEART)
    private val m = Matrix()
    private val tmp = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, weight: Float = 1.8f, shadow: Boolean = false) {
        val p = paths.getOrPut(g) { build(g) }
        val s = size / 24f
        m.setScale(s, s)
        m.postTranslate(cx - size / 2f, cy - size / 2f)
        p.transform(m, tmp)
        paint.color = color
        paint.strokeWidth = weight * s
        paint.style = if (g in filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        if (shadow) paint.setShadowLayer(size * 0.12f, 0f, size * 0.04f, 0xC0000000.toInt())
        c.drawPath(tmp, paint)
        if (shadow) paint.clearShadowLayer()
    }

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
        arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true)
        return this
    }

    private fun build(g: Glyph): Path {
        val p = Path()
        when (g) {
            Glyph.SEARCH -> {
                p.circle(10.5f, 10.5f, 6.5f); p.poly(15.5f, 15.5f, 20.5f, 20.5f)
            }
            Glyph.CLOSE -> {
                p.poly(6f, 6f, 18f, 18f); p.poly(18f, 6f, 6f, 18f)
            }
            Glyph.PLUS -> {
                p.poly(12f, 5f, 12f, 19f); p.poly(5f, 12f, 19f, 12f)
            }
            Glyph.CHECK -> p.poly(5f, 12.5f, 10f, 17.5f, 19.5f, 7f)
            Glyph.STAR -> p.shape(12f, 3f, 14.6f, 9.2f, 21f, 9.6f, 16.1f, 13.8f, 17.6f, 20.4f, 12f, 16.9f, 6.4f, 20.4f, 7.9f, 13.8f, 3f, 9.6f, 9.4f, 9.2f)
            Glyph.INFO -> {
                p.circle(12f, 12f, 9f); p.poly(12f, 11f, 12f, 17f); p.poly(12f, 7.6f, 12f, 7.8f)
            }
            Glyph.EYE -> {
                p.moveTo(2.5f, 12f); p.quadTo(12f, 3f, 21.5f, 12f); p.quadTo(12f, 21f, 2.5f, 12f); p.close(); p.circle(12f, 12f, 3f)
            }
            Glyph.PREV -> {
                p.shape(18f, 6f, 9.5f, 12f, 18f, 18f); p.poly(6.5f, 6f, 6.5f, 18f)
            }
            Glyph.NEXT -> {
                p.shape(6f, 6f, 14.5f, 12f, 6f, 18f); p.poly(17.5f, 6f, 17.5f, 18f)
            }
            Glyph.PLAY -> p.shape(8f, 5f, 19f, 12f, 8f, 19f)
            Glyph.PAUSE -> {
                p.poly(9f, 6f, 9f, 18f); p.poly(15f, 6f, 15f, 18f)
            }
            Glyph.NOTE -> {
                p.poly(9f, 17f, 9f, 5f, 19f, 3f, 19f, 15f); p.circle(7f, 17f, 2.2f); p.circle(17f, 15f, 2.2f)
            }
            Glyph.GEAR -> {
                p.circle(12f, 12f, 3.2f); p.circle(12f, 12f, 6.6f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    p.poly(12f + 6.6f * Math.cos(a).toFloat(), 12f + 6.6f * Math.sin(a).toFloat(), 12f + 9.4f * Math.cos(a).toFloat(), 12f + 9.4f * Math.sin(a).toFloat())
                }
            }
            Glyph.PENCIL -> {
                p.shape(4f, 20f, 5f, 15f, 15.5f, 4.5f, 19.5f, 8.5f, 9f, 19f); p.poly(13.5f, 6.5f, 17.5f, 10.5f)
            }
            Glyph.FOLDER -> p.shape(3f, 6f, 9f, 6f, 11f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f)
            Glyph.HOME -> {
                p.poly(3f, 11.5f, 12f, 3.5f, 21f, 11.5f); p.poly(6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); p.poly(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f)
            }
            Glyph.WIFI -> {
                p.arc(12f, 19f, 3.5f, 225f, 90f); p.arc(12f, 19f, 8f, 225f, 90f); p.arc(12f, 19f, 12.5f, 225f, 90f); p.poly(12f, 19f, 12f, 19.2f)
            }
            Glyph.TORCH -> {
                p.shape(7.5f, 3f, 16.5f, 3f, 16.5f, 7.5f, 14.5f, 10.5f, 14.5f, 21f, 9.5f, 21f, 9.5f, 10.5f, 7.5f, 7.5f); p.poly(12f, 13f, 12f, 15f)
            }
            Glyph.BLUETOOTH -> p.poly(7f, 7.5f, 17f, 16f, 12f, 20.5f, 12f, 3.5f, 17f, 8f, 7f, 16.5f)
            Glyph.MOON -> {
                p.moveTo(15.5f, 3.5f); p.cubicTo(8.5f, 4f, 5f, 10f, 7.5f, 15f); p.cubicTo(10f, 20f, 17f, 21f, 20.5f, 16.5f)
                p.cubicTo(14f, 17f, 10f, 10.5f, 15.5f, 3.5f); p.close()
            }
            Glyph.VOLUME -> {
                p.shape(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f); p.arc(12f, 12f, 4.5f, -45f, 90f); p.arc(12f, 12f, 8.5f, -45f, 90f)
            }
            Glyph.PIN -> {
                p.moveTo(12f, 21f); p.cubicTo(6f, 14f, 5f, 11.5f, 5f, 9f); p.arc(12f, 9f, 7f, 180f, 180f); p.cubicTo(19f, 11.5f, 18f, 14f, 12f, 21f); p.circle(12f, 9f, 2.4f)
            }
            Glyph.BATTERY -> {
                p.shape(3f, 7.5f, 18.5f, 7.5f, 18.5f, 16.5f, 3f, 16.5f); p.poly(21f, 10.5f, 21f, 13.5f); p.poly(6f, 10.5f, 6f, 13.5f); p.poly(9.5f, 10.5f, 9.5f, 13.5f)
            }
            Glyph.HOTSPOT -> {
                p.circle(12f, 12f, 1.6f); p.arc(12f, 12f, 5f, 135f, 90f); p.arc(12f, 12f, 5f, -45f, 90f); p.arc(12f, 12f, 9f, 135f, 90f); p.arc(12f, 12f, 9f, -45f, 90f)
            }
            Glyph.PLANE -> p.shape(12f, 2.5f, 13.6f, 9f, 21f, 13f, 21f, 15f, 13.6f, 12.8f, 13.6f, 18f, 16f, 20f, 16f, 21.5f, 12f, 20.3f, 8f, 21.5f, 8f, 20f, 10.4f, 18f, 10.4f, 12.8f, 3f, 15f, 3f, 13f, 10.4f, 9f)
            Glyph.GLOBE -> {
                p.circle(12f, 12f, 9f); p.poly(3f, 12f, 21f, 12f); p.moveTo(12f, 3f); p.quadTo(6.5f, 12f, 12f, 21f); p.moveTo(12f, 3f); p.quadTo(17.5f, 12f, 12f, 21f)
            }
            Glyph.BACK -> p.poly(15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.HEART -> {
                p.moveTo(12f, 20f); p.cubicTo(3f, 13.5f, 2.5f, 6f, 7.5f, 5f); p.cubicTo(9.8f, 4.6f, 11.3f, 6f, 12f, 7.6f)
                p.cubicTo(12.7f, 6f, 14.2f, 4.6f, 16.5f, 5f); p.cubicTo(21.5f, 6f, 21f, 13.5f, 12f, 20f); p.close()
            }
            Glyph.BOOK -> {
                p.shape(4f, 5f, 11f, 6.5f, 11f, 20f, 4f, 18.5f); p.shape(20f, 5f, 13f, 6.5f, 13f, 20f, 20f, 18.5f)
            }
        }
        return p
    }
}
