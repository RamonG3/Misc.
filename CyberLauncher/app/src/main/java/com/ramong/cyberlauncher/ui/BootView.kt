package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Lifepath
import com.ramong.cyberlauncher.core.Tag
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

/** Full-screen moments: the boot readout, picking a lifepath, and the breach animation. */
@SuppressLint("ViewConstructor")
class BootView(context: Context, private val game: Game) : View(context) {
    enum class Mode { BOOT, LIFEPATH, BREACH }

    var mode = Mode.BOOT
        private set
    private var started = 0L
    private var lifepathAfter = false
    private var closing = 0L
    private var topInset = 0
    private var bottomInset = 0
    private val cards = Array(3) { RectF() }
    private var pressedCard = -1

    private val line = Cy.paint(11.5f, Cy.Face.MONO, spacing = 0.04f)
    private val big = Cy.paint(26f, Cy.Face.BOLD, Paint.Align.CENTER, 0.12f)
    private val subCenter = Cy.paint(13f, Cy.Face.BODY, Paint.Align.CENTER)
    private val cardTitle = Cy.paint(22f, Cy.Face.BOLD, spacing = 0.1f)
    private val cardTag = Cy.paint(13.5f, Cy.Face.BOLD)
    private val cardStory = Cy.paint(12.5f, Cy.Face.BODY)
    private val hex = Cy.paint(13f, Cy.Face.MONO, Paint.Align.CENTER)
    private val tiny = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.14f)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
    }

    fun boot(thenLifepath: Boolean) {
        lifepathAfter = thenLifepath
        start(if (game.save.boot) Mode.BOOT else Mode.LIFEPATH)
    }

    fun breach() = start(Mode.BREACH)

    private fun start(m: Mode) {
        mode = m
        started = SystemClock.uptimeMillis()
        closing = 0L
        pressedCard = -1
        visibility = VISIBLE
        invalidate()
    }

    private fun finish() {
        if (closing == 0L) closing = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun done() {
        visibility = INVISIBLE
        when (mode) {
            Mode.BOOT -> if (lifepathAfter) {
                lifepathAfter = false
                start(Mode.LIFEPATH)
                return
            }
            Mode.BREACH -> game.feed(Tag.SYS, "Breach successful. You hacked your own phone. Proud of you.")
            Mode.LIFEPATH -> Unit
        }
        game.ui.invalidateAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.LIFEPATH) {
            val i = cards.indexOfFirst { it.contains(event.x, event.y) }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> pressedCard = i
                MotionEvent.ACTION_UP -> {
                    if (i >= 0 && closing == 0L) {
                        game.haptic()
                        game.chooseLifepath(Lifepath.values()[i], first = true)
                        finish()
                    }
                    pressedCard = -1
                }
                MotionEvent.ACTION_CANCEL -> pressedCard = -1
            }
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) finish()
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!showing) return
        val now = SystemClock.uptimeMillis()
        val e = now - started
        val w = width.toFloat()
        val h = height.toFloat()
        val out = if (closing > 0L) ((now - closing) / OUT_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (out >= 1f) {
            done()
            return
        }
        Cy.rect(c, 0f, 0f, w, h, Cy.withAlpha(0xFF000000.toInt(), ((if (mode == Mode.LIFEPATH) 0xF4 else 0xFF) * (1f - out)).toInt()))
        when (mode) {
            Mode.BOOT -> drawBoot(c, e, w, h)
            Mode.LIFEPATH -> drawLifepath(c, w, h)
            Mode.BREACH -> drawBreach(c, e, w, h)
        }
        if (out > 0f) {
            val r = Random(now / 30)
            repeat(10) {
                val y = r.nextFloat() * h
                Cy.rect(c, 0f, y, w, y + Cy.dp(2f + r.nextInt(10)), Cy.withAlpha(if (r.nextBoolean()) Cy.SPLIT_CYAN else Cy.SPLIT_RED, (0x80 * (1f - out)).toInt()))
            }
        }
        when (mode) {
            Mode.BOOT -> if (e > BOOT_MS && closing == 0L) finish()
            Mode.BREACH -> if (e > BREACH_MS && closing == 0L) finish()
            Mode.LIFEPATH -> Unit
        }
        // The lifepath cards sit still until touched; everything else is animated.
        if (mode != Mode.LIFEPATH || closing > 0L) postInvalidateOnAnimation()
    }

    private fun drawBoot(c: Canvas, e: Long, w: Float, h: Float) {
        val frame = RectF(Cy.dp(10f), topInset + Cy.dp(10f), w - Cy.dp(10f), h - bottomInset - Cy.dp(10f))
        Cy.scanlines(c, frame)
        Cy.brackets(c, frame, Cy.dim, Cy.dp(22f), Cy.px * 1.5f)
        // A column of junk hex down the right edge, scrolling like a memory dump.
        val r = Random(e / 90)
        var hy = topInset + Cy.dp(330f)
        while (hy < frame.bottom - Cy.dp(90f)) {
            val hexLine = String.format(Locale.US, "%04X %02X%02X %02X%02X", r.nextInt(0xFFFF), r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256))
            Cy.text(c, hexLine, frame.right - Cy.dp(12f) - line.measureText(hexLine), hy, line, Cy.withAlpha(Cy.primary, 0x30))
            hy += Cy.dp(16f)
        }
        val x = Cy.dp(22f)
        var y = topInset + Cy.dp(90f)
        Cy.split(c, "CYBERLAUNCHER", x, y, big.apply { textAlign = Paint.Align.LEFT }, Cy.primary, Cy.dp(2f), Cy.dp(8f))
        big.textAlign = Paint.Align.CENTER
        y += Cy.dp(16f)
        Cy.text(c, "NEURAL OS 20.77 // NIGHT CITY BUILD", x, y, line, Cy.MUTED)
        y += Cy.dp(34f)
        val shown = (e / LINE_MS).toInt().coerceAtMost(Content.bootLines.size)
        for (i in 0 until shown) {
            val raw = Content.bootLines[i]
                .replace("%RAM%", String.format(Locale.US, "%.1f GB", game.stats.ramTotalGb))
                .replace("%APPS%", "${game.apps.size}")
                .replace("%NAME%", game.save.name.uppercase(Locale.US))
            val col = when {
                raw.endsWith("OK") -> Cy.accent
                raw.startsWith("WELCOME") -> Cy.hi
                raw.endsWith("JAMMED") || raw.endsWith("IGNORED") -> Cy.primary
                else -> Cy.TEXT
            }
            Cy.text(c, raw, x, y, line, col)
            y += Cy.dp(20f)
        }
        if ((e / 300) % 2 == 0L) Cy.rect(c, x, y - Cy.dp(11f), x + Cy.dp(8f), y + Cy.dp(2f), Cy.accent)
        val p = (e / BOOT_MS.toFloat()).coerceIn(0f, 1f)
        val bar = RectF(x, h - bottomInset - Cy.dp(70f), w - x, h - bottomInset - Cy.dp(64f))
        Cy.segments(c, bar, 24, p * 24, Cy.primary, Cy.withAlpha(Cy.primary, 0x22))
        Cy.text(c, "TAP TO SKIP", w / 2f, bar.bottom + Cy.dp(22f), tiny, Cy.MUTED)
    }

    private fun drawLifepath(c: Canvas, w: Float, h: Float) {
        val top = topInset + Cy.dp(28f)
        Cy.split(c, "CHOOSE YOUR LIFEPATH", w / 2f, top + Cy.dp(30f), big, Cy.hi, Cy.dp(1.6f), Cy.dp(8f))
        Cy.text(c, "It sets your colours. Your past is your own business.", w / 2f, top + Cy.dp(54f), subCenter, Cy.MUTED)
        val avail = h - bottomInset - Cy.dp(40f) - (top + Cy.dp(76f))
        val gap = Cy.dp(12f)
        val ch = min(Cy.dp(148f), (avail - gap * 2) / 3f)
        var y = top + Cy.dp(76f) + (avail - ch * 3 - gap * 2) * 0.35f
        val m = Cy.dp(18f)
        Lifepath.values().forEachIndexed { i, lp ->
            val p = Content.palette(lp.palette)
            val r = cards[i]
            r.set(m, y, w - m, y + ch)
            val pressed = pressedCard == i
            Cy.panel(c, r, Cy.dp(16f), Cy.mix(p.bg, if (pressed) p.primary else 0xFF000000.toInt(), if (pressed) 0.18f else 0.1f),
                if (pressed) p.hi else p.primary, Cy.TR or Cy.BL, ticks = false)
            Cy.rect(c, r.left, r.top + Cy.dp(12f), r.left + Cy.dp(3f), r.bottom - Cy.dp(12f), p.primary)
            val tx = r.left + Cy.dp(18f)
            cardTitle.setShadowLayer(Cy.dp(8f), 0f, 0f, Cy.withAlpha(p.primary, 0xA0))
            Cy.text(c, lp.title.uppercase(Locale.US), tx, r.top + Cy.dp(34f), cardTitle, p.primary)
            cardTitle.clearShadowLayer()
            var sx = r.right - Cy.dp(18f) - 3 * Cy.dp(13f)
            for (col in listOf(p.primary, p.accent, p.hi)) {
                Cy.rect(c, sx, r.top + Cy.dp(20f), sx + Cy.dp(10f), r.top + Cy.dp(32f), col)
                sx += Cy.dp(13f)
            }
            Cy.text(c, lp.tagline, tx, r.top + Cy.dp(56f), cardTag, p.accent)
            var ly = r.top + Cy.dp(76f)
            for (s in Cy.wrap(lp.story, cardStory, r.width() - Cy.dp(36f))) {
                if (ly > r.bottom - Cy.dp(10f)) break
                Cy.text(c, s, tx, ly, cardStory, Cy.TEXT)
                ly += Cy.dp(17f)
            }
            y += ch + gap
        }
        Cy.text(c, "TAP A LIFEPATH · CHANGE IT LATER IN SETTINGS", w / 2f, y + Cy.dp(10f), tiny, Cy.MUTED)
    }

    private fun drawBreach(c: Canvas, e: Long, w: Float, h: Float) {
        val codes = arrayOf("1C", "55", "BD", "E9", "7A", "FF")
        val cols = 6
        val cell = min((w - Cy.dp(48f)) / cols, Cy.dp(52f))
        val gridW = cell * cols
        val x0 = (w - gridW) / 2f
        val y0 = topInset + Cy.dp(130f)
        val r = Random(7)
        val step = (e / 110).toInt()
        Cy.split(c, "BREACH PROTOCOL", w / 2f, topInset + Cy.dp(70f), big, Cy.primary, Cy.dp(1.6f), Cy.dp(8f))
        Cy.text(c, "CODE MATRIX // UPLOADING DAEMON", w / 2f, topInset + Cy.dp(94f), tiny, Cy.MUTED)
        val frame = RectF(x0 - Cy.dp(10f), y0 - Cy.dp(10f), x0 + gridW + Cy.dp(10f), y0 + cell * cols + Cy.dp(10f))
        Cy.panel(c, frame, Cy.dp(14f), Cy.withAlpha(Cy.bg, 0xE0), Cy.dim, Cy.TR or Cy.BL)
        val col = step % cols
        val row = (step / 2) % cols
        for (yy in 0 until cols) for (xx in 0 until cols) {
            val code = codes[r.nextInt(codes.size)]
            val cx = x0 + cell * (xx + 0.5f)
            val cy = y0 + cell * (yy + 0.62f)
            val hot = xx == col || yy == row
            val hit = xx == col && yy == row
            if (hot) Cy.rect(c, x0 + cell * xx + Cy.dp(2f), y0 + cell * yy + Cy.dp(2f), x0 + cell * (xx + 1) - Cy.dp(2f), y0 + cell * (yy + 1) - Cy.dp(2f), Cy.withAlpha(Cy.accent, if (hit) 0x50 else 0x18))
            Cy.text(c, code, cx, cy, hex, if (hit) Cy.hi else if (hot) Cy.accent else Cy.withAlpha(Cy.primary, 0x90))
        }
        val p = (e / BREACH_MS.toFloat()).coerceIn(0f, 1f)
        val bar = RectF(frame.left, frame.bottom + Cy.dp(28f), frame.right, frame.bottom + Cy.dp(36f))
        Cy.segments(c, bar, 20, p * 20, Cy.accent, Cy.withAlpha(Cy.accent, 0x22))
        val msg = if (p >= 1f) "BREACH SUCCESSFUL" else "BUFFER ${(p * 100).toInt()}%"
        Cy.text(c, msg, w / 2f, bar.bottom + Cy.dp(24f), tiny, if (p >= 1f) Cy.hi else Cy.accent)
    }

    companion object {
        const val LINE_MS = 120L
        const val BOOT_MS = 1350L
        const val BREACH_MS = 1700L
        const val OUT_MS = 260L
    }
}
