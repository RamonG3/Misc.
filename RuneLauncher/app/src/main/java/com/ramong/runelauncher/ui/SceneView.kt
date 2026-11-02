package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.ramong.runelauncher.core.Game
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random

/**
 * The live background: a painted Gielinor vista (or a soft veil over the phone's own wallpaper),
 * darkened towards the bottom so icons stay readable. It draws nothing interactive.
 */
@SuppressLint("ViewConstructor")
class SceneView(context: Context, private val game: Game) : View(context) {
    /** True while something opaque covers the screen; stops the animation loop. */
    var covered = false
        set(v) {
            field = v
            if (!v) invalidate()
        }

    private val vista = Vista()
    private var key = ""
    private val start = SystemClock.uptimeMillis()
    private var frameQueued = false
    private val frame = Runnable {
        frameQueued = false
        invalidate()
    }
    private val fill = Paint()
    private val scrim = Paint()
    private val topScrim = Paint()

    fun tick() {
        if (!animating()) invalidate()
    }

    fun worldHop() {
        key = ""
        invalidate()
    }

    fun themeChanged() = invalidate()

    private fun animating(): Boolean = game.resumed && !covered && !game.stats.powerSave && game.save.scene == 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrim.shader = LinearGradient(0f, h * 0.32f, 0f, h.toFloat(), 0x00000000, 0xB4000000.toInt(), Shader.TileMode.CLAMP)
        // Keeps the clock and status line readable whatever the sky is doing.
        topScrim.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        if (game.save.scene == 0) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val k = "${width}x$height:${game.save.world}:${game.save.place}:${Vista.phaseOf(hour)}"
            if (k != key && width > 0 && height > 0) {
                key = k
                vista.build(width, height, game.save.world * 31 + game.save.place, hour, R3.density)
            }
            vista.draw(c, t)
        } else {
            fill.shader = LinearGradient(0f, 0f, 0f, h, 0x40000000, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
        c.drawRect(0f, 0f, w, h, scrim)
        c.drawRect(0f, 0f, w, h * 0.3f, topScrim)
        if (animating() && !frameQueued) {
            frameQueued = true
            postDelayed(frame, FRAME_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        frameQueued = false
        super.onDetachedFromWindow()
    }

    companion object {
        const val FRAME_MS = 50L
    }
}

/** Paints the scene: a static backdrop bitmap, then clouds, birds and a shimmering lake on top. */
class Vista {
    enum class Phase { NIGHT, DAWN, DAY, DUSK }

    private var backdrop: Bitmap? = null
    private var w = 0f
    private var h = 0f
    private var d = 1f
    private var phase = Phase.DAY
    private var lakeTop = 0f
    private var lakeBottom = 0f

    private class Cloud(var x: Float, val y: Float, val s: Float, val speed: Float, val puffs: List<FloatArray>)

    private val clouds = ArrayList<Cloud>()
    private val birds = ArrayList<FloatArray>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    fun build(width: Int, height: Int, seed: Int, hour: Int, density: Float) {
        w = width.toFloat()
        h = height.toFloat()
        d = density
        phase = phaseOf(hour)
        val r = Random(seed)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (skyTop, skyBottom) = when (phase) {
            Phase.NIGHT -> 0xFF070B1E.toInt() to 0xFF1E2A4E.toInt()
            Phase.DAWN -> 0xFF4C6EAE.toInt() to 0xFFF0A868.toInt()
            Phase.DAY -> 0xFF5A9BDB.toInt() to 0xFFC9E3F2.toInt()
            Phase.DUSK -> 0xFF2C2D5E.toInt() to 0xFFE77A52.toInt()
        }
        val horizon = h * 0.36f
        fill.shader = LinearGradient(0f, 0f, 0f, horizon * 1.1f, skyTop, skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // Sun or moon, and stars at night.
        val sx = w * (0.2f + r.nextFloat() * 0.25f)
        val sy = horizon * (if (phase == Phase.DAY) 0.28f else 0.6f)
        val sunCol = when (phase) {
            Phase.NIGHT -> 0xFFE8ECF5.toInt()
            Phase.DAY -> 0xFFFFF4C8.toInt()
            else -> 0xFFFFC27A.toInt()
        }
        fill.shader = RadialGradient(sx, sy, h * 0.12f, intArrayOf(R3.withAlpha(sunCol, 0x90), R3.withAlpha(sunCol, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, h * 0.12f, fill)
        fill.shader = null
        fill.color = sunCol
        c.drawCircle(sx, sy, d * (if (phase == Phase.NIGHT) 16f else 22f), fill)
        if (phase == Phase.NIGHT) {
            fill.color = skyTop
            c.drawCircle(sx + d * 7f, sy - d * 5f, d * 14f, fill)
            repeat(140) {
                fill.color = R3.withAlpha(0xFFFFFFFF.toInt(), 80 + r.nextInt(170))
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * horizon, d * (0.5f + r.nextFloat() * 1.1f), fill)
            }
        }

        val tint = when (phase) {
            Phase.NIGHT -> 0.72f
            Phase.DUSK, Phase.DAWN -> 0.35f
            Phase.DAY -> 0f
        }
        fun shade(col: Int) = R3.mix(col, 0xFF0A1028.toInt(), tint)

        // Far mountains, misty blue.
        ridge(c, r, horizon - h * 0.07f, h * 0.08f, 5, shade(0xFF7D8FB0.toInt()), shade(0xFF9AA9C4.toInt()))
        ridge(c, r, horizon - h * 0.02f, h * 0.05f, 8, shade(0xFF61738F.toInt()), shade(0xFF7E8EA8.toInt()))

        // Rolling green hills with a castle on the highest one.
        val hillTop = horizon + h * 0.02f
        val castleX = w * (0.58f + r.nextFloat() * 0.2f)
        hill(c, hillTop, h * 0.06f, castleX, shade(0xFF4F8A3C.toInt()), shade(0xFF3B6B2E.toInt()))
        castle(c, castleX, hillTop - h * 0.045f, h * 0.11f, shade(0xFF8A8A92.toInt()), shade(0xFF5D5E68.toInt()), shade(0xFF6E3B2E.toInt()))
        trees(c, r, hillTop + h * 0.035f, 18, shade(0xFF2E5A22.toInt()), shade(0xFF24481B.toInt()))

        // The lake, catching the sky.
        lakeTop = hillTop + h * 0.07f
        lakeBottom = lakeTop + h * 0.06f
        fill.shader = LinearGradient(0f, lakeTop, 0f, lakeBottom, R3.mix(skyBottom, skyTop, 0.3f), R3.mix(skyTop, 0xFF0A1028.toInt(), 0.3f), Shader.TileMode.CLAMP)
        c.drawRect(0f, lakeTop, w, lakeBottom, fill)
        fill.shader = null
        fill.color = R3.withAlpha(sunCol, 0x50)
        oval.set(sx - d * 24f, lakeTop + d * 4f, sx + d * 24f, lakeTop + d * 14f)
        c.drawOval(oval, fill)

        // Near hills and a dark foreground for the icons to sit on.
        hill(c, lakeBottom - h * 0.012f, h * 0.035f, w * 0.25f, shade(0xFF3A6A2C.toInt()), shade(0xFF27491F.toInt()))
        trees(c, r, lakeBottom + h * 0.02f, 10, shade(0xFF1F4219.toInt()), shade(0xFF183513.toInt()))
        fill.shader = LinearGradient(0f, lakeBottom + h * 0.04f, 0f, h, shade(0xFF27491F.toInt()), 0xFF0B140A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, lakeBottom + h * 0.04f, w, h, fill)
        fill.shader = null
        backdrop = bmp

        clouds.clear()
        repeat(5) {
            val puffs = List(4 + r.nextInt(3)) { floatArrayOf((r.nextFloat() - 0.5f) * 60f, (r.nextFloat() - 0.5f) * 8f, 9f + r.nextFloat() * 10f) }
            clouds += Cloud(r.nextFloat() * w, horizon * (0.5f + r.nextFloat() * 0.4f), d * (0.6f + r.nextFloat() * 0.5f), d * (3f + r.nextFloat() * 6f), puffs)
        }
        birds.clear()
        if (phase != Phase.NIGHT) repeat(3) { birds += floatArrayOf(r.nextFloat() * w, horizon * (0.3f + r.nextFloat() * 0.4f), d * (14f + r.nextFloat() * 10f)) }
    }

    private fun ridge(c: Canvas, r: Random, base: Float, amp: Float, peaks: Int, top: Int, bottom: Int) {
        path.reset()
        path.moveTo(0f, h)
        val phaseA = r.nextFloat() * 6f
        val phaseB = r.nextFloat() * 6f
        var x = 0f
        while (x <= w) {
            val u = x / w
            val y = base - amp * (0.55f + 0.3f * sin(u * peaks * 3.1f + phaseA) + 0.15f * sin(u * peaks * 7.3f + phaseB))
            path.lineTo(x, y)
            x += w / 80f
        }
        path.lineTo(w, h)
        path.close()
        fill.shader = LinearGradient(0f, base - amp, 0f, base + amp, bottom, top, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
    }

    private fun hill(c: Canvas, base: Float, amp: Float, peakX: Float, top: Int, bottom: Int) {
        path.reset()
        path.moveTo(0f, h)
        var x = 0f
        while (x <= w) {
            val dist = (x - peakX) / w
            val y = base - amp * (1f / (1f + dist * dist * 18f)) - amp * 0.25f * sin(x / w * 9f)
            path.lineTo(x, y)
            x += w / 80f
        }
        path.lineTo(w, h)
        path.close()
        fill.shader = LinearGradient(0f, base - amp, 0f, base + amp * 2f, top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
    }

    /** A Lumbridge-ish castle: a keep, two towers with pointed roofs and a flag. */
    private fun castle(c: Canvas, cx: Float, base: Float, size: Float, wall: Int, dark: Int, roof: Int) {
        val kw = size * 0.9f
        val kh = size * 0.55f
        fill.color = wall
        c.drawRect(cx - kw / 2f, base - kh, cx + kw / 2f, base + size * 0.2f, fill)
        val m = kw / 9f
        for (i in 0 until 9 step 2) c.drawRect(cx - kw / 2f + i * m, base - kh - m * 0.8f, cx - kw / 2f + (i + 1) * m, base - kh, fill)
        fill.color = dark
        c.drawRect(cx - kw * 0.1f, base - kh * 0.45f, cx + kw * 0.1f, base + size * 0.2f, fill)
        for (side in listOf(-1f, 1f)) {
            val tx = cx + side * kw * 0.55f
            val tw = size * 0.26f
            val th = size * 0.85f
            fill.color = wall
            c.drawRect(tx - tw / 2f, base - th, tx + tw / 2f, base + size * 0.2f, fill)
            fill.color = dark
            c.drawRect(tx - tw * 0.12f, base - th * 0.7f, tx + tw * 0.12f, base - th * 0.55f, fill)
            fill.color = roof
            path.reset()
            path.moveTo(tx - tw * 0.62f, base - th)
            path.lineTo(tx, base - th - size * 0.38f)
            path.lineTo(tx + tw * 0.62f, base - th)
            path.close()
            c.drawPath(path, fill)
        }
        val fx = cx + kw * 0.55f
        val fy = base - size * 0.85f - size * 0.38f
        stroke.color = dark
        stroke.strokeWidth = d * 1.2f
        c.drawLine(fx, fy, fx, fy - size * 0.22f, stroke)
        fill.color = 0xFF2F55A8.toInt()
        path.reset()
        path.moveTo(fx, fy - size * 0.22f)
        path.lineTo(fx + size * 0.16f, fy - size * 0.17f)
        path.lineTo(fx, fy - size * 0.12f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun trees(c: Canvas, r: Random, base: Float, n: Int, leaf: Int, dark: Int) {
        repeat(n) {
            val x = r.nextFloat() * w
            val s = d * (9f + r.nextFloat() * 10f)
            val y = base + (r.nextFloat() - 0.5f) * h * 0.02f
            fill.color = dark
            c.drawRect(x - s * 0.12f, y, x + s * 0.12f, y + s * 0.9f, fill)
            fill.color = leaf
            c.drawCircle(x, y - s * 0.2f, s, fill)
            c.drawCircle(x - s * 0.6f, y + s * 0.2f, s * 0.7f, fill)
            c.drawCircle(x + s * 0.6f, y + s * 0.2f, s * 0.7f, fill)
        }
    }

    fun draw(c: Canvas, t: Float) {
        val b = backdrop ?: return
        c.drawBitmap(b, 0f, 0f, null)
        val cloudCol = when (phase) {
            Phase.NIGHT -> 0x40A0B0D8
            Phase.DAY -> 0xB8FFFFFF.toInt()
            else -> 0x80FFD8C0.toInt()
        }
        fill.color = cloudCol
        for (cl in clouds) {
            val span = w + d * 220f
            val x = ((cl.x + cl.speed * t) % span) - d * 110f
            for (p in cl.puffs) {
                val rr = p[2] * cl.s
                oval.set(x + p[0] * cl.s - rr * 1.4f, cl.y + p[1] * cl.s - rr * 0.8f, x + p[0] * cl.s + rr * 1.4f, cl.y + p[1] * cl.s + rr * 0.8f)
                c.drawOval(oval, fill)
            }
        }
        stroke.color = 0xB0202020.toInt()
        stroke.strokeWidth = d * 1.4f
        for (bd in birds) {
            val x = (bd[0] + t * d * 18f) % (w + d * 60f) - d * 30f
            val y = bd[1] + sin(t * 0.7f + bd[0]) * d * 6f
            val s = bd[2] * 0.5f
            val flap = sin(t * 6f + bd[0]) * s * 0.35f
            path.reset()
            path.moveTo(x - s, y - flap)
            path.quadTo(x - s * 0.4f, y - s * 0.3f, x, y)
            path.quadTo(x + s * 0.4f, y - s * 0.3f, x + s, y - flap)
            c.drawPath(path, stroke)
        }
        // A few glints on the lake.
        fill.color = 0x50FFFFFF
        for (i in 0 until 7) {
            val gx = (i * w / 7f + sin(t * 0.5f + i) * d * 20f + w) % w
            val gy = lakeTop + (lakeBottom - lakeTop) * (0.3f + 0.5f * ((i * 37) % 10) / 10f)
            val a = (sin(t * 1.6f + i * 1.3f) * 0.5f + 0.5f)
            fill.alpha = (0x60 * a).toInt()
            oval.set(gx - d * 10f, gy - d * 1f, gx + d * 10f, gy + d * 1f)
            c.drawOval(oval, fill)
        }
        fill.alpha = 255
    }

    companion object {
        fun phaseOf(hour: Int) = when (hour) {
            in 5..7 -> Phase.DAWN
            in 8..16 -> Phase.DAY
            in 17..20 -> Phase.DUSK
            else -> Phase.NIGHT
        }
    }
}
