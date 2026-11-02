package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.Banner
import com.ramong.cyberlauncher.core.FeedLine
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Rarity
import com.ramong.cyberlauncher.core.Tag
import kotlin.math.min

/**
 * Banners along the top (level ups, upgrades, finished jobs) and toasts just above the dock
 * (messages and command output). Purely visual: touches go straight through.
 */
@SuppressLint("ViewConstructor")
class NoticeView(context: Context, private val game: Game) : View(context) {
    var topInset = 0
    /** Toasts sit with their bottom edge here (just above the dock). */
    var toastBottom = 0f

    private val banners = ArrayDeque<Banner>()
    private var bannerStart = 0L
    private val toasts = ArrayDeque<FeedLine>()
    private var toastStart = 0L

    private val bTitle = Cy.paint(20f, Cy.Face.BOLD, Paint.Align.CENTER, 0.1f)
    private val bSub = Cy.paint(12.5f, Cy.Face.BOLD, Paint.Align.CENTER, 0.1f)
    private val bExtra = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val tTag = Cy.paint(10f, Cy.Face.MONO)
    private val tBody = Cy.paint(13.5f, Cy.Face.BODY)
    private val box = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = false

    fun banner(b: Banner) {
        if (banners.isEmpty()) bannerStart = SystemClock.uptimeMillis()
        if (banners.size < 5) banners.addLast(b)
        invalidate()
    }

    fun toast(line: FeedLine) {
        if (toasts.isEmpty()) toastStart = SystemClock.uptimeMillis()
        while (toasts.size >= 3) toasts.removeFirst()
        toasts.addLast(line)
        invalidate()
    }

    fun clearToasts() {
        toasts.clear()
        invalidate()
    }

    private fun toastMs(line: FeedLine) = (2200L + line.text.length * 35L).coerceAtMost(6500L)

    private fun tagColor(t: Tag) = when (t) {
        Tag.SYS -> Cy.MUTED
        Tag.XP -> Cy.primary
        Tag.EDDIES, Tag.JOB -> Cy.hi
        Tag.MSG, Tag.SCAN -> Cy.accent
        Tag.NCPD, Tag.ERR -> Cy.DANGER
        Tag.YOU -> Cy.WHITE
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        // Nobody is watching while an app is open, so the clocks only run on the home screen.
        if (!game.resumed) {
            bannerStart = now
            toastStart = now
            return
        }
        var busy = false
        banners.firstOrNull()?.let { b ->
            val e = now - bannerStart
            if (e > BANNER_MS) {
                banners.removeFirst()
                bannerStart = now
            } else {
                drawBanner(c, b, e)
            }
            busy = true
        }
        toasts.firstOrNull()?.let { t ->
            val e = now - toastStart
            if (e > toastMs(t)) {
                toasts.removeFirst()
                toastStart = now
            } else {
                drawToast(c, t, e, toastMs(t))
            }
            busy = true
        }
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawBanner(c: Canvas, b: Banner, e: Long) {
        val k = min((e / 180f).coerceIn(0f, 1f), ((BANNER_MS - e) / 220f).coerceIn(0f, 1f))
        val w = min(width - Cy.dp(24f), Cy.dp(360f))
        val h = Cy.dp(64f)
        val cx = width / 2f
        val top = topInset + Cy.dp(8f)
        box.set(cx - w / 2f * k, top, cx + w / 2f * k, top + h)
        val accent = when (b.kind) {
            Banner.Kind.ITEM -> Rarity.values().firstOrNull { it.title.equals(b.extra, true) }?.color ?: Cy.hi
            Banner.Kind.CALL -> Cy.accent
            else -> Cy.hi
        }
        Cy.panel(c, box, Cy.dp(12f), Cy.withAlpha(Cy.bg, (0xEE * k).toInt()), Cy.withAlpha(accent, (0xC0 * k).toInt()), Cy.TR or Cy.BL)
        if (k < 0.99f) return
        val split = if (e < 400) Cy.dp(4f) * (1f - e / 400f) + Cy.dp(1f) else Cy.dp(1f)
        Cy.split(c, b.title, cx, top + Cy.dp(26f), bTitle, accent, split, Cy.dp(6f))
        if (b.sub.isNotEmpty()) Cy.text(c, Cy.ellipsize(b.sub, bSub, w - Cy.dp(24f)), cx, top + Cy.dp(43f), bSub, Cy.WHITE)
        if (b.extra.isNotEmpty()) Cy.text(c, b.extra, cx, top + Cy.dp(57f), bExtra, if (b.kind == Banner.Kind.ITEM) accent else Cy.dim)
    }

    private fun drawToast(c: Canvas, t: FeedLine, e: Long, total: Long) {
        val a = min((e / 160f).coerceIn(0f, 1f), ((total - e) / 260f).coerceIn(0f, 1f))
        val w = min(width - Cy.dp(32f), Cy.dp(460f))
        val label = "[${t.tag.label}]"
        val tagW = tTag.measureText("$label ")
        val lines = Cy.wrapRich(Cy.rich(t.text, Cy.TEXT), tBody, w - Cy.dp(28f) - tagW).take(3)
        val lineH = Cy.dp(18f)
        val h = Cy.dp(18f) + lines.size * lineH
        val left = (width - w) / 2f
        val bottom = toastBottom + (1f - a) * Cy.dp(10f)
        box.set(left, bottom - h, left + w, bottom)
        Cy.panel(c, box, Cy.dp(10f), Cy.withAlpha(Cy.bg, (0xF0 * a).toInt()), Cy.withAlpha(tagColor(t.tag), (0xB0 * a).toInt()), Cy.TL or Cy.BR, ticks = false)
        var y = box.top + Cy.dp(9f) + lineH * 0.75f
        Cy.text(c, label, box.left + Cy.dp(12f), y, tTag, Cy.withAlpha(tagColor(t.tag), (255 * a).toInt()))
        tBody.alpha = (255 * a).toInt()
        for (l in lines) {
            Cy.drawRich(c, l, box.left + Cy.dp(12f) + tagW, y, tBody)
            y += lineH
        }
        tBody.alpha = 255
    }

    companion object {
        const val BANNER_MS = 2600L
    }
}
