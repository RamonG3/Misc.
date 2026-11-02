package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Game
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * All apps, A to Z, with search at the top and a letter rail down the side. Hold an app for
 * options; hold and drag it out to drop it on the home screen.
 */
@SuppressLint("ViewConstructor")
class DrawerView(context: Context, private val game: Game, private val home: HomeView) : View(context) {
    private var items: List<AppEntry> = emptyList()
    private var recent: List<AppEntry> = emptyList()
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var topInset = 0
    private var bottomInset = 0
    private var openedAt = 0L
    private var closingAt = 0L
    var pick = false
        private set

    private val searchBox = RectF()
    private val clearBtn = RectF()
    private val list = RectF()
    private val rail = RectF()
    private val cell = RectF()
    private var cols = 5
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private var recentH = 0f
    private var headerH = 0f
    private var letters: List<Pair<Char, Int>> = emptyList()
    private var railLetter: Char? = null

    private val prompt = Cy.paint(15f, Cy.Face.MONO, spacing = 0.04f)
    private val placeholder = Cy.paint(15f, Cy.Face.MONO, spacing = 0.06f)
    private val section = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.16f)
    private val pickHint = Cy.paint(10f, Cy.Face.MONO, spacing = 0.1f)
    private val railPaint = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.CENTER)
    private val bubble = Cy.paint(34f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val webRow = Cy.paint(14f, Cy.Face.BOLD, spacing = 0.04f)
    private val empty = Cy.paint(13f, Cy.Face.BODY, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE && closingAt == 0L && mode != Mode.DRAGGING_OUT
    private val searching get() = game.inputMode == Game.InputMode.SEARCH

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        layoutParts()
        invalidate()
    }

    fun open(pickMode: Boolean) {
        pick = pickMode
        scroll = 0f
        scroller.forceFinished(true)
        openedAt = SystemClock.uptimeMillis()
        closingAt = 0L
        visibility = VISIBLE
        refresh()
    }

    fun close(animate: Boolean = true) {
        if (visibility != VISIBLE) return
        pick = false
        game.picking = false
        if (animate) {
            closingAt = SystemClock.uptimeMillis()
            invalidate()
        } else {
            closingAt = 0L
            visibility = INVISIBLE
        }
    }

    fun refresh() {
        val q = if (searching) game.inputText else ""
        items = game.drawerItems(q)
        recent = if (q.isEmpty() && !pick) game.recentApps(cols) else emptyList()
        letters = if (q.isEmpty()) indexLetters(items) else emptyList()
        layoutParts()
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun indexLetters(list: List<AppEntry>): List<Pair<Char, Int>> {
        val out = ArrayList<Pair<Char, Int>>()
        list.forEachIndexed { i, a ->
            val ch = a.label.firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' } ?: '#'
            if (out.none { it.first == ch }) out += ch to i
        }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        searchBox.set(Cy.dp(14f), topInset + Cy.dp(12f), w - Cy.dp(14f), topInset + Cy.dp(60f))
        clearBtn.set(searchBox.right - Cy.dp(44f), searchBox.top, searchBox.right, searchBox.bottom)
        list.set(Cy.dp(8f), searchBox.bottom + Cy.dp(8f), w - Cy.dp(30f), h - bottomInset - Cy.dp(4f))
        rail.set(w - Cy.dp(28f), list.top + Cy.dp(8f), w - Cy.dp(4f), list.bottom - Cy.dp(8f))
        cols = when {
            w > h * 1.15f -> 7
            w / Cy.density < 360f -> 4
            else -> 5
        }
        cellW = list.width() / cols
        cellH = min(cellW * 1.2f, Cy.dp(104f))
        iconSize = min(Cy.dp(50f), cellW * 0.62f)
        recentH = if (recent.isNotEmpty()) Cy.dp(26f) + cellH + Cy.dp(8f) else 0f
        headerH = Cy.dp(28f)
    }

    private fun gridTop() = recentH + headerH
    private fun rows() = ceil(items.size / cols.toFloat()).toInt()
    private fun contentHeight() = gridTop() + rows() * cellH + Cy.dp(16f)
    private fun maxScroll() = max(0f, contentHeight() - list.height())

    /** Cell [i] of the main grid, in view coordinates. */
    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        val top = list.top - scroll + gridTop() + row * cellH
        cell.set(list.left + col * cellW, top, list.left + (col + 1) * cellW, top + cellH)
        return cell
    }

    private fun recentRect(i: Int): RectF {
        val top = list.top - scroll + Cy.dp(26f)
        cell.set(list.left + i * cellW, top, list.left + (i + 1) * cellW, top + cellH)
        return cell
    }

    private fun appAt(x: Float, y: Float): AppEntry? {
        if (!list.contains(x, y)) return null
        val cy = y - list.top + scroll
        if (cy < recentH) {
            if (cy < Cy.dp(26f) || cy > Cy.dp(26f) + cellH) return null
            return recent.getOrNull(((x - list.left) / cellW).toInt())
        }
        val gy = cy - gridTop()
        if (gy < 0f) return null
        val col = ((x - list.left) / cellW).toInt()
        val i = (gy / cellH).toInt() * cols + col
        return if (col in 0 until cols) items.getOrNull(i) else null
    }

    private fun jumpTo(y: Float) {
        if (letters.isEmpty()) return
        val f = ((y - rail.top) / rail.height()).coerceIn(0f, 0.9999f)
        val (ch, index) = letters[(f * letters.size).toInt()]
        if (ch != railLetter) game.haptic()
        railLetter = ch
        scroll = (gridTop() + (index / cols) * cellH).coerceIn(0f, maxScroll())
        invalidate()
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { IDLE, PENDING, SCROLL, RAIL, HELD, DRAGGING_OUT }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pressed: AppEntry? = null
    private var held: AppEntry? = null
    private val swipe = Swipe()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val timer = Handler(Looper.getMainLooper())
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val longPress = Runnable {
        val a = pressed
        if (mode == Mode.PENDING && a != null) {
            mode = Mode.HELD
            held = a
            pressed = null
            game.appMenu(a, null, downX, downY - iconSize)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!showing && mode != Mode.DRAGGING_OUT) return false
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = x
                downY = y
                lastY = y
                swipe.down(e)
                if (letters.size > 1 && rail.contains(x, y) && !searching) {
                    mode = Mode.RAIL
                    jumpTo(y)
                    return true
                }
                mode = Mode.PENDING
                pressed = appAt(x, y)
                if (pressed != null) timer.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                swipe.move(e)
                when (mode) {
                    Mode.PENDING -> if (hypot(x - downX, y - downY) > slop) {
                        timer.removeCallbacks(longPress)
                        pressed = null
                        mode = Mode.SCROLL
                    }
                    Mode.SCROLL -> scroll = (scroll - (y - lastY)).coerceIn(0f, maxScroll())
                    Mode.RAIL -> jumpTo(y)
                    Mode.HELD -> if (hypot(x - downX, y - downY) > slop * 2f) {
                        // Pulled out of the drawer: carry it onto the home screen.
                        val a = held
                        if (a != null && !pick) {
                            mode = Mode.DRAGGING_OUT
                            game.ui.hideMenu()
                            close(animate = false)
                            visibility = VISIBLE
                            alpha = 0f
                            home.beginExternalDrag(a, x, y)
                        }
                    }
                    Mode.DRAGGING_OUT -> home.externalDragMove(x, y)
                    else -> Unit
                }
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(longPress)
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                swipe.up(e, minFling)
                val vy = swipe.vy
                when (mode) {
                    Mode.PENDING -> if (!cancel) tap(x, y)
                    Mode.SCROLL -> if (!cancel) {
                        if (scroll <= 0f && vy > minFling * 10 && abs(y - downY) > Cy.dp(60f) && !searching) {
                            game.ui.closeDrawer()
                        } else {
                            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    Mode.DRAGGING_OUT -> {
                        home.externalDragEnd(x, y, cancel)
                        visibility = INVISIBLE
                        alpha = 1f
                    }
                    else -> Unit
                }
                mode = Mode.IDLE
                pressed = null
                held = null
                railLetter = null
                invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (searchBox.contains(x, y)) {
            game.haptic()
            if (searching && clearBtn.contains(x, y)) {
                game.cancelInput()
            } else if (searching) {
                game.ui.openKeyboard(game.inputText)
            } else {
                game.beginSearch()
            }
            return
        }
        if (items.isEmpty() && searching && webRect.contains(x, y)) {
            game.haptic()
            game.submitInput()
            return
        }
        val a = appAt(x, y) ?: return
        if (pick) {
            game.picked(a)
            return
        }
        // The launch animation grows out of the icon that was tapped.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val col = ((x - list.left) / cellW).toInt()
        val l = loc[0] + list.left + col * cellW
        val t = loc[1] + y - (y - list.top + scroll - gridTop()).mod(cellH)
        game.launch(a, Rect(l.toInt(), t.toInt(), (l + cellW).toInt(), (t + cellH).toInt()))
    }

    private val webRect = RectF()
    private var blinkQueued = false
    private val blink = Runnable {
        blinkQueued = false
        if (showing) invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (alpha == 0f) return
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val k = if (closingAt > 0L) {
            val f = 1f - ((now - closingAt) / CLOSE_MS.toFloat()).coerceIn(0f, 1f)
            if (f <= 0f) {
                closingAt = 0L
                visibility = INVISIBLE
                return
            }
            f
        } else {
            ((now - openedAt) / OPEN_MS.toFloat()).coerceIn(0f, 1f)
        }
        val ease = 1f - (1f - k) * (1f - k)
        Cy.rect(c, 0f, 0f, w, h, Cy.withAlpha(Cy.bg, (0xFA * ease).toInt()))
        c.save()
        c.translate(0f, (1f - ease) * Cy.dp(60f))
        Cy.scanlines(c, RectF(0f, 0f, w, h))

        // Search box.
        Cy.panel(c, searchBox, Cy.dp(10f), Cy.withAlpha(0xFF000000.toInt(), 0xB0), if (searching) Cy.accent else Cy.withAlpha(Cy.primary, 0x90), Cy.TR or Cy.BL, ticks = false)
        val by = Cy.baseline(prompt, searchBox.top, searchBox.bottom)
        val px = searchBox.left + Cy.dp(16f)
        Glyphs.draw(c, Glyph.SEARCH, px + Cy.dp(4f), searchBox.centerY(), Cy.dp(17f), Cy.accent, 2f)
        val tx = px + Cy.dp(22f)
        if (searching) {
            Cy.text(c, game.inputText, tx, by, prompt, Cy.WHITE)
            if ((now / 500) % 2 == 0L) {
                val cx = tx + prompt.measureText(game.inputText) + Cy.dp(2f)
                Cy.rect(c, cx, by - Cy.dp(13f), cx + Cy.dp(8f), by + Cy.dp(2f), Cy.accent)
            }
            Glyphs.draw(c, Glyph.CLOSE, clearBtn.centerX(), clearBtn.centerY(), Cy.dp(14f), Cy.MUTED, 2f)
            if (!blinkQueued) {
                blinkQueued = true
                postDelayed(blink, 500L)
            }
        } else {
            Cy.text(c, if (pick) "FIND AN APP TO ADD" else "SEARCH APPS", tx, by, placeholder, Cy.withAlpha(Cy.TEXT, 0xB0))
        }

        c.save()
        c.clipRect(0f, list.top, w, list.bottom)
        var y = list.top - scroll
        if (recent.isNotEmpty()) {
            Cy.text(c, "RECENT", list.left + Cy.dp(8f), y + Cy.dp(16f), section, Cy.dim)
            recent.forEachIndexed { i, a ->
                val r = recentRect(i)
                AppIcon.cell(c, game, a, r, iconSize, labels = true, pressed = pressed === a && appAt(downX, downY) === a)
            }
            y += recentH
            Cy.line(c, list.left + Cy.dp(8f), y - Cy.dp(4f), list.right, y - Cy.dp(4f), Cy.faint)
        }
        val head = when {
            pick -> "TAP AN APP TO ADD IT TO YOUR HOME SCREEN"
            searching && game.inputText.startsWith("/") -> "PRESS ENTER TO RUN ${game.inputText.uppercase(Locale.US)}"
            searching -> "${items.size} MATCHES"
            else -> "ALL APPS · ${items.size}"
        }
        Cy.text(c, head, list.left + Cy.dp(8f), y + Cy.dp(18f), if (pick) pickHint else section, if (pick) Cy.hi else Cy.dim)
        if (items.isEmpty() && searching && game.inputText.isNotBlank() && !game.inputText.startsWith("/")) {
            webRect.set(list.left + Cy.dp(8f), y + headerH + Cy.dp(8f), list.right, y + headerH + Cy.dp(56f))
            Cy.panel(c, webRect, Cy.dp(8f), Cy.withAlpha(Cy.accent, 0x18), Cy.accent, Cy.TR, ticks = false)
            Glyphs.draw(c, Glyph.NET, webRect.left + Cy.dp(22f), webRect.centerY(), Cy.dp(18f), Cy.accent, 1.8f)
            Cy.text(c, Cy.ellipsize("SEARCH THE NET FOR \"${game.inputText.uppercase(Locale.US)}\"", webRow, webRect.width() - Cy.dp(56f)),
                webRect.left + Cy.dp(42f), Cy.baseline(webRow, webRect.top, webRect.bottom), webRow, Cy.WHITE)
        } else if (items.isEmpty() && !searching) {
            Cy.text(c, "No apps. Impressive.", w / 2f, y + Cy.dp(70f), empty, Cy.MUTED)
        }
        val firstRow = max(0, ((scroll - gridTop()) / cellH).toInt())
        val lastRow = min(rows() - 1, ((scroll - gridTop() + list.height()) / cellH).toInt() + 1)
        for (row in firstRow..lastRow) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= items.size) break
            val r = cellRect(i)
            AppIcon.cell(c, game, items[i], r, iconSize, labels = true, pressed = pressed === items[i])
        }
        c.restore()

        // Letter rail and the bubble while scrubbing it.
        if (letters.size > 1 && !searching) {
            val step = rail.height() / letters.size
            letters.forEachIndexed { i, (ch, _) ->
                val ly = rail.top + step * (i + 0.5f) + railPaint.textSize * 0.35f
                Cy.text(c, ch.toString(), rail.centerX(), ly, railPaint, if (ch == railLetter) Cy.accent else Cy.withAlpha(Cy.primary, 0xB0))
            }
            railLetter?.let { ch ->
                val br = RectF(w / 2f - Cy.dp(36f), h * 0.4f - Cy.dp(36f), w / 2f + Cy.dp(36f), h * 0.4f + Cy.dp(36f))
                Cy.panel(c, br, Cy.dp(12f), Cy.withAlpha(Cy.bg, 0xF0), Cy.accent, Cy.TR or Cy.BL)
                Cy.glow(c, ch.toString(), br.centerX(), br.centerY() + bubble.textSize * 0.36f, bubble, Cy.accent, Cy.dp(8f))
            }
        }
        val ms = maxScroll()
        if (ms > 0f && (letters.size <= 1 || searching)) {
            val th = max(Cy.dp(28f), list.height() * list.height() / contentHeight())
            val ty = list.top + (list.height() - th) * (scroll / ms)
            Cy.rect(c, w - Cy.dp(8f), ty, w - Cy.dp(5f), ty + th, Cy.primary)
        }
        c.restore()
        if (k < 1f || closingAt > 0L) postInvalidateOnAnimation()
    }

    companion object {
        const val OPEN_MS = 200L
        const val CLOSE_MS = 160L
    }
}
