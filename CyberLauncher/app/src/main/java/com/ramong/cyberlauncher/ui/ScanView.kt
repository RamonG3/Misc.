package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.AppIntel
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.ui.panels.ago
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The scanner: brackets close on an app, then everything worth knowing about it. */
@SuppressLint("ViewConstructor")
class ScanView(context: Context, private val game: Game) : View(context) {
    private var app: AppEntry? = null
    private var intel: AppIntel? = null
    private var icon: Drawable? = null
    private var shownAt = 0L
    private var topInset = 0
    private var bottomInset = 0

    private val target = RectF()
    private val panel = RectF()
    private val bar = RectF()
    private val useBtn = RectF()
    private val infoBtn = RectF()
    private val closeBtn = RectF()
    private var pressed: RectF? = null

    private val name = Cy.paint(22f, Cy.Face.BOLD, Paint.Align.CENTER, 0.06f)
    private val klass = Cy.paint(10f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val status = Cy.paint(10f, Cy.Face.MONO, Paint.Align.CENTER, 0.16f)
    private val key = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.12f)
    private val value = Cy.paint(13.5f, Cy.Face.BOLD, spacing = 0.02f)
    private val flavor = Cy.paint(12.5f, Cy.Face.BODY)
    private val btn = Cy.paint(12.5f, Cy.Face.BOLD, Paint.Align.CENTER, 0.1f)
    private val tint = Paint()
    private val sweep = Paint()
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        layoutParts()
    }

    fun show(a: AppEntry) {
        app = a
        intel = game.source.intel(a)
        icon = game.source.icon(a)?.mutate()
        shownAt = SystemClock.uptimeMillis()
        contentBottom = 0f
        visibility = VISIBLE
        layoutParts()
        invalidate()
    }

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        app = null
        icon = null
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val size = Cy.dp(84f)
        val cy = topInset + Cy.dp(96f)
        target.set(w / 2f - size / 2f, cy - size / 2f, w / 2f + size / 2f, cy + size / 2f)
        bar.set(Cy.dp(16f), h - bottomInset - Cy.dp(66f), w - Cy.dp(16f), h - bottomInset - Cy.dp(18f))
        panel.set(Cy.dp(16f), target.bottom + Cy.dp(78f), w - Cy.dp(16f), bar.top - Cy.dp(14f))
        val gap = Cy.dp(6f)
        val bw = (bar.width() - gap * 2) / 3f
        useBtn.set(bar.left, bar.top, bar.left + bw, bar.bottom)
        infoBtn.set(useBtn.right + gap, bar.top, useBtn.right + gap + bw, bar.bottom)
        closeBtn.set(infoBtn.right + gap, bar.top, bar.right, bar.bottom)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            pressed = listOf(useBtn, infoBtn, closeBtn).firstOrNull { it.contains(e.x, e.y) }
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val a = app ?: return true
            when {
                useBtn.contains(e.x, e.y) -> {
                    hide()
                    game.launch(a)
                }
                infoBtn.contains(e.x, e.y) -> {
                    game.haptic()
                    game.source.details(a)
                }
                else -> {
                    game.haptic()
                    hide()
                }
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = null
            invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val a = app ?: return
        val e = SystemClock.uptimeMillis() - shownAt
        val scan = (e / SCAN_MS.toFloat()).coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        val scanColor = Cy.hi
        tint.shader = LinearGradient(0f, 0f, 0f, h, Cy.withAlpha(Cy.mix(Cy.bg, scanColor, 0.12f), 0xF0), Cy.withAlpha(Cy.bg, 0xF6), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, tint)
        Cy.scanlines(c, RectF(0f, 0f, w, h))

        // Target box: brackets close in, then lock.
        val grow = (1f - scan) * Cy.dp(46f)
        val box = RectF(target.left - Cy.dp(14f) - grow, target.top - Cy.dp(14f) - grow, target.right + Cy.dp(14f) + grow, target.bottom + Cy.dp(14f) + grow)
        Cy.brackets(c, box, if (scan >= 1f) scanColor else Cy.withAlpha(scanColor, 0xA0), Cy.dp(14f), Cy.px * 2.5f)
        icon?.let {
            it.setBounds(target.left.toInt(), target.top.toInt(), target.right.toInt(), target.bottom.toInt())
            it.alpha = (110 + 145 * scan).toInt()
            it.draw(c)
            it.alpha = 255
        }
        if (scan < 1f) {
            val y = box.top + box.height() * ((e % 700) / 700f)
            sweep.shader = LinearGradient(0f, y - Cy.dp(16f), 0f, y, 0, Cy.withAlpha(scanColor, 0x90), Shader.TileMode.CLAMP)
            c.drawRect(box.left, y - Cy.dp(16f), box.right, y, sweep)
            Cy.rect(c, box.left, y, box.right, y + Cy.px, scanColor)
        }
        val rarity = game.rarity(a)
        Cy.glow(c, Cy.ellipsize(a.label.uppercase(Locale.US), name, w - Cy.dp(40f)), w / 2f, box.bottom + Cy.dp(30f), name, Cy.WHITE, Cy.dp(6f))
        Cy.text(c, "${rarity.title.uppercase(Locale.US)} · ${a.attr.title.uppercase(Locale.US)} · ${a.attr.skill.uppercase(Locale.US)}", w / 2f, box.bottom + Cy.dp(47f), klass, rarity.color)

        if (scan < 1f) {
            val pct = (scan * 100).toInt()
            Cy.text(c, "SCANNING ... $pct%", w / 2f, panel.top + Cy.dp(24f), status, scanColor)
            val pb = RectF(w * 0.25f, panel.top + Cy.dp(34f), w * 0.75f, panel.top + Cy.dp(38f))
            Cy.bar(c, pb, scan, scanColor)
            postInvalidateOnAnimation()
            return
        }
        drawIntel(c, a)
        drawButtons(c)
        if (e < SCAN_MS + 400) postInvalidateOnAnimation()
    }

    private var contentBottom = 0f

    private fun drawIntel(c: Canvas, a: AppEntry) {
        val it = intel
        // Hug the content once it has been measured (the first frame uses the full height).
        val box = RectF(panel)
        if (contentBottom > 0f) box.bottom = min(panel.bottom, contentBottom + Cy.dp(14f))
        Cy.panel(c, box, Cy.dp(14f), Cy.withAlpha(Cy.bg, 0xE0), Cy.withAlpha(Cy.hi, 0x80), Cy.TR or Cy.BL)
        Cy.text(c, "◢ SCAN COMPLETE", panel.left + Cy.dp(14f), panel.top + Cy.dp(20f), key, Cy.hi)
        Cy.text(c, "OPTICS // ${a.packageName.length * 37 % 900 + 100}", panel.right - Cy.dp(14f) - key.measureText("OPTICS // 000"), panel.top + Cy.dp(20f), key, Cy.MUTED)
        val kx = panel.left + Cy.dp(14f)
        val vx = panel.left + max(Cy.dp(96f), panel.width() * 0.3f)
        val maxW = panel.right - Cy.dp(14f) - vx
        var y = panel.top + Cy.dp(44f)
        val rowH = Cy.dp(21f)
        c.save()
        c.clipRect(panel.left, panel.top, panel.right, panel.bottom - Cy.dp(6f))

        fun row(k: String, v: String, col: Int = Cy.WHITE) {
            Cy.text(c, k, kx, y, key, Cy.MUTED)
            Cy.text(c, Cy.ellipsize(v, value, maxW), vx, y, value, col)
            y += rowH
        }

        row("FACTION", a.packageName, Cy.TEXT)
        if (it == null || !it.readable) {
            row("THREAT", "UNKNOWN", Cy.MUTED)
            row("ACCESS", if (a.isWork) "ENCRYPTED · CORPO SUBNET" else "NO DATA", Cy.MUTED)
        } else {
            val threatCol = when (it.threat) {
                0 -> Cy.accent
                1 -> Cy.hi
                2 -> Cy.primary
                else -> Cy.DANGER
            }
            Cy.text(c, "THREAT", kx, y, key, Cy.MUTED)
            val label = AppIntel.THREATS[it.threat]
            Cy.text(c, label, vx, y, value, threatCol)
            val sx = vx + value.measureText(label) + Cy.dp(10f)
            Cy.segments(c, RectF(sx, y - Cy.dp(9f), min(sx + Cy.dp(90f), panel.right - Cy.dp(14f)), y - Cy.dp(2f)), 5, it.threat + 1f, threatCol, Cy.withAlpha(threatCol, 0x26))
            y += rowH
            Cy.text(c, "ACCESS", kx, y, key, Cy.MUTED)
            val access = if (it.granted.isEmpty()) listOf("NOTHING SENSITIVE") else Cy.wrap(it.granted.joinToString(" · "), value, maxW).take(3)
            for (line in access) {
                Cy.text(c, line, vx, y, value, if (it.granted.isEmpty()) Cy.accent else Cy.WHITE)
                y += rowH
            }
            if (it.denied.isNotEmpty()) {
                Cy.text(c, "DENIED", kx, y, key, Cy.MUTED)
                Cy.text(c, Cy.ellipsize(it.denied.joinToString(" · "), value, maxW), vx, y, value, Cy.MUTED)
                y += rowH
            }
            it.version?.let { v -> row("VERSION", v, Cy.TEXT) }
            if (it.installed > 0) row("INSTALLED", date.format(Date(it.installed)), Cy.TEXT)
            if (it.updated > 0) row("UPDATED", ago(it.updated).let { s -> if (s == "ONLINE") "JUST NOW" else s }, Cy.TEXT)
            row("TARGET SDK", "API ${it.targetSdk}" + if (it.targetSdk in 1..29) " · OUTDATED ICE" else "", if (it.targetSdk in 1..29) Cy.hi else Cy.TEXT)
        }
        val n = game.launches(a)
        row("USES", "$n · LAST SEEN ${ago(game.lastUsed(a))}", Cy.TEXT)
        val bounty = 500L + n * 37L + (intel?.threat ?: 0) * 1_250L + a.label.length * 11L
        row("BOUNTY", "€$ ${Game.number(bounty)}", Cy.hi)
        y += Cy.dp(4f)
        Cy.line(c, kx, y - Cy.dp(12f), panel.right - Cy.dp(14f), y - Cy.dp(12f), Cy.faint)
        val lines = Cy.wrap(Content.threatLines[(intel?.threat ?: 0).coerceIn(0, 4)] + " " + game.scanLine(a), flavor, panel.width() - Cy.dp(28f))
        for (line in lines.take(3)) {
            Cy.text(c, line, kx, y + Cy.dp(4f), flavor, Cy.MUTED)
            y += Cy.dp(17f)
        }
        c.restore()
        if (contentBottom != y) {
            contentBottom = y
            invalidate()
        }
    }

    private fun drawButtons(c: Canvas) {
        for ((r, label, g) in listOf(Triple(useBtn, "USE", Glyph.PLAY), Triple(infoBtn, "SYS INFO", Glyph.INFO), Triple(closeBtn, "CLOSE", Glyph.CLOSE))) {
            Cy.panel(c, r, Cy.dp(8f), if (pressed === r) Cy.withAlpha(Cy.hi, 0x30) else Cy.withAlpha(Cy.bg, 0xD0), Cy.withAlpha(Cy.hi, if (r === useBtn) 0xD0 else 0x70), Cy.TR, ticks = false)
            val tw = btn.measureText(label)
            val gs = Cy.dp(14f)
            val sx = r.centerX() - (gs + Cy.dp(7f) + tw) / 2f
            Glyphs.draw(c, g, sx + gs / 2f, r.centerY(), gs, Cy.hi, 2f)
            Cy.text(c, label, sx + gs + Cy.dp(7f) + tw / 2f, Cy.baseline(btn, r.top, r.bottom), btn, Cy.WHITE)
        }
    }

    companion object {
        const val SCAN_MS = 750L
    }
}
