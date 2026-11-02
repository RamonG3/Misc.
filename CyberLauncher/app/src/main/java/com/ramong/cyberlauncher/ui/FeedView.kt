package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.FeedLine
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Tag
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** The net feed: launcher events in a terminal, with the search prompt on the last line. */
@SuppressLint("ViewConstructor")
class FeedView(context: Context, private val game: Game) : View(context) {
    private val frame = RectF()
    private val inner = RectF()
    private val promptRow = RectF()
    private val head = Cy.paint(8.5f, Cy.Face.MONO, spacing = 0.12f)
    private val headRight = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.RIGHT, 0.12f)
    private val time = Cy.paint(10f, Cy.Face.MONO)
    private val tag = Cy.paint(10f, Cy.Face.MONO)
    private val body = Cy.paint(13.5f, Cy.Face.BODY)
    private val prompt = Cy.paint(13f, Cy.Face.MONO, spacing = 0.04f)
    private val cal = Calendar.getInstance()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            game.haptic()
            game.beginSearch()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            game.haptic()
            game.deviceReport()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < 0 && abs(vy) > abs(vx)) {
                game.ui.openStash(null, null)
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = Cy.dp(8f)
        frame.set(m, Cy.dp(2f), w - m, h - Cy.dp(4f))
        inner.set(frame.left + Cy.dp(10f), frame.top + Cy.dp(18f), frame.right - Cy.dp(10f), frame.bottom - Cy.dp(28f))
        promptRow.set(frame.left + Cy.dp(6f), frame.bottom - Cy.dp(26f), frame.right - Cy.dp(6f), frame.bottom - Cy.dp(5f))
    }

    private fun tagColor(t: Tag) = when (t) {
        Tag.SYS -> Cy.MUTED
        Tag.XP -> Cy.primary
        Tag.EDDIES, Tag.JOB -> Cy.hi
        Tag.MSG, Tag.SCAN -> Cy.accent
        Tag.NCPD, Tag.ERR -> Cy.DANGER
        Tag.YOU -> Cy.WHITE
    }

    override fun onDraw(c: Canvas) {
        Cy.panel(c, frame, Cy.dp(10f), Cy.panel, Cy.dim, Cy.TL or Cy.BR, ticks = false)
        Cy.line(c, frame.right - Cy.dp(10f) - Cy.dp(1f), frame.bottom, frame.right + Cy.dp(0.5f), frame.bottom - Cy.dp(11f), Cy.primary, Cy.px * 2)
        Cy.scanlines(c, frame)
        val hy = frame.top + Cy.dp(12f)
        Cy.text(c, "◢ NET FEED", frame.left + Cy.dp(12f), hy, head, Cy.primary)
        // Redrawn on the one-second game tick, so the cursor blinks once a second.
        val blink = (SystemClock.uptimeMillis() / 1000) % 2 == 0L
        Cy.text(c, "CH 07 // ENCRYPTED", frame.right - Cy.dp(20f), hy, headRight, Cy.MUTED)
        if (blink) Cy.rect(c, frame.right - Cy.dp(15f), hy - Cy.dp(5.5f), frame.right - Cy.dp(10f), hy - Cy.dp(0.5f), Cy.accent)

        // Newest lines at the bottom, wrapped, as many as fit.
        val lineH = Cy.dp(16.5f)
        val maxLines = ((inner.height()) / lineH).toInt().coerceAtLeast(1)
        val rows = ArrayList<Pair<FeedLine?, Cy.Rich>>()
        val tagW = time.measureText("00:00 ") + tag.measureText("[NCPD] ")
        val textW = inner.width() - tagW
        for (i in game.feed.indices.reversed()) {
            val f = game.feed[i]
            val wrapped = Cy.wrapRich(Cy.rich(f.text, Cy.TEXT), body, textW).take(3)
            for (j in wrapped.indices.reversed()) rows.add(0, (if (j == 0) f else null) to wrapped[j])
            if (rows.size >= maxLines) break
        }
        while (rows.size > maxLines) rows.removeAt(0)
        var y = inner.bottom - (rows.size - 1) * lineH
        c.save()
        c.clipRect(frame.left, frame.top + Cy.dp(14f), frame.right, promptRow.top)
        for ((i, row) in rows.withIndex()) {
            val f = row.first
            val fade = if (rows.size > 2 && i == 0) 0.55f else 1f
            if (f != null) {
                cal.timeInMillis = f.time
                Cy.text(c, String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
                    inner.left, y, time, Cy.withAlpha(Cy.MUTED, (0xC0 * fade).toInt()))
                val tx = inner.left + time.measureText("00:00 ")
                val label = if (f.tag == Tag.YOU) ">" else "[${f.tag.label}]"
                Cy.text(c, label, tx, y, tag, Cy.withAlpha(tagColor(f.tag), (255 * fade).toInt()))
            }
            body.alpha = (255 * fade).toInt()
            Cy.drawRich(c, row.second, inner.left + tagW, y, body)
            body.alpha = 255
            y += lineH
        }
        c.restore()

        Cy.line(c, promptRow.left, promptRow.top, promptRow.right, promptRow.top, Cy.faint)
        val py = Cy.baseline(prompt, promptRow.top, promptRow.bottom)
        val px = promptRow.left + Cy.dp(6f)
        Cy.text(c, ">", px, py, prompt, Cy.accent)
        val textX = px + prompt.measureText("> ")
        val searching = game.inputMode == Game.InputMode.SEARCH
        val shown = if (searching) game.inputText else ""
        val cursorX = textX + if (shown.isEmpty()) 0f else prompt.measureText(shown) + Cy.dp(2f)
        if (blink) Cy.rect(c, cursorX, py - Cy.dp(11f), cursorX + Cy.dp(7f), py + Cy.dp(2f), Cy.withAlpha(Cy.accent, if (shown.isEmpty()) 0x90 else 0xFF))
        if (shown.isEmpty()) {
            Cy.text(c, "SEARCH THE STASH OR TYPE /HELP", textX + Cy.dp(12f), py, prompt, Cy.withAlpha(Cy.MUTED, 0xB0))
        } else {
            Cy.text(c, shown, textX, py, prompt, Cy.WHITE)
        }
    }
}
