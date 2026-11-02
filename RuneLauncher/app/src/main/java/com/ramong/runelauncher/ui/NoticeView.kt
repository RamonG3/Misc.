package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ramong.runelauncher.core.Banner
import com.ramong.runelauncher.core.FeedLine
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Skill
import com.ramong.runelauncher.core.Tag
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Banners across the top (level ups, quests, achievements), XP drops floating up under the
 * minimap, and chatbox-style messages just above the dock. Purely visual: touches go through.
 */
@SuppressLint("ViewConstructor")
class NoticeView(context: Context, private val game: Game) : View(context) {
    var topInset = 0
    /** Messages sit with their bottom edge here (just above the dock). */
    var toastBottom = 0f
    /** XP drops start here and float upwards. */
    var dropX = 0f
    var dropY = 0f

    private val banners = ArrayDeque<Banner>()
    private var bannerStart = 0L
    private val toasts = ArrayDeque<FeedLine>()
    private var toastStart = 0L

    private class Drop(val skill: Skill, val amount: Int, val start: Long, val row: Int)

    private val drops = ArrayList<Drop>()

    private class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val life: Float, val start: Long)

    private val sparks = ArrayList<Spark>()

    private val bTitle = R3.paint(22f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.04f)
    private val bSub = R3.paint(16f, R3.Face.BOLD, Paint.Align.CENTER)
    private val bExtra = R3.paint(12.5f, R3.Face.BODY, Paint.Align.CENTER)
    private val tTag = R3.paint(13f, R3.Face.BOLD)
    private val tBody = R3.paint(14.5f, R3.Face.BODY)
    private val dropPaint = R3.paint(16f, R3.Face.BOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
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
        if (b.kind == Banner.Kind.LEVEL || b.kind == Banner.Kind.QUEST) fireworks()
        invalidate()
    }

    fun toast(line: FeedLine) {
        if (toasts.isEmpty()) toastStart = SystemClock.uptimeMillis()
        while (toasts.size >= 3) toasts.removeFirst()
        toasts.addLast(line)
        invalidate()
    }

    fun xpDrop(skill: Skill, amount: Int) {
        val now = SystemClock.uptimeMillis()
        val row = drops.count { now - it.start < 350 }
        drops += Drop(skill, amount, now, row)
        invalidate()
    }

    private fun fireworks() {
        val now = SystemClock.uptimeMillis()
        val colors = intArrayOf(R3.hi, R3.accent, 0xFFFFFFFF.toInt(), 0xFFFF8A3D.toInt(), 0xFF8CE06A.toInt(), 0xFFE070FF.toInt())
        val cx = width / 2f
        val cy = topInset + R3.dp(46f)
        repeat(90) {
            val a = Random.nextDouble(0.0, Math.PI * 2)
            val v = R3.dp(60f + Random.nextFloat() * 180f)
            sparks += Spark(cx + (Random.nextFloat() - 0.5f) * R3.dp(200f), cy, cos(a).toFloat() * v, sin(a).toFloat() * v - R3.dp(40f),
                colors.random(), 1.0f + Random.nextFloat() * 0.9f, now)
        }
    }

    private fun toastMs(line: FeedLine) = (2600L + line.text.length * 40L).coerceAtMost(7000L)

    private fun tagColor(t: Tag) = when (t) {
        Tag.GAME -> R3.hi
        Tag.NEWS -> R3.accent
        Tag.XP -> 0xFF8CE06A.toInt()
        Tag.QUEST -> 0xFFF0D44A.toInt()
        Tag.ERR -> R3.DANGER
        Tag.YOU -> R3.WHITE
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
        if (drops.isNotEmpty()) {
            drawDrops(c, now)
            busy = true
        }
        if (sparks.isNotEmpty()) {
            drawSparks(c, now)
            busy = true
        }
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawBanner(c: Canvas, b: Banner, e: Long) {
        val k = min((e / 200f).coerceIn(0f, 1f), ((BANNER_MS - e) / 260f).coerceIn(0f, 1f))
        val w = min(width - R3.dp(24f), R3.dp(380f))
        val h = R3.dp(92f)
        val cx = width / 2f
        val top = topInset + R3.dp(10f) - (1f - k) * R3.dp(30f)
        box.set(cx - w / 2f, top, cx + w / 2f, top + h)
        c.save()
        c.saveLayerAlpha(box.left - R3.dp(10f), box.top - R3.dp(10f), box.right + R3.dp(10f), box.bottom + R3.dp(10f), (255 * k).toInt())
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF2), R3.dp(14f))
        val skill = b.skill
        val textCx = if (skill != null) cx + R3.dp(28f) else cx
        if (skill != null) R3.skillBadge(c, skill, box.left + R3.dp(46f), box.centerY(), R3.dp(64f))
        else if (b.kind == Banner.Kind.ACHIEVEMENT) Glyphs.draw(c, Glyph.STAR, box.left + R3.dp(40f), box.centerY(), R3.dp(34f), R3.hi)
        else if (b.kind == Banner.Kind.QUEST) Glyphs.draw(c, Glyph.BOOK, box.left + R3.dp(40f), box.centerY(), R3.dp(32f), R3.hi, 2f)
        val tc = if (skill != null || b.kind == Banner.Kind.ACHIEVEMENT || b.kind == Banner.Kind.QUEST) textCx else cx
        val tw = w - R3.dp(110f)
        R3.text(c, b.title, tc, top + R3.dp(32f), bTitle, R3.hi, shadow = true)
        if (b.sub.isNotEmpty()) R3.text(c, R3.ellipsize(b.sub, bSub, tw), tc, top + R3.dp(56f), bSub, R3.WHITE)
        if (b.extra.isNotEmpty()) R3.text(c, R3.ellipsize(b.extra, bExtra, tw), tc, top + R3.dp(76f), bExtra, R3.MUTED)
        c.restore()
        c.restore()
    }

    private fun drawToast(c: Canvas, t: FeedLine, e: Long, total: Long) {
        val a = min((e / 160f).coerceIn(0f, 1f), ((total - e) / 260f).coerceIn(0f, 1f))
        val w = min(width - R3.dp(28f), R3.dp(460f))
        val label = "${t.tag.label}:"
        val tagW = tTag.measureText("$label ")
        val lines = R3.wrapRich(R3.rich(t.text, R3.TEXT), tBody, w - R3.dp(32f) - tagW).take(4)
        val lineH = R3.dp(20f)
        val h = R3.dp(22f) + lines.size * lineH
        val left = (width - w) / 2f
        val bottom = toastBottom + (1f - a) * R3.dp(10f)
        box.set(left, bottom - h, left + w, bottom)
        c.saveLayerAlpha(box.left - R3.dp(8f), box.top - R3.dp(8f), box.right + R3.dp(8f), box.bottom + R3.dp(8f), (255 * a).toInt())
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF0), R3.dp(10f), studs = false)
        var y = box.top + R3.dp(11f) + lineH * 0.72f
        R3.text(c, label, box.left + R3.dp(14f), y, tTag, tagColor(t.tag))
        for (l in lines) {
            R3.drawRich(c, l, box.left + R3.dp(14f) + tagW, y, tBody)
            y += lineH
        }
        c.restore()
    }

    private fun drawDrops(c: Canvas, now: Long) {
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val u = (now - d.start) / DROP_MS.toFloat()
            if (u >= 1f) {
                it.remove()
                continue
            }
            val alpha = if (u > 0.7f) ((1f - u) / 0.3f * 255).toInt() else 255
            // Under the banner while one is showing, so the two never overlap.
            val base = if (banners.isNotEmpty()) maxOf(dropY, topInset + R3.dp(170f)) else dropY
            val y = base + d.row * R3.dp(26f) - u * R3.dp(if (banners.isNotEmpty()) 30f else 70f)
            val text = "+${d.amount}"
            val iconSize = R3.dp(22f)
            val tw = dropPaint.measureText(text)
            val x = dropX - (iconSize + R3.dp(6f) + tw) / 2f
            R3.skill(c, d.skill, x + iconSize / 2f, y - R3.dp(5f), iconSize, alpha)
            dropPaint.alpha = alpha
            dropPaint.setShadowLayer(R3.dp(2f), 0f, R3.dp(1f), R3.withAlpha(R3.BLACK, alpha))
            dropPaint.color = R3.withAlpha(R3.WHITE, alpha)
            c.drawText(text, x + iconSize + R3.dp(6f), y, dropPaint)
            dropPaint.clearShadowLayer()
        }
        dropPaint.alpha = 255
    }

    private fun drawSparks(c: Canvas, now: Long) {
        val it = sparks.iterator()
        val g = R3.dp(170f)
        val size = R3.dp(2.6f)
        while (it.hasNext()) {
            val s = it.next()
            val t = (now - s.start) / 1000f
            if (t > s.life) {
                it.remove()
                continue
            }
            val x = s.x + s.vx * t
            val y = s.y + s.vy * t + 0.5f * g * t * t
            fill.color = R3.withAlpha(s.color, ((1f - t / s.life) * 255).toInt())
            c.drawCircle(x, y, size, fill)
        }
    }

    companion object {
        const val BANNER_MS = 3200L
        const val DROP_MS = 1500L
    }
}
