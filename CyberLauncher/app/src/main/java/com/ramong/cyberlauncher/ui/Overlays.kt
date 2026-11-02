package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.Choice
import com.ramong.cyberlauncher.core.ItemMenu
import com.ramong.cyberlauncher.core.MenuOption
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The long-press menu: item name in its rarity colour, then actions with key hints. */
@SuppressLint("ViewConstructor")
class MenuView(context: Context) : View(context) {
    private var menu: ItemMenu? = null
    private var options: List<MenuOption> = emptyList()
    private val box = RectF()
    private val row = RectF()
    private val keyBox = RectF()
    private var rowH = 0f
    private var headH = 0f
    private var pressed = -1
    private var shownAt = 0L
    private val titlePaint = Cy.paint(16f, Cy.Face.BOLD, spacing = 0.06f)
    private val subPaint = Cy.paint(9f, Cy.Face.MONO, spacing = 0.08f)
    private val verb = Cy.paint(14f, Cy.Face.BOLD, spacing = 0.08f)
    private val key = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE

    fun show(m: ItemMenu) {
        menu = m
        options = m.options + MenuOption("Cancel", "ESC")
        rowH = Cy.dp(42f)
        headH = Cy.dp(52f)
        val longest = options.maxOf { verb.measureText(it.verb.uppercase(Locale.US)) }
        val w = min(
            max(max(Cy.dp(220f), longest + Cy.dp(76f)), min(titlePaint.measureText(m.title), Cy.dp(280f)) + Cy.dp(32f)),
            ((parent as? View)?.width?.toFloat() ?: width.toFloat()) - Cy.dp(16f),
        )
        val h = headH + options.size * rowH + Cy.dp(8f)
        val pw = (parent as? View)?.width?.toFloat() ?: width.toFloat()
        val ph = (parent as? View)?.height?.toFloat() ?: height.toFloat()
        val l = (m.x - w / 2f).coerceIn(Cy.dp(8f), max(Cy.dp(8f), pw - w - Cy.dp(8f)))
        val t = (m.y - headH / 2f).coerceIn(Cy.dp(36f), max(Cy.dp(36f), ph - h - Cy.dp(24f)))
        box.set(l, t, l + w, t + h)
        pressed = -1
        shownAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (!box.contains(x, y) || y < box.top + headH) return -1
        val i = ((y - box.top - headH) / rowH).toInt()
        return if (i in options.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!box.contains(event.x, event.y)) {
                    hide()
                    return true
                }
                pressed = rowAt(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pressed = rowAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val i = rowAt(event.x, event.y)
                if (i >= 0) {
                    val o = options[i]
                    hide()
                    o.action()
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val m = menu ?: return
        if (!showing) return
        val k = ((SystemClock.uptimeMillis() - shownAt) / 120f).coerceIn(0f, 1f)
        Cy.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), Cy.withAlpha(0xFF000000.toInt(), (0x60 * k).toInt()))
        c.save()
        c.clipRect(box.left - Cy.dp(4f), box.top - Cy.dp(4f), box.right + Cy.dp(4f), box.top + box.height() * k + Cy.dp(4f))
        Cy.panel(c, box, Cy.dp(12f), Cy.withAlpha(Cy.bg, 0xF6), Cy.dim, Cy.TR or Cy.BL)
        Cy.rect(c, box.left, box.top, box.left + Cy.dp(3f), box.top + headH - Cy.dp(8f), m.titleColor)
        Cy.glow(c, Cy.ellipsize(m.title.uppercase(Locale.US), titlePaint, box.width() - Cy.dp(36f)), box.left + Cy.dp(14f), box.top + Cy.dp(24f), titlePaint, m.titleColor, Cy.dp(4f))
        Cy.text(c, Cy.ellipsize(m.subtitle, subPaint, box.width() - Cy.dp(28f)), box.left + Cy.dp(14f), box.top + Cy.dp(40f), subPaint, Cy.MUTED)
        Cy.line(c, box.left + Cy.dp(10f), box.top + headH - Cy.dp(2f), box.right - Cy.dp(10f), box.top + headH - Cy.dp(2f), Cy.faint)
        options.forEachIndexed { i, o ->
            val top = box.top + headH + i * rowH
            row.set(box.left + Cy.dp(4f), top, box.right - Cy.dp(4f), top + rowH)
            val p = i == pressed
            if (p) {
                Cy.rect(c, row, Cy.withAlpha(Cy.hi, 0x22))
                Cy.rect(c, row.left, row.top + Cy.dp(6f), row.left + Cy.dp(3f), row.bottom - Cy.dp(6f), Cy.hi)
            }
            val by = Cy.baseline(verb, row.top, row.bottom)
            keyBox.set(row.left + Cy.dp(10f), row.centerY() - Cy.dp(9f), row.left + Cy.dp(36f), row.centerY() + Cy.dp(9f))
            Cy.panel(c, keyBox, Cy.dp(3f), 0, Cy.withAlpha(if (o.danger) Cy.DANGER else Cy.primary, 0x90), Cy.TR, ticks = false)
            Cy.text(c, o.key, keyBox.centerX(), Cy.baseline(key, keyBox.top, keyBox.bottom), key, if (o.danger) Cy.DANGER else Cy.dim)
            val col = when {
                p -> Cy.hi
                o.danger -> Cy.DANGER
                i == options.size - 1 -> Cy.MUTED
                else -> Cy.WHITE
            }
            Cy.text(c, o.verb.uppercase(Locale.US), keyBox.right + Cy.dp(12f), by, verb, col)
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}

/** Dialogue choices along the bottom, the way conversations look in the game. */
@SuppressLint("ViewConstructor")
class ChoiceView(context: Context) : View(context) {
    private var choice: Choice? = null
    private val box = RectF()
    private val row = RectF()
    private var titleLines: List<String> = emptyList()
    private var optionLines: List<List<String>> = emptyList()
    private var pressed = -1
    private var shownAt = 0L
    private var bottomInset = 0
    private val speaker = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.16f)
    private val title = Cy.paint(15.5f, Cy.Face.BODY)
    private val option = Cy.paint(14.5f, Cy.Face.BOLD, spacing = 0.02f)
    private val lineH get() = Cy.dp(19f)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(bottom: Int) {
        bottomInset = bottom
    }

    fun show(ch: Choice) {
        choice = ch
        pressed = -1
        val pw = (parent as? View)?.width?.toFloat() ?: width.toFloat()
        val ph = (parent as? View)?.height?.toFloat() ?: height.toFloat()
        val w = min(pw - Cy.dp(24f), Cy.dp(440f))
        titleLines = Cy.wrap(ch.title, title, w - Cy.dp(32f))
        optionLines = ch.options.map { Cy.wrap(it.text, option, w - Cy.dp(64f)).take(2) }
        var h = Cy.dp(40f) + titleLines.size * lineH + Cy.dp(12f)
        for (lines in optionLines) h += optionH(lines)
        h += Cy.dp(10f)
        val l = (pw - w) / 2f
        val b = ph - bottomInset - Cy.dp(20f)
        box.set(l, b - h, l + w, b)
        shownAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    private fun optionH(lines: List<String>) = Cy.dp(26f) + lines.size * lineH

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    private fun optionTop(i: Int): Float {
        var y = box.top + Cy.dp(40f) + titleLines.size * lineH + Cy.dp(12f)
        for (k in 0 until i) y += optionH(optionLines[k])
        return y
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (!box.contains(x, y)) return -1
        for (i in optionLines.indices) {
            val t = optionTop(i)
            if (y >= t && y < t + optionH(optionLines[i])) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ch = choice ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!box.contains(event.x, event.y) && ch.cancellable) {
                    hide()
                    return true
                }
                pressed = rowAt(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pressed = rowAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val i = rowAt(event.x, event.y)
                if (i >= 0) {
                    hide()
                    ch.options[i].action()
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val ch = choice ?: return
        if (!showing) return
        val k = ((SystemClock.uptimeMillis() - shownAt) / 160f).coerceIn(0f, 1f)
        Cy.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), Cy.withAlpha(0xFF000000.toInt(), (0x99 * k).toInt()))
        c.save()
        c.translate(0f, (1f - k) * Cy.dp(24f))
        Cy.panel(c, box, Cy.dp(14f), Cy.withAlpha(Cy.bg, 0xF4), Cy.dim, Cy.TR or Cy.BL)
        Cy.scanlines(c, box)
        Cy.diamond(c, box.left + Cy.dp(18f), box.top + Cy.dp(20f), Cy.dp(4f), Cy.hi)
        Cy.text(c, ch.speaker, box.left + Cy.dp(30f), box.top + Cy.dp(24f), speaker, Cy.hi)
        var y = box.top + Cy.dp(40f)
        for (line in titleLines) {
            y += lineH
            Cy.text(c, line, box.left + Cy.dp(16f), y - Cy.dp(4f), title, Cy.WHITE)
        }
        ch.options.forEachIndexed { i, o ->
            val t = optionTop(i)
            val lines = optionLines[i]
            row.set(box.left + Cy.dp(8f), t + Cy.dp(3f), box.right - Cy.dp(8f), t + optionH(lines) - Cy.dp(3f))
            val p = i == pressed
            if (p) Cy.rect(c, row, Cy.withAlpha(Cy.hi, 0x26))
            val col = when {
                p -> Cy.hi
                o.danger -> Cy.DANGER
                o.primary -> Cy.hi
                else -> Cy.WHITE
            }
            Cy.diamond(c, row.left + Cy.dp(14f), row.top + Cy.dp(17f), Cy.dp(4.5f), if (o.danger) Cy.DANGER else if (o.primary) Cy.hi else Cy.MUTED, filled = o.primary || o.danger)
            var ly = row.top + Cy.dp(22f)
            for (line in lines) {
                Cy.text(c, line, row.left + Cy.dp(30f), ly, option, col)
                ly += lineH
            }
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}
