package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Skill
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The Bank of Gielinor: every installed app, with skill tabs, search and sorting. */
@SuppressLint("ViewConstructor")
class BankView(context: Context, private val game: Game) : View(context) {
    var skill: Skill? = null
        private set
    private var items: List<AppEntry> = emptyList()
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var topInset = 0
    private var bottomInset = 0

    private val frame = RectF()
    private val header = RectF()
    private val close = RectF()
    private val tabsRow = RectF()
    private val grid = RectF()
    private val prompt = RectF()
    private val bar = RectF()
    private val searchBtn = RectF()
    private val sortBtn = RectF()
    private val closeBtn = RectF()
    private val cell = RectF()
    private val track = RectF()
    private val thumb = RectF()
    private val tabs = ArrayList<Pair<Skill?, RectF>>()
    private var pressed: RectF? = null
    private var cols = 5
    private var cellW = 0f
    private var cellH = 0f

    private val titlePaint = Rs.paint(16f, bold = true, align = Paint.Align.CENTER)
    private val countPaint = Rs.paint(10.5f, align = Paint.Align.CENTER)
    private val labelPaint = Rs.paint(10.5f, align = Paint.Align.CENTER)
    private val btnPaint = Rs.paint(13f, align = Paint.Align.CENTER)
    private val promptPaint = Rs.paint(12.5f)
    private val inputPaint = Rs.paint(14f)
    private val emptyPaint = Rs.paint(14f, align = Paint.Align.CENTER)
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = Rs.dp(3f)
        strokeCap = Paint.Cap.SQUARE
    }

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        layoutParts()
        invalidate()
    }

    fun open(s: Skill?) {
        skill = s
        scroll = 0f
        scroller.forceFinished(true)
        visibility = VISIBLE
        refresh()
    }

    fun refresh() {
        val q = if (game.inputMode == Game.InputMode.SEARCH) game.inputText else ""
        items = game.bankItems(q, skill)
        layoutParts()
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val m = Rs.dp(4f)
        frame.set(m, topInset + m, w - m, h - bottomInset - m)
        val f = Rs.px * 3
        header.set(frame.left + f, frame.top + f, frame.right - f, frame.top + f + Rs.dp(42f))
        close.set(header.right - Rs.dp(38f), header.top + Rs.dp(5f), header.right - Rs.dp(6f), header.bottom - Rs.dp(5f))
        tabsRow.set(header.left, header.bottom, header.right, header.bottom + Rs.dp(46f))
        bar.set(frame.left + f, frame.bottom - f - Rs.dp(46f), frame.right - f, frame.bottom - f)
        val searching = game.inputMode == Game.InputMode.SEARCH
        prompt.set(bar.left, bar.top - if (searching) Rs.dp(46f) else 0f, bar.right, bar.top)
        grid.set(tabsRow.left + Rs.dp(4f), tabsRow.bottom + Rs.dp(4f), tabsRow.right - Rs.dp(18f), prompt.top - Rs.dp(4f))
        cols = max(4, (grid.width() / Rs.dp(78f)).toInt())
        cellW = grid.width() / cols
        cellH = cellW * 1.02f
        val gap = Rs.dp(4f)
        val bw = (bar.width() - gap * 4) / 3f
        searchBtn.set(bar.left + gap, bar.top + gap, bar.left + gap + bw, bar.bottom - gap)
        sortBtn.set(searchBtn.right + gap, searchBtn.top, searchBtn.right + gap + bw, searchBtn.bottom)
        closeBtn.set(sortBtn.right + gap, searchBtn.top, bar.right - gap, searchBtn.bottom)
        layoutTabs()
    }

    private fun layoutTabs() {
        val counts = Skill.values().map { it to game.appsTraining(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val tw = min(Rs.dp(50f), tabsRow.width() / 8f)
        val maxTabs = (tabsRow.width() / tw).toInt() - 1
        val chosen = ArrayList<Skill>(counts.take(maxTabs).map { it.first })
        val s = skill
        if (s != null && s !in chosen) {
            if (chosen.size >= maxTabs && chosen.isNotEmpty()) chosen.removeAt(chosen.size - 1)
            chosen += s
        }
        tabs.clear()
        var x = tabsRow.left
        for (t in listOf<Skill?>(null) + chosen) {
            tabs += t to RectF(x, tabsRow.top + Rs.dp(4f), x + tw - Rs.dp(3f), tabsRow.bottom - Rs.dp(2f))
            x += tw
        }
    }

    private fun rows() = ceil(items.size / cols.toFloat()).toInt()
    private fun maxScroll() = max(0f, rows() * cellH - grid.height())

    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        cell.set(grid.left + col * cellW, grid.top + row * cellH - scroll, grid.left + (col + 1) * cellW, grid.top + (row + 1) * cellH - scroll)
        return cell
    }

    private fun itemAt(x: Float, y: Float): Int {
        if (!grid.contains(x, y)) return -1
        val col = ((x - grid.left) / cellW).toInt()
        val row = ((y - grid.top + scroll) / cellH).toInt()
        val i = row * cols + col
        return if (col in 0 until cols && i in items.indices) i else -1
    }

    // ------------------------------------------------------------------ touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            pressed = listOf(close, searchBtn, sortBtn, closeBtn).firstOrNull { it.contains(e.x, e.y) }
                ?: tabs.firstOrNull { it.second.contains(e.x, e.y) }?.second
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            when {
                close.contains(x, y) || closeBtn.contains(x, y) -> {
                    game.haptic()
                    game.ui.closeBank()
                }
                searchBtn.contains(x, y) || prompt.contains(x, y) -> {
                    game.haptic()
                    if (game.inputMode == Game.InputMode.SEARCH) game.ui.openKeyboard(game.inputText) else game.beginSearch()
                }
                sortBtn.contains(x, y) -> {
                    game.haptic()
                    game.save.bankSort = (game.save.bankSort + 1) % 3
                    game.save.save()
                    refresh()
                }
                else -> {
                    val t = tabs.firstOrNull { it.second.contains(x, y) }
                    if (t != null) {
                        game.haptic()
                        skill = t.first
                        scroll = 0f
                        refresh()
                        return true
                    }
                    val i = itemAt(x, y)
                    if (i >= 0) {
                        val r = RectF(cellRect(i))
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        game.launch(items[i], android.graphics.Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt()))
                    }
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = itemAt(e.x, e.y)
            if (i >= 0) game.bankMenu(items[i], left + e.x, top + e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scroll = (scroll + distanceY).coerceIn(0f, maxScroll())
            pressed = null
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scroll <= 0f && velocityY > Rs.dp(1200f) && abs(velocityY) > abs(velocityX) && game.inputMode != Game.InputMode.SEARCH) {
                game.ui.closeBank()
                return true
            }
            scroller.fling(0, scroll.toInt(), 0, -velocityY.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
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

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (prompt.height() > 0f != (game.inputMode == Game.InputMode.SEARCH)) layoutParts()
        Rs.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), 0x88000000.toInt())
        Rs.stone(c, frame, light = true)
        Rs.bevel(c, frame)

        val title = skill?.let { "Bank: ${it.title}" } ?: "The Bank of Gielinor"
        Rs.text(c, title, header.centerX(), Rs.baseline(titlePaint, header.top, header.bottom), titlePaint, Rs.ORANGE)
        Rs.text(c, "${items.size}", header.left + Rs.dp(22f), header.top + Rs.dp(18f), countPaint, Rs.ORANGE)
        Rs.rect(c, header.left + Rs.dp(10f), header.top + Rs.dp(22f), header.left + Rs.dp(34f), header.top + Rs.dp(22f) + Rs.px, Rs.ORANGE)
        Rs.text(c, "800", header.left + Rs.dp(22f), header.top + Rs.dp(34f), countPaint, Rs.ORANGE)
        Rs.button(c, close, pressed = pressed === close)
        val ci = Rs.dp(8f)
        xPaint.color = Rs.BLACK
        c.drawLine(close.left + ci + Rs.px, close.top + ci + Rs.px, close.right - ci + Rs.px, close.bottom - ci + Rs.px, xPaint)
        c.drawLine(close.left + ci + Rs.px, close.bottom - ci + Rs.px, close.right - ci + Rs.px, close.top + ci + Rs.px, xPaint)
        xPaint.color = 0xFFC8281E.toInt()
        c.drawLine(close.left + ci, close.top + ci, close.right - ci, close.bottom - ci, xPaint)
        c.drawLine(close.left + ci, close.bottom - ci, close.right - ci, close.top + ci, xPaint)

        for ((s, r) in tabs) {
            Rs.button(c, r, selected = s == skill, pressed = pressed === r)
            if (s == null) {
                Rs.text(c, "All", r.centerX(), Rs.baseline(btnPaint, r.top, r.bottom), btnPaint, Rs.ORANGE)
            } else {
                Sprites.skill(s).draw(c, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.6f)
            }
        }

        val well = RectF(grid.left - Rs.dp(2f), grid.top - Rs.dp(2f), grid.right + Rs.dp(16f), grid.bottom + Rs.dp(2f))
        Rs.stone(c, well)
        Rs.bevel(c, well, sunken = true)
        c.save()
        c.clipRect(grid)
        if (items.isEmpty()) {
            val msg = if (game.inputMode == Game.InputMode.SEARCH && game.inputText.isNotBlank()) "No items match." else "The bank is empty. Somehow."
            Rs.text(c, msg, grid.centerX(), grid.top + Rs.dp(40f), emptyPaint, Rs.ORANGE)
        }
        val firstRow = max(0, (scroll / cellH).toInt())
        val lastRow = min(rows() - 1, ((scroll + grid.height()) / cellH).toInt())
        for (row in firstRow..lastRow) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= items.size) break
            val r = cellRect(i)
            val app = items[i]
            val icon = min(cellW * 0.6f, cellH * 0.6f)
            ItemDraw.icon(c, game, app, r.centerX(), r.top + cellH * 0.38f, icon)
            Rs.text(c, Rs.ellipsize(app.label, labelPaint, cellW - Rs.dp(4f)), r.centerX(), r.bottom - Rs.dp(9f), labelPaint, Rs.ORANGE)
        }
        c.restore()
        drawScrollbar(c)

        if (prompt.height() > 0f) {
            c.drawRect(prompt, Rs.parchmentPaint)
            Rs.bevel(c, prompt, sunken = true)
            val pad = Rs.dp(8f)
            Rs.text(c, "Show items whose names contain the following text:", prompt.left + pad, prompt.top + Rs.dp(17f), promptPaint, Rs.BLACK, shadow = false)
            Rs.text(c, game.inputText + "*", prompt.left + pad, prompt.top + Rs.dp(37f), inputPaint, Rs.CHAT_BLUE, shadow = false)
        }
        for ((r, label) in listOf(searchBtn to "Search", sortBtn to "Sort: ${arrayOf("A-Z", "Used", "Skill")[game.save.bankSort]}", closeBtn to "Close")) {
            Rs.button(c, r, pressed = pressed === r, selected = r === searchBtn && game.inputMode == Game.InputMode.SEARCH)
            if (r === searchBtn) {
                val iconSize = r.height() * 0.6f
                val tw = btnPaint.measureText(label)
                val start = r.centerX() - (iconSize + Rs.dp(6f) + tw) / 2f
                Sprites.MAGNIFIER.draw(c, start + iconSize / 2f, r.centerY(), iconSize)
                Rs.text(c, label, start + iconSize + Rs.dp(6f) + tw / 2f, Rs.baseline(btnPaint, r.top, r.bottom), btnPaint, Rs.ORANGE)
            } else {
                Rs.text(c, label, r.centerX(), Rs.baseline(btnPaint, r.top, r.bottom), btnPaint, Rs.ORANGE)
            }
        }
    }

    private fun drawScrollbar(c: Canvas) {
        track.set(grid.right + Rs.dp(2f), grid.top, grid.right + Rs.dp(14f), grid.bottom)
        Rs.rect(c, track, 0xFF231F1A.toInt())
        Rs.outline(c, track, Rs.BLACK)
        val ms = maxScroll()
        if (ms <= 0f) return
        val th = max(Rs.dp(28f), track.height() * grid.height() / (rows() * cellH))
        val ty = track.top + (track.height() - th) * (scroll / ms)
        thumb.set(track.left + Rs.px, ty, track.right - Rs.px, ty + th)
        Rs.stone(c, thumb, light = true)
        Rs.bevel(c, thumb)
    }
}
