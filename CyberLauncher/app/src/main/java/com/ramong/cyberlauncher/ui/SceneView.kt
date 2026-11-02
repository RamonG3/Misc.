package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.ramong.cyberlauncher.core.Game
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The live background: Night City (or a dark veil over the phone's own wallpaper), darkened
 * towards the bottom so icons stay readable. It draws nothing interactive.
 */
@SuppressLint("ViewConstructor")
class SceneView(context: Context, private val game: Game) : View(context) {
    /** True while something opaque covers the screen; stops the animation loop. */
    var covered = false
        set(v) {
            field = v
            if (!v) invalidate()
        }

    private val city = City()
    private var cityKey = ""
    private var seed = (System.currentTimeMillis() / 86_400_000L).toInt()
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var frameQueued = false
    private val frame = Runnable {
        frameQueued = false
        invalidate()
    }

    private var glitchStart = 0L
    private var glitchLen = 0L
    private var glitchPower = 0f
    private var nextAmbientGlitch = SystemClock.uptimeMillis() + 9_000L

    private val fill = Paint()
    private val scrim = Paint()
    private val vignette = Paint()

    /** Where the stray cat sits, for the home view's hit test. */
    val catBox: RectF get() = city.catBox

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (game.save.glitch && now > nextAmbientGlitch) {
            nextAmbientGlitch = now + 12_000L + Random.nextLong(14_000L)
            glitch(0.25f, 140L)
        }
        if (!animating()) invalidate()
    }

    fun glitch(strength: Float, length: Long = (180 + strength * 220).toLong()) {
        glitchStart = SystemClock.uptimeMillis()
        glitchLen = length
        glitchPower = strength
        invalidate()
    }

    fun travel() {
        seed = Random.nextInt()
        glitch(0.9f)
    }

    fun themeChanged() {
        cityKey = ""
        invalidate()
    }

    private fun glitchAmount(now: Long): Float {
        if (!game.save.glitch && glitchPower < 0.5f) return 0f
        val e = now - glitchStart
        return if (e in 0 until glitchLen) glitchPower * (1f - e.toFloat() / glitchLen) else 0f
    }

    private fun animating(): Boolean = game.resumed && !covered && !game.stats.powerSave && game.save.scene == 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrim.shader = LinearGradient(0f, h * 0.3f, 0f, h.toFloat(), 0x00000000, 0xC8000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val w = width.toFloat()
        val h = height.toFloat()
        val g = glitchAmount(now)
        val save = game.save
        if (save.scene == 0) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val key = "${width}x$height:${Cy.palette.id}:$seed:${City.phaseOf(hour)}"
            if (key != cityKey && width > 0 && height > 0) {
                cityKey = key
                city.build(width, height, Cy.palette, seed, hour, Cy.density)
            }
            city.draw(c, now, t, dt, save.rain, save.hasCat, g)
        } else {
            fill.shader = LinearGradient(0f, 0f, 0f, h, 0x55000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
        c.drawRect(0f, 0f, w, h, scrim)
        if (g > 0f) drawGlitchBars(c, g)
        drawCritical(c, t)
        if ((animating() || g > 0f) && !frameQueued) {
            frameQueued = true
            postDelayed(frame, if (save.rain || g > 0f) FRAME_MS else SLOW_FRAME_MS)
        }
    }

    private fun drawGlitchBars(c: Canvas, g: Float) {
        val r = Random(SystemClock.uptimeMillis() / 45)
        repeat((g * 7).toInt() + 1) {
            val y = r.nextFloat() * height
            val bh = Cy.dp(1f + r.nextInt(5))
            val x = r.nextFloat() * width * 0.7f
            val bw = width * (0.1f + r.nextFloat() * 0.5f)
            val col = when (r.nextInt(3)) {
                0 -> Cy.SPLIT_CYAN
                1 -> Cy.SPLIT_RED
                else -> Cy.primary
            }
            Cy.rect(c, x, y, x + bw, y + bh, Cy.withAlpha(col, (0x70 * min(1f, g)).toInt()))
        }
    }

    /** Below 15% and not charging, the edges pulse red like low health. */
    private fun drawCritical(c: Canvas, t: Float) {
        val s = game.stats
        if (s.battery > 15 || s.charging) return
        val a = ((sin(t * 3f) * 0.5f + 0.5f) * 0x60).toInt() + 0x20
        vignette.shader = RadialGradient(width / 2f, height / 2f, max(width, height) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, Cy.withAlpha(Cy.DANGER, a)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        frameQueued = false
        super.onDetachedFromWindow()
    }

    companion object {
        const val FRAME_MS = 33L
        const val SLOW_FRAME_MS = 66L
    }
}
