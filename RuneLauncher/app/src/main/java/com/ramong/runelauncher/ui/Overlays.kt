package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.AppIntel
import com.ramong.runelauncher.core.Choice
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.ItemMenu
import com.ramong.runelauncher.core.MenuOption
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** The long-press menu: the item's name and a line about it, then the options. */
@SuppressLint("ViewConstructor")
class MenuView(context: Context) : View(context) {
    private var menu: ItemMenu? = null
    private var options: List<MenuOption> = emptyList()
    private val box = RectF()
    private val row = RectF()
    private var rowH = 0f
    private var headH = 0f
    private var pressed = -1
    private var shownAt = 0L
    private val titlePaint = R3.paint(17f, R3.Face.TITLE_BOLD)
    private val subPaint = R3.paint(12f, R3.Face.BODY)
    private val verb = R3.paint(16f, R3.Face.BOLD)

    val showing: Boolean get() = visibility == VISIBLE

    fun show(m: ItemMenu) {
        menu = m
        options = m.options + MenuOption("Cancel")
        rowH = R3.dp(46f)
        headH = R3.dp(60f)
        val pw = (parent as? View)?.width?.toFloat() ?: width.toFloat()
        val ph = (parent as? View)?.height?.toFloat() ?: height.toFloat()
        val longest = options.maxOf { verb.measureText(it.verb) }
        val w = min(max(max(R3.dp(230f), longest + R3.dp(56f)), min(titlePaint.measureText(m.title), R3.dp(290f)) + R3.dp(40f)), pw - R3.dp(24f))
        val h = headH + options.size * rowH + R3.dp(10f)
        val l = (m.x - w / 2f).coerceIn(R3.dp(12f), max(R3.dp(12f), pw - w - R3.dp(12f)))
        val t = (m.y - headH / 2f).coerceIn(R3.dp(44f), max(R3.dp(44f), ph - h - R3.dp(28f)))
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
        R3.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), R3.withAlpha(R3.BLACK, (0x70 * k).toInt()))
        c.save()
        c.clipRect(box.left - R3.dp(6f), box.top - R3.dp(6f), box.right + R3.dp(6f), box.top + box.height() * k + R3.dp(6f))
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF6), R3.dp(10f))
        R3.text(c, R3.ellipsize(m.title, titlePaint, box.width() - R3.dp(36f)), box.left + R3.dp(18f), box.top + R3.dp(28f), titlePaint, m.titleColor)
        R3.text(c, R3.ellipsize(m.subtitle, subPaint, box.width() - R3.dp(36f)), box.left + R3.dp(18f), box.top + R3.dp(46f), subPaint, R3.MUTED)
        R3.ornament(c, box.left + R3.dp(12f), box.right - R3.dp(12f), box.top + headH - R3.dp(4f))
        options.forEachIndexed { i, o ->
            val top = box.top + headH + i * rowH
            row.set(box.left + R3.dp(6f), top, box.right - R3.dp(6f), top + rowH)
            val p = i == pressed
            if (p) R3.round(c, row, R3.dp(6f), R3.withAlpha(R3.hi, 0x26))
            val col = when {
                p -> R3.hi
                o.danger -> R3.DANGER
                i == options.size - 1 -> R3.MUTED
                else -> R3.WHITE
            }
            R3.text(c, o.verb, row.left + R3.dp(14f), R3.baseline(verb, row.top, row.bottom), verb, col)
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}

/** A dialogue box along the bottom with numbered options, like talking to an NPC. */
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
    private val speaker = R3.paint(15f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val title = R3.paint(16f, R3.Face.BODY, Paint.Align.CENTER)
    private val option = R3.paint(16f, R3.Face.BOLD)
    private val lineH get() = R3.dp(21f)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(bottom: Int) {
        bottomInset = bottom
    }

    fun show(ch: Choice) {
        choice = ch
        pressed = -1
        val pw = (parent as? View)?.width?.toFloat() ?: width.toFloat()
        val ph = (parent as? View)?.height?.toFloat() ?: height.toFloat()
        val w = min(pw - R3.dp(24f), R3.dp(440f))
        titleLines = R3.wrap(ch.title, title, w - R3.dp(36f))
        optionLines = ch.options.mapIndexed { i, o -> R3.wrap("${i + 1}. ${o.text}", option, w - R3.dp(48f)).take(2) }
        var h = R3.dp(50f) + titleLines.size * lineH + R3.dp(14f)
        for (lines in optionLines) h += optionH(lines)
        h += R3.dp(12f)
        val l = (pw - w) / 2f
        val b = ph - bottomInset - R3.dp(20f)
        box.set(l, b - h, l + w, b)
        shownAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    private fun optionH(lines: List<String>) = R3.dp(24f) + lines.size * lineH

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    private fun optionTop(i: Int): Float {
        var y = box.top + R3.dp(50f) + titleLines.size * lineH + R3.dp(14f)
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
        R3.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), R3.withAlpha(R3.BLACK, (0x99 * k).toInt()))
        c.save()
        c.translate(0f, (1f - k) * R3.dp(24f))
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF6), R3.dp(12f), titleText = ch.speaker, titlePaint = speaker, titleH = R3.dp(40f))
        var y = box.top + R3.dp(50f)
        for (line in titleLines) {
            y += lineH
            R3.text(c, line, box.centerX(), y - R3.dp(4f), title, R3.WHITE)
        }
        ch.options.forEachIndexed { i, o ->
            val t = optionTop(i)
            val lines = optionLines[i]
            row.set(box.left + R3.dp(10f), t + R3.dp(3f), box.right - R3.dp(10f), t + optionH(lines) - R3.dp(3f))
            val p = i == pressed
            R3.button(c, row, pressed = p, lit = o.primary)
            val col = when {
                p -> R3.hi
                o.danger -> R3.DANGER
                o.primary -> R3.hi
                else -> R3.WHITE
            }
            var ly = row.top + R3.dp(25f)
            for (line in lines) {
                R3.text(c, line, row.left + R3.dp(16f), ly, option, col)
                ly += lineH
            }
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}

/** "Examine": what an app is, what it trains, and which sensitive permissions it holds. */
@SuppressLint("ViewConstructor")
class ExamineView(context: Context, private val game: Game) : View(context) {
    private var app: AppEntry? = null
    private var intel: AppIntel? = null
    private var shownAt = 0L
    private var topInset = 0
    private var bottomInset = 0
    private val box = RectF()
    private val tmp = RectF()
    private val titlePaint = R3.paint(17f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.03f)
    private val name = R3.paint(20f, R3.Face.TITLE_BOLD)
    private val body = R3.paint(14f, R3.Face.BODY)
    private val bold = R3.paint(14f, R3.Face.BOLD)
    private val right = R3.paint(14f, R3.Face.BOLD, Paint.Align.RIGHT)
    private val hint = R3.paint(12f, R3.Face.BODY, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
    }

    fun show(a: AppEntry) {
        app = a
        intel = try {
            game.source.intel(a)
        } catch (e: Exception) {
            null
        }
        shownAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val a = app
            if (a != null && tmp.contains(event.x, event.y)) game.source.details(a)
            hide()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val a = app ?: return
        if (!showing) return
        val k = ((SystemClock.uptimeMillis() - shownAt) / 160f).coerceIn(0f, 1f)
        R3.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), R3.withAlpha(R3.BLACK, (0xA0 * k).toInt()))
        val w = min(width - R3.dp(28f), R3.dp(420f))
        val i = intel
        val perms = i?.granted ?: emptyList()
        val h = R3.dp(352f) + max(1, perms.size) * R3.dp(22f)
        val top = max(topInset + R3.dp(20f), (height - h) / 2f)
        box.set((width - w) / 2f, top, (width + w) / 2f, top + h)
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF6), R3.dp(12f), titleText = "Examine", titlePaint = titlePaint, titleH = R3.dp(42f))
        val x = box.left + R3.dp(18f)
        val r = box.right - R3.dp(18f)
        var y = box.top + R3.dp(58f)
        AppIcon.icon(c, game, a, x + R3.dp(26f), y + R3.dp(26f), R3.dp(52f))
        R3.text(c, R3.ellipsize(a.label, name, r - x - R3.dp(70f)), x + R3.dp(66f), y + R3.dp(22f), name, R3.WHITE)
        R3.text(c, R3.ellipsize(game.examineLine(a), body, r - x - R3.dp(70f)), x + R3.dp(66f), y + R3.dp(44f), body, R3.TEXT)
        y += R3.dp(72f)
        R3.ornament(c, x, r, y)
        y += R3.dp(24f)
        R3.skillBadge(c, a.skill, x + R3.dp(14f), y - R3.dp(5f), R3.dp(28f))
        R3.text(c, "Trains ${a.skill.title}", x + R3.dp(36f), y, bold, R3.hi)
        R3.text(c, "${game.launches(a)} uses", r, y, right, R3.TEXT)
        y += R3.dp(28f)
        if (i != null && i.readable) {
            R3.text(c, "Version", x, y, body, R3.MUTED)
            R3.text(c, R3.ellipsize(i.version ?: "?", right, r - x - R3.dp(90f)), r, y, right, R3.WHITE)
            y += R3.dp(22f)
            R3.text(c, "Installed", x, y, body, R3.MUTED)
            R3.text(c, DateFormat.format("d MMM yyyy", Date(i.installed)).toString(), r, y, right, R3.WHITE)
            y += R3.dp(22f)
            R3.text(c, "Updated", x, y, body, R3.MUTED)
            R3.text(c, DateFormat.format("d MMM yyyy", Date(i.updated)).toString(), r, y, right, R3.WHITE)
            y += R3.dp(30f)
            val threat = AppIntel.THREATS[i.threat]
            val tc = when (i.threat) {
                0 -> R3.LIFE_OK
                1 -> 0xFFE2C23A.toInt()
                else -> R3.DANGER
            }
            R3.text(c, "Snooping level", x, y, bold, R3.WHITE)
            R3.text(c, threat, r, y, right, tc)
            y += R3.dp(24f)
            if (perms.isEmpty()) {
                R3.text(c, "Holds no sensitive permissions. Wholesome.", x, y, body, R3.TEXT)
                y += R3.dp(22f)
            } else {
                for (p in perms) {
                    R3.diamond(c, x + R3.dp(5f), y - R3.dp(5f), R3.dp(3.5f), tc)
                    R3.text(c, p, x + R3.dp(16f), y, body, R3.TEXT)
                    y += R3.dp(22f)
                }
            }
        } else {
            R3.text(c, "Its secrets are hidden from you (work profile).", x, y, body, R3.MUTED)
            y += R3.dp(22f)
        }
        tmp.set(x, box.bottom - R3.dp(58f), r, box.bottom - R3.dp(18f))
        R3.button(c, tmp, lit = true)
        R3.text(c, "App info", tmp.centerX(), R3.baseline(titlePaint, tmp.top, tmp.bottom), titlePaint, R3.hi)
        if (k < 1f) postInvalidateOnAnimation()
    }
}

/** The text prompt (rename a folder, name a quest), sitting just above the keyboard. */
@SuppressLint("ViewConstructor")
class PromptView(context: Context, private val game: Game) : View(context) {
    var bottomInset = 0
    private val box = RectF()
    private val ok = RectF()
    private val titlePaint = R3.paint(15f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val textPaint = R3.paint(18f, R3.Face.BOLD)
    private val okPaint = R3.paint(14f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private var blinkQueued = false
    private val blink = Runnable {
        blinkQueued = false
        if (visibility == VISIBLE) invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                ok.contains(event.x, event.y) -> game.submitInput()
                box.contains(event.x, event.y) -> game.ui.openKeyboard(game.inputText)
                else -> game.cancelInput()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (game.inputMode != Game.InputMode.PROMPT) return
        val w = min(width - R3.dp(24f), R3.dp(440f))
        val h = R3.dp(112f)
        val b = height - bottomInset - R3.dp(12f)
        box.set((width - w) / 2f, b - h, (width + w) / 2f, b)
        R3.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), R3.withAlpha(R3.BLACK, 0x80))
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF6), R3.dp(12f), titleText = game.promptTitle, titlePaint = titlePaint, titleH = R3.dp(38f))
        ok.set(box.right - R3.dp(84f), box.top + R3.dp(52f), box.right - R3.dp(14f), box.bottom - R3.dp(14f))
        R3.button(c, ok, lit = true)
        R3.text(c, "OK", ok.centerX(), R3.baseline(okPaint, ok.top, ok.bottom), okPaint, R3.hi)
        val tx = box.left + R3.dp(18f)
        val by = R3.baseline(textPaint, ok.top, ok.bottom)
        val shown = R3.ellipsize(game.inputText, textPaint, ok.left - tx - R3.dp(16f))
        R3.text(c, shown, tx, by, textPaint, R3.WHITE)
        if ((SystemClock.uptimeMillis() / 500) % 2 == 0L) {
            val cx = tx + textPaint.measureText(shown) + R3.dp(2f)
            R3.rect(c, cx, by - R3.dp(16f), cx + R3.dp(2f), by + R3.dp(3f), R3.hi)
        }
        if (!blinkQueued) {
            blinkQueued = true
            postDelayed(blink, 500L)
        }
    }
}
