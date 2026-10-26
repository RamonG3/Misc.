package com.ramong.runelauncher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.ramong.runelauncher.core.ChatEffects
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Emote(val label: String, val seconds: Float) {
    YES("Yes", 1.2f), NO("No", 1.2f), BOW("Bow", 1.6f), ANGRY("Angry", 1.6f),
    THINK("Think", 2f), WAVE("Wave", 1.8f), SHRUG("Shrug", 1.3f), CHEER("Cheer", 1.6f),
    BECKON("Beckon", 1.5f), LAUGH("Laugh", 1.6f), JUMP("Jump for Joy", 1.6f), YAWN("Yawn", 2f),
    DANCE("Dance", 2.4f), JIG("Jig", 2.4f), SPIN("Spin", 1.6f), HEADBANG("Headbang", 2f),
    CRY("Cry", 2f), KISS("Blow Kiss", 1.6f), PANIC("Panic", 2f), RASPBERRY("Raspberry", 1.5f),
    CLAP("Clap", 1.6f), SALUTE("Salute", 1.5f), CAST("Cast", 0.9f);

    companion object {
        val menu: List<Emote> get() = values().filter { it != CAST }

        fun find(name: String): Emote? {
            val n = name.trim().lowercase().replace(" ", "")
            if (n.isEmpty()) return null
            return values().firstOrNull { it.name.lowercase() == n || it.label.lowercase().replace(" ", "") == n }
        }
    }
}

/** Joint angles for one frame. Arm angle 0 hangs down; positive swings outwards, negative across the body. */
class Pose {
    var bodyY = 0f
    var squash = 1f
    var headDx = 0f
    var headDy = 0f
    var headTilt = 0f
    var headTurn = 1f
    var armA = 6f
    var armB = 6f
    var armALen = 1f
    var armBLen = 1f
    var legA = 0f
    var legB = 0f
    var lift = 0f
    var scaleX = 1f
    var shake = 0f

    fun reset() {
        bodyY = 0f; squash = 1f; headDx = 0f; headDy = 0f; headTilt = 0f; headTurn = 1f
        armA = 6f; armB = 6f; armALen = 1f; armBLen = 1f; legA = 0f; legB = 0f; lift = 0f; scaleX = 1f; shake = 0f
    }
}

/** A blocky, front-facing adventurer drawn from rectangles, 33 "art pixels" tall. */
class Avatar {
    private val p = Paint()
    private val pose = Pose()

    private val shirt = 0xFF3D5AA8.toInt()
    private val shirtDark = 0xFF2C4280.toInt()
    private val trousers = 0xFF5A4632.toInt()
    private val trousersDark = 0xFF46362A.toInt()
    private val boots = 0xFF2B2018.toInt()
    private val skin = 0xFFD7A176.toInt()
    private val skinDark = 0xFFB07F55.toInt()
    private val hair = 0xFF4A2E18.toInt()
    private val belt = 0xFF2A1E12.toInt()

    private fun env(u: Float): Float {
        val a = (u / 0.15f).coerceIn(0f, 1f)
        val b = ((1f - u) / 0.15f).coerceIn(0f, 1f)
        return min(a, b)
    }

    private fun wave(t: Float, hz: Float) = sin(t * 2f * PI.toFloat() * hz)

    fun pose(e: Emote?, t: Float, breathe: Boolean): Pose {
        val q = pose
        q.reset()
        if (e == null) {
            if (breathe) q.bodyY = 0.4f
            return q
        }
        val u = (t / e.seconds).coerceIn(0f, 1f)
        val k = env(u)
        when (e) {
            Emote.YES -> q.headDy = wave(t, 2.5f) * 1.2f
            Emote.NO -> {
                q.headTurn = cos(t * 2f * PI.toFloat() * 2.2f) * 0.25f + 0.75f
                q.headDx = wave(t, 2.2f) * 1.2f
            }
            Emote.BOW -> {
                q.squash = 1f - 0.35f * k
                q.armA = 12f * k + 6f
                q.armB = q.armA
            }
            Emote.ANGRY -> {
                q.armA = (150f + wave(t, 4f) * 15f) * k
                q.armB = (150f - wave(t, 4f) * 15f) * k
                q.armALen = 0.85f
                q.armBLen = 0.85f
                q.shake = wave(t, 9f) * 0.5f * k
            }
            Emote.THINK -> {
                q.armB = -150f * k
                q.armBLen = 1f - 0.4f * k
                q.headTilt = 8f * k
            }
            Emote.WAVE -> q.armB = (150f + wave(t, 2.5f) * 25f) * k
            Emote.SHRUG -> {
                q.armA = 45f * k
                q.armB = 45f * k
                q.armALen = 0.85f
                q.armBLen = 0.85f
                q.headDy = 0.8f * k
            }
            Emote.CHEER -> {
                q.armA = (165f + wave(t, 3f) * 10f) * k
                q.armB = (165f - wave(t, 3f) * 10f) * k
                q.lift = abs(wave(t, 1.5f)) * 4f * k
            }
            Emote.BECKON -> {
                q.armB = (70f + wave(t, 3f) * 25f) * k
                q.armBLen = 0.8f
            }
            Emote.LAUGH -> {
                q.shake = wave(t, 6f) * 0.6f * k
                q.headDy = -0.8f * k
                q.bodyY = abs(wave(t, 3f)) * 0.8f * k
            }
            Emote.JUMP -> {
                q.lift = abs(wave(t, 1.25f)) * 9f
                q.armA = 160f * k
                q.armB = 160f * k
            }
            Emote.YAWN -> {
                q.armA = 175f * k
                q.armB = 175f * k
                q.headDy = -0.8f * k
            }
            Emote.DANCE -> {
                q.legA = wave(t, 2f) * 20f
                q.legB = -q.legA
                q.armA = 60f + wave(t, 2f) * 40f
                q.armB = 60f - wave(t, 2f) * 40f
                q.bodyY = abs(wave(t, 2f)) * 1f
            }
            Emote.JIG -> {
                q.lift = abs(wave(t, 3f)) * 3f
                q.legA = wave(t, 3f) * 25f
                q.armA = 30f
                q.armB = 30f
            }
            Emote.SPIN -> q.scaleX = cos(t * 2f * PI.toFloat() * 1.5f)
            Emote.HEADBANG -> {
                q.headDy = wave(t, 3f) * 2f + 1f
                q.armA = 35f
                q.armB = -60f
                q.armBLen = 0.8f
            }
            Emote.CRY -> {
                q.armA = -155f * k
                q.armB = -155f * k
                q.armALen = 0.6f
                q.armBLen = 0.6f
                q.headDy = 1f * k
                q.shake = wave(t, 8f) * 0.4f * k
            }
            Emote.KISS -> {
                if (u < 0.5f) {
                    q.armB = -150f * k
                    q.armBLen = 0.55f
                } else {
                    q.armB = 100f * k
                }
            }
            Emote.PANIC -> {
                q.armA = 170f + wave(t, 5f) * 15f
                q.armB = 170f - wave(t, 5f) * 15f
                q.legA = wave(t, 4f) * 25f
                q.legB = -q.legA
                q.lift = abs(wave(t, 4f)) * 1.5f
            }
            Emote.RASPBERRY -> {
                q.shake = wave(t, 1.5f) * 1.5f
                q.headTurn = cos(t * 2f * PI.toFloat() * 3f) * 0.15f + 0.85f
            }
            Emote.CLAP -> {
                q.armA = -60f + wave(t, 3f) * 20f
                q.armB = q.armA
                q.armALen = 0.8f
                q.armBLen = 0.8f
            }
            Emote.SALUTE -> {
                q.armB = -150f * k
                q.armBLen = 1f - 0.25f * k
            }
            Emote.CAST -> {
                q.armA = 120f * k
                q.armB = 120f * k
                q.lift = 1f * k
            }
        }
        return q
    }

    /** Draws with the feet at ([x],[y]); [s] is the size of one art pixel. */
    fun draw(c: Canvas, x: Float, y: Float, s: Float, q: Pose) {
        c.save()
        c.translate(x + q.shake * s, y - q.lift * s)
        c.scale(s * q.scaleX, s)
        leg(c, -1.9f, q.legA)
        leg(c, 1.9f, q.legB)
        c.save()
        c.translate(0f, -12f + q.bodyY)
        c.scale(1f, q.squash)
        box(c, -4.5f, -11f, 4.5f, 0f, shirt)
        box(c, 2.5f, -11f, 4.5f, 0f, shirtDark)
        box(c, -4.5f, -1.6f, 4.5f, 0f, belt)
        c.restore()
        val shoulderY = -12f + q.bodyY - 10f * q.squash
        arm(c, -5.6f, shoulderY, q.armA, q.armALen)
        arm(c, 5.6f, shoulderY, -q.armB, q.armBLen)
        c.save()
        c.translate(q.headDx, -12f + q.bodyY - 11f * q.squash + q.headDy)
        c.rotate(q.headTilt)
        c.scale(q.headTurn, 1f)
        box(c, -1f, -1.5f, 1f, 0.5f, skinDark)
        box(c, -3.5f, -8.5f, 3.5f, -1.5f, skin)
        box(c, 1.5f, -8.5f, 3.5f, -1.5f, skinDark)
        box(c, -3.8f, -9.6f, 3.8f, -6.8f, hair)
        box(c, -3.8f, -6.8f, -2.8f, -3.8f, hair)
        box(c, 2.8f, -6.8f, 3.8f, -3.8f, hair)
        box(c, -2.2f, -5.4f, -1.0f, -4.2f, 0xFF1A1A1A.toInt())
        box(c, 1.0f, -5.4f, 2.2f, -4.2f, 0xFF1A1A1A.toInt())
        box(c, -1.0f, -3.0f, 1.0f, -2.4f, 0xFF8A4A3A.toInt())
        c.restore()
        c.restore()
    }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.color = color
        c.drawRect(l, t, r, b, p)
    }

    private fun leg(c: Canvas, hx: Float, angle: Float) {
        c.save()
        c.translate(hx, -12f)
        c.rotate(angle)
        box(c, -1.8f, 0f, 1.8f, 10.2f, if (hx < 0) trousers else trousersDark)
        box(c, -1.9f, 10.2f, 2.1f, 12f, boots)
        c.restore()
    }

    private fun arm(c: Canvas, sx: Float, sy: Float, angle: Float, len: Float) {
        c.save()
        c.translate(sx, sy)
        c.rotate(angle)
        box(c, -1.3f, -0.5f, 1.3f, 8.6f * len, if (sx < 0) shirt else shirtDark)
        box(c, -1.2f, 8.6f * len, 1.2f, 10.4f * len, skin)
        c.restore()
    }
}

/** Yellow overhead chat with RuneScape's colour/motion effects. */
class Overhead(raw: String, val start: Long) {
    private val parsed = ChatEffects.parse(raw)
    val text: String = parsed.body
    val duration = if (parsed.effect == "scroll" || parsed.effect == "slide") 4500L else 3500L

    fun alive(now: Long) = now - start < duration

    private fun colorAt(t: Float, i: Int): Int {
        val phase = ((t * 1000f) / 200f).toInt() % 2 == 0
        return when (parsed.color) {
            "red" -> Rs.RED
            "green" -> Rs.GREEN
            "cyan" -> Rs.CYAN
            "purple" -> 0xFFFF00FF.toInt()
            "white" -> Rs.WHITE
            "flash1" -> if (phase) Rs.RED else Rs.YELLOW
            "flash2" -> if (phase) Rs.CYAN else 0xFF0000FF.toInt()
            "flash3" -> if (phase) 0xFF00B000.toInt() else 0xFF80FF80.toInt()
            "glow1" -> Color.HSVToColor(floatArrayOf(((t * 120f) + i * 4f) % 240f, 1f, 1f))
            "glow2" -> Color.HSVToColor(floatArrayOf(240f + ((t * 90f) % 120f), 1f, 1f))
            "glow3" -> Color.HSVToColor(floatArrayOf(90f + ((t * 60f) % 90f), 0.6f, 1f))
            else -> Rs.YELLOW
        }
    }

    fun draw(c: Canvas, cx: Float, baseY: Float, p: Paint, now: Long, maxW: Float) {
        val t = (now - start) / 1000f
        val u = (now - start).toFloat() / duration
        val total = p.measureText(text)
        val align = p.textAlign
        p.textAlign = Paint.Align.LEFT
        var x0 = cx - total / 2f
        var dyAll = 0f
        when (parsed.effect) {
            "scroll" -> x0 = cx + maxW / 2f - (maxW + total) * u
            "slide" -> dyAll = when {
                u < 0.12f -> -(1f - u / 0.12f) * Rs.dp(20f)
                u > 0.88f -> ((u - 0.88f) / 0.12f) * Rs.dp(20f)
                else -> 0f
            }
        }
        val o = Rs.shadowOf(p)
        for (i in text.indices) {
            val x = x0 + p.measureText(text, 0, i)
            var dx = 0f
            var dy = dyAll
            when (parsed.effect) {
                "wave" -> dy += sin(t * 7f + i * 0.6f) * Rs.dp(3.5f)
                "wave2" -> {
                    dy += sin(t * 7f + i * 0.6f) * Rs.dp(3.5f)
                    dx = cos(t * 7f + i * 0.6f) * Rs.dp(2.5f)
                }
                "shake" -> if (u < 0.5f) dy += sin(t * 40f + i) * Rs.dp(3f) * (1f - u * 2f)
            }
            p.color = Rs.BLACK
            c.drawText(text, i, i + 1, x + dx + o, baseY + dy + o, p)
            p.color = colorAt(t, i)
            c.drawText(text, i, i + 1, x + dx, baseY + dy, p)
        }
        p.textAlign = align
    }
}
