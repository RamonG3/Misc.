package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.ramong.runelauncher.core.Channel
import com.ramong.runelauncher.core.Dialogue
import com.ramong.runelauncher.core.Game
import kotlin.math.max
import kotlin.math.min

/** The parchment chatbox: game messages, the input line, dialogues and the channel tabs. */
@SuppressLint("ViewConstructor")
class ChatboxView(context: Context, private val game: Game) : View(context) {
    private val msgPaint = Rs.paint(13.5f)
    private val tabPaint = Rs.paint(11f, align = Paint.Align.CENTER)
    private val tabStatePaint = Rs.paint(9.5f, align = Paint.Align.CENTER)
    private val dlgPaint = Rs.paint(14f, align = Paint.Align.CENTER)
    private val dlgTitle = Rs.paint(14.5f, bold = true, align = Paint.Align.CENTER)

    private val frame = RectF()
    private val area = RectF()
    private val inputLine = RectF()
    private val tabsRow = RectF()
    private val tabRects = Array(8) { RectF() }
    private val optionRects = ArrayList<RectF>()
    private val tmp = RectF()

    private val tabNames = arrayOf("All", "Game", "Public", "Private", "Channel", "Clan", "Trade", "Report")
    private val tabChannels = arrayOf(null, Channel.GAME, Channel.PUBLIC, Channel.PRIVATE, Channel.CHANNEL, Channel.CLAN, Channel.TRADE, null)
    private val tabState = IntArray(8)
    private var filter = 0
    private var scrollLines = 0f
    private var pressedTab = -1

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            pressedTab = tabRects.indexOfFirst { it.contains(e.x, e.y) }
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (game.dialogues.isNotEmpty()) return false
            scrollLines = max(0f, scrollLines - distanceY / Rs.lineHeight(msgPaint))
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressedTab = -1
            invalidate()
        }
        return r
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val f = Rs.px * 3
        frame.set(0f, 0f, w.toFloat(), h.toFloat())
        val tabH = Rs.dp(30f)
        tabsRow.set(f, h - f - tabH, w - f, h - f)
        area.set(f, f, w - f, tabsRow.top - Rs.px)
        inputLine.set(area.left, area.bottom - Rs.dp(20f), area.right, area.bottom)
        val tw = tabsRow.width() / 8f
        for (i in 0 until 8) tabRects[i].set(tabsRow.left + i * tw, tabsRow.top, tabsRow.left + (i + 1) * tw, tabsRow.bottom)
    }

    private fun tap(x: Float, y: Float) {
        val d = game.dialogues.firstOrNull()
        if (area.contains(x, y) && d != null) {
            if (d is Dialogue.Options) {
                val i = optionRects.indexOfFirst { it.contains(x, y) }
                if (i >= 0) {
                    game.haptic()
                    game.chooseOption(i)
                }
            } else {
                game.haptic()
                game.continueDialogue()
            }
            return
        }
        val tab = tabRects.indexOfFirst { it.contains(x, y) }
        if (tab >= 0) {
            game.haptic()
            when {
                tab == 7 -> game.message("Thank you for your report. We'll get right on that.")
                tab == filter && tab != 0 -> tabState[tab] = (tabState[tab] + 1) % 3
                else -> filter = tab
            }
            scrollLines = 0f
            invalidate()
            return
        }
        if (area.contains(x, y) && game.inputMode == Game.InputMode.NONE) {
            game.haptic()
            game.beginSearch()
        }
    }

    override fun onDraw(c: Canvas) {
        Rs.stone(c, frame, light = true)
        Rs.bevel(c, frame)
        c.drawRect(area, Rs.parchmentPaint)
        Rs.bevel(c, area, sunken = true)
        val d = game.dialogues.firstOrNull()
        c.save()
        c.clipRect(area)
        if (d != null) drawDialogue(c, d) else {
            if (game.inputMode == Game.InputMode.PROMPT) drawPrompt(c) else drawMessages(c)
            drawInput(c)
        }
        c.restore()
        drawTabs(c)
    }

    private fun visible(ch: Channel): Boolean {
        val idx = tabChannels.indexOf(ch)
        if (idx > 0 && tabState[idx] == 2) return false
        val want = tabChannels[filter] ?: return true
        return ch == want
    }

    private fun drawMessages(c: Canvas) {
        val pad = Rs.dp(6f)
        val maxW = area.width() - pad * 2
        val lh = Rs.lineHeight(msgPaint)
        val lines = ArrayList<Rs.Rich>()
        for (m in game.chat) {
            if (!visible(m.channel)) continue
            lines.addAll(Rs.wrapRich(Rs.rich(m.text, Rs.BLACK), msgPaint, maxW))
        }
        val capacity = ((inputLine.top - area.top - pad) / lh).toInt()
        scrollLines = min(scrollLines, max(0, lines.size - capacity).toFloat())
        val skip = scrollLines.toInt()
        var y = inputLine.top - Rs.dp(5f)
        var i = lines.size - 1 - skip
        while (i >= 0 && y - msgPaint.textSize * 0.8f > area.top) {
            Rs.drawRich(c, lines[i], area.left + pad, y, msgPaint, shadow = false)
            y -= lh
            i--
        }
    }

    private fun drawPrompt(c: Canvas) {
        val cy = (area.top + inputLine.top) / 2f
        val lines = Rs.wrap(game.promptTitle, dlgPaint, area.width() - Rs.dp(16f))
        var y = cy - (lines.size - 1) * Rs.lineHeight(dlgPaint) / 2f
        for (l in lines) {
            Rs.text(c, l, area.centerX(), y, dlgPaint, Rs.BLACK, shadow = false)
            y += Rs.lineHeight(dlgPaint)
        }
    }

    private fun drawInput(c: Canvas) {
        Rs.rect(c, inputLine.left, inputLine.top, inputLine.right, inputLine.top + Rs.px, 0x66000000)
        val y = Rs.baseline(msgPaint, inputLine.top, inputLine.bottom)
        val x = area.left + Rs.dp(6f)
        if (game.inputMode == Game.InputMode.PROMPT) {
            val s = game.inputText + "*"
            Rs.text(c, s, area.centerX() - msgPaint.measureText(s) / 2f, y, msgPaint, Rs.CHAT_BLUE, shadow = false)
            return
        }
        val name = game.save.name + ": "
        Rs.text(c, name, x, y, msgPaint, Rs.BLACK, shadow = false)
        val typed = if (game.inputMode == Game.InputMode.SEARCH) game.inputText else ""
        Rs.text(c, "$typed*", x + msgPaint.measureText(name), y, msgPaint, Rs.CHAT_BLUE, shadow = false)
        if (game.inputMode == Game.InputMode.NONE && game.chat.size <= 4) {
            val hint = "(tap to search)"
            Rs.text(c, hint, area.right - Rs.dp(6f) - msgPaint.measureText(hint), y, msgPaint, 0x88000000.toInt(), shadow = false)
        }
    }

    private fun drawDialogue(c: Canvas, d: Dialogue) {
        optionRects.clear()
        val lh = Rs.lineHeight(dlgPaint)
        when (d) {
            is Dialogue.LevelUp -> {
                val iconBox = min(area.height() * 0.55f, Rs.dp(48f))
                Sprites.skill(d.skill).draw(c, area.left + Rs.dp(14f) + iconBox / 2f, area.centerY() - lh * 0.3f, iconBox)
                val textLeft = area.left + Rs.dp(20f) + iconBox
                val cx = (textLeft + area.right) / 2f
                val w = area.right - textLeft - Rs.dp(8f)
                val title = Rs.wrap("Congratulations, you just advanced ${d.skill.article} ${d.skill.title} level.", dlgTitle, w)
                var y = area.top + Rs.dp(8f) + lh * 0.8f
                for (l in title) {
                    Rs.text(c, l, cx, y, dlgTitle, Rs.DIALOGUE_BLUE, shadow = false)
                    y += lh
                }
                Rs.text(c, "Your ${d.skill.title} level is now ${d.level}.", cx, y, dlgPaint, Rs.BLACK, shadow = false)
                Rs.text(c, "Click here to continue", cx, area.bottom - Rs.dp(8f), dlgPaint, Rs.CHAT_BLUE, shadow = false)
            }
            is Dialogue.Message -> {
                var y = area.top + Rs.dp(8f) + lh * 0.8f
                Rs.text(c, d.title, area.centerX(), y, dlgTitle, Rs.DIALOGUE_BLUE, shadow = false)
                y += lh
                for (line in d.lines) {
                    for (l in Rs.wrap(line, dlgPaint, area.width() - Rs.dp(16f))) {
                        Rs.text(c, l, area.centerX(), y, dlgPaint, Rs.BLACK, shadow = false)
                        y += lh
                    }
                }
                Rs.text(c, "Click here to continue", area.centerX(), area.bottom - Rs.dp(8f), dlgPaint, Rs.CHAT_BLUE, shadow = false)
            }
            is Dialogue.Options -> {
                val titleLines = Rs.wrap(d.title, dlgTitle, area.width() - Rs.dp(16f))
                var y = area.top + Rs.dp(6f) + lh * 0.8f
                for (l in titleLines) {
                    Rs.text(c, l, area.centerX(), y, dlgTitle, Rs.DARK_RED, shadow = false)
                    y += lh
                }
                val rest = area.bottom - (y - lh * 0.6f)
                val rowH = min(Rs.dp(30f), rest / max(1, d.options.size))
                var top = y - lh * 0.6f
                for (o in d.options) {
                    val r = RectF(area.left, top, area.right, top + rowH)
                    optionRects += r
                    Rs.text(c, o.first, area.centerX(), Rs.baseline(dlgPaint, r.top, r.bottom), dlgPaint, Rs.BLACK, shadow = false)
                    top += rowH
                }
            }
        }
    }

    private fun drawTabs(c: Canvas) {
        for (i in 0 until 8) {
            val r = tabRects[i]
            Rs.button(c, r, selected = i == filter, pressed = i == pressedTab)
            if (i == 0 || i == 7) {
                Rs.text(c, tabNames[i], r.centerX(), Rs.baseline(tabPaint, r.top, r.bottom), tabPaint, Rs.WHITE)
            } else {
                Rs.text(c, tabNames[i], r.centerX(), r.top + r.height() * 0.45f, tabPaint, Rs.WHITE)
                val (label, color) = when (tabState[i]) {
                    0 -> "On" to Rs.GREEN
                    1 -> "Filter" to Rs.YELLOW
                    else -> "Off" to Rs.RED
                }
                Rs.text(c, label, r.centerX(), r.top + r.height() * 0.85f, tabStatePaint, color)
            }
        }
    }
}
