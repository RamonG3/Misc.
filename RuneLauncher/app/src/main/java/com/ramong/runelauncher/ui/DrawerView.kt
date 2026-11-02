package com.ramong.runelauncher.ui

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
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Game
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Bank of Gielinor: all apps, A to Z, with search at the top and a letter rail down the
 * side. Hold an app for options; hold and drag it out to drop it on the home screen.
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

    private val frameBox = RectF()
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

    private val titlePaint = R3.paint(17f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.03f)
    private val prompt = R3.paint(16f, R3.Face.BOLD)
    private val placeholder = R3.paint(16f, R3.Face.BODY)
    private val section = R3.paint(13f, R3.Face.TITLE_BOLD, spacing = 0.03f)
    private val railPaint = R3.paint(10.5f, R3.Face.BOLD, Paint.Align.CENTER)
    private val bubble = R3.paint(34f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val webRow = R3.paint(15f, R3.Face.BOLD)
    private val empty = R3.paint(14f, R3.Face.BODY, Paint.Align.CENTER)

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
        alpha = 1f
        visibility = VISIBLE
        refresh()
    }

    fun close(animate: Boolean = true) {
        if (visibility != VISIBLE) return
        pick = false
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
        frameBox.set(R3.dp(6f), topInset + R3.dp(6f), w - R3.dp(6f), h - bottomInset - R3.dp(6f))
        searchBox.set(frameBox.left + R3.dp(12f), frameBox.top + R3.dp(50f), frameBox.right - R3.dp(12f), frameBox.top + R3.dp(98f))
        clearBtn.set(searchBox.right - R3.dp(46f), searchBox.top, searchBox.right, searchBox.bottom)
        list.set(frameBox.left + R3.dp(6f), searchBox.bottom + R3.dp(8f), frameBox.right - R3.dp(26f), frameBox.bottom - R3.dp(8f))
        rail.set(frameBox.right - R3.dp(26f), list.top + R3.dp(6f), frameBox.right - R3.dp(4f), list.bottom - R3.dp(6f))
        cols = when {
            w > h * 1.15f -> 7
            w / R3.density < 360f -> 4
            else -> 5
        }
        cellW = list.width() / cols
        cellH = min(cellW * 1.22f, R3.dp(104f))
        iconSize = min(R3.dp(52f), cellW * 0.64f)
        recentH = if (recent.isNotEmpty()) R3.dp(28f) + cellH + R3.dp(8f) else 0f
        headerH = R3.dp(30f)
    }

    private fun gridTop() = recentH + headerH
    private fun rows() = ceil(items.size / cols.toFloat()).toInt()
    private fun contentHeight() = gridTop() + rows() * cellH + R3.dp(16f)
    private fun maxScroll() = max(0f, contentHeight() - list.height())

    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        val top = list.top - scroll + gridTop() + row * cellH
        cell.set(list.left + col * cellW, top, list.left + (col + 1) * cellW, top + cellH)
        return cell
    }

    private fun recentRect(i: Int): RectF {
        val top = list.top - scroll + R3.dp(28f)
        cell.set(list.left + i * cellW, top, list.left + (i + 1) * cellW, top + cellH)
        return cell
    }

    private fun appAt(x: Float, y: Float): AppEntry? {
        if (!list.contains(x, y)) return null
        val cy = y - list.top + scroll
        if (cy < recentH) {
            if (cy < R3.dp(28f) || cy > R3.dp(28f) + cellH) return null
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
            game.appMenu(a, null, null, downX, downY - iconSize)
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
                        // Pulled out of the bank: carry it onto the home screen.
                        val a = held
                        if (a != null && !pick) {
                            mode = Mode.DRAGGING_OUT
                            game.ui.hideMenu()
                            close(animate = false)
                            visibility = VISIBLE
                            alpha = 0f
                            home.beginExternalDrag(a, null, x, y)
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
                        if (scroll <= 0f && vy > minFling * 10 && abs(y - downY) > R3.dp(60f) && !searching) {
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
        if (!frameBox.contains(x, y)) {
            game.ui.closeDrawer()
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
        R3.rect(c, 0f, 0f, w, h, R3.withAlpha(R3.BLACK, (0x88 * ease).toInt()))
        c.save()
        c.translate(0f, (1f - ease) * R3.dp(60f))
        val title = if (pick) {
            if (game.pickFolder != null) "Add to ${game.folder(game.pickFolder)?.name ?: "folder"}" else "Add to home screen"
        } else {
            "Bank of Gielinor"
        }
        R3.panel(c, frameBox, R3.withAlpha(R3.bg, (0xF4 * ease).toInt()), R3.dp(10f), titleText = title, titlePaint = titlePaint, titleH = R3.dp(42f))
        R3.text(c, "${game.apps.size}", frameBox.left + R3.dp(22f), frameBox.top + R3.dp(27f), section, R3.withAlpha(R3.hi, 0xB0))

        // Search box.
        R3.panel(c, searchBox, R3.withAlpha(R3.BLACK, 0x90), R3.dp(22f), studs = false)
        val by = R3.baseline(prompt, searchBox.top, searchBox.bottom)
        val px = searchBox.left + R3.dp(18f)
        Glyphs.draw(c, Glyph.SEARCH, px + R3.dp(4f), searchBox.centerY(), R3.dp(19f), R3.hi, 2f)
        val tx = px + R3.dp(24f)
        if (searching) {
            R3.text(c, game.inputText, tx, by, prompt, R3.WHITE)
            if ((now / 500) % 2 == 0L) {
                val cx = tx + prompt.measureText(game.inputText) + R3.dp(2f)
                R3.rect(c, cx, by - R3.dp(15f), cx + R3.dp(2f), by + R3.dp(3f), R3.hi)
            }
            Glyphs.draw(c, Glyph.CLOSE, clearBtn.centerX(), clearBtn.centerY(), R3.dp(15f), R3.MUTED, 2f)
            if (!blinkQueued) {
                blinkQueued = true
                postDelayed(blink, 500L)
            }
        } else {
            R3.text(c, if (pick) "Find an app" else "Search ${game.apps.size} items", tx, by, placeholder, R3.withAlpha(R3.TEXT, 0xB0))
        }

        c.save()
        c.clipRect(0f, list.top, w, list.bottom)
        var y = list.top - scroll
        if (recent.isNotEmpty()) {
            R3.text(c, "Recent", list.left + R3.dp(10f), y + R3.dp(18f), section, R3.hi)
            recent.forEachIndexed { i, a ->
                val r = recentRect(i)
                AppIcon.cell(c, game, a, r, iconSize, labels = true, pressed = pressed === a && appAt(downX, downY) === a)
            }
            y += recentH
            R3.ornament(c, list.left + R3.dp(10f), list.right - R3.dp(4f), y - R3.dp(5f))
        }
        val head = when {
            pick -> "Tap an app to add it"
            searching && (game.inputText.startsWith("::") || game.inputText.startsWith("/")) -> "Press Enter to run ${game.inputText}"
            searching -> "${items.size} match${if (items.size == 1) "" else "es"}"
            else -> "All items"
        }
        R3.text(c, head, list.left + R3.dp(10f), y + R3.dp(20f), section, R3.hi)
        if (items.isEmpty() && searching && game.inputText.isNotBlank() && !game.inputText.startsWith("::") && !game.inputText.startsWith("/")) {
            webRect.set(list.left + R3.dp(8f), y + headerH + R3.dp(8f), list.right, y + headerH + R3.dp(58f))
            R3.button(c, webRect, lit = true)
            Glyphs.draw(c, Glyph.GLOBE, webRect.left + R3.dp(24f), webRect.centerY(), R3.dp(20f), R3.hi, 1.8f)
            R3.text(c, R3.ellipsize("Search the web for \"${game.inputText}\"", webRow, webRect.width() - R3.dp(56f)),
                webRect.left + R3.dp(44f), R3.baseline(webRow, webRect.top, webRect.bottom), webRow, R3.WHITE)
        } else if (items.isEmpty() && !searching) {
            R3.text(c, "Your bank is empty. Impressive.", w / 2f, y + R3.dp(70f), empty, R3.MUTED)
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
                R3.text(c, ch.toString(), rail.centerX(), ly, railPaint, if (ch == railLetter) R3.hi else R3.withAlpha(R3.frameLight, 0xC0))
            }
            railLetter?.let { ch ->
                val br = RectF(w / 2f - R3.dp(38f), h * 0.4f - R3.dp(38f), w / 2f + R3.dp(38f), h * 0.4f + R3.dp(38f))
                R3.panel(c, br, R3.withAlpha(R3.bg, 0xF4), R3.dp(12f))
                R3.text(c, ch.toString(), br.centerX(), br.centerY() + bubble.textSize * 0.36f, bubble, R3.hi)
            }
        }
        val ms = maxScroll()
        if (ms > 0f && (letters.size <= 1 || searching)) {
            val th = max(R3.dp(28f), list.height() * list.height() / contentHeight())
            val ty = list.top + (list.height() - th) * (scroll / ms)
            R3.round(c, RectF(frameBox.right - R3.dp(14f), ty, frameBox.right - R3.dp(10f), ty + th), R3.dp(2f), R3.frame)
        }
        c.restore()
        if (k < 1f || closingAt > 0L) postInvalidateOnAnimation()
    }

    companion object {
        const val OPEN_MS = 200L
        const val CLOSE_MS = 160L
    }
}
