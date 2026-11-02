package com.ramong.cyberlauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.ramong.cyberlauncher.core.Palette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Night City, procedurally. The sky and the far and middle skylines are baked into [back];
 * the near rooftops into [front]. Between and over them: a hologram koi, flying cars, neon
 * signs, tower beacons, rain and (eventually) a cat.
 */
class City {
    private var back: Bitmap? = null
    private var front: Bitmap? = null
    private var w = 0
    private var h = 0
    private var d = 2f
    private var pal: Palette? = null

    private class Sign(val r: RectF, val vertical: Boolean, val text: String, val color: Int, val phase: Int, val near: Boolean) {
        var sprite: Bitmap? = null
        var pad = 0f
    }
    private class Car(var x: Float, val y: Float, val vx: Float, val scale: Float)
    private class Drop(var x: Float, var y: Float, val len: Float, val speed: Float, val near: Boolean)

    private val signs = ArrayList<Sign>()
    private val beacons = ArrayList<FloatArray>()
    private val cars = ArrayList<Car>()
    private val drops = ArrayList<Drop>()
    private val rainRnd = Random(77)
    private var nextCar = 0L

    /** Where the cat sits, for hit testing. Empty until the city is built. */
    val catBox = RectF()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val signText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rainFar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainNear = Paint(Paint.ANTI_ALIAS_FLAG)
    private var farLines = FloatArray(0)
    private var nearLines = FloatArray(0)
    private val koiFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val koiLocal = Path()
    private val koiWorld = Path()
    private val koiSpots = Path()
    private val koiSpotsWorld = Path()
    private val koiMatrix = Matrix()
    private val whiskers = Path()
    private val koiWhiskerLocal = Path()
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()

    val built: Boolean get() = back != null

    fun build(width: Int, height: Int, p: Palette, seed: Int, hour: Int, density: Float) {
        if (width <= 0 || height <= 0) return
        w = width
        h = height
        d = density
        pal = p
        back?.recycle()
        front?.recycle()
        signs.forEach { it.sprite?.recycle() }
        val rnd = Random(seed)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cb = Canvas(b)
        val cf = Canvas(f)
        signs.clear()
        beacons.clear()

        val phase = phaseOf(hour)
        val (top, mid, horizon) = sky(p, phase)
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(top, mid, horizon), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cb.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = RadialGradient(w * 0.55f, h * 1.05f, w * 0.9f, Cy.withAlpha(p.haze, 0x90), 0, Shader.TileMode.CLAMP)
        cb.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = null
        val lit = when (phase) {
            NIGHT -> 1f
            DAY -> 0.45f
            else -> 0.8f
        }

        // Far skyline, hazy and faint.
        val farColor = Cy.mix(Cy.mix(p.bg, 0xFF16123A.toInt(), 0.5f), p.haze, 0.3f)
        var x = -rnd.nextFloat() * 20 * d
        while (x < w) {
            val bw = (14 + rnd.nextInt(26)) * d
            val bh = h * (0.26f + rnd.nextFloat() * 0.3f)
            building(cb, rnd, x, h - bh, bw, bh, farColor, 0.06f * lit, 0x50, p)
            x += bw + rnd.nextInt(3) * d
        }
        // Two megatowers with spires and beacons.
        repeat(2) { i ->
            val tw = (20 + rnd.nextInt(10)) * d
            val tx = w * (if (i == 0) 0.56f + rnd.nextFloat() * 0.12f else 0.8f + rnd.nextFloat() * 0.12f)
            val th = h * (0.7f + rnd.nextFloat() * 0.16f)
            val col = Cy.mix(p.bg, p.haze, 0.28f)
            building(cb, rnd, tx, h - th, tw, th, col, 0.1f * lit, 0x70, p)
            fill.color = col
            path.reset()
            path.moveTo(tx + tw * 0.15f, h - th)
            path.lineTo(tx + tw * 0.5f, h - th - 26 * d)
            path.lineTo(tx + tw * 0.85f, h - th)
            path.close()
            cb.drawPath(path, fill)
            stroke.color = col
            stroke.strokeWidth = d
            cb.drawLine(tx + tw * 0.5f, h - th - 26 * d, tx + tw * 0.5f, h - th - 44 * d, stroke)
            beacons += floatArrayOf(tx + tw * 0.5f, h - th - 44 * d, rnd.nextFloat() * 1.6f)
            fill.color = Cy.withAlpha(p.accent, 0x50)
            cb.drawRect(tx + 2 * d, h - th + 10 * d, tx + tw - 2 * d, h - th + 11.2f * d, fill)
        }
        // Middle skyline, with LED bands and the odd vertical sign.
        val midColor = Cy.mix(p.bg, p.haze, 0.17f)
        x = -rnd.nextFloat() * 30 * d
        while (x < w) {
            val bw = (22 + rnd.nextInt(34)) * d
            val bh = h * (0.18f + rnd.nextFloat() * 0.28f)
            building(cb, rnd, x, h - bh, bw, bh, midColor, 0.13f * lit, 0x90, p)
            if (rnd.nextInt(3) == 0) {
                fill.color = Cy.withAlpha(if (rnd.nextBoolean()) p.primary else p.accent, 0x70)
                val ly = h - bh + bh * (0.15f + rnd.nextFloat() * 0.5f)
                cb.drawRect(x + 1.5f * d, ly, x + bw - 1.5f * d, ly + 1.2f * d, fill)
            }
            if (rnd.nextInt(4) == 0 && bw > 26 * d) {
                val text = VERTICAL.random(rnd)
                val sx = x + bw - 6 * d
                val sh = (12 + text.length * 10) * d
                val sy = h - bh + (8 + rnd.nextInt(14)) * d
                if (sy + sh < h - 10 * d && sx > 8 * d && sx < w - 8 * d) {
                    signs += Sign(RectF(sx - 5 * d, sy, sx + 5 * d, sy + sh), true, text, signColor(rnd, p), rnd.nextInt(97), near = false)
                }
            }
            x += bw + rnd.nextInt(4) * d
        }
        // Near rooftops, almost black, on their own layer so the koi and cars pass behind.
        val nearColor = Cy.mix(p.bg, 0xFF000000.toInt(), 0.35f)
        x = -rnd.nextFloat() * 40 * d
        var bestCat = -1f
        while (x < w) {
            val bw = (42 + rnd.nextInt(50)) * d
            val bh = h * (0.08f + rnd.nextFloat() * 0.16f)
            val top2 = h - bh
            building(cf, rnd, x, top2, bw, bh, nearColor, 0.18f * lit, 0xB0, p)
            fill.color = nearColor
            if (rnd.nextBoolean()) cf.drawRect(x + bw * 0.2f, top2 - 6 * d, x + bw * 0.2f + 9 * d, top2, fill)
            if (rnd.nextBoolean()) cf.drawRect(x + bw * 0.65f, top2 - 4 * d, x + bw * 0.65f + 12 * d, top2, fill)
            if (rnd.nextInt(3) == 0 && bw > 60 * d) {
                val text = HORIZONTAL.random(rnd)
                val sw = min(bw - 10 * d, (text.length * 7.5f + 12) * d)
                val sy = top2 + 8 * d
                val sl = x + (bw - sw) / 2f
                if (sl > 6 * d && sl + sw < w - 6 * d && sy + 13 * d < h - 4 * d) {
                    signs += Sign(RectF(sl, sy, sl + sw, sy + 13 * d), false, text, signColor(rnd, p), rnd.nextInt(97), near = true)
                }
            }
            val catSpot = x + bw * 0.45f
            if (bw > 60 * d && catSpot > w * 0.08f && catSpot < w * 0.46f && (bestCat < 0 || rnd.nextBoolean())) {
                bestCat = catSpot
                catBox.set(catSpot - 9 * d, top2 - 17 * d, catSpot + 9 * d, top2)
            }
            x += bw + rnd.nextInt(6) * d
        }
        if (bestCat < 0) catBox.setEmpty()
        for (sg in signs) bakeSign(sg)
        back = b
        front = f
        buildKoi()
        resetRain(rnd)
        cars.clear()
        nextCar = 0L
    }

    private fun building(c: Canvas, rnd: Random, x: Float, top: Float, bw: Float, bh: Float, color: Int, lit: Float, alpha: Int, p: Palette) {
        fill.shader = null
        fill.color = color
        c.drawRect(x, top, x + bw, h.toFloat(), fill)
        if (rnd.nextInt(3) == 0) c.drawRect(x + bw * 0.2f, top - bh * 0.06f, x + bw * 0.8f, top, fill)
        val cw = 3.3f * d
        val ch = 4.6f * d
        val warm = 0xFFFFC873.toInt()
        val cool = 0xFF9BE7FF.toInt()
        var wy = top + 4 * d
        while (wy < h - 2 * d) {
            // Now and then a whole floor is lit: somebody's working late.
            val rowLit = if (rnd.nextInt(9) == 0) lit * 3f else lit
            var wx = x + 2.2f * d
            while (wx < x + bw - 2.5f * d) {
                if (rnd.nextFloat() < rowLit) {
                    val base = when (rnd.nextInt(6)) {
                        0, 1, 2 -> warm
                        3, 4 -> cool
                        else -> p.primary
                    }
                    fill.color = Cy.withAlpha(base, (alpha * (0.45f + rnd.nextFloat() * 0.55f)).toInt())
                    c.drawRect(wx, wy, wx + 1.7f * d, wy + 2.3f * d, fill)
                }
                wx += cw
            }
            wy += ch
        }
    }

    private fun signColor(rnd: Random, p: Palette) = when (rnd.nextInt(5)) {
        0 -> p.primary
        1 -> p.accent
        2 -> p.hi
        3 -> Cy.MAGENTA
        else -> p.primary
    }

    private fun resetRain(rnd: Random) {
        drops.clear()
        val n = (w / d * 0.36f).toInt().coerceIn(60, 220)
        repeat(n) {
            val near = it % 3 == 0
            drops += Drop(rnd.nextFloat() * w * 1.2f - w * 0.2f, rnd.nextFloat() * h, (if (near) 14 + rnd.nextInt(8) else 7 + rnd.nextInt(6)) * d,
                (if (near) 820 + rnd.nextInt(300) else 520 + rnd.nextInt(260)) * d, near)
        }
        farLines = FloatArray(drops.count { !it.near } * 4)
        nearLines = FloatArray(drops.count { it.near } * 4)
    }

    /** Koi markings and the eye, one unit long, facing right. The body is rebuilt per frame. */
    private fun buildKoi() {
        koiSpots.reset()
        koiSpots.addOval(RectF(0.16f, -0.15f, 0.36f, -0.04f), Path.Direction.CW)
        koiSpots.addOval(RectF(-0.16f, -0.12f, 0.06f, -0.02f), Path.Direction.CW)
        koiSpots.addOval(RectF(-0.3f, -0.05f, -0.18f, 0.03f), Path.Direction.CW)
        koiSpots.addCircle(0.41f, -0.045f, 0.022f, Path.Direction.CW)
        koiWhiskerLocal.reset()
        koiWhiskerLocal.moveTo(0.48f, 0.05f)
        koiWhiskerLocal.quadTo(0.56f, 0.09f, 0.55f, 0.17f)
        koiWhiskerLocal.moveTo(0.46f, 0.06f)
        koiWhiskerLocal.quadTo(0.5f, 0.12f, 0.47f, 0.18f)
    }

    /**
     * A koi seen from the side: blunt head, deep body, a soft dorsal ridge, little fins,
     * whiskers, and a big fan of a tail that swishes with [sway] (-1..1).
     */
    private fun koiShape(sway: Float) {
        val p = koiLocal
        p.reset()
        val ty = sway * 0.09f
        p.moveTo(0.5f, 0.01f)
        p.cubicTo(0.49f, -0.09f, 0.4f, -0.15f, 0.26f, -0.17f)
        p.cubicTo(0.06f, -0.19f, -0.16f, -0.13f, -0.3f, -0.045f + ty * 0.3f)
        // Tail: two rounded lobes fanning out behind the body.
        p.cubicTo(-0.4f, -0.12f + ty, -0.56f, -0.26f + ty, -0.68f, -0.2f + ty * 1.4f)
        p.cubicTo(-0.64f, -0.08f + ty, -0.6f, -0.02f + ty, -0.62f, ty * 1.2f)
        p.cubicTo(-0.6f, 0.03f + ty, -0.64f, 0.1f + ty, -0.68f, 0.2f + ty * 1.4f)
        p.cubicTo(-0.56f, 0.26f + ty, -0.4f, 0.12f + ty, -0.3f, 0.045f + ty * 0.3f)
        p.cubicTo(-0.16f, 0.13f, 0.06f, 0.18f, 0.26f, 0.15f)
        p.cubicTo(0.4f, 0.13f, 0.49f, 0.1f, 0.5f, 0.01f)
        p.close()
        // Dorsal ridge.
        p.moveTo(0.2f, -0.175f)
        p.cubicTo(0.12f, -0.27f, -0.06f, -0.25f, -0.14f, -0.145f)
        p.close()
        // Pectoral and pelvic fins, trailing back as it swims.
        p.moveTo(0.3f, 0.12f)
        p.cubicTo(0.26f, 0.22f + sway * 0.02f, 0.16f, 0.26f, 0.14f, 0.21f)
        p.lineTo(0.22f, 0.14f)
        p.close()
        p.moveTo(0.0f, 0.165f)
        p.cubicTo(-0.04f, 0.24f, -0.12f, 0.24f, -0.12f, 0.2f)
        p.lineTo(-0.06f, 0.15f)
        p.close()
    }


    // ---------------------------------------------------------------- drawing

    /** [t] is seconds since an arbitrary start; [dt] the seconds since the last frame. */
    fun draw(c: Canvas, now: Long, t: Float, dt: Float, rain: Boolean, cat: Boolean, glitch: Float) {
        val b = back ?: return
        val p = pal ?: return
        if (glitch > 0f) drawSliced(c, b, glitch) else c.drawBitmap(b, 0f, 0f, bmpPaint)

        for (bc in beacons) {
            if ((t + bc[2]) % 1.6f < 0.28f) {
                fill.color = 0x50FF3040
                c.drawCircle(bc[0], bc[1], 5 * d, fill)
                fill.color = 0xFFFF3040.toInt()
                c.drawCircle(bc[0], bc[1], 1.6f * d, fill)
            }
        }
        for (s in signs) if (!s.near) drawSign(c, s, now)
        drawKoi(c, t, p)
        drawCars(c, now, dt, p)
        front?.let { if (glitch > 0f) drawSliced(c, it, glitch * 0.6f) else c.drawBitmap(it, 0f, 0f, bmpPaint) }
        for (s in signs) if (s.near) drawSign(c, s, now)
        if (cat && !catBox.isEmpty) drawCat(c, t)
        if (rain) drawRain(c, dt, p)
    }

    private fun drawSliced(c: Canvas, b: Bitmap, strength: Float) {
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        val r = Random((System.nanoTime() / 40_000_000L).toInt())
        repeat(3 + (strength * 5).toInt()) {
            val sy = r.nextInt(h)
            val sh = (2 + r.nextInt(18)) * d
            val dx = (r.nextFloat() - 0.5f) * 60 * d * strength
            src.set(0, sy, w, min(h, (sy + sh).toInt()))
            dst.set(dx, sy.toFloat(), w + dx, src.bottom.toFloat())
            c.drawBitmap(b, src, dst, bmpPaint)
        }
    }

    private fun drawKoi(c: Canvas, t: Float, p: Palette) {
        val len = min(140 * d, w * 0.36f)
        val speed = 16 * d
        val span = w + len * 2
        val x = ((t * speed + w * 0.55f + len) % span) - len
        val y = h * 0.62f + sin(t * 0.45f) * 8 * d
        val tilt = (Math.cos(t * 0.45) * 4.0).toFloat()
        koiShape(sin(t * 2.6f))
        koiMatrix.setScale(len, len)
        koiMatrix.postRotate(tilt)
        koiMatrix.postTranslate(x, y)
        koiLocal.transform(koiMatrix, koiWorld)
        koiSpots.transform(koiMatrix, koiSpotsWorld)
        if (koiFill.shader == null || koiTint != p.accent) {
            koiTint = p.accent
            val n = max(3, (3 * d).toInt())
            val pat = IntArray(n) { if (it < n - 1) Cy.withAlpha(p.accent, 0x55) else 0 }
            koiFill.shader = BitmapShader(Bitmap.createBitmap(pat, 1, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        c.drawPath(koiWorld, koiFill)
        fill.color = Cy.withAlpha(p.primary, 0x80)
        c.drawPath(koiSpotsWorld, fill)
        koiWhiskerLocal.transform(koiMatrix, whiskers)
        stroke.color = Cy.withAlpha(p.accent, 0x90)
        stroke.strokeWidth = 0.9f * d
        c.drawPath(whiskers, stroke)
        stroke.color = Cy.withAlpha(p.accent, 0x2C)
        stroke.strokeWidth = 5 * d
        c.drawPath(koiWorld, stroke)
        stroke.color = Cy.withAlpha(p.accent, 0xB8)
        stroke.strokeWidth = 1.1f * d
        c.drawPath(koiWorld, stroke)
    }

    private var koiTint = 0

    private fun drawCars(c: Canvas, now: Long, dt: Float, p: Palette) {
        if (now >= nextCar) {
            val r = Random(now)
            val right = r.nextBoolean()
            val scale = 0.55f + r.nextFloat() * 0.6f
            val speed = (34 + r.nextInt(40)) * d * scale * (if (right) 1 else -1)
            cars += Car(if (right) -30 * d else w + 30 * d, h * (0.16f + r.nextFloat() * 0.34f), speed, scale)
            nextCar = now + 3_500 + r.nextInt(8_000)
        }
        val it = cars.iterator()
        while (it.hasNext()) {
            val car = it.next()
            car.x += car.vx * dt
            if (car.x < -60 * d || car.x > w + 60 * d) {
                it.remove()
                continue
            }
            val s = car.scale * d
            val dir = if (car.vx > 0) 1f else -1f
            val len = 18 * s
            val ht = 4.6f * s
            fill.color = 0xFF0A0B10.toInt()
            path.reset()
            path.moveTo(car.x - len / 2 * dir, car.y)
            path.lineTo(car.x + len / 2 * dir, car.y + ht * 0.2f)
            path.lineTo(car.x + len / 2 * dir - 3 * s * dir, car.y + ht)
            path.lineTo(car.x - len / 2 * dir + 2 * s * dir, car.y + ht)
            path.close()
            c.drawPath(path, fill)
            fill.color = Cy.withAlpha(p.accent, 0x70)
            c.drawRect(min(car.x, car.x + 5 * s * dir), car.y + ht * 0.25f, max(car.x, car.x + 5 * s * dir), car.y + ht * 0.5f, fill)
            val tailX = car.x - len / 2 * dir + 2 * s * dir
            val headX = car.x + len / 2 * dir - 1.5f * s * dir
            fill.color = 0x55FF2A3D
            c.drawCircle(tailX, car.y + ht * 0.55f, 4 * s, fill)
            fill.color = 0xFFFF2A3D.toInt()
            c.drawCircle(tailX, car.y + ht * 0.55f, 1.3f * s, fill)
            fill.color = 0x50BFF8FF
            c.drawCircle(headX, car.y + ht * 0.45f, 4.5f * s, fill)
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(headX, car.y + ht * 0.45f, 1.1f * s, fill)
            fill.shader = LinearGradient(car.x + len / 2 * dir, 0f, car.x + (len / 2 + 30 * s) * dir, 0f, 0x40FFFFFF, 0, Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(car.x + len / 2 * dir, car.y + ht * 0.45f)
            path.lineTo(car.x + (len / 2 + 30 * s) * dir, car.y - 3 * s)
            path.lineTo(car.x + (len / 2 + 30 * s) * dir, car.y + ht + 4 * s)
            path.close()
            c.drawPath(path, fill)
            fill.shader = null
        }
    }

    /** Renders a sign once, glow included, so each frame is a single bitmap draw. */
    private fun bakeSign(s: Sign) {
        val pad = 9 * d
        val bw = (s.r.width() + pad * 2).toInt().coerceAtLeast(1)
        val bh = (s.r.height() + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(pad, pad, pad + s.r.width(), pad + s.r.height())
        fill.color = 0xD0060509.toInt()
        c.drawRect(r, fill)
        stroke.color = s.color
        stroke.strokeWidth = 1.1f * d
        stroke.setShadowLayer(6 * d, 0f, 0f, Cy.withAlpha(s.color, 0xC0))
        c.drawRect(r, stroke)
        stroke.clearShadowLayer()
        signText.typeface = Cy.fontBold
        signText.color = s.color
        signText.setShadowLayer(5 * d, 0f, 0f, Cy.withAlpha(s.color, 0xD0))
        if (s.vertical) {
            signText.textSize = r.width() * 0.95f
            val n = s.text.length
            val step = r.height() / n
            for (i in 0 until n) {
                c.drawText(s.text, i, i + 1, r.centerX(), r.top + step * (i + 0.5f) + signText.textSize * 0.34f, signText)
            }
        } else {
            signText.textSize = r.height() * 0.78f
            signText.letterSpacing = 0.12f
            c.drawText(s.text, r.centerX(), r.centerY() + signText.textSize * 0.34f, signText)
            signText.letterSpacing = 0f
        }
        signText.clearShadowLayer()
        s.sprite = bmp
        s.pad = pad
    }

    private fun drawSign(c: Canvas, s: Sign, now: Long) {
        val sprite = s.sprite ?: return
        // A bad ballast: now and then the tube drops out for a moment.
        val off = ((now / 70 + s.phase) % 113) < 4 || ((now / 1300 + s.phase) % 23) == 0L
        bmpPaint.alpha = if (off) 60 else 255
        c.drawBitmap(sprite, s.r.left - s.pad, s.r.top - s.pad, bmpPaint)
        bmpPaint.alpha = 255
    }

    private fun drawCat(c: Canvas, t: Float) {
        val r = catBox
        val cx = r.centerX()
        val base = r.bottom
        val u = r.width() / 18f
        fill.color = 0xFF040406.toInt()
        c.drawOval(cx - 6 * u, base - 9 * u, cx + 5 * u, base, fill)
        c.drawCircle(cx + 4 * u, base - 11 * u, 3.6f * u, fill)
        path.reset()
        path.moveTo(cx + 1.4f * u, base - 12.5f * u); path.lineTo(cx + 1.8f * u, base - 17f * u); path.lineTo(cx + 3.9f * u, base - 14f * u); path.close()
        path.moveTo(cx + 4.6f * u, base - 14f * u); path.lineTo(cx + 6.8f * u, base - 17f * u); path.lineTo(cx + 7.2f * u, base - 12.5f * u); path.close()
        c.drawPath(path, fill)
        stroke.color = 0xFF040406.toInt()
        stroke.strokeWidth = 1.6f * u
        stroke.strokeCap = Paint.Cap.ROUND
        val sway = sin(t * 1.3f) * 3 * u
        path.reset()
        path.moveTo(cx - 5.5f * u, base - 2 * u)
        path.quadTo(cx - 11 * u, base - 3 * u, cx - 10 * u + sway, base - 10 * u)
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        if ((t % 4.2f) > 0.18f) {
            fill.color = Cy.withAlpha(Cy.accent, 0x50)
            c.drawCircle(cx + 2.8f * u, base - 11.5f * u, 2f * u, fill)
            c.drawCircle(cx + 5.4f * u, base - 11.5f * u, 2f * u, fill)
            fill.color = Cy.accent
            c.drawCircle(cx + 2.8f * u, base - 11.5f * u, 0.7f * u, fill)
            c.drawCircle(cx + 5.4f * u, base - 11.5f * u, 0.7f * u, fill)
        }
    }

    private fun drawRain(c: Canvas, dt: Float, p: Palette) {
        val slant = 0.2f
        var fi = 0
        var ni = 0
        for (dr in drops) {
            dr.y += dr.speed * dt
            dr.x += dr.speed * dt * slant
            if (dr.y > h) {
                dr.y = -dr.len - rainRnd.nextFloat() * h * 0.2f
                dr.x = rainRnd.nextFloat() * w * 1.2f - w * 0.2f
            }
            val arr = if (dr.near) nearLines else farLines
            val i = if (dr.near) ni else fi
            arr[i] = dr.x
            arr[i + 1] = dr.y
            arr[i + 2] = dr.x - dr.len * slant
            arr[i + 3] = dr.y - dr.len
            if (dr.near) ni += 4 else fi += 4
        }
        val tint = Cy.mix(0xFFFFFFFF.toInt(), p.accent, 0.3f)
        rainFar.color = Cy.withAlpha(tint, 0x38)
        rainFar.strokeWidth = 0.8f * d
        rainNear.color = Cy.withAlpha(tint, 0x60)
        rainNear.strokeWidth = 1.1f * d
        c.drawLines(farLines, 0, fi, rainFar)
        c.drawLines(nearLines, 0, ni, rainNear)
    }

    fun release() {
        back?.recycle()
        front?.recycle()
        back = null
        front = null
    }

    companion object {
        const val NIGHT = 0
        const val DAWN = 1
        const val DAY = 2
        const val DUSK = 3

        private val VERTICAL = listOf("BAR", "HOTEL", "24H", "PAWN", "OPEN", "CLUB", "SUSHI", "DOJO", "BD")
        private val HORIZONTAL = listOf("NOODLES", "RIPPERDOC", "MOTEL", "LIQUOR", "NO FUTURE", "BRAINDANCE", "ARCADE", "TACOS 24H")

        fun phaseOf(hour: Int) = when (hour) {
            in 5..7 -> DAWN
            in 8..16 -> DAY
            in 17..20 -> DUSK
            else -> NIGHT
        }

        fun sky(p: Palette, phase: Int): Triple<Int, Int, Int> = when (phase) {
            DAWN -> Triple(0xFF0E1220.toInt(), Cy.mix(0xFF3A2A4A.toInt(), p.haze, 0.3f), Cy.mix(0xFFFF8A5C.toInt(), p.haze, 0.45f))
            DAY -> Triple(0xFF17242C.toInt(), Cy.mix(0xFF33454A.toInt(), p.haze, 0.2f), Cy.mix(0xFF9C8F55.toInt(), p.haze, 0.3f))
            DUSK -> Triple(0xFF0C0C1A.toInt(), Cy.mix(0xFF45203C.toInt(), p.haze, 0.4f), Cy.mix(0xFFFF6A3D.toInt(), p.haze, 0.4f))
            else -> Triple(0xFF05050D.toInt(), Cy.mix(0xFF16123A.toInt(), p.haze, 0.38f), Cy.mix(p.haze, 0xFFFF5070.toInt(), 0.16f))
        }
    }
}
