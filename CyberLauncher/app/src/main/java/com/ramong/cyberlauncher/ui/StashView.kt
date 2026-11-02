package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Attr
import com.ramong.cyberlauncher.core.Game
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The stash: every installed app as an item, with attribute filters, sorting and search. */
@SuppressLint("ViewConstructor")
class StashView(context: Context, private val game: Game) : View(context) {
    var attr: Attr? = null
        private set
    var pick: Game.Pick? = null
        private set
    private var items: List<AppEntry> = emptyList()
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var topInset = 0
    private var bottomInset = 0
    private var openedAt = 0L
    private var blinkQueued = false
    private val blink = Runnable {
        blinkQueued = false
        if (showing) invalidate()
    }

    private val frame = RectF()
    private val header = RectF()
    private val close = RectF()
    private val chips = ArrayList<Pair<Attr?, RectF>>()
    private val chipRow = RectF()
    private val grid = RectF()
    private val search = RectF()
    private val bar = RectF()
    private val searchBtn = RectF()
    private val sortBtn = RectF()
    private val closeBtn = RectF()
    private val cell = RectF()
    private var pressed: RectF? = null
    private var pressedItem = -1
    private var cols = 4
    private var cellW = 0f
    private var cellH = 0f

    private val title = Cy.paint(22f, Cy.Face.BOLD, spacing = 0.14f)
    private val count = Cy.paint(10f, Cy.Face.MONO, spacing = 0.1f)
    private val chipPaint = Cy.paint(10f, Cy.Face.MONO, Paint.Align.CENTER, 0.06f)
    private val btn = Cy.paint(12.5f, Cy.Face.BOLD, Paint.Align.CENTER, 0.1f)
    private val input = Cy.paint(15f, Cy.Face.MONO)
    private val hint = Cy.paint(9f, Cy.Face.MONO, Paint.Align.RIGHT, 0.08f)
    private val empty = Cy.paint(14f, Cy.Face.BODY, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        layoutParts()
        invalidate()
    }

    fun open(a: Attr?, p: Game.Pick?) {
        attr = a
        pick = p
        scroll = 0f
        scroller.forceFinished(true)
        openedAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        refresh()
    }

    fun refresh() {
        val q = if (game.inputMode == Game.InputMode.SEARCH) game.inputText else ""
        items = game.stashItems(q, attr)
        layoutParts()
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val m = Cy.dp(8f)
        frame.set(m, topInset + m, w - m, h - bottomInset - m)
        header.set(frame.left, frame.top, frame.right, frame.top + Cy.dp(52f))
        close.set(header.right - Cy.dp(48f), header.top + Cy.dp(6f), header.right - Cy.dp(6f), header.bottom - Cy.dp(6f))
        chipRow.set(frame.left + Cy.dp(8f), header.bottom + Cy.dp(2f), frame.right - Cy.dp(8f), header.bottom + Cy.dp(36f))
        bar.set(frame.left + Cy.dp(8f), frame.bottom - Cy.dp(54f), frame.right - Cy.dp(8f), frame.bottom - Cy.dp(8f))
        val searching = game.inputMode == Game.InputMode.SEARCH
        search.set(bar.left, bar.top - if (searching) Cy.dp(50f) else 0f, bar.right, bar.top - if (searching) Cy.dp(6f) else 0f)
        grid.set(frame.left + Cy.dp(8f), chipRow.bottom + Cy.dp(8f), frame.right - Cy.dp(12f), (if (searching) search.top else bar.top) - Cy.dp(8f))
        cols = max(4, (grid.width() / Cy.dp(86f)).toInt())
        cellW = grid.width() / cols
        cellH = cellW * if (game.save.labels) 1.0f else 0.84f
        val gap = Cy.dp(6f)
        val bw = (bar.width() - gap * 2) / 3f
        searchBtn.set(bar.left, bar.top, bar.left + bw, bar.bottom)
        sortBtn.set(searchBtn.right + gap, bar.top, searchBtn.right + gap + bw, bar.bottom)
        closeBtn.set(sortBtn.right + gap, bar.top, bar.right, bar.bottom)
        chips.clear()
        val all = listOf<Attr?>(null) + Attr.values().toList()
        val cw = chipRow.width() / all.size
        all.forEachIndexed { i, a ->
            chips += a to RectF(chipRow.left + i * cw + Cy.dp(2f), chipRow.top, chipRow.left + (i + 1) * cw - Cy.dp(2f), chipRow.bottom)
        }
    }

    private fun rows() = ceil(items.size / cols.toFloat()).toInt()
    private fun maxScroll() = max(0f, rows() * cellH - grid.height())

    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        val g = Cy.dp(3f)
        cell.set(grid.left + col * cellW + g, grid.top + row * cellH - scroll + g, grid.left + (col + 1) * cellW - g, grid.top + (row + 1) * cellH - scroll - g)
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
            pressed = listOf(close, searchBtn, sortBtn, closeBtn, search).firstOrNull { it.contains(e.x, e.y) }
                ?: chips.firstOrNull { it.second.contains(e.x, e.y) }?.second
            pressedItem = itemAt(e.x, e.y)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            when {
                close.contains(x, y) || closeBtn.contains(x, y) -> {
                    game.haptic()
                    game.ui.closeStash()
                }
                searchBtn.contains(x, y) || search.contains(x, y) -> {
                    game.haptic()
                    if (game.inputMode == Game.InputMode.SEARCH) game.ui.openKeyboard(game.inputText) else game.beginSearch()
                }
                sortBtn.contains(x, y) -> {
                    game.haptic()
                    game.save.stashSort = (game.save.stashSort + 1) % 3
                    game.save.save()
                    refresh()
                }
                else -> {
                    val chip = chips.firstOrNull { it.second.contains(x, y) }
                    if (chip != null) {
                        game.haptic()
                        attr = chip.first
                        scroll = 0f
                        refresh()
                        return true
                    }
                    val i = itemAt(x, y)
                    if (i >= 0) {
                        val p = pick
                        if (p != null) {
                            game.picked(items[i], p)
                            return true
                        }
                        val r = RectF(cellRect(i))
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        game.launch(items[i], Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt()))
                    } else if (!frame.contains(x, y)) {
                        game.ui.closeStash()
                    }
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = itemAt(e.x, e.y)
            if (i >= 0) game.stashMenu(items[i], left + e.x, top + e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scroll = (scroll + dy).coerceIn(0f, maxScroll())
            pressed = null
            pressedItem = -1
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scroll <= 0f && vy > Cy.dp(1100f) && abs(vy) > abs(vx) && game.inputMode != Game.InputMode.SEARCH) {
                game.ui.closeStash()
                return true
            }
            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = null
            pressedItem = -1
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
        val searching = game.inputMode == Game.InputMode.SEARCH
        if ((search.height() > 0f) != searching) layoutParts()
        val since = SystemClock.uptimeMillis() - openedAt
        val k = (since / 160f).coerceIn(0f, 1f)
        Cy.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), Cy.withAlpha(0xFF000000.toInt(), (0x99 * k).toInt()))
        c.save()
        c.translate(0f, (1f - k) * Cy.dp(30f))
        Cy.panel(c, frame, Cy.dp(16f), Cy.withAlpha(Cy.bg, 0xF4), Cy.dim, Cy.TR or Cy.BL)
        Cy.scanlines(c, frame)

        val p = pick
        val titleText = when (p) {
            is Game.Pick.Slot -> "SELECT ITEM"
            is Game.Pick.Implant -> "INSTALL"
            null -> "STASH"
        }
        val ty = Cy.baseline(title, header.top, header.bottom)
        Cy.split(c, titleText, header.left + Cy.dp(16f), ty, title, Cy.primary, Cy.dp(1.2f), Cy.dp(6f))
        val sub = when (p) {
            is Game.Pick.Slot -> "FOR SLOT ${p.index + 1}"
            is Game.Pick.Implant -> p.ware.title.uppercase(Locale.US)
            null -> "${items.size} ITEMS" + (attr?.let { " · ${it.title.uppercase(Locale.US)}" } ?: "")
        }
        Cy.text(c, sub, header.left + Cy.dp(22f) + title.measureText(titleText), ty, count, Cy.dim)
        if (pressed === close) Cy.rect(c, close, Cy.withAlpha(Cy.primary, 0x30))
        Glyphs.draw(c, Glyph.CLOSE, close.centerX(), close.centerY(), Cy.dp(20f), Cy.primary, 2f)
        Cy.line(c, header.left + Cy.dp(10f), header.bottom, header.right - Cy.dp(10f), header.bottom, Cy.faint)

        for ((a, r) in chips) {
            val sel = a == attr
            Cy.panel(c, r, Cy.dp(6f), if (sel) Cy.withAlpha(Cy.accent, 0x30) else Cy.withAlpha(Cy.bg, 0x80),
                if (sel) Cy.accent else if (pressed === r) Cy.primary else Cy.withAlpha(Cy.primary, 0x40), Cy.TR, ticks = false)
            val n = if (a == null) game.apps.size else game.appsTraining(a)
            Cy.text(c, a?.short ?: "ALL", r.centerX(), r.centerY() - Cy.dp(1f), chipPaint, if (sel) Cy.accent else Cy.TEXT)
            Cy.text(c, "$n", r.centerX(), r.centerY() + Cy.dp(10f), chipPaint, Cy.MUTED)
        }

        c.save()
        c.clipRect(grid.left - Cy.dp(2f), grid.top, grid.right + Cy.dp(2f), grid.bottom)
        if (items.isEmpty()) {
            val msg = if (searching && game.inputText.isNotBlank()) "No items match. Enter searches the net." else "Nothing here. Suspicious."
            Cy.text(c, msg, grid.centerX(), grid.top + Cy.dp(44f), empty, Cy.MUTED)
        }
        val firstRow = max(0, (scroll / cellH).toInt())
        val lastRow = min(rows() - 1, ((scroll + grid.height()) / cellH).toInt())
        for (row in firstRow..lastRow) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= items.size) break
            ItemDraw.tile(c, game, items[i], cellRect(i), pressed = i == pressedItem, labels = true)
        }
        c.restore()
        val ms = maxScroll()
        if (ms > 0f) {
            val x = grid.right + Cy.dp(6f)
            val th = max(Cy.dp(28f), grid.height() * grid.height() / (rows() * cellH))
            val y = grid.top + (grid.height() - th) * (scroll / ms)
            Cy.line(c, x, grid.top, x, grid.bottom, Cy.faint)
            Cy.rect(c, x - Cy.dp(1.5f), y, x + Cy.dp(1.5f), y + th, Cy.primary)
        }

        if (search.height() > 0f) {
            Cy.panel(c, search, Cy.dp(8f), Cy.withAlpha(0xFF000000.toInt(), 0xC0), Cy.accent, Cy.TR, ticks = false)
            val by = Cy.baseline(input, search.top, search.bottom)
            Cy.text(c, ">", search.left + Cy.dp(12f), by, input, Cy.accent)
            val tx = search.left + Cy.dp(12f) + input.measureText("> ")
            Cy.text(c, game.inputText, tx, by, input, Cy.WHITE)
            if ((SystemClock.uptimeMillis() / 500) % 2 == 0L) {
                val cx = tx + input.measureText(game.inputText) + Cy.dp(2f)
                Cy.rect(c, cx, by - Cy.dp(13f), cx + Cy.dp(8f), by + Cy.dp(2f), Cy.accent)
            }
            Cy.text(c, if (p != null) "ENTER PICKS TOP MATCH" else "ENTER RUNS TOP MATCH", search.right - Cy.dp(10f), search.top + Cy.dp(12f), hint, Cy.MUTED)
            if (!blinkQueued) {
                blinkQueued = true
                postDelayed(blink, 500)
            }
        }

        val sortName = arrayOf("A-Z", "RARITY", "RECENT")[game.save.stashSort.coerceIn(0, 2)]
        for ((r, label, glyph) in listOf(Triple(searchBtn, "SEARCH", Glyph.SEARCH), Triple(sortBtn, sortName, Glyph.SORT), Triple(closeBtn, "CLOSE", Glyph.CLOSE))) {
            val sel = r === searchBtn && searching
            Cy.panel(c, r, Cy.dp(8f), if (pressed === r || sel) Cy.withAlpha(Cy.primary, 0x2A) else Cy.withAlpha(Cy.bg, 0xC0),
                if (sel) Cy.accent else Cy.withAlpha(Cy.primary, 0x80), Cy.TR, ticks = false)
            val tw = btn.measureText(label)
            val gs = Cy.dp(15f)
            val startX = r.centerX() - (gs + Cy.dp(7f) + tw) / 2f
            Glyphs.draw(c, glyph, startX + gs / 2f, r.centerY(), gs, if (sel) Cy.accent else Cy.primary, 2f)
            Cy.text(c, label, startX + gs + Cy.dp(7f) + tw / 2f, Cy.baseline(btn, r.top, r.bottom), btn, if (sel) Cy.accent else Cy.WHITE)
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}
